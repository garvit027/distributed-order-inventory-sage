package com.doiwsc.order.grpc;

import io.grpc.Server;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.services.HealthStatusManager;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Hosts the gRPC {@link Server} (order.v1.OrderService + health + reflection) on
 * GRPC_PORT (default 50051) under Spring lifecycle control. Plain grpc-java with
 * the netty-shaded transport, no spring-grpc starters.
 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final OrderGrpcService orderGrpcService;
    private final int port;
    private volatile Server server;
    private volatile boolean running;

    public GrpcServerLifecycle(OrderGrpcService orderGrpcService,
                               @Value("${grpc.port:50051}") int port) {
        this.orderGrpcService = orderGrpcService;
        this.port = port;
    }

    @Override
    public void start() {
        HealthStatusManager health = new HealthStatusManager();
        health.setStatus("", io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING);
        server = NettyServerBuilder.forPort(port)
                .addService(orderGrpcService)
                .addService(health.getHealthService())
                .addService(ProtoReflectionService.newInstance())
                .build();
        try {
            server.start();
            running = true;
            log.info("order-service gRPC server listening on port {}", port);
        } catch (Exception e) {
            throw new IllegalStateException("failed to start gRPC server on port " + port, e);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (server != null) {
            server.shutdown();
            try {
                server.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            server.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
