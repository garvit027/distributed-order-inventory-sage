package com.doiwsc.order.api;

import com.doiwsc.order.domain.Order;
import com.doiwsc.order.orchestrator.SagaStarter;
import com.doiwsc.order.repo.OrderRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public REST edge (docs/CONTRACT.md §3.2). Only place HTTP is exposed for the
 * whole order flow; everything internal is gRPC.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final OrderRepository repository;
    private final SagaStarter sagaStarter;

    public OrderController(OrderService orderService, OrderRepository repository,
                           SagaStarter sagaStarter) {
        this.orderService = orderService;
        this.repository = repository;
        this.sagaStarter = sagaStarter;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestBody(required = false) CreateOrderRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        OrderService.CreateOutcome outcome = orderService.create(request, idempotencyKey);
        Order order = outcome.order();

        if (outcome.created()) {
            // Kick off the saga after the row is committed. StartSaga is idempotent
            // per order, and technical unavailability must NOT fail the order — it
            // stays PENDING (see §3.2).
            sagaStarter.startAsync(order);
            return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(order));
        }
        // Idempotent replay of the same key + same body.
        return ResponseEntity.ok(toResponse(order));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> get(@PathVariable String orderId) {
        return repository.findById(orderId)
                .map(o -> ResponseEntity.ok(toResponse(o)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public Map<String, List<OrderResponse>> list() {
        List<OrderResponse> all = repository.findAll().stream()
                .map(OrderController::toResponse)
                .toList();
        return Map.of("orders", all);
    }

    static OrderResponse toResponse(Order o) {
        List<OrderResponse.Item> items = o.getItems().stream()
                .map(i -> new OrderResponse.Item(i.getSku(), i.getQuantity()))
                .toList();
        return new OrderResponse(o.getOrderId(), o.getCustomerId(), o.getStatus().name(),
                o.getFailureReason(), o.getSagaId(), o.getAmountCents(), o.getCurrency(),
                items, o.getCreatedAt(), o.getUpdatedAt());
    }
}
