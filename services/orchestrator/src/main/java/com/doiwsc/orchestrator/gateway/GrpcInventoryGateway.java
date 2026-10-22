package com.doiwsc.orchestrator.gateway;

import com.doiwsc.inventory.v1.InventoryServiceGrpc;
import com.doiwsc.inventory.v1.ReleaseRequest;
import com.doiwsc.inventory.v1.ReleaseResponse;
import com.doiwsc.inventory.v1.ReserveRequest;
import com.doiwsc.inventory.v1.ReserveResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Real gRPC client for inventory.v1.InventoryService (Reserve / Release). */
@Component
public class GrpcInventoryGateway implements InventoryGateway, AutoCloseable {

    private final ManagedChannel channel;
    private final InventoryServiceGrpc.InventoryServiceBlockingStub stub;
    private final long deadlineMs;

    public GrpcInventoryGateway(
            @Value("${inventory.service.host:localhost}") String host,
            @Value("${inventory.service.port:50053}") int port,
            @Value("${orchestrator.grpc.deadline-ms:10000}") long deadlineMs) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = InventoryServiceGrpc.newBlockingStub(channel);
        this.deadlineMs = deadlineMs;
    }

    @Override
    public ReserveResult reserve(String idempotencyKey, String orderId, List<StockLine> items) {
        ReserveRequest.Builder req = ReserveRequest.newBuilder()
                .setIdempotencyKey(idempotencyKey)
                .setOrderId(orderId);
        items.forEach(i -> req.addItems(
                com.doiwsc.inventory.v1.StockLine.newBuilder()
                        .setSku(i.sku()).setQuantity(i.quantity()).build()));
        ReserveResponse resp = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .reserve(req.build());
        return new ReserveResult(resp.getReserved(), resp.getReservationId(),
                resp.getFailureReason());
    }

    @Override
    public ReleaseResult release(String idempotencyKey, String reservationId) {
        ReleaseResponse resp = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .release(ReleaseRequest.newBuilder()
                        .setIdempotencyKey(idempotencyKey)
                        .setReservationId(reservationId)
                        .build());
        return new ReleaseResult(resp.getReleased(), resp.getFailureReason());
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
