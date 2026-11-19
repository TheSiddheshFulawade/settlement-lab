package com.siddheshfulawade.settlementlab.domain;

/** Why a transfer is invalid (REQ-LAB-004). */
public enum InvalidReason {
    NON_POSITIVE_AMOUNT,
    SAME_ACCOUNT,
    UNKNOWN_SOURCE,
    UNKNOWN_TARGET
}
