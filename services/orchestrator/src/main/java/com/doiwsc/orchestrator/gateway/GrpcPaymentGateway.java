package com.doiwsc.orchestrator.gateway;

import com.doiwsc.payment.v1.ChargeRequest;
import com.doiwsc.payment.v1.ChargeResponse;
import com.doiwsc.payment.v1.PaymentServiceGrpc;
import com.doiwsc.payment.v1.RefundRequest;
import com.doiwsc.payment.v1.RefundResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Real gRPC client for payment.v1.PaymentService (Charge / Refund). */
@Component
public class GrpcPaymentGateway implements PaymentGateway, AutoCloseable {

    private final ManagedChannel channel;
    private final PaymentServiceGrpc.PaymentServiceBlockingStub stub;
    private final long deadlineMs;

    public GrpcPaymentGateway(
            @Value("${payment.service.host:localhost}") String host,
            @Value("${payment.service.port:50054}") int port,
            @Value("${orchestrator.grpc.deadline-ms:10000}") long deadlineMs) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = PaymentServiceGrpc.newBlockingStub(channel);
        this.deadlineMs = deadlineMs;
    }

    @Override
    public ChargeResult charge(String idempotencyKey, String orderId, String customerId,
                               long amountCents, String currency) {
        ChargeResponse resp = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .charge(ChargeRequest.newBuilder()
                        .setIdempotencyKey(idempotencyKey)
                        .setOrderId(orderId)
                        .setCustomerId(customerId)
                        .setAmountCents(amountCents)
                        .setCurrency(currency)
                        .build());
        return new ChargeResult(resp.getCharged(), resp.getPaymentId(), resp.getFailureReason());
    }

    @Override
    public RefundResult refund(String idempotencyKey, String paymentId, String orderId) {
        RefundResponse resp = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .refund(RefundRequest.newBuilder()
                        .setIdempotencyKey(idempotencyKey)
                        .setPaymentId(paymentId)
                        .setOrderId(orderId)
                        .build());
        return new RefundResult(resp.getRefunded(), resp.getFailureReason());
    }

    @Override
    public void close() {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }
}
