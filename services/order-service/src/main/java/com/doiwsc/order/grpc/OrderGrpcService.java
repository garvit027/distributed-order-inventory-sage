package com.doiwsc.order.grpc;

import com.doiwsc.order.domain.Order;
import com.doiwsc.order.domain.OrderStatus;
import com.doiwsc.order.repo.OrderRepository;
import com.doiwsc.order.v1.ConfirmOrderRequest;
import com.doiwsc.order.v1.ConfirmOrderResponse;
import com.doiwsc.order.v1.FailOrderRequest;
import com.doiwsc.order.v1.FailOrderResponse;
import com.doiwsc.order.v1.GetOrderRequest;
import com.doiwsc.order.v1.GetOrderResponse;
import com.doiwsc.order.v1.OrderServiceGrpc;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * gRPC surface used only by the orchestrator to drive the order state machine
 * (docs/CONTRACT.md §3.2). ConfirmOrder and FailOrder are idempotent and never
 * flip a terminal state: a CONFIRMED order can never become FAILED and vice
 * versa. Second call (same or different key) returns updated=false plus the
 * current status.
 */
@Component
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(OrderGrpcService.class);

    private final OrderRepository repository;

    public OrderGrpcService(OrderRepository repository) {
        this.repository = repository;
    }

    @Override
    public void confirmOrder(ConfirmOrderRequest request, StreamObserver<ConfirmOrderResponse> observer) {
        Order order = repository.findById(request.getOrderId()).orElse(null);
        if (order == null) {
            observer.onError(io.grpc.Status.NOT_FOUND
                    .withDescription("order not found: " + request.getOrderId())
                    .asRuntimeException());
            return;
        }
        boolean updated = false;
        synchronized (order) {
            if (order.getStatus() == OrderStatus.PENDING) {
                order.confirm(request.getSagaId());
                repository.save(order);
                updated = true;
            }
        }
        log.info("ConfirmOrder orderId={} updated={} status={}",
                request.getOrderId(), updated, order.getStatus());
        observer.onNext(ConfirmOrderResponse.newBuilder()
                .setUpdated(updated)
                .setCurrentStatus(order.getStatus().name())
                .build());
        observer.onCompleted();
    }

    @Override
    public void failOrder(FailOrderRequest request, StreamObserver<FailOrderResponse> observer) {
        Order order = repository.findById(request.getOrderId()).orElse(null);
        if (order == null) {
            observer.onError(io.grpc.Status.NOT_FOUND
                    .withDescription("order not found: " + request.getOrderId())
                    .asRuntimeException());
            return;
        }
        boolean updated = false;
        synchronized (order) {
            if (order.getStatus() == OrderStatus.PENDING) {
                order.fail(request.getReason(), request.getSagaId());
                repository.save(order);
                updated = true;
            }
        }
        log.info("FailOrder orderId={} reason={} updated={} status={}",
                request.getOrderId(), request.getReason(), updated, order.getStatus());
        observer.onNext(FailOrderResponse.newBuilder()
                .setUpdated(updated)
                .setCurrentStatus(order.getStatus().name())
                .build());
        observer.onCompleted();
    }

    @Override
    public void getOrder(GetOrderRequest request, StreamObserver<GetOrderResponse> observer) {
        Order order = repository.findById(request.getOrderId()).orElse(null);
        GetOrderResponse.Builder builder = GetOrderResponse.newBuilder()
                .setOrderId(request.getOrderId());
        if (order != null) {
            builder.setFound(true)
                    .setCustomerId(nullSafe(order.getCustomerId()))
                    .setStatus(order.getStatus().name())
                    .setFailureReason(nullSafe(order.getFailureReason()))
                    .setSagaId(nullSafe(order.getSagaId()))
                    .setAmountCents(order.getAmountCents())
                    .setCurrency(nullSafe(order.getCurrency()));
        } else {
            builder.setFound(false);
        }
        observer.onNext(builder.build());
        observer.onCompleted();
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
