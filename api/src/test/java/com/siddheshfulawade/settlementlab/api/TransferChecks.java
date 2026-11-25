package com.siddheshfulawade.settlementlab.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The named checks over a {@link TransferObservation}. Each has its own name and its own message, so a
 * red line says what broke without reading code.
 */
final class TransferChecks {

    static final String ALL_ANSWERED = "ALL_ANSWERED";
    static final String EXPECTED_STATUS_PER_KIND = "EXPECTED_STATUS_PER_KIND";
    static final String TOTAL_CONSERVED = "TOTAL_CONSERVED";
    static final String NO_NEGATIVE_BALANCE = "NO_NEGATIVE_BALANCE";
    static final String BALANCES_MATCH_201S = "BALANCES_MATCH_OPENING_PLUS_201S";
    static final String REPEATS_GET_ORIGINAL = "REPEATS_GET_ORIGINAL_STATUS";
    static final String METRICS_AGREE = "METRICS_AGREE_WITH_CLIENT";
    static final String GL_ONE_ENTRY = "GL_ONE_ENTRY_PER_POSTING";
    static final String MIX_PRODUCED = "MIX_PRODUCED";

    private TransferChecks() {}

    static List<CheckResult> evaluate(TransferObservation o) {
        return List.of(
                allAnswered(o),
                expectedStatusPerKind(o),
                totalConserved(o),
                noNegativeBalance(o),
                balancesMatch201s(o),
                repeatsGetOriginal(o),
                metricsAgree(o),
                glOneEntryPerPosting(o),
                mixProduced(o));
    }

    /**
     * Guards every other check against passing on an empty or one-sided load: the run must really have
     * produced a posting, a refusal for funds, a refusal as invalid, and a request sent more than once.
     */
    static CheckResult mixProduced(TransferObservation o) {
        List<String> missing = new ArrayList<>();
        if (o.decidedWith("POSTED") == 0) {
            missing.add("no POSTED transfer");
        }
        if (o.decidedWith("REJECTED_FUNDS") == 0) {
            missing.add("no REJECTED_FUNDS transfer");
        }
        if (o.decidedWith("REJECTED_INVALID") == 0) {
            missing.add("no REJECTED_INVALID transfer");
        }
        long decidedAnswers =
                o.answers().stream().filter(TransferLoad.Answer::decided).count();
        if (decidedAnswers <= o.originals().size()) {
            missing.add("no repeated request (an id answered twice)");
        }
        return CheckResult.of(
                MIX_PRODUCED,
                missing.isEmpty(),
                missing.isEmpty()
                        ? "the load produced posted, rejected-funds and rejected-invalid transfers and repeated requests"
                        : "the load did not produce the whole mix: %s. A very small --operations can cause this; use more."
                                .formatted(String.join(", ", missing)));
    }

    /** Every transfer id ended with a decided answer (201, 422 or 400): no lost request, no 503 left after the retries. */
    static CheckResult allAnswered(TransferObservation o) {
        Map<String, TransferLoad.Answer> originals = o.originals();
        List<String> undecided = o.ops().stream()
                .map(TransferPlan.Op::id)
                .filter(id -> !originals.containsKey(id))
                .toList();
        long noAnswer = o.answers().stream().filter(a -> a.status() < 0).count();
        return CheckResult.of(
                ALL_ANSWERED,
                undecided.isEmpty() && noAnswer == 0,
                undecided.isEmpty() && noAnswer == 0
                        ? "all %d transfers were decided".formatted(o.ops().size())
                        : "%d transfers never got a decided answer (first: %s); %d requests had no answer at all"
                                .formatted(
                                        undecided.size(),
                                        undecided.stream().limit(3).toList(),
                                        noAnswer));
    }

    /** Over-the-top amounts are 422, invalid ones 400, normal ones 201 or (if the source ran dry) 422. */
    static CheckResult expectedStatusPerKind(TransferObservation o) {
        Map<String, TransferPlan.Op> ops = o.opsById();
        List<String> wrong = new ArrayList<>();
        for (TransferLoad.Answer answer : o.originals().values()) {
            TransferPlan.Op op = ops.get(answer.id());
            boolean ok = switch (op.kind()) {
                case OVERDRAW -> answer.status() == 422 && "REJECTED_FUNDS".equals(answer.outcome());
                case INVALID -> answer.status() == 400 && "REJECTED_INVALID".equals(answer.outcome());
                case NORMAL ->
                    (answer.status() == 201 && "POSTED".equals(answer.outcome()))
                            || (answer.status() == 422 && "REJECTED_FUNDS".equals(answer.outcome()));
            };
            if (!ok) {
                wrong.add("%s (%s) got %d %s".formatted(op.id(), op.kind(), answer.status(), answer.outcome()));
            }
        }
        return CheckResult.of(
                EXPECTED_STATUS_PER_KIND,
                wrong.isEmpty(),
                wrong.isEmpty()
                        ? "every transfer got the status its kind calls for"
                        : "%d transfers got an unexpected status, first: %s"
                                .formatted(wrong.size(), wrong.stream().limit(3).toList()));
    }

    static CheckResult totalConserved(TransferObservation o) {
        long opening = sum(o.openingBalances());
        long closing = sum(o.closingBalances());
        return CheckResult.of(
                TOTAL_CONSERVED,
                opening == closing,
                String.format(
                        Locale.ROOT,
                        "total before %,d, after %,d, difference %,d (money was %s)",
                        opening,
                        closing,
                        closing - opening,
                        closing > opening ? "created" : closing < opening ? "lost" : "conserved"));
    }

    static CheckResult noNegativeBalance(TransferObservation o) {
        List<String> negative = new ArrayList<>();
        o.closingBalances().forEach((id, balance) -> {
            if (balance < 0) {
                negative.add("account %d has %d".formatted(id, balance));
            }
        });
        return CheckResult.of(
                NO_NEGATIVE_BALANCE,
                negative.isEmpty(),
                negative.isEmpty() ? "no account is below zero" : "negative balances, first: " + negative.get(0));
    }

    /** Client's own expectation: opening balance plus the net effect of the distinct transfers it saw answered 201. */
    static CheckResult balancesMatch201s(TransferObservation o) {
        Map<Long, Long> expected = new TreeMap<>(o.openingBalances());
        Map<String, TransferPlan.Op> ops = o.opsById();
        for (String id : o.postedIds()) {
            TransferPlan.Op op = ops.get(id);
            expected.merge(op.source(), -op.amount(), Long::sum);
            expected.merge(op.target(), op.amount(), Long::sum);
        }
        List<String> differing = new ArrayList<>();
        expected.forEach((account, want) -> {
            Long actual = o.closingBalances().get(account);
            if (actual == null || actual.longValue() != want) {
                differing.add("account %d expected %d but is %s".formatted(account, want, actual));
            }
        });
        return CheckResult.of(
                BALANCES_MATCH_201S,
                differing.isEmpty(),
                differing.isEmpty()
                        ? "all %d balances equal opening + net of %d distinct 201 transfers"
                                .formatted(expected.size(), o.postedIds().size())
                        : "%d balances differ from opening + the 201 transfers, first: %s"
                                .formatted(differing.size(), differing.get(0)));
    }

    /** REQ-API-002: every repeat of an id, concurrent or late, is answered with the original status and outcome. */
    static CheckResult repeatsGetOriginal(TransferObservation o) {
        Map<String, TransferLoad.Answer> originals = o.originals();
        List<String> different = new ArrayList<>();
        long repeats = 0;
        for (TransferLoad.Answer answer : o.answers()) {
            TransferLoad.Answer original = originals.get(answer.id());
            // skipped: the original itself, an earlier 503 that was retried, and a request that got no answer
            boolean retriedLater = answer.status() == 503 && answer.phase() != TransferLoad.Phase.REPLAY;
            if (original == null || answer == original || retriedLater || answer.status() < 0) {
                continue;
            }
            repeats++;
            if (answer.status() != original.status() || !original.outcome().equals(answer.outcome())) {
                different.add("%s first answered %d %s, then %d %s"
                        .formatted(
                                answer.id(), original.status(), original.outcome(), answer.status(), answer.outcome()));
            }
        }
        return CheckResult.of(
                REPEATS_GET_ORIGINAL,
                different.isEmpty(),
                different.isEmpty()
                        ? "%d repeated requests all got the original status".formatted(repeats)
                        : "%d repeats got a different answer, first: %s".formatted(different.size(), different.get(0)));
    }

    /** The engine's counters, as a difference over the load, agree with what the client counted. */
    static CheckResult metricsAgree(TransferObservation o) {
        ServerReads.Metrics b = o.before();
        ServerReads.Metrics a = o.after();
        long submitted = a.submitted() - b.submitted();
        long duplicates = a.duplicates() - b.duplicates();
        long queueFull = a.queueFull() - b.queueFull();
        long contention = a.rejectedContention() - b.rejectedContention();
        long failed = a.failed() - b.failed();
        long withOutcome = o.answers().stream().filter(x -> x.outcome() != null).count();
        long answered503 = o.answers().stream().filter(x -> x.status() == 503).count();
        // each id is claimed by one owner; an owner ends as a decided id, or releases the id (503 or failure)
        long owners = submitted - duplicates;

        List<String> problems = new ArrayList<>();
        expect(problems, "posted", a.posted() - b.posted(), o.decidedWith("POSTED"));
        expect(problems, "rejectedFunds", a.rejectedFunds() - b.rejectedFunds(), o.decidedWith("REJECTED_FUNDS"));
        expect(
                problems,
                "rejectedInvalid",
                a.rejectedInvalid() - b.rejectedInvalid(),
                o.decidedWith("REJECTED_INVALID"));
        expect(problems, "submitted (answers that carried an outcome)", submitted, withOutcome);
        expect(
                problems,
                "owner claims (submitted - duplicates) = decided ids + released ids",
                owners,
                o.originals().size() + queueFull + contention + failed);
        expect(problems, "failed", failed, 0);
        expect(problems, "listenerErrors", a.listenerErrors() - b.listenerErrors(), 0);
        expect(problems, "publishErrors", a.publishErrors() - b.publishErrors(), 0);
        if (queueFull + contention > answered503) {
            problems.add("engine released %d ids for 503 but the client saw only %d 503 answers"
                    .formatted(queueFull + contention, answered503));
        }
        long repeatsAfterDecision = o.answers().stream()
                .filter(x -> x.phase() == TransferLoad.Phase.REPLAY && x.decided())
                .count();
        if (duplicates < repeatsAfterDecision) {
            problems.add("duplicates counted %d but the client sent %d late repeats"
                    .formatted(duplicates, repeatsAfterDecision));
        }
        return CheckResult.of(
                METRICS_AGREE,
                problems.isEmpty(),
                problems.isEmpty()
                        ? "engine counters agree: posted %d, rejectedFunds %d, rejectedInvalid %d, duplicates %d"
                                .formatted(
                                        a.posted() - b.posted(),
                                        a.rejectedFunds() - b.rejectedFunds(),
                                        a.rejectedInvalid() - b.rejectedInvalid(),
                                        duplicates)
                        : "engine metrics disagree with the client: " + String.join("; ", problems));
    }

    /** After the outbox drained, the general ledger holds exactly one entry for each posting the ledger made. */
    static CheckResult glOneEntryPerPosting(TransferObservation o) {
        long entries = o.after().glEntries() - o.before().glEntries();
        long postings = o.postedIds().size();
        return CheckResult.of(
                GL_ONE_ENTRY,
                entries == postings && o.after().outboxSize() == 0,
                "general ledger gained %d entries for %d postings (outbox waiting: %d)"
                        .formatted(entries, postings, o.after().outboxSize()));
    }

    private static void expect(List<String> problems, String what, long actual, long expected) {
        if (actual != expected) {
            problems.add("%s: engine says %d, client expects %d".formatted(what, actual, expected));
        }
    }

    private static long sum(Map<Long, Long> balances) {
        return balances.values().stream().mapToLong(Long::longValue).sum();
    }
}
