package com.doiwsc.order.api;

import java.util.List;

/** camelCase JSON body for POST /api/orders. Validation is done manually in the service. */
public record CreateOrderRequest(
        String customerId,
        String contact,
        String currency,
        Long amountCents,
        List<ItemDto> items) {

    public record ItemDto(String sku, Integer quantity) {
    }
}
