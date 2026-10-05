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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.fineract.infrastructure.core.exception.PlatformApiDataValidationException;
import org.junit.jupiter.api.Test;

/**
 * Runs the real {@link AdvancedPaymentAllocationsValidator} over a batch of candidate product configurations and
 * emits a trace of the ones it ACCEPTS, one row per accepted config (entries / recognised / distinct types).
 * `allium monitor specs/accepted-config-audit.allium` over the trace captured on 1.15.0 flags the null-bearing
 * configs the validator wrongly accepts (the find); over the trace captured after the fix every accepted config
 * is complete and it holds (the fix). The assertion (every null-bearing config is rejected) is red on 1.15.0,
 * green after the fix.
 */
class AdvancedPaymentAllocationsAuditTraceTest {

    private final AdvancedPaymentAllocationsValidator underTest = new AdvancedPaymentAllocationsValidator();

    private record Candidate(String name, List<Pair<Integer, PaymentAllocationType>> rules, boolean nullBearing) {}

    /** 12 ordered rules; types taken from the enum rotated by {@code shift} (all 12 present -> a valid config). */
    private static Candidate valid(int shift) {
        PaymentAllocationType[] all = PaymentAllocationType.values();
        List<Pair<Integer, PaymentAllocationType>> rules = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rules.add(Pair.of(i + 1, all[(i + shift) % 12]));
        }
        return new Candidate("valid-rot" + shift, rules, false);
    }

    /** 12 ordered rules with the type at {@code drop} replaced by null (a misspelled, unrecognised rule name). */
    private static Candidate nullBearing(int drop) {
        PaymentAllocationType[] all = PaymentAllocationType.values();
        List<Pair<Integer, PaymentAllocationType>> rules = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rules.add(Pair.of(i + 1, i == drop ? null : all[i]));
        }
        return new Candidate("null-at-" + all[drop].name(), rules, true);
    }

    private static List<Candidate> batch() {
        List<Candidate> batch = new ArrayList<>();
        for (int shift = 0; shift < 6; shift++) {
            batch.add(valid(shift));
        }
        for (int drop : new int[] { 0, 2, 4, 6, 8, 10 }) {
            batch.add(nullBearing(drop));
        }
        return batch;
    }

    @Test
    void emitAcceptedConfigAuditTrace() throws IOException {
        StringBuilder trace = new StringBuilder("# accepted advanced payment-allocation configs (real AdvancedPaymentAllocationsValidator)\n");
        int period = 0;
        boolean allNullBearingRejected = true;
        for (Candidate c : batch()) {
            boolean accepted;
            try {
                underTest.validatePairOfOrderAndPaymentAllocationType(c.rules());
                accepted = true;
            } catch (PlatformApiDataValidationException e) {
                accepted = false;
            }
            if (c.nullBearing() && accepted) {
                allNullBearingRejected = false;
            }
            if (accepted) {
                long entries = c.rules().size();
                long recognised = c.rules().stream().map(Pair::getRight).filter(Objects::nonNull).count();
                long distinct = c.rules().stream().map(Pair::getRight).filter(Objects::nonNull).distinct().count();
                trace.append(String.format("period=%d entries=%d recognised=%d distinct_types=%d%n", period++, entries, recognised,
                        distinct));
            }
        }
        String out = System.getProperty("allium.trace.out");
        Path dest = out != null ? Path.of(out) : Path.of(System.getProperty("java.io.tmpdir"), "accepted-config-audit.trace");
        if (dest.getParent() != null) {
            Files.createDirectories(dest.getParent());
        }
        Files.writeString(dest, trace.toString());
        System.out.println("ALLIUM trace (" + period + " accepted configs): " + dest.toAbsolutePath());
        assertEquals(true, allNullBearingRejected, "every null-bearing (incomplete) config must be rejected by the validator");
    }
}
