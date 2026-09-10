package io.hallmark.tradeflow.domain;

public enum LifecycleState {
    REPORTED,

    SETTLING,

    SETTLED,

    PARTIALLY_SETTLED,

    FAILED,

    UNSETTLED,

    OVER_SETTLED,

    SETTLED_UNREPORTED
}
