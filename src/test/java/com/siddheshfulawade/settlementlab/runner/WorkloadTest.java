package com.siddheshfulawade.settlementlab.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.Transfer;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WorkloadTest {

    private final Workload workload = new Workload(1_000, 10_000, 10, 80, 5, 42);

    @Test
    @DisplayName("REQ-LAB-015 the same seed and index always give the same transfer")
    void isDeterministic() {
        Workload again = new Workload(1_000, 10_000, 10, 80, 5, 42);

        for (int i = 0; i < 1_000; i++) {
            assertThat(workload.transfer(i)).isEqualTo(again.transfer(i));
        }
    }

    @Test
    @DisplayName("REQ-LAB-015 about hotPercent of transfers stay within the hot accounts")
    void hotAccountsGetTheirShare() {
        int hot = 0;
        for (int i = 0; i < 10_000; i++) {
            Transfer t = workload.transfer(i);
            if (t.source().value() <= 10 && t.target().value() <= 10) {
                hot++;
            }
        }
        assertThat(hot).isBetween(7_600, 8_400);
    }

    @Test
    @DisplayName("REQ-LAB-008 about duplicatePercent of transfers reuse an earlier id with the same content")
    void duplicatesRepeatEarlierTransfers() {
        Set<String> ids = new HashSet<>();
        int repeats = 0;
        for (int i = 0; i < 10_000; i++) {
            if (!ids.add(workload.transfer(i).id().value())) {
                repeats++;
            }
        }
        assertThat(repeats).isBetween(350, 650);
        // A duplicate is the very same transfer, as a resent message would be.
        for (int i = 0; i < 10_000; i++) {
            Transfer t = workload.transfer(i);
            int original = Integer.parseInt(t.id().value().substring(2));
            assertThat(workload.transfer(original)).isEqualTo(t);
        }
    }

    @Test
    @DisplayName("accounts are 1..accounts and every opening balance is the same")
    void openingBalances() {
        assertThat(workload.openingBalances()).hasSize(1_000);
        assertThat(workload.openingBalances().values()).allMatch(m -> m.minorUnits() == 10_000);
    }
}
