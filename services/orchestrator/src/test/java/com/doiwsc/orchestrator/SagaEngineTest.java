package com.doiwsc.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.doiwsc.orchestrator.domain.Saga;
import com.doiwsc.orchestrator.domain.SagaItem;
import com.doiwsc.orchestrator.domain.SagaState;
import com.doiwsc.orchestrator.domain.SagaStep;
import com.doiwsc.orchestrator.domain.StepName;
import com.doiwsc.orchestrator.domain.StepStatus;
import com.doiwsc.orchestrator.fakes.FakeInventoryGateway;
import com.doiwsc.orchestrator.fakes.FakeNotificationGateway;
import com.doiwsc.orchestrator.fakes.FakeOrderGateway;
import com.doiwsc.orchestrator.fakes.FakePaymentGateway;
import com.doiwsc.orchestrator.fakes.FakesConfig;
import com.doiwsc.orchestrator.repo.SagaRepository;
import com.doiwsc.orchestrator.repo.SagaStepRepository;
import com.doiwsc.orchestrator.saga.SagaEngine;
import com.doiwsc.orchestrator.saga.SagaStore;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/** End-to-end saga state-machine tests using in-memory fakes (contract §3.1). */
@SpringBootTest
@Import(FakesConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:orch-saga-test;DB_CLOSE_DELAY=-1",
        "grpc.port=0",
        "orchestrator.retry-backoff-ms=0",
        "orchestrator.max-retries=3"
})
class SagaEngineTest {

    @Autowired private SagaStore store;
    @Autowired private SagaEngine engine;
    @Autowired private SagaRepository sagaRepository;
    @Autowired private SagaStepRepository stepRepository;

    @Autowired private FakeInventoryGateway inventory;
    @Autowired private FakePaymentGateway payment;
    @Autowired private FakeOrderGateway order;
    @Autowired private FakeNotificationGateway notification;

    @BeforeEach
    void reset() {
        stepRepository.deleteAll();
        sagaRepository.deleteAll();
        inventory.reserveMode = FakeInventoryGateway.ReserveMode.SUCCESS;
        inventory.reserveAttempts = 0;
        inventory.releasedReservations.clear();
        inventory.reserveKeys.clear();
        payment.chargeMode = FakePaymentGateway.ChargeMode.SUCCESS;
        payment.chargeAttempts = 0;
        payment.refundedPayments.clear();
        payment.chargeKeys.clear();
        order.confirmMode = FakeOrderGateway.ConfirmMode.CONFIRMED;
        order.confirmAttempts = 0;
        order.failedReasons.clear();
        order.confirmKeys.clear();
        notification.failing = false;
        notification.calls = 0;
        notification.kinds.clear();
        notification.keys.clear();
    }

    private String run(String orderId) {
        List<SagaItem> items = List.of(new SagaItem("WIDGET-RED", 2));
        store.createIfAbsent("saga_" + orderId, orderId, "cus_ok", "buyer@example.com",
                2500, "USD", items);
        engine.execute("saga_" + orderId);
        return "saga_" + orderId;
    }

    private Saga saga(String sagaId) {
        return sagaRepository.findById(sagaId).orElseThrow();
    }

    private Map<String, SagaStep> stepsById(String sagaId) {
        return stepRepository.findBySagaIdOrderByIdAsc(sagaId).stream()
                .collect(Collectors.toMap(SagaStep::getName, Function.identity()));
    }

    private List<String> stepOrder(String sagaId) {
        return stepRepository.findBySagaIdOrderByIdAsc(sagaId).stream()
                .map(SagaStep::getName)
                .toList();
    }

    @Test
    void happyPathSucceedsWithAllForwardSteps() {
        String sagaId = run("ord_ok");

        assertThat(saga(sagaId).getState()).isEqualTo(SagaState.SUCCEEDED);
        assertThat(order.failedReasons).isEmpty();
        assertThat(order.confirmAttempts).isEqualTo(1);
        assertThat(inventory.releasedReservations).isEmpty();
        assertThat(payment.refundedPayments).isEmpty();

        assertThat(stepOrder(sagaId)).containsExactly(
                StepName.RESERVE_INVENTORY, StepName.CHARGE_PAYMENT,
                StepName.CONFIRM_ORDER, StepName.NOTIFY);
        stepsById(sagaId).values().forEach(s ->
                assertThat(s.getStatus()).isEqualTo(StepStatus.SUCCEEDED));

        // Success notification, deterministic key.
        assertThat(notification.kinds).containsExactly("ORDER_CONFIRMED");
        assertThat(notification.keys).containsExactly("notify:ord_ok:success");
    }

    @Test
    void paymentDeclinedCompensatesInventoryAndFails() {
        payment.chargeMode = FakePaymentGateway.ChargeMode.CARD_DECLINED;
        String sagaId = run("ord_decline");

        // Compensation: inventory released, order failed with PAYMENT_DECLINED.
        assertThat(inventory.releasedReservations).containsExactly("resv_1");
        assertThat(order.failedReasons).containsExactly("PAYMENT_DECLINED");
        assertThat(saga(sagaId).getState()).isEqualTo(SagaState.COMPENSATED);

        // Business failure is not retried.
        assertThat(payment.chargeAttempts).isEqualTo(1);

        // Reverse-order steps: reserve, charge, release, notify(failure).
        assertThat(stepOrder(sagaId)).containsExactly(
                StepName.RESERVE_INVENTORY, StepName.CHARGE_PAYMENT,
                StepName.RELEASE_INVENTORY, StepName.NOTIFY);
        assertThat(stepsById(sagaId).get(StepName.RELEASE_INVENTORY).getStatus())
                .isEqualTo(StepStatus.SUCCEEDED);
        assertThat(notification.kinds).containsExactly("ORDER_FAILED");
        // No refund because no payment was captured.
        assertThat(payment.refundedPayments).isEmpty();
    }

    @Test
    void confirmFailureRefundsThenReleases() {
        order.confirmMode = FakeOrderGateway.ConfirmMode.NOT_CONFIRMED;
        String sagaId = run("ord_confirmfail");

        // Reverse compensation: refund payment, then release inventory.
        assertThat(payment.refundedPayments).containsExactly("pay_1");
        assertThat(inventory.releasedReservations).containsExactly("resv_1");
        assertThat(order.failedReasons).containsExactly("ORDER_UPDATE_FAILED");
        assertThat(saga(sagaId).getState()).isEqualTo(SagaState.COMPENSATED);

        assertThat(stepOrder(sagaId)).containsExactly(
                StepName.RESERVE_INVENTORY, StepName.CHARGE_PAYMENT,
                StepName.CONFIRM_ORDER, StepName.REFUND_PAYMENT,
                StepName.RELEASE_INVENTORY, StepName.NOTIFY);
        assertThat(notification.kinds).containsExactly("ORDER_FAILED");
    }

    @Test
    void reserveUnavailableRetriesThenFailsWithoutCompensation() {
        inventory.reserveMode = FakeInventoryGateway.ReserveMode.UNAVAILABLE;
        String sagaId = run("ord_res_unavail");

        // maxRetries=3 -> 1 initial + 3 retries = 4 attempts.
        assertThat(inventory.reserveAttempts).isEqualTo(4);
        assertThat(order.failedReasons).containsExactly("INVENTORY_UNAVAILABLE");
        assertThat(saga(sagaId).getState()).isEqualTo(SagaState.FAILED);
        // Nothing was reserved, so no compensation.
        assertThat(inventory.releasedReservations).isEmpty();
        assertThat(payment.chargeAttempts).isZero();
    }

    @Test
    void chargeUnavailableCompensatesReserveThenFails() {
        payment.chargeMode = FakePaymentGateway.ChargeMode.UNAVAILABLE;
        String sagaId = run("ord_charge_unavail");

        assertThat(payment.chargeAttempts).isEqualTo(4);
        // Reserve was compensated.
        assertThat(inventory.releasedReservations).containsExactly("resv_1");
        assertThat(order.failedReasons).containsExactly("PAYMENT_UNAVAILABLE");
        assertThat(saga(sagaId).getState()).isEqualTo(SagaState.COMPENSATED);
        assertThat(order.confirmAttempts).isZero();
    }

    @Test
    void notifyFailureDoesNotFailSaga() {
        notification.failing = true;
        String sagaId = run("ord_notify_fail");

        // Saga still succeeds; notify is best-effort.
        assertThat(saga(sagaId).getState()).isEqualTo(SagaState.SUCCEEDED);
        assertThat(order.failedReasons).isEmpty();
        assertThat(order.confirmAttempts).isEqualTo(1);
        assertThat(stepsById(sagaId).get(StepName.NOTIFY).getStatus())
                .isEqualTo(StepStatus.FAILED);
        assertThat(stepsById(sagaId).get(StepName.NOTIFY).getDetail())
                .contains("notification service down");
    }

    @Test
    void reserveStockFailureSkipsCompensationAndFails() {
        inventory.reserveMode = FakeInventoryGateway.ReserveMode.INSUFFICIENT_STOCK;
        String sagaId = run("ord_stock");

        assertThat(inventory.reserveAttempts).isEqualTo(1);
        assertThat(order.failedReasons).containsExactly("INSUFFICIENT_STOCK");
        assertThat(saga(sagaId).getState()).isEqualTo(SagaState.FAILED);
        assertThat(inventory.releasedReservations).isEmpty();
        assertThat(payment.chargeAttempts).isZero();
    }
}
