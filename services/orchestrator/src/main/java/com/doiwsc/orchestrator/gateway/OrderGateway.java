package com.doiwsc.orchestrator.gateway;

/**
 * Order boundary (ConfirmOrder / FailOrder on order-service). Outcomes are
 * fields; technical gRPC failures throw {@link io.grpc.StatusRuntimeException}.
 */
public interface OrderGateway {

    ConfirmResult confirm(String idempotencyKey, String orderId, String sagaId);

    FailResult fail(String idempotencyKey, String orderId, String sagaId, String reason);

    record ConfirmResult(boolean updated, String currentStatus) {
    }

    record FailResult(boolean updated, String currentStatus) {
    }
}
