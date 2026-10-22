package com.doiwsc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.doiwsc.orchestrator.domain.SagaItem;
import com.doiwsc.orchestrator.domain.SagaState;
import com.doiwsc.orchestrator.fakes.FakesConfig;
import com.doiwsc.orchestrator.grpc.OrchestratorGrpcService;
import com.doiwsc.orchestrator.repo.SagaRepository;
import com.doiwsc.orchestrator.repo.SagaStepRepository;
import com.doiwsc.orchestrator.saga.SagaEngine;
import com.doiwsc.orchestrator.saga.SagaStarter;
import com.doiwsc.orchestrator.saga.SagaStore;
import com.doiwsc.orchestrator.v1.GetSagaRequest;
import com.doiwsc.orchestrator.v1.GetSagaResponse;
import com.doiwsc.orchestrator.v1.StartSagaRequest;
import com.doiwsc.orchestrator.v1.StartSagaResponse;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** StartSaga idempotency and GetSaga step-history tests. */
@SpringBootTest
@Import(FakesConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:orch-svc-test;DB_CLOSE_DELAY=-1",
        "grpc.port=0",
        "orchestrator.retry-backoff-ms=0"
})
class SagaServiceTest {

    @Autowired private SagaStarter starter;
    @Autowired private SagaStore store;
    @Autowired private SagaEngine engine;
    @Autowired private OrchestratorGrpcService grpcService;
    @Autowired private SagaRepository sagaRepository;
    @Autowired private SagaStepRepository stepRepository;

    @BeforeEach
    void clean() {
        stepRepository.deleteAll();
        sagaRepository.deleteAll();
    }

    private static final class Obs<T> implements StreamObserver<T> {
        private final List<T> values = new ArrayList<>();
        private T value;
        @Override public void onNext(T v) { this.value = v; values.add(v); }
        @Override public void onError(Throwable t) { throw new RuntimeException(t); }
        @Override public void onCompleted() { }
    }

    private StartSagaRequest request(String orderId) {
        return StartSagaRequest.newBuilder()
                .setOrderId(orderId)
                .setCustomerId("cus_ok")
                .setRecipient("buyer@example.com")
                .addItems(com.doiwsc.inventory.v1.StockLine.newBuilder()
                        .setSku("WIDGET-RED").setQuantity(2).build())
                .setAmountCents(2500)
                .setCurrency("USD")
                .build();
    }

    @Test
    void startSagaIsIdempotentPerOrder() {
        StartSagaResponse first = start("ord_dup");
        StartSagaResponse second = start("ord_dup");

        assertThat(first.getAccepted()).isTrue();
        assertThat(first.getSagaId()).isEqualTo("saga_ord_dup");
        // Duplicate returns accepted=true with the SAME existing saga id.
        assertThat(second.getAccepted()).isTrue();
        assertThat(second.getSagaId()).isEqualTo("saga_ord_dup");
        assertThat(sagaRepository.count()).isEqualTo(1);
    }

    @Test
    void getSagaReturnsFullStepHistory() {
        String sagaId = "saga_ord_hist";
        store.createIfAbsent(sagaId, "ord_hist", "cus_ok", "buyer@example.com",
                2500, "USD", List.of(new SagaItem("WIDGET-RED", 2)));
        engine.execute(sagaId);

        GetSagaResponse resp = getSaga(sagaId);
        assertThat(resp.getFound()).isTrue();
        assertThat(resp.getSagaId()).isEqualTo(sagaId);
        assertThat(resp.getOrderId()).isEqualTo("ord_hist");
        assertThat(resp.getState()).isEqualTo(SagaState.SUCCEEDED.name());
        assertThat(resp.getStepsCount()).isGreaterThanOrEqualTo(4);
        assertThat(resp.getStepsList().get(0).getName()).isEqualTo("RESERVE_INVENTORY");
        assertThat(resp.getStepsList().stream().allMatch(s -> !s.getDetail().isEmpty())).isTrue();
        assertThat(resp.getStepsList().stream().allMatch(s -> s.getStartedAtUnixMs() > 0)).isTrue();
    }

    @Test
    void getSagaUnknownReturnsNotFound() {
        GetSagaResponse resp = getSaga("saga_missing");
        assertThat(resp.getFound()).isFalse();
    }

    private StartSagaResponse start(String orderId) {
        Obs<StartSagaResponse> obs = new Obs<>();
        grpcService.startSaga(request(orderId), obs);
        return obs.value;
    }

    private GetSagaResponse getSaga(String sagaId) {
        Obs<GetSagaResponse> obs = new Obs<>();
        grpcService.getSaga(GetSagaRequest.newBuilder().setSagaId(sagaId).build(), obs);
        return obs.value;
    }
}
