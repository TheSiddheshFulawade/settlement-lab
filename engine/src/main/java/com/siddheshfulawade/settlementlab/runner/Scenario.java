package com.siddheshfulawade.settlementlab.runner;

import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;

/** One cell of the matrix: ledger × threads × faults, on a shared workload and settings. */
public record Scenario(
        LedgerType ledger, ThreadMode threads, FaultProfile faults, Workload workload, RunnerSettings settings) {}
