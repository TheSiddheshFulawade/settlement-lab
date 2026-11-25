package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.InvariantChecker;
import com.siddheshfulawade.settlementlab.domain.InvariantReport;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V2-05: the HTTP load check against a real port, for one ledger and thread mode (the subclass sets
 * them). The client side is black-box ({@link HttpLoadCheck}); this class then also looks inside the
 * server with the engine's own {@link InvariantChecker}.
 */
abstract class HttpLoadCheckContract {

    abstract String baseUrl();

    abstract SettlementRuntime runtime();

    abstract String ledger();

    abstract String threads();

    private static void assertNoCheckFailed(HttpLoadCheck.Report report) {
        List<String> failed = report.checks().stream()
                .filter(check -> !check.passed())
                .map(check -> check.name() + ": " + check.message())
                .toList();
        assertThat(failed).as("failed checks").isEmpty();
    }

    @Test
    @DisplayName("REQ-API-002 a seeded transfer mix over HTTP passes every named check, and the engine agrees")
    void transferLoadHoldsEveryInvariant() {
        HttpLoadCheck.Options options = new HttpLoadCheck.Options(600, 32, 7L, 0, ledger(), threads(), true, false);

        HttpLoadCheck.Report report = HttpLoadCheck.run(baseUrl(), options);

        System.out.println("LOADCHECK " + getClass().getSimpleName() + " requests=" + report.requests() + " in "
                + report.duration().toMillis() + " ms, statuses=" + report.statusCounts());
        assertNoCheckFailed(report);
        assertThat(report.checks()).extracting(CheckResult::name).hasSize(9);
        TransferObservation seen = report.transfers();
        assertThat(report.statusCounts()).as("the mix really produced these").containsKeys("201", "422", "400");
        assertThat(seen.answers())
                .extracting(TransferLoad.Answer::phase)
                .contains(TransferLoad.Phase.CONCURRENT_COPY, TransferLoad.Phase.REPLAY);
        assertThat(report.requests()).isGreaterThan(options.operations());
        assertEngineInvariants(seen);
    }

    /** The server's own view: the engine's six invariants over what it holds, with the client's accepted ids. */
    private void assertEngineInvariants(TransferObservation seen) {
        SettlementRuntime runtime = runtime();
        Map<AccountId, Money> opening = new LinkedHashMap<>();
        seen.openingBalances().forEach((id, balance) -> opening.put(AccountId.of(id), Money.of(balance)));
        List<TransferId> accepted =
                seen.postedIds().stream().map(TransferId::of).toList();

        InvariantReport report = InvariantChecker.checkRun(
                opening,
                runtime.engine().snapshot(),
                runtime.engine().postings(),
                accepted,
                runtime.generalLedger().entries());

        assertThat(report.allPassed()).as(report.toString()).isTrue();
    }

    @Test
    @DisplayName("REQ-RUN-003 one FLAKY run over HTTP: events end in one report, and every run check passes")
    void runPassesItsChecks() {
        HttpLoadCheck.Options options = new HttpLoadCheck.Options(0, 1, 7L, 1_000, ledger(), threads(), false, true);

        HttpLoadCheck.Report report = HttpLoadCheck.run(baseUrl(), options);

        System.out.println("LOADCHECK " + getClass().getSimpleName() + " requests=" + report.requests() + " in "
                + report.duration().toMillis() + " ms, statuses=" + report.statusCounts());
        assertNoCheckFailed(report);
        assertThat(report.checks()).extracting(CheckResult::name).hasSize(10);
        assertThat(report.routeCounts())
                .containsOnlyKeys("POST /api/v1/runs", "GET /api/v1/runs/{id}/events", "GET /api/v1/runs/{id}");
    }
}
