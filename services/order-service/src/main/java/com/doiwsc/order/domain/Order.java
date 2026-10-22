package com.doiwsc.order.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The single orders table. The unique {@code idempotency_key} column is the
 * backbone of the create-idempotency contract: find-or-create happens inside one
 * transaction and the database enforces that two concurrent requests with the
 * same key can never both insert.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    private String orderId;

    private String customerId;

    private String contact;

    @Enumerated(EnumType.STRING)
    private OrderStatus status = OrderStatus.PENDING;

    /** Stable machine code, null while the order has not failed. */
    private String failureReason;

    private String sagaId;

    private long amountCents;

    private String currency;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_items", joinColumns = @JoinColumn(name = "order_id"))
    private List<OrderItem> items = new ArrayList<>();

    /** Null for orders created without an Idempotency-Key. */
    private String idempotencyKey;

    /** SHA-256 over the canonical request body, used for 409 conflict detection. */
    private String requestHash;

    private Instant createdAt;

    private Instant updatedAt;

    protected Order() {
    }

    public Order(String orderId, String customerId, String contact, long amountCents,
                 String currency, List<OrderItem> items, String idempotencyKey, String requestHash) {
        Instant now = Instant.now();
        this.orderId = orderId;
        this.customerId = customerId;
        this.contact = contact;
        this.status = OrderStatus.PENDING;
        this.amountCents = amountCents;
        this.currency = currency;
        this.items = new ArrayList<>(items);
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.sagaId = "saga_" + orderId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void confirm(String sagaId) {
        if (this.sagaId == null && sagaId != null) {
            this.sagaId = sagaId;
        }
        this.status = OrderStatus.CONFIRMED;
        this.updatedAt = Instant.now();
    }

    public void fail(String reason, String sagaId) {
        if (this.sagaId == null && sagaId != null) {
            this.sagaId = sagaId;
        }
        this.status = OrderStatus.FAILED;
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }

    public String getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public String getContact() { return contact; }
    public OrderStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public String getSagaId() { return sagaId; }
    public long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public List<OrderItem> getItems() { return items; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
