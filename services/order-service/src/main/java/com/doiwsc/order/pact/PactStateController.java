package com.doiwsc.order.pact;

import com.doiwsc.order.domain.Order;
import com.doiwsc.order.domain.OrderItem;
import com.doiwsc.order.domain.OrderStatus;
import com.doiwsc.order.repo.OrderRepository;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider-state seeding endpoint, active only under the "pact" profile. The
 * pact-jvm verifier calls this before each interaction so the H2 store holds the
 * fixture the consumer expects.
 *
 * <p>Two mappings are exposed so the endpoint is both literally
 * {@code POST /pact/state/{state}} (curl-able, per docs/CONTRACT.md §3.8) and
 * compatible with pact-jvm's state-change transport, which POSTs
 * {@code {"state":..,"params":..}} to a fixed {@code stateChangeUrl} (state is
 * never placed in the path by pact-jvm). Both delegate to {@link #seed(String)}.
 *
 * <p>Supported states:
 * <ul>
 *   <li>"an order that is pending"</li>
 *   <li>"a confirmed order"</li>
 *   <li>"an order that does not exist"</li>
 *   <li>"invalid order payload"</li>
 * </ul>
 */
@RestController
@RequestMapping("/pact/state")
@Profile("pact")
public class PactStateController {

    /** Deterministic ids the consumer pact references in its paths/bodies. */
    public static final String PENDING_ORDER_ID = "ord_pact_pending";
    public static final String CONFIRMED_ORDER_ID = "ord_pact_confirmed";

    private final OrderRepository repository;

    public PactStateController(OrderRepository repository) {
        this.repository = repository;
    }

    /** Literal path form: POST /pact/state/{state}. */
    @PostMapping("/{state}")
    public void stateByPath(@PathVariable String state) {
        seed(state);
    }

    /** pact-jvm transport: POST /pact/state with {"state": ".."} JSON body. */
    @PostMapping
    public void stateByBody(@RequestBody(required = false) Map<String, Object> body) {
        if (body != null && body.get("state") != null) {
            seed(String.valueOf(body.get("state")));
        }
    }

    /** pact-jvm transport fallback: POST /pact/state?state=.. when usesBody=false. */
    @PostMapping(params = "state")
    public void stateByQuery(@RequestParam("state") String state) {
        seed(state);
    }

    private void seed(String state) {
        switch (state) {
            case "an order that is pending" -> save(PENDING_ORDER_ID, OrderStatus.PENDING);
            case "a confirmed order" -> save(CONFIRMED_ORDER_ID, OrderStatus.CONFIRMED);
            case "an order that does not exist" -> {
                if (repository.existsById("ord_pact_missing")) {
                    repository.deleteById("ord_pact_missing");
                }
            }
            // "invalid order payload" and unknown states need no fixture.
            default -> { }
        }
    }

    private void save(String orderId, OrderStatus status) {
        Order order = new Order(orderId, "cus_ok", "buyer@example.com",
                2500, "USD", List.of(new OrderItem("WIDGET-RED", 2)),
                null, "pact-hash");
        if (status == OrderStatus.CONFIRMED) {
            order.confirm("saga_" + orderId);
        }
        repository.save(order);
    }
}
