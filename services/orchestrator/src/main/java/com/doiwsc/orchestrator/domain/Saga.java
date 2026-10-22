package com.doiwsc.orchestrator.domain;

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

/** One order saga: {@code saga_id = "saga_" + order_id}. */
@Entity
@Table(name = "sagas")
public class Saga {

    @Id
    private String sagaId;

    private String orderId;

    private String customerId;

    private String recipient;

    private long amountCents;

    private String currency;

    @Enumerated(EnumType.STRING)
    private SagaState state = SagaState.PENDING;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "saga_items", joinColumns = @JoinColumn(name = "saga_id"))
    private List<SagaItem> items = new ArrayList<>();

    private Instant createdAt;

    private Instant updatedAt;

    protected Saga() {
    }

    public Saga(String sagaId, String orderId, String customerId, String recipient,
                long amountCents, String currency, List<SagaItem> items) {
        Instant now = Instant.now();
        this.sagaId = sagaId;
        this.orderId = orderId;
        this.customerId = customerId;
        this.recipient = recipient;
        this.amountCents = amountCents;
        this.currency = currency;
        this.items = new ArrayList<>(items);
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void setState(SagaState state) {
        this.state = state;
        this.updatedAt = Instant.now();
    }

    public String getSagaId() { return sagaId; }
    public String getOrderId() { return orderId; }
    public String getCustomerId() { return customerId; }
    public String getRecipient() { return recipient; }
    public long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public SagaState getState() { return state; }
    public List<SagaItem> getItems() { return items; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
