package com.doiwsc.orchestrator.gateway;

/** A stock line handed to the inventory gateway. */
public record StockLine(String sku, int quantity) {
}
