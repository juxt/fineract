/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.portfolio.loanaccount.domain.transactionprocessor.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.fineract.infrastructure.businessdate.domain.BusinessDateType;
import org.apache.fineract.infrastructure.core.domain.ActionContext;
import org.apache.fineract.infrastructure.core.domain.ExternalId;
import org.apache.fineract.infrastructure.core.domain.FineractPlatformTenant;
import org.apache.fineract.infrastructure.core.service.ExternalIdFactory;
import org.apache.fineract.infrastructure.core.service.ThreadLocalContextUtil;
import org.apache.fineract.organisation.monetary.domain.MonetaryCurrency;
import org.apache.fineract.organisation.monetary.domain.Money;
import org.apache.fineract.organisation.monetary.domain.MoneyHelper;
import org.apache.fineract.organisation.office.domain.Office;
import org.apache.fineract.portfolio.loanaccount.data.TransactionChangeData;
import org.apache.fineract.portfolio.loanaccount.domain.ChangedTransactionDetail;
import org.apache.fineract.portfolio.loanaccount.domain.Loan;
import org.apache.fineract.portfolio.loanaccount.domain.LoanRepaymentScheduleInstallment;
import org.apache.fineract.portfolio.loanaccount.domain.LoanTransaction;
import org.apache.fineract.portfolio.loanaccount.serialization.LoanChargeValidator;
import org.apache.fineract.portfolio.loanaccount.service.LoanBalanceService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * When a loan's transaction history is replayed (after a reversal/backdate), the overpayment holder that splits
 * a chargeback is seeded to zero (AbstractLoanRepaymentScheduleTransactionProcessor:172, "TODO: analyze and
 * remove this"), where every live path seeds it from the loan's true overpaid balance. So a chargeback that the
 * customer's existing overpaid credit should absorb instead creates brand-new principal: replay is not
 * idempotent and silently converts credit into debt.
 *
 * {@link #replayMustNotTurnCoveredChargebackIntoNewPrincipal()} is the single crisp case (red on 1.15.0).
 * {@link #emitConformanceTrace()} runs the real reprocess over a grid of (amount, overpaid-balance) examples and
 * writes a trace of the real split outputs, for `allium monitor specs/replay-conservation.allium` to check in
 * bulk: on 1.15.0 the covered rows violate the conservation invariant, after the fix they all hold.
 */
public class ReplayOverpaymentConservationTest {

    private static final MonetaryCurrency CCY = new MonetaryCurrency("USD", 2, 1);
    private static final MockedStatic<MoneyHelper> MONEY_HELPER = Mockito.mockStatic(MoneyHelper.class);
    private final LocalDate disbursementDate = LocalDate.of(2023, 6, 11);
    private final LocalDate txnDate = LocalDate.of(2023, 7, 11);
    private final LocalDate dueDate = LocalDate.of(2023, 7, 31); // after txnDate -> the credit maps to this period

    private FineractStyleLoanRepaymentScheduleTransactionProcessor underTest;
    private Office office;

    @BeforeAll
    public static void init() {
        MONEY_HELPER.when(MoneyHelper::getMathContext).thenReturn(new MathContext(12, RoundingMode.HALF_EVEN));
        MONEY_HELPER.when(MoneyHelper::getRoundingMode).thenReturn(RoundingMode.HALF_EVEN);
    }

    @AfterAll
    public static void destruct() {
        MONEY_HELPER.close();
    }

    @BeforeEach
    public void setUp() {
        underTest = new FineractStyleLoanRepaymentScheduleTransactionProcessor(mock(ExternalIdFactory.class),
                mock(LoanChargeValidator.class), mock(LoanBalanceService.class));
        office = mock(Office.class);
        ThreadLocalContextUtil.setTenant(new FineractPlatformTenant(1L, "default", "Default", "Asia/Kolkata", null));
        ThreadLocalContextUtil.setActionContext(ActionContext.DEFAULT);
        ThreadLocalContextUtil.setBusinessDates(new HashMap<>(Map.of(BusinessDateType.BUSINESS_DATE, txnDate)));
    }

    @AfterEach
    public void tearDown() {
        ThreadLocalContextUtil.reset();
    }

    /** Replay a chargeback of {@code amount} on a loan whose true overpaid balance is {@code overpaidBefore}. */
    private Split replayChargeback(BigDecimal amount, BigDecimal overpaidBefore) {
        Loan loan = mock(Loan.class);
        doReturn(office).when(loan).getOffice();
        doReturn(Money.of(CCY, overpaidBefore)).when(loan).getTotalOverpaidAsMoney();
        LoanTransaction cb = spy(LoanTransaction.chargeback(loan, Money.of(CCY, amount), null, txnDate, ExternalId.empty()));
        doReturn(1L).when(cb).getId(); // an existing (persisted) transaction, so the replay actually re-splits it
        LoanRepaymentScheduleInstallment installment = new LoanRepaymentScheduleInstallment(loan, 1, disbursementDate, dueDate,
                BigDecimal.valueOf(500L), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, null, BigDecimal.ZERO);
        List<LoanRepaymentScheduleInstallment> installments = new ArrayList<>(List.of(installment));
        ChangedTransactionDetail changed = underTest.reprocessLoanTransactions(disbursementDate, List.of(cb), CCY, installments,
                new HashSet<>());
        // The reprocess records a new transaction only when the split changes the amounts; otherwise the original
        // carries the (unchanged) split. Read whichever one holds the result.
        LoanTransaction effective = cb;
        for (TransactionChangeData change : changed.getTransactionChanges()) {
            if (change.getNewTransaction().isChargeback()) {
                effective = change.getNewTransaction();
                break;
            }
        }
        BigDecimal principalCreated = effective.getPrincipalPortion(CCY).getAmount();
        BigDecimal creditConsumed = effective == cb ? BigDecimal.ZERO : effective.getOverPaymentPortion(CCY).getAmount();
        return new Split(principalCreated, creditConsumed);
    }

    private record Split(BigDecimal principalCreated, BigDecimal creditConsumed) {}

    @Test
    public void replayMustNotTurnCoveredChargebackIntoNewPrincipal() {
        Split s = replayChargeback(BigDecimal.valueOf(300), BigDecimal.valueOf(300));
        assertEquals(0, s.principalCreated().compareTo(BigDecimal.ZERO),
                "the loan is overpaid by 300 and the chargeback is 300, so the replay must create no new principal");
    }

    @Test
    public void emitConformanceTrace() throws IOException {
        BigDecimal[] amounts = { BigDecimal.valueOf(100), BigDecimal.valueOf(250), BigDecimal.valueOf(300), BigDecimal.valueOf(500),
                BigDecimal.valueOf(750) };
        StringBuilder trace = new StringBuilder("# captured from AbstractLoanRepaymentScheduleTransactionProcessor.reprocessLoanTransactions\n");
        int period = 0;
        boolean allCoveredAbsorbed = true;
        for (BigDecimal amount : amounts) {
            // true overpaid balance before the replay: none, half, exactly covering, more than covering
            for (BigDecimal overpaidBefore : new BigDecimal[] { BigDecimal.ZERO, amount.divide(BigDecimal.valueOf(2)), amount,
                    amount.multiply(BigDecimal.valueOf(2)) }) {
                Split s = replayChargeback(amount, overpaidBefore);
                BigDecimal overpaidAfter = overpaidBefore.subtract(s.creditConsumed());
                trace.append(String.format(
                        "period=%d amount=%s is_chargeback=true overpaid_before=%s overpaid_after=%s principal_created=%s credit_consumed=%s%n",
                        period++, amount.toPlainString(), overpaidBefore.toPlainString(), overpaidAfter.toPlainString(),
                        s.principalCreated().toPlainString(), s.creditConsumed().toPlainString()));
                if (overpaidBefore.compareTo(amount) >= 0 && s.principalCreated().compareTo(BigDecimal.ZERO) != 0) {
                    allCoveredAbsorbed = false;
                }
            }
        }
        String out = System.getProperty("allium.trace.out");
        Path dest = out != null ? Path.of(out) : Path.of(System.getProperty("java.io.tmpdir"), "replay-conservation.trace");
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Files.writeString(dest, trace.toString());
        System.out.println("ALLIUM trace (" + period + " examples): " + dest.toAbsolutePath());
        assertEquals(true, allCoveredAbsorbed,
                "every chargeback covered by existing overpaid credit must create no new principal");
    }
}
