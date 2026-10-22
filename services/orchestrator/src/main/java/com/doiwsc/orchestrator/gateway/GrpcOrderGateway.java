package com.doiwsc.orchestrator.gateway;

import com.doiwsc.order.v1.ConfirmOrderRequest;
import com.doiwsc.order.v1.ConfirmOrderResponse;
import com.doiwsc.order.v1.FailOrderRequest;
import com.doiwsc.order.v1.FailOrderResponse;
import com.doiwsc.order.v1.OrderServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Real gRPC client for order.v1.OrderService (ConfirmOrder / FailOrder). */
@Component
public class GrpcOrderGateway implements OrderGateway, AutoCloseable {

    private final ManagedChannel channel;
    private final OrderServiceGrpc.OrderServiceBlockingStub stub;
    private final long deadlineMs;

    public GrpcOrderGateway(
            @Value("${order.service.host:localhost}") String host,
            @Value("${order.service.port:50051}") int port,
            @Value("${orchestrator.grpc.deadline-ms:10000}") long deadlineMs) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = OrderServiceGrpc.newBlockingStub(channel);
        this.deadlineMs = deadlineMs;
    }

    @Override
    public ConfirmResult confirm(String idempotencyKey, String orderId, String sagaId) {
        ConfirmOrderResponse resp = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .confirmOrder(ConfirmOrderRequest.newBuilder()
                        .setIdempotencyKey(idempotencyKey)
                        .setOrderId(orderId)
                        .setSagaId(sagaId)
                        .build());
        return new ConfirmResult(resp.getUpdated(), resp.getCurrentStatus());
    }

    @Override
    public FailResult fail(String idempotencyKey, String orderId, String sagaId, String reason) {
        FailOrderResponse resp = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .failOrder(FailOrderRequest.newBuilder()
                        .setIdempotencyKey(idempotencyKey)
                        .setOrderId(orderId)
                        .setSagaId(sagaId)
                        .setReason(reason)
                        .build());
        return new FailResult(resp.getUpdated(), resp.getCurrentStatus());
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
