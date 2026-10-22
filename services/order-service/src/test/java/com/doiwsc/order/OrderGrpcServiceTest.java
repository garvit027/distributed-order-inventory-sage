package com.doiwsc.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.doiwsc.order.domain.Order;
import com.doiwsc.order.domain.OrderItem;
import com.doiwsc.order.domain.OrderStatus;
import com.doiwsc.order.grpc.OrderGrpcService;
import com.doiwsc.order.repo.OrderRepository;
import com.doiwsc.order.v1.ConfirmOrderRequest;
import com.doiwsc.order.v1.ConfirmOrderResponse;
import com.doiwsc.order.v1.FailOrderRequest;
import com.doiwsc.order.v1.FailOrderResponse;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Confirms the idempotency and terminal-state immutability rules of ConfirmOrder
 * / FailOrder (docs/CONTRACT.md §3.2).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-grpc-test;DB_CLOSE_DELAY=-1",
        "grpc.port=0"
})
class OrderGrpcServiceTest {

    @Autowired
    private OrderRepository repository;

    private OrderGrpcService service;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        service = new OrderGrpcService(repository);
    }

    private Order newOrder(String id, OrderStatus status) {
        Order order = new Order(id, "cus_ok", "a@b.com", 1000, "USD",
                List.of(new OrderItem("WIDGET-RED", 1)), null, "hash");
        if (status == OrderStatus.CONFIRMED) {
            order.confirm("saga_" + id);
        } else if (status == OrderStatus.FAILED) {
            order.fail("INSUFFICIENT_STOCK", "saga_" + id);
        }
        return repository.save(order);
    }

    private static final class CapturingObserver<T> implements StreamObserver<T> {
        private final List<T> values = new ArrayList<>();
        private Throwable error;
        private boolean completed;

        @Override
        public void onNext(T value) { values.add(value); }
        @Override
        public void onError(Throwable t) { error = t; }
        @Override
        public void onCompleted() { completed = true; }

        T single() { return values.get(0); }
    }

    private ConfirmOrderResponse confirm(String orderId, String key) {
        CapturingObserver<ConfirmOrderResponse> obs = new CapturingObserver<>();
        service.confirmOrder(ConfirmOrderRequest.newBuilder()
                .setOrderId(orderId).setSagaId("saga_" + orderId)
                .setIdempotencyKey(key).build(), obs);
        assertThat(obs.error).isNull();
        return obs.single();
    }

    private FailOrderResponse fail(String orderId, String key, String reason) {
        CapturingObserver<FailOrderResponse> obs = new CapturingObserver<>();
        service.failOrder(FailOrderRequest.newBuilder()
                .setOrderId(orderId).setSagaId("saga_" + orderId)
                .setIdempotencyKey(key).setReason(reason).build(), obs);
        assertThat(obs.error).isNull();
        return obs.single();
    }

    @Test
    void confirmTransitionsPendingToConfirmed() {
        newOrder("ord_1", OrderStatus.PENDING);
        ConfirmOrderResponse resp = confirm("ord_1", "k1");
        assertThat(resp.getUpdated()).isTrue();
        assertThat(resp.getCurrentStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void confirmIsIdempotentSecondCallNotUpdated() {
        newOrder("ord_2", OrderStatus.PENDING);
        assertThat(confirm("ord_2", "k1").getUpdated()).isTrue();
        ConfirmOrderResponse second = confirm("ord_2", "k2");
        assertThat(second.getUpdated()).isFalse();
        assertThat(second.getCurrentStatus()).isEqualTo("CONFIRMED");
    }

    @Test
    void confirmedOrderCanNeverGoFailed() {
        newOrder("ord_3", OrderStatus.PENDING);
        confirm("ord_3", "k1");
        FailOrderResponse failResp = fail("ord_3", "k2", "INSUFFICIENT_STOCK");
        assertThat(failResp.getUpdated()).isFalse();
        assertThat(failResp.getCurrentStatus()).isEqualTo("CONFIRMED");
        assertThat(repository.findById("ord_3").orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CONFIRMED);
        assertThat(repository.findById("ord_3").orElseThrow().getFailureReason()).isNull();
    }

    @Test
    void failTransitionsPendingToFailedWithReason() {
        newOrder("ord_4", OrderStatus.PENDING);
        FailOrderResponse resp = fail("ord_4", "k1", "PAYMENT_DECLINED");
        assertThat(resp.getUpdated()).isTrue();
        assertThat(resp.getCurrentStatus()).isEqualTo("FAILED");
        Order stored = repository.findById("ord_4").orElseThrow();
        assertThat(stored.getFailureReason()).isEqualTo("PAYMENT_DECLINED");
    }

    @Test
    void failedOrderCanNeverBeConfirmed() {
        newOrder("ord_5", OrderStatus.PENDING);
        fail("ord_5", "k1", "INSUFFICIENT_STOCK");
        ConfirmOrderResponse confirmResp = confirm("ord_5", "k2");
        assertThat(confirmResp.getUpdated()).isFalse();
        assertThat(confirmResp.getCurrentStatus()).isEqualTo("FAILED");
        assertThat(repository.findById("ord_5").orElseThrow().getStatus())
                .isEqualTo(OrderStatus.FAILED);
    }

    @Test
    void failIsIdempotentSecondCallNotUpdated() {
        newOrder("ord_6", OrderStatus.PENDING);
        assertThat(fail("ord_6", "k1", "PAYMENT_DECLINED").getUpdated()).isTrue();
        FailOrderResponse second = fail("ord_6", "k2", "INSUFFICIENT_STOCK");
        assertThat(second.getUpdated()).isFalse();
        assertThat(second.getCurrentStatus()).isEqualTo("FAILED");
        // Original reason is preserved.
        assertThat(repository.findById("ord_6").orElseThrow().getFailureReason())
                .isEqualTo("PAYMENT_DECLINED");
    }
}
