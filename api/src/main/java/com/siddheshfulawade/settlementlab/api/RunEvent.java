package com.siddheshfulawade.settlementlab.api;

/**
 * One server-sent event of a run: {@code metrics} (data is a {@link RunMetrics}) or {@code report} (data is
 * a {@link RunReport}). There is deliberately no id: no replay, so no use for one (api-contract section 2).
 */
record RunEvent(String name, Object data) {

    static final String METRICS = "metrics";
    static final String REPORT = "report";

    boolean isReport() {
        return REPORT.equals(name);
    }
}
