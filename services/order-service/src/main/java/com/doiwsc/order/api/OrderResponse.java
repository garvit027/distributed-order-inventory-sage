package com.doiwsc.order.api;

import java.time.Instant;
import java.util.List;

/** Order JSON shape fixed by docs/CONTRACT.md §3.2. */
public record OrderResponse(
        String orderId,
        String customerId,
        String status,
        String failureReason,
        String sagaId,
        long amountCents,
        String currency,
        List<Item> items,
        Instant createdAt,
        Instant updatedAt) {

    public record Item(String sku, int quantity) {
    }
}
