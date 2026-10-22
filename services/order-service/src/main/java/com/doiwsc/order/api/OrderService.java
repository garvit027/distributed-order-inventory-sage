package com.doiwsc.order.api;

import com.doiwsc.order.domain.Order;
import com.doiwsc.order.domain.OrderItem;
import com.doiwsc.order.repo.OrderRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order creation with idempotency. The find-or-create runs in a single
 * transaction; the unique index on idempotency_key makes the check-and-insert
 * atomic even under concurrent requests with the same key.
 */
@Service
public class OrderService {

    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    /** Outcome of a create attempt: the order plus whether it was newly created. */
    public record CreateOutcome(Order order, boolean created) {
    }

    @Transactional
    public CreateOutcome create(CreateOrderRequest request, String idempotencyKey) {
        RequestNormalizer.Normalized normalized = RequestNormalizer.normalize(request);
        String hash = RequestNormalizer.requestHash(normalized);

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<Order> existing = repository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                return reconcile(existing.get(), hash);
            }
        }

        String orderId = "ord_" + UUID.randomUUID();
        List<OrderItem> items = normalized.items().stream()
                .map(i -> new OrderItem(i.sku(), i.quantity()))
                .collect(Collectors.toList());
        Order order = new Order(orderId, normalized.customerId(), normalized.contact(),
                normalized.amountCents(), normalized.currency(), items,
                blankToNull(idempotencyKey), hash);
        try {
            repository.saveAndFlush(order);
        } catch (DataIntegrityViolationException race) {
            // Another request with the same key won the insert; treat ours as a replay.
            Order winner = repository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> race);
            return reconcile(winner, hash);
        }
        return new CreateOutcome(order, true);
    }

    /** Replay: same key + same body -> return original (200); different body -> 409. */
    private CreateOutcome reconcile(Order existing, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            throw new ApiException(409, "IDEMPOTENCY_CONFLICT",
                    "Idempotency-Key was used with a different request body");
        }
        return new CreateOutcome(existing, false);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
