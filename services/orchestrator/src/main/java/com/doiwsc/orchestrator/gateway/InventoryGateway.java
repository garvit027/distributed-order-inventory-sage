package com.doiwsc.orchestrator.gateway;

import java.util.List;

/**
 * Inventory boundary. Business outcomes are returned as fields; only technical
 * gRPC failures are signalled by throwing {@link io.grpc.StatusRuntimeException}.
 */
public interface InventoryGateway {

    ReserveResult reserve(String idempotencyKey, String orderId, List<StockLine> items);

    ReleaseResult release(String idempotencyKey, String reservationId);

    record ReserveResult(boolean reserved, String reservationId, String failureReason) {
    }

    record ReleaseResult(boolean released, String failureReason) {
    }
}
