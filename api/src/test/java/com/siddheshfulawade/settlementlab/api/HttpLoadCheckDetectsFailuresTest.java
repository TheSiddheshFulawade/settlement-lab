package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A check that cannot fail proves nothing. Each test starts from an observation where every check
 * passes, damages it the way a real bug would, and expects exactly the named check to turn red.
 */
class HttpLoadCheckDetectsFailuresTest {

    private static final TransferPlan.Op A = new TransferPlan.Op("a", 1, 2, 30, TransferPlan.Kind.NORMAL, 2);
    private static final TransferPlan.Op B = new TransferPlan.Op("b", 2, 3, 10, TransferPlan.Kind.NORMAL, 1);
    private static final TransferPlan.Op C = new TransferPlan.Op("c", 1, 3, 1_000, TransferPlan.Kind.OVERDRAW, 1);
    private static final TransferPlan.Op D = new TransferPlan.Op("d", 2, 2, 5, TransferPlan.Kind.INVALID, 1);

    private static TransferLoad.Answer answer(String id, TransferLoad.Phase phase, int status, String outcome) {
        return new TransferLoad.Answer(id, phase, status, outcome);
    }

    /** a: 30 from 1 to 2 (sent twice at once, then again late), b: 10 from 2 to 3, c: refused, d: invalid. */
    private static List<TransferLoad.Answer> goodAnswers() {
        return List.of(
                answer("a", TransferLoad.Phase.FIRST, 201, "POSTED"),
                answer("a", TransferLoad.Phase.CONCURRENT_COPY, 201, "POSTED"),
                answer("b", TransferLoad.Phase.FIRST, 201, "POSTED"),
                answer("c", TransferLoad.Phase.FIRST, 422, "REJECTED_FUNDS"),
                answer("d", TransferLoad.Phase.FIRST, 400, "REJECTED_INVALID"),
                answer("a", TransferLoad.Phase.REPLAY, 201, "POSTED"));
    }

    private static ServerReads.Metrics before() {
        return new ServerReads.Metrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    /** 6 requests reached the engine, 4 of them claimed an id, 2 were duplicates; 2 postings, 2 GL entries. */
    private static ServerReads.Metrics goodAfter() {
        return new ServerReads.Metrics(6, 2, 1, 1, 0, 0, 2, 0, 0, 0, 2, 0);
    }

    private static TransferObservation good() {
        return new TransferObservation(
                List.of(A, B, C, D),
                goodAnswers(),
                Map.of(1L, 100L, 2L, 100L, 3L, 100L),
                Map.of(1L, 70L, 2L, 120L, 3L, 110L),
                before(),
                goodAfter());
    }

    private static TransferObservation with(
            UnaryOperator<List<TransferLoad.Answer>> answers, Map<Long, Long> closing, ServerReads.Metrics after) {
        TransferObservation base = good();
        return new TransferObservation(
                base.ops(), answers.apply(base.answers()), base.openingBalances(), closing, before(), after);
    }

    private static List<String> failedNames(TransferObservation observation) {
        return TransferChecks.evaluate(observation).stream()
                .filter(check -> !check.passed())
                .map(CheckResult::name)
                .toList();
    }

    private static TransferObservation withAnswers(UnaryOperator<List<TransferLoad.Answer>> answers) {
        return with(answers, good().closingBalances(), goodAfter());
    }

    @Test
    @DisplayName("the untouched observation passes all nine transfer checks")
    void goodObservationPasses() {
        assertThat(TransferChecks.evaluate(good())).hasSize(9);
        assertThat(failedNames(good())).isEmpty();
    }

    @Test
    @DisplayName("credit without debit turns TOTAL_CONSERVED and the balance check red")
    void creditWithoutDebit() {
        TransferObservation o = with(l -> l, Map.of(1L, 70L, 2L, 120L, 3L, 140L), goodAfter());

        assertThat(failedNames(o)).contains(TransferChecks.TOTAL_CONSERVED, TransferChecks.BALANCES_MATCH_201S);
        assertThat(TransferChecks.totalConserved(o).message()).contains("created");
    }

    @Test
    @DisplayName("a negative balance turns NO_NEGATIVE_BALANCE red")
    void negativeBalance() {
        TransferObservation o = with(l -> l, Map.of(1L, -30L, 2L, 120L, 3L, 110L), goodAfter());

        assertThat(failedNames(o)).contains(TransferChecks.NO_NEGATIVE_BALANCE);
    }

    @Test
    @DisplayName("a duplicate applied twice (balances moved by 60) turns the 201-balances check red, total still holds")
    void duplicateAppliedTwice() {
        TransferObservation o = with(l -> l, Map.of(1L, 40L, 2L, 150L, 3L, 110L), goodAfter());

        assertThat(failedNames(o)).containsExactly(TransferChecks.BALANCES_MATCH_201S);
    }

    @Test
    @DisplayName(
            "REQ-API-002 a late repeat answered 422 instead of the original 201 turns REPEATS_GET_ORIGINAL_STATUS red")
    void repeatGetsDifferentStatus() {
        TransferObservation o = withAnswers(list -> {
            List<TransferLoad.Answer> changed = new ArrayList<>(list.subList(0, 5));
            changed.add(answer("a", TransferLoad.Phase.REPLAY, 422, "REJECTED_FUNDS"));
            return changed;
        });

        assertThat(failedNames(o)).contains(TransferChecks.REPEATS_GET_ORIGINAL);
    }

    @Test
    @DisplayName("a transfer that never got a decided answer turns ALL_ANSWERED red")
    void unansweredTransfer() {
        TransferObservation o = withAnswers(
                list -> list.stream().filter(a -> !a.id().equals("b")).toList());

        assertThat(failedNames(o)).contains(TransferChecks.ALL_ANSWERED);
    }

    @Test
    @DisplayName("an over-the-top amount that was accepted turns EXPECTED_STATUS_PER_KIND red")
    void overdrawAccepted() {
        TransferObservation o = withAnswers(list -> list.stream()
                .map(a -> a.id().equals("c") ? answer("c", a.phase(), 201, "POSTED") : a)
                .toList());

        assertThat(failedNames(o)).contains(TransferChecks.EXPECTED_STATUS_PER_KIND);
    }

    @Test
    @DisplayName("an engine that counts 3 postings while the client saw 2 turns METRICS_AGREE_WITH_CLIENT red")
    void metricsDisagree() {
        ServerReads.Metrics after = new ServerReads.Metrics(6, 3, 1, 1, 0, 0, 2, 0, 0, 0, 2, 0);

        TransferObservation o = with(l -> l, good().closingBalances(), after);

        assertThat(failedNames(o)).containsExactly(TransferChecks.METRICS_AGREE);
        assertThat(TransferChecks.metricsAgree(o).message()).contains("posted: engine says 3, client expects 2");
    }

    @Test
    @DisplayName("a duplicate that the engine did not count as one turns METRICS_AGREE_WITH_CLIENT red")
    void duplicateNotCounted() {
        ServerReads.Metrics after = new ServerReads.Metrics(6, 2, 1, 1, 0, 0, 0, 0, 0, 0, 2, 0);

        TransferObservation o = with(l -> l, good().closingBalances(), after);

        assertThat(failedNames(o)).contains(TransferChecks.METRICS_AGREE);
    }

    @Test
    @DisplayName("a dropped general-ledger posting turns GL_ONE_ENTRY_PER_POSTING red")
    void glMissingAPosting() {
        ServerReads.Metrics after = new ServerReads.Metrics(6, 2, 1, 1, 0, 0, 2, 0, 0, 0, 1, 0);

        TransferObservation o = with(l -> l, good().closingBalances(), after);

        assertThat(failedNames(o)).containsExactly(TransferChecks.GL_ONE_ENTRY);
        assertThat(TransferChecks.glOneEntryPerPosting(o).message()).contains("gained 1 entries for 2 postings");
    }

    // ---- a load that proves nothing must not pass (audit M-1) ----

    private static TransferObservation observation(
            List<TransferPlan.Op> ops, List<TransferLoad.Answer> answers, ServerReads.Metrics after) {
        return new TransferObservation(
                ops, answers, good().openingBalances(), good().closingBalances(), before(), after);
    }

    @Test
    @DisplayName("REQ-API-002 a server that answers every NORMAL transfer 422 turns only MIX_PRODUCED red")
    void serverAnswersEveryNormal422() {
        List<TransferLoad.Answer> answers = List.of(
                answer("a", TransferLoad.Phase.FIRST, 422, "REJECTED_FUNDS"),
                answer("a", TransferLoad.Phase.CONCURRENT_COPY, 422, "REJECTED_FUNDS"),
                answer("b", TransferLoad.Phase.FIRST, 422, "REJECTED_FUNDS"),
                answer("c", TransferLoad.Phase.FIRST, 422, "REJECTED_FUNDS"),
                answer("d", TransferLoad.Phase.FIRST, 400, "REJECTED_INVALID"),
                answer("a", TransferLoad.Phase.REPLAY, 422, "REJECTED_FUNDS"));
        TransferObservation o = new TransferObservation(
                List.of(A, B, C, D),
                answers,
                Map.of(1L, 100L, 2L, 100L, 3L, 100L),
                Map.of(1L, 100L, 2L, 100L, 3L, 100L),
                before(),
                new ServerReads.Metrics(6, 0, 3, 1, 0, 0, 2, 0, 0, 0, 0, 0));

        assertThat(failedNames(o)).containsExactly(TransferChecks.MIX_PRODUCED);
        assertThat(TransferChecks.mixProduced(o).message()).contains("POSTED");
    }

    @Test
    @DisplayName(
            "a load where nothing was sent twice turns MIX_PRODUCED red, and the message names the small-run cause")
    void noRepeatedRequest() {
        List<TransferLoad.Answer> answers = List.of(
                answer("a", TransferLoad.Phase.FIRST, 201, "POSTED"),
                answer("b", TransferLoad.Phase.FIRST, 201, "POSTED"),
                answer("c", TransferLoad.Phase.FIRST, 422, "REJECTED_FUNDS"),
                answer("d", TransferLoad.Phase.FIRST, 400, "REJECTED_INVALID"));
        TransferObservation o =
                observation(List.of(A, B, C, D), answers, new ServerReads.Metrics(4, 2, 1, 1, 0, 0, 0, 0, 0, 0, 2, 0));

        assertThat(failedNames(o)).containsExactly(TransferChecks.MIX_PRODUCED);
        assertThat(TransferChecks.mixProduced(o).message()).contains("repeated").contains("--operations");
    }

    @Test
    @DisplayName("a load with no REJECTED_FUNDS answer turns MIX_PRODUCED red")
    void noRejectedFunds() {
        List<TransferLoad.Answer> answers = List.of(
                answer("a", TransferLoad.Phase.FIRST, 201, "POSTED"),
                answer("a", TransferLoad.Phase.CONCURRENT_COPY, 201, "POSTED"),
                answer("b", TransferLoad.Phase.FIRST, 201, "POSTED"),
                answer("d", TransferLoad.Phase.FIRST, 400, "REJECTED_INVALID"),
                answer("a", TransferLoad.Phase.REPLAY, 201, "POSTED"));
        TransferObservation o =
                observation(List.of(A, B, D), answers, new ServerReads.Metrics(5, 2, 0, 1, 0, 0, 2, 0, 0, 0, 2, 0));

        assertThat(failedNames(o)).containsExactly(TransferChecks.MIX_PRODUCED);
        assertThat(TransferChecks.mixProduced(o).message()).contains("REJECTED_FUNDS");
    }

    @Test
    @DisplayName("a load with no REJECTED_INVALID answer turns MIX_PRODUCED red")
    void noRejectedInvalid() {
        List<TransferLoad.Answer> answers = List.of(
                answer("a", TransferLoad.Phase.FIRST, 201, "POSTED"),
                answer("a", TransferLoad.Phase.CONCURRENT_COPY, 201, "POSTED"),
                answer("b", TransferLoad.Phase.FIRST, 201, "POSTED"),
                answer("c", TransferLoad.Phase.FIRST, 422, "REJECTED_FUNDS"),
                answer("a", TransferLoad.Phase.REPLAY, 201, "POSTED"));
        TransferObservation o =
                observation(List.of(A, B, C), answers, new ServerReads.Metrics(5, 2, 1, 0, 0, 0, 2, 0, 0, 0, 2, 0));

        assertThat(failedNames(o)).containsExactly(TransferChecks.MIX_PRODUCED);
        assertThat(TransferChecks.mixProduced(o).message()).contains("REJECTED_INVALID");
    }

    @Test
    @DisplayName("a load with no transfers at all fails MIX_PRODUCED instead of passing vacuously")
    void emptyLoad() {
        TransferObservation o = new TransferObservation(
                List.of(), List.of(), Map.of(1L, 100L, 2L, 100L), Map.of(1L, 100L, 2L, 100L), before(), before());

        assertThat(failedNames(o)).contains(TransferChecks.MIX_PRODUCED);
    }

    @Test
    @DisplayName("a NORMAL transfer answered 201 with the outcome REJECTED_FUNDS turns EXPECTED_STATUS_PER_KIND red")
    void normalWithWrongOutcome() {
        TransferObservation o = withAnswers(list -> list.stream()
                .map(a -> a.id().equals("b") ? answer("b", a.phase(), 201, "REJECTED_FUNDS") : a)
                .toList());

        assertThat(failedNames(o)).contains(TransferChecks.EXPECTED_STATUS_PER_KIND);
    }

    @Test
    @DisplayName("a NORMAL transfer answered 422 with the outcome POSTED or REJECTED_INVALID turns EXPECTED_STATUS red")
    void normalWith422AndWrongOutcome() {
        for (String outcome : List.of("POSTED", "REJECTED_INVALID")) {
            TransferObservation o = withAnswers(list -> list.stream()
                    .map(a -> a.id().equals("b") ? answer("b", a.phase(), 422, outcome) : a)
                    .toList());

            assertThat(failedNames(o)).as(outcome).contains(TransferChecks.EXPECTED_STATUS_PER_KIND);
        }
    }

    @Test
    @DisplayName("an empty list of checks is not a pass")
    void emptyReportDoesNotPass() {
        HttpLoadCheck.Report empty =
                new HttpLoadCheck.Report(List.of(), 0, java.time.Duration.ZERO, Map.of(), Map.of(), null);

        assertThat(empty.passed()).isFalse();
    }

    // ---- the run checks ----

    private static final String SETTINGS =
            "{\"ledger\":\"LOCKING\",\"threads\":\"VIRTUAL\",\"faults\":\"FLAKY\",\"transfers\":100,\"duplicatePercent\":2}";

    private static final String GOOD_REPORT = "{\"runId\":\"r-1\",\"status\":\"DONE\",\"stoppedEarly\":false,"
            + "\"settings\":" + SETTINGS + ","
            + "\"transfersCompleted\":100,\"openingTotal\":10000000,\"closingTotal\":10000000,"
            + "\"duplicatesSent\":2,\"duplicatesApplied\":0,\"invariants\":[%s],\"allPassed\":true}";

    private static String invariants(String failing) {
        List<String> parts = new ArrayList<>();
        for (String name : RunHttp.INVARIANT_NAMES) {
            boolean passed = !name.equals(failing);
            parts.add("{\"name\":\"%s\",\"passed\":%b,\"counterexample\":%s}"
                    .formatted(name, passed, passed ? "null" : "\"boom\""));
        }
        return String.join(",", parts);
    }

    private static String reportEvent(String json) {
        return "event: report\ndata: " + json + "\n\n";
    }

    private static final String METRICS_EVENT = "event: metrics\ndata: {\"elapsedMs\":250}\n\n";

    private static RunLoad.RunObservation run(String text, boolean closed, String getBody, int transfers) {
        return new RunLoad.RunObservation(
                transfers,
                202,
                "{\"runId\":\"r-1\"}",
                "/api/v1/runs/r-1",
                new LoadHttp.Stream(200, text, closed),
                200,
                getBody,
                RunHttp.body("LOCKING", "VIRTUAL", "FLAKY", transfers, 2));
    }

    private static List<String> failedRun(RunLoad.RunObservation o) {
        return RunChecks.evaluate(o).stream()
                .filter(c -> !c.passed())
                .map(CheckResult::name)
                .toList();
    }

    private static String goodReport() {
        return GOOD_REPORT.formatted(invariants(null));
    }

    @Test
    @DisplayName("the untouched run observation passes all ten run checks")
    void goodRunPasses() {
        RunLoad.RunObservation o = run(METRICS_EVENT + reportEvent(goodReport()), true, goodReport(), 100);

        assertThat(RunChecks.evaluate(o)).hasSize(10);
        assertThat(failedRun(o)).isEmpty();
    }

    @Test
    @DisplayName("REQ-RUN-003 two reports, or events after the report, turn RUN_ONE_REPORT_AND_IT_IS_LAST red")
    void twoReports() {
        String text = METRICS_EVENT + reportEvent(goodReport()) + reportEvent(goodReport());
        String after = METRICS_EVENT + reportEvent(goodReport()) + METRICS_EVENT;

        assertThat(failedRun(run(text, true, goodReport(), 100))).contains(RunChecks.ONE_REPORT_LAST);
        assertThat(failedRun(run(after, true, goodReport(), 100))).contains(RunChecks.ONE_REPORT_LAST);
    }

    @Test
    @DisplayName("REQ-RUN-003 a stream the server never closes turns RUN_STREAM_CLOSES_AFTER_REPORT red")
    void streamNotClosed() {
        RunLoad.RunObservation o = run(METRICS_EVENT + reportEvent(goodReport()), false, goodReport(), 100);

        assertThat(failedRun(o)).containsExactly(RunChecks.STREAM_CLOSES);
    }

    @Test
    @DisplayName("a run with no metrics event turns RUN_HAS_A_METRICS_EVENT red")
    void noMetrics() {
        assertThat(failedRun(run(reportEvent(goodReport()), true, goodReport(), 100)))
                .containsExactly(RunChecks.HAS_METRICS);
    }

    @Test
    @DisplayName("REQ-RUN-010 one failed invariant turns RUN_ALL_SIX_INVARIANTS_PASSED red and names it")
    void invariantFailed() {
        String report = GOOD_REPORT.formatted(invariants("GL_MATCHES_POSTINGS"));
        RunLoad.RunObservation o = run(METRICS_EVENT + reportEvent(report), true, report, 100);

        assertThat(failedRun(o)).contains(RunChecks.INVARIANTS);
        assertThat(RunChecks.invariants(RunHttp.json(report)).message()).contains("GL_MATCHES_POSTINGS: boom");
    }

    @Test
    @DisplayName("a closing total different from the opening total turns RUN_OPENING_TOTAL_EQUALS_CLOSING_TOTAL red")
    void totalsDiffer() {
        String report = goodReport().replace("\"closingTotal\":10000000", "\"closingTotal\":9999999");

        assertThat(failedRun(run(METRICS_EVENT + reportEvent(report), true, report, 100)))
                .contains(RunChecks.TOTALS);
    }

    @Test
    @DisplayName("a duplicate applied turns RUN_NO_DUPLICATE_APPLIED red")
    void duplicateApplied() {
        String report = goodReport().replace("\"duplicatesApplied\":0", "\"duplicatesApplied\":1");

        assertThat(failedRun(run(METRICS_EVENT + reportEvent(report), true, report, 100)))
                .contains(RunChecks.NO_DUPLICATES);
    }

    @Test
    @DisplayName("REQ-RUN-005 fewer transfers than asked, or stoppedEarly, turns RUN_COMPLETED_EVERY_TRANSFER red")
    void notCompleted() {
        String fewer = goodReport().replace("\"transfersCompleted\":100", "\"transfersCompleted\":99");
        String early = goodReport().replace("\"stoppedEarly\":false", "\"stoppedEarly\":true");

        assertThat(failedRun(run(METRICS_EVENT + reportEvent(fewer), true, fewer, 100)))
                .contains(RunChecks.COMPLETED);
        assertThat(failedRun(run(METRICS_EVENT + reportEvent(early), true, early, 100)))
                .contains(RunChecks.COMPLETED);
    }

    @Test
    @DisplayName("a GET body that differs from the report event turns RUN_GET_RETURNS_THE_SAME_REPORT red")
    void getDiffers() {
        String other = goodReport().replace("\"duplicatesSent\":2", "\"duplicatesSent\":3");

        assertThat(failedRun(run(METRICS_EVENT + reportEvent(goodReport()), true, other, 100)))
                .containsExactly(RunChecks.GET_MATCHES);
    }

    @Test
    @DisplayName("a report with another runId than the 202 turns RUN_REPORT_IS_FOR_THIS_REQUEST red")
    void reportForAnotherRun() {
        String other = goodReport().replace("\"runId\":\"r-1\"", "\"runId\":\"r-9\"");

        assertThat(failedRun(run(METRICS_EVENT + reportEvent(other), true, other, 100)))
                .contains(RunChecks.REPORT_MATCHES_REQUEST);
    }

    @Test
    @DisplayName("a report whose settings differ from what was sent turns RUN_REPORT_IS_FOR_THIS_REQUEST red")
    void reportWithOtherSettings() {
        String ledger = goodReport().replace("\"ledger\":\"LOCKING\"", "\"ledger\":\"CAS\"");
        String size = goodReport().replace("\"duplicatePercent\":2", "\"duplicatePercent\":0");

        assertThat(failedRun(run(METRICS_EVENT + reportEvent(ledger), true, ledger, 100)))
                .contains(RunChecks.REPORT_MATCHES_REQUEST);
        assertThat(failedRun(run(METRICS_EVENT + reportEvent(size), true, size, 100)))
                .contains(RunChecks.REPORT_MATCHES_REQUEST);
    }

    @Test
    @DisplayName("a report without settings turns RUN_REPORT_IS_FOR_THIS_REQUEST red")
    void reportWithoutSettings() {
        String bare = goodReport().replace("\"settings\":" + SETTINGS + ",", "");

        assertThat(failedRun(run(METRICS_EVENT + reportEvent(bare), true, bare, 100)))
                .containsExactly(RunChecks.REPORT_MATCHES_REQUEST);
    }

    @Test
    @DisplayName("a run the server refuses fails RUN_ACCEPTED and nothing else is judged")
    void runRefused() {
        RunLoad.RunObservation o = new RunLoad.RunObservation(
                100, 429, "{\"status\":429}", "", new LoadHttp.Stream(0, "", false), 0, "", "{}");

        assertThat(failedRun(o)).containsExactly(RunChecks.ACCEPTED);
        assertThat(RunChecks.evaluate(o)).hasSize(1);
    }
}
