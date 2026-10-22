package com.doiwsc.orchestrator.gateway;

import com.doiwsc.notification.v1.NotificationServiceGrpc;
import com.doiwsc.notification.v1.NotifyRequest;
import com.doiwsc.notification.v1.NotifyResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Real gRPC client for notification.v1.NotificationService (Notify). */
@Component
public class GrpcNotificationGateway implements NotificationGateway, AutoCloseable {

    private final ManagedChannel channel;
    private final NotificationServiceGrpc.NotificationServiceBlockingStub stub;
    private final long deadlineMs;

    public GrpcNotificationGateway(
            @Value("${notification.service.host:localhost}") String host,
            @Value("${notification.service.port:50055}") int port,
            @Value("${orchestrator.grpc.deadline-ms:10000}") long deadlineMs) {
        this.channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        this.stub = NotificationServiceGrpc.newBlockingStub(channel);
        this.deadlineMs = deadlineMs;
    }

    @Override
    public NotifyResult notify(String idempotencyKey, String orderId, String recipient,
                               String kind, String subject, String body) {
        NotifyResponse resp = stub.withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                .notify(NotifyRequest.newBuilder()
                        .setIdempotencyKey(idempotencyKey)
                        .setOrderId(orderId)
                        .setRecipient(recipient == null ? "" : recipient)
                        .setKind(kind)
                        .setSubject(subject == null ? "" : subject)
                        .setBody(body == null ? "" : body)
                        .build());
        return new NotifyResult(resp.getAccepted(), resp.getNotificationId());
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
