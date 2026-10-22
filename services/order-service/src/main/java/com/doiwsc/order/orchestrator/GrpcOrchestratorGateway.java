package com.doiwsc.order.orchestrator;

import com.doiwsc.orchestrator.v1.OrchestratorServiceGrpc;
import com.doiwsc.orchestrator.v1.StartSagaRequest;
import com.doiwsc.orchestrator.v1.StartSagaResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Real gRPC client for orchestrator.v1.OrchestratorService#StartSaga. Targets
 * ORCHESTRATOR_SERVICE_HOST/PORT (default localhost:50052).
 */
@Component
public class GrpcOrchestratorGateway implements OrchestratorGateway, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GrpcOrchestratorGateway.class);

    private final ManagedChannel channel;
    private final OrchestratorServiceGrpc.OrchestratorServiceBlockingStub stub;

    public GrpcOrchestratorGateway(
            @Value("${orchestrator.service.host:localhost}") String host,
            @Value("${orchestrator.service.port:50052}") int port) {
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.stub = OrchestratorServiceGrpc.newBlockingStub(channel);
    }

    @Override
    public boolean startSaga(String orderId, String customerId, String recipient,
                             List<Line> items, long amountCents, String currency) {
        List<com.doiwsc.inventory.v1.StockLine> stockLines = items.stream()
                .map(l -> com.doiwsc.inventory.v1.StockLine.newBuilder()
                        .setSku(l.sku()).setQuantity(l.quantity()).build())
                .toList();
        StartSagaRequest request = StartSagaRequest.newBuilder()
                .setOrderId(orderId)
                .setCustomerId(customerId)
                .setRecipient(recipient == null ? "" : recipient)
                .addAllItems(stockLines)
                .setAmountCents(amountCents)
                .setCurrency(currency)
                .build();
        try {
            StartSagaResponse response = stub
                    .withDeadlineAfter(10, TimeUnit.SECONDS)
                    .startSaga(request);
            log.info("StartSaga accepted={} sagaId={} orderId={}",
                    response.getAccepted(), response.getSagaId(), orderId);
            return response.getAccepted();
        } catch (StatusRuntimeException e) {
            // Technical unavailability is tolerated — the order stays PENDING.
            log.warn("StartSaga technically unavailable for orderId={}: {}", orderId,
                    e.getStatus());
            return false;
        }
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
