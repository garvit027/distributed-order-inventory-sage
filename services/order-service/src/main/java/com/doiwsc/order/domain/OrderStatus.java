package com.doiwsc.order.domain;

/** Order lifecycle as seen by the public edge: PENDING -> CONFIRMED | FAILED. */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    FAILED
}
