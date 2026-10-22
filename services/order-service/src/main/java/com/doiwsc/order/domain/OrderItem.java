package com.doiwsc.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** One line of an order: a SKU and a positive quantity. */
@Embeddable
public class OrderItem {

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private int quantity;

    protected OrderItem() {
    }

    public OrderItem(String sku, int quantity) {
        this.sku = sku;
        this.quantity = quantity;
    }

    public String getSku() { return sku; }
    public int getQuantity() { return quantity; }
}
