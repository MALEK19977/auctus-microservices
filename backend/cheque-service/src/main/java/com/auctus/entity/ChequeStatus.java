package com.auctus.entity;

public enum ChequeStatus {
    PROCESSING,
    ACCEPTED,
    /** Passed every blocking rule, but something needs a human eye. */
    REVIEW,
    REJECTED
}