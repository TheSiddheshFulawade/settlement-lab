package com.siddheshfulawade.settlementlab.api;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The named checks over a {@link RunLoad.RunObservation} (SPEC section 18, docs/api-contract.md section 2). */
final class RunChecks {

    static final String ACCEPTED = "RUN_ACCEPTED";
    static final String ONE_REPORT_LAST = "RUN_ONE_REPORT_AND_IT_IS_LAST";
    static final String STREAM_CLOSES = "RUN_STREAM_CLOSES_AFTER_REPORT";
    static final String HAS_METRICS = "RUN_HAS_A_METRICS_EVENT";
    static final String INVARIANTS = "RUN_ALL_SIX_INVARIANTS_PASSED";
    static final String TOTALS = "RUN_OPENING_TOTAL_EQUALS_CLOSING_TOTAL";
    static final String NO_DUPLICATES = "RUN_NO_DUPLICATE_APPLIED";
    static final String COMPLETED = "RUN_COMPLETED_EVERY_TRANSFER";
    static final String GET_MATCHES = "RUN_GET_RETURNS_THE_SAME_REPORT";
    static final String REPORT_MATCHES_REQUEST = "RUN_REPORT_IS_FOR_THIS_REQUEST";

    private RunChecks() {}

    static List<CheckResult> evaluate(RunLoad.RunObservation o) {
        List<CheckResult> results = new ArrayList<>();
        results.add(accepted(o));
        if (o.acceptStatus() != 202) {
            return results; // nothing else can be judged
        }
        List<SseText.Event> events = SseText.parse(o.events().text());
        Map<String, Object> report = reportOf(events);
        results.add(oneReportLast(events));
        results.add(streamCloses(o, events));
        results.add(hasMetrics(events));
        results.add(invariants(report));
        results.add(totals(report));
        results.add(noDuplicates(report));
        results.add(completed(o, report));
        results.add(getMatches(o, report));
        results.add(reportMatchesRequest(o, report));
        return results;
    }

    static CheckResult accepted(RunLoad.RunObservation o) {
        boolean ok = o.acceptStatus() == 202 && o.location().matches("/api/v1/runs/[A-Za-z0-9-]{1,64}");
        return CheckResult.of(
                ACCEPTED,
                ok,
                ok
                        ? "202 with Location " + o.location()
                        : "expected 202 with a Location header, got %d %s".formatted(o.acceptStatus(), o.acceptBody()));
    }

    static CheckResult oneReportLast(List<SseText.Event> events) {
        long reports = events.stream().filter(e -> "report".equals(e.name())).count();
        boolean last = !events.isEmpty()
                && "report".equals(events.get(events.size() - 1).name());
        return CheckResult.of(
                ONE_REPORT_LAST,
                reports == 1 && last,
                "%d report events among %d events, last event is %s"
                        .formatted(
                                reports,
                                events.size(),
                                events.isEmpty()
                                        ? "missing"
                                        : events.get(events.size() - 1).name()));
    }

    /** The server ended the stream by itself, and nothing but whitespace follows the report. */
    static CheckResult streamCloses(RunLoad.RunObservation o, List<SseText.Event> events) {
        boolean endsWithReport = !events.isEmpty()
                && "report".equals(events.get(events.size() - 1).name());
        String text = o.events().text();
        // the last event is complete: the text ends with a blank line
        boolean nothingAfter = text.endsWith("\n\n") || text.endsWith("\r\n\r\n");
        boolean ok = o.events().closedByServer() && endsWithReport && nothingAfter;
        return CheckResult.of(
                STREAM_CLOSES,
                ok,
                ok
                        ? "the server closed the stream right after the report"
                        : "stream closed by server: %b, ends with report: %b, nothing after report: %b"
                                .formatted(o.events().closedByServer(), endsWithReport, nothingAfter));
    }

    static CheckResult hasMetrics(List<SseText.Event> events) {
        long metrics = events.stream().filter(e -> "metrics".equals(e.name())).count();
        return CheckResult.of(HAS_METRICS, metrics >= 1, metrics + " metrics events before the report");
    }

    /** {@code allPassed} is true and each of the six invariants is present, in order, by name, and passed. */
    @SuppressWarnings("unchecked")
    static CheckResult invariants(Map<String, Object> report) {
        if (report == null) {
            return CheckResult.of(INVARIANTS, false, "no report to read");
        }
        List<Map<String, Object>> list = (List<Map<String, Object>>) report.get("invariants");
        List<String> names = list == null
                ? List.of()
                : list.stream().map(i -> String.valueOf(i.get("name"))).toList();
        List<String> failed = list == null
                ? List.of()
                : list.stream()
                        .filter(i -> !Boolean.TRUE.equals(i.get("passed")))
                        .map(i -> i.get("name") + ": " + i.get("counterexample"))
                        .toList();
        boolean ok = names.equals(RunHttp.INVARIANT_NAMES)
                && failed.isEmpty()
                && Boolean.TRUE.equals(report.get("allPassed"));
        return CheckResult.of(
                INVARIANTS,
                ok,
                ok
                        ? "allPassed with " + names
                        : "invariants %s, failed %s, allPassed=%s".formatted(names, failed, report.get("allPassed")));
    }

    static CheckResult totals(Map<String, Object> report) {
        if (report == null) {
            return CheckResult.of(TOTALS, false, "no report to read");
        }
        Object opening = report.get("openingTotal");
        Object closing = report.get("closingTotal");
        return CheckResult.of(
                TOTALS, opening != null && opening.equals(closing), "opening " + opening + ", closing " + closing);
    }

    static CheckResult noDuplicates(Map<String, Object> report) {
        if (report == null) {
            return CheckResult.of(NO_DUPLICATES, false, "no report to read");
        }
        Object applied = report.get("duplicatesApplied");
        return CheckResult.of(
                NO_DUPLICATES,
                Integer.valueOf(0).equals(applied) || Long.valueOf(0).equals(applied),
                "duplicatesSent %s, duplicatesApplied %s".formatted(report.get("duplicatesSent"), applied));
    }

    static CheckResult completed(RunLoad.RunObservation o, Map<String, Object> report) {
        if (report == null) {
            return CheckResult.of(COMPLETED, false, "no report to read");
        }
        Object done = report.get("transfersCompleted");
        boolean ok = done instanceof Number n
                && n.longValue() == o.transfers()
                && Boolean.FALSE.equals(report.get("stoppedEarly"));
        return CheckResult.of(
                COMPLETED,
                ok,
                "transfersCompleted %s of %d, stoppedEarly %s"
                        .formatted(done, o.transfers(), report.get("stoppedEarly")));
    }

    /** GET after the run is the same JSON document as the report event. */
    static CheckResult getMatches(RunLoad.RunObservation o, Map<String, Object> report) {
        Map<String, Object> fromGet = parse(o.getBody());
        boolean ok = o.getStatus() == 200 && report != null && report.equals(fromGet);
        return CheckResult.of(
                GET_MATCHES,
                ok,
                ok
                        ? "GET answered the same report as the event"
                        : "GET answered %d; its body differs from the report event".formatted(o.getStatus()));
    }

    /** The report is about the run that was started (same runId as the 202) with the settings that were sent. */
    static CheckResult reportMatchesRequest(RunLoad.RunObservation o, Map<String, Object> report) {
        if (report == null) {
            return CheckResult.of(REPORT_MATCHES_REQUEST, false, "no report to read");
        }
        Object acceptedId =
                parse(o.acceptBody()) == null ? null : parse(o.acceptBody()).get("runId");
        Object sentSettings = parse(o.requestBody());
        boolean sameRun = acceptedId != null && acceptedId.equals(report.get("runId"));
        boolean sameSettings = sentSettings != null && sentSettings.equals(report.get("settings"));
        return CheckResult.of(
                REPORT_MATCHES_REQUEST,
                sameRun && sameSettings,
                "runId accepted %s, in report %s; settings sent %s, in report %s"
                        .formatted(acceptedId, report.get("runId"), sentSettings, report.get("settings")));
    }

    private static Map<String, Object> reportOf(List<SseText.Event> events) {
        return events.stream()
                .filter(e -> "report".equals(e.name()))
                .reduce((first, second) -> second)
                .map(e -> parse(e.data()))
                .orElse(null);
    }

    private static Map<String, Object> parse(String json) {
        try {
            return JsonPath.read(json, "$");
        } catch (RuntimeException e) {
            return null;
        }
    }
}
