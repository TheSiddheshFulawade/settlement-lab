package com.siddheshfulawade.settlementlab.api;

/** One named check of the HTTP load check: it passed, or it failed with a message a person can act on. */
record CheckResult(String name, boolean passed, String message) {

    /** Passes when {@code ok}; the message says what was compared, either way. */
    static CheckResult of(String name, boolean ok, String message) {
        return new CheckResult(name, ok, message);
    }
}
