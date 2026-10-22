package com.doiwsc.orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** One order line carried on the saga. */
@Embeddable
public class SagaItem {

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    protected SagaItem() {
    }

    public SagaItem(String sku, int quantity) {
        this.sku = sku;
        this.quantity = quantity;
    }

    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
}
