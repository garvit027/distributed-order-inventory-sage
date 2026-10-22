package com.doiwsc.order.orchestrator;

import com.doiwsc.order.domain.Order;
import java.util.List;

/**
 * Boundary to the orchestrator's StartSaga RPC. Mocked in tests; the real impl
 * speaks gRPC over the wire.
 */
public interface OrchestratorGateway {

    /**
     * Best-effort StartSaga call. Returns true if the orchestrator accepted the
     * saga; false if it was technically unavailable (the order stays PENDING).
     */
    boolean startSaga(String orderId, String customerId, String recipient,
                      List<Line> items, long amountCents, String currency);

    record Line(String sku, int quantity) {
    }

    static List<Line> linesFrom(Order order) {
        return order.getItems().stream()
                .map(i -> new Line(i.getSku(), i.getQuantity()))
                .toList();
    }
}
