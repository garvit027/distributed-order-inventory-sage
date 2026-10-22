package com.doiwsc.orchestrator.grpc;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.protobuf.services.ProtoReflectionService;
import io.grpc.services.HealthStatusManager;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Hosts the gRPC Server (orchestrator.v1.OrchestratorService + health +
 * reflection) on GRPC_PORT (default 50052) under Spring lifecycle control.
 */
@Component
public class GrpcServerLifecycle implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GrpcServerLifecycle.class);

    private final OrchestratorGrpcService service;
    private final int port;
    private volatile Server server;
    private volatile boolean running;

    public GrpcServerLifecycle(OrchestratorGrpcService service,
                               @Value("${grpc.port:50052}") int port) {
        this.service = service;
        this.port = port;
    }

    @Override
    public void start() {
        HealthStatusManager health = new HealthStatusManager();
        health.setStatus("", HealthCheckResponse.ServingStatus.SERVING);
        server = NettyServerBuilder.forPort(port)
                .addService(service)
                .addService(health.getHealthService())
                .addService(ProtoReflectionService.newInstance())
                .build();
        try {
            server.start();
            running = true;
            log.info("orchestrator gRPC server listening on port {}", port);
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
                server.awaitTermination(5, TimeUnit.SECONDS);
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
