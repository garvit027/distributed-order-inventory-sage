package com.doiwsc.orchestrator.gateway;

/**
 * Payment boundary. Business outcomes (declines) are fields; technical gRPC
 * failures throw {@link io.grpc.StatusRuntimeException}.
 */
public interface PaymentGateway {

    ChargeResult charge(String idempotencyKey, String orderId, String customerId,
                        long amountCents, String currency);

    RefundResult refund(String idempotencyKey, String paymentId, String orderId);

    record ChargeResult(boolean charged, String paymentId, String failureReason) {
    }

    record RefundResult(boolean refunded, String failureReason) {
    }
}
