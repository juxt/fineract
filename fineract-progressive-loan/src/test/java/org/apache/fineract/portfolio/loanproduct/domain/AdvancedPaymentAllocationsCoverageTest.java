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
package org.apache.fineract.portfolio.loanproduct.domain;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;

/**
 * The advanced-payment-allocation completeness check is null-blind. An unrecognised rule name parses to null
 * (AdvancedPaymentAllocationsJsonParser: Enums.getIfPresent(...).orNull()), and the validator's gate checks only
 * size==12, distinctness and order 1..12, where null counts as a distinct value. So 11 real types plus one null
 * (from a one-character typo) passes validation, is persisted, and later NPEs where the processor does
 * groupingBy(PaymentAllocationType::getDueType).
 */
class AdvancedPaymentAllocationsCoverageTest {

    private final AdvancedPaymentAllocationsValidator underTest = new AdvancedPaymentAllocationsValidator();

    /** A 12-entry list, orders 1..12, with one allocation type replaced by null (the dropped DUE_PRINCIPAL). */
    private static List<Pair<Integer, PaymentAllocationType>> twelveEntriesWithOneNullType() {
        List<PaymentAllocationType> types = new ArrayList<>(EnumSet.allOf(PaymentAllocationType.class).stream().toList());
        types.set(0, null); // what the parser produces for a misspelled rule name, e.g. "DUE_PRINCIPLE"
        List<Pair<Integer, PaymentAllocationType>> pairs = new ArrayList<>();
        for (int i = 0; i < types.size(); i++) {
            pairs.add(Pair.of(i + 1, types.get(i)));
        }
        return pairs;
    }

    @Test
    void validatorShouldRejectNullBearingAllocationSet() {
        // An incomplete, null-bearing set must be rejected; it is not, so this fails on 1.15.0.
        assertThrows(PlatformApiDataValidationException.class,
                () -> underTest.validatePairOfOrderAndPaymentAllocationType(twelveEntriesWithOneNullType()));
    }

    @Test
    void acceptedSetNpesAtGroupingByDueType() {
        // The accepted null-bearing set is what the processor later groups by due type.
        List<PaymentAllocationType> types = new ArrayList<>(EnumSet.allOf(PaymentAllocationType.class).stream().toList());
        types.set(0, null);
        assertThrows(NullPointerException.class,
                () -> types.stream().collect(Collectors.groupingBy(PaymentAllocationType::getDueType)));
    }
}
