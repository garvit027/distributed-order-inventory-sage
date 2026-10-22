package com.doiwsc.orchestrator.saga;

import com.doiwsc.orchestrator.domain.Saga;
import com.doiwsc.orchestrator.domain.SagaItem;
import com.doiwsc.orchestrator.domain.SagaState;
import com.doiwsc.orchestrator.domain.SagaStep;
import com.doiwsc.orchestrator.domain.StepName;
import com.doiwsc.orchestrator.domain.StepStatus;
import com.doiwsc.orchestrator.gateway.InventoryGateway;
import com.doiwsc.orchestrator.gateway.NotificationGateway;
import com.doiwsc.orchestrator.gateway.OrderGateway;
import com.doiwsc.orchestrator.gateway.PaymentGateway;
import com.doiwsc.orchestrator.gateway.StockLine;
import com.doiwsc.orchestrator.repo.SagaRepository;
import com.doiwsc.orchestrator.repo.SagaStepRepository;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The saga state machine (docs/CONTRACT.md §3.1).
 *
 * <p>Happy path: RESERVE_INVENTORY -&gt; CHARGE_PAYMENT -&gt; CONFIRM_ORDER -&gt;
 * NOTIFY -&gt; SUCCEEDED. On failure the engine compensates in reverse order and
 * ends COMPENSATED (or FAILED when nothing needs compensating).
 *
 * <p>Business outcomes arrive as response fields (reserved=false, charged=false,
 * currentStatus!=CONFIRMED). Only technical gRPC errors (UNAVAILABLE,
 * DEADLINE_EXCEEDED, INTERNAL) are retried ORCHESTRATOR_MAX_RETRIES times with
 * ORCHESTRATOR_RETRY_BACKOFF_MS backoff; deterministic idempotency keys make the
 * retries safe. NOTIFY is best-effort and never fails the saga.
 */
@Component
public class SagaEngine {

    private static final Logger log = LoggerFactory.getLogger(SagaEngine.class);

    /**
     * Tracer for saga step spans (CONTRACT §3.7). With the OTel javaagent
     * attached this yields real spans named RESERVE_INVENTORY, CHARGE_PAYMENT,
     * ... that stitch the async saga back onto the StartSaga trace; without the
     * agent (unit tests) GlobalOpenTelemetry returns a no-op tracer.
     */
    private static final Tracer TRACER =
            GlobalOpenTelemetry.get().getTracer("com.doiwsc.orchestrator.saga");

    private final SagaRepository sagaRepository;
    private final SagaStepRepository stepRepository;
    private final InventoryGateway inventoryGateway;
    private final PaymentGateway paymentGateway;
    private final OrderGateway orderGateway;
    private final NotificationGateway notificationGateway;
    private final int maxRetries;
    private final long retryBackoffMs;

    public SagaEngine(SagaRepository sagaRepository,
                      SagaStepRepository stepRepository,
                      InventoryGateway inventoryGateway,
                      PaymentGateway paymentGateway,
                      OrderGateway orderGateway,
                      NotificationGateway notificationGateway,
                      @Value("${orchestrator.max-retries:3}") int maxRetries,
                      @Value("${orchestrator.retry-backoff-ms:250}") long retryBackoffMs) {
        this.sagaRepository = sagaRepository;
        this.stepRepository = stepRepository;
        this.inventoryGateway = inventoryGateway;
        this.paymentGateway = paymentGateway;
        this.orderGateway = orderGateway;
        this.notificationGateway = notificationGateway;
        this.maxRetries = maxRetries;
        this.retryBackoffMs = retryBackoffMs;
    }

    /** Compensation context carried across the forward steps of one run. */
    private static final class Context {
        String reservationId;
        String paymentId;
    }

    /**
     * Starts a span named after the saga step, tagged with saga/order ids. The
     * caller wraps the step body in {@code makeCurrent()} so that every child
     * span (JPA work, downstream gRPC calls) nests under it.
     */
    private Span beginStep(String step, Saga saga) {
        return TRACER.spanBuilder(step)
                .setAttribute("saga.id", saga.getSagaId())
                .setAttribute("order.id", saga.getOrderId())
                .startSpan();
    }

    /** Executes the full state machine for a saga, synchronously. */
    public void execute(String sagaId) {
        Saga saga = sagaRepository.findById(sagaId).orElse(null);
        if (saga == null) {
            log.warn("execute called for unknown saga {}", sagaId);
            return;
        }
        if (saga.getState() == SagaState.SUCCEEDED || saga.getState() == SagaState.FAILED
                || saga.getState() == SagaState.COMPENSATED) {
            log.info("saga {} already terminal ({}), skipping", sagaId, saga.getState());
            return;
        }
        saga.setState(SagaState.RUNNING);
        sagaRepository.save(saga);

        Context ctx = new Context();
        String orderId = saga.getOrderId();

        // 1. RESERVE ------------------------------------------------------
        SagaStep reserve = start(sagaId, StepName.RESERVE_INVENTORY);
        List<StockLine> lines = saga.getItems().stream()
                .map(i -> new StockLine(i.getSku(), i.getQuantity()))
                .toList();
        Span reserveSpan = beginStep(StepName.RESERVE_INVENTORY, saga);
        try (Scope ignored = reserveSpan.makeCurrent()) {
            try {
                InventoryGateway.ReserveResult r = retry(() ->
                        inventoryGateway.reserve("reserve:" + orderId, orderId, lines));
                if (r.reserved()) {
                    ctx.reservationId = r.reservationId();
                    reserveSpan.setAttribute("inventory.reservation_id", r.reservationId());
                    finish(reserve, StepStatus.SUCCEEDED, "reservation=" + r.reservationId());
                } else {
                    // Business stock failure -> no compensation needed.
                    reserveSpan.setStatus(StatusCode.ERROR, r.failureReason());
                    finish(reserve, StepStatus.FAILED, "reason=" + r.failureReason());
                    failSaga(saga, "INSUFFICIENT_STOCK", SagaState.FAILED);
                    return;
                }
            } catch (StatusRuntimeException e) {
                reserveSpan.setStatus(StatusCode.ERROR, technical(e));
                finish(reserve, StepStatus.FAILED, technical(e));
                failSaga(saga, "INVENTORY_UNAVAILABLE", SagaState.FAILED);
                return;
            }
        } finally {
            reserveSpan.end();
        }

        // 2. CHARGE -------------------------------------------------------
        SagaStep charge = start(sagaId, StepName.CHARGE_PAYMENT);
        Span chargeSpan = beginStep(StepName.CHARGE_PAYMENT, saga);
        try (Scope ignored = chargeSpan.makeCurrent()) {
            try {
                PaymentGateway.ChargeResult c = retry(() -> paymentGateway.charge(
                        "charge:" + orderId, orderId, saga.getCustomerId(),
                        saga.getAmountCents(), saga.getCurrency()));
                if (c.charged()) {
                    ctx.paymentId = c.paymentId();
                    chargeSpan.setAttribute("payment.id", c.paymentId());
                    finish(charge, StepStatus.SUCCEEDED, "payment=" + c.paymentId());
                } else {
                    chargeSpan.setStatus(StatusCode.ERROR, c.failureReason());
                    finish(charge, StepStatus.FAILED, "reason=" + c.failureReason());
                    compensateRelease(saga, ctx);
                    // Business decline (CARD_DECLINED / INVALID_AMOUNT) -> PAYMENT_DECLINED.
                    failSaga(saga, "PAYMENT_DECLINED", SagaState.COMPENSATED);
                    return;
                }
            } catch (StatusRuntimeException e) {
                chargeSpan.setStatus(StatusCode.ERROR, technical(e));
                finish(charge, StepStatus.FAILED, technical(e));
                compensateRelease(saga, ctx);
                failSaga(saga, "PAYMENT_UNAVAILABLE", SagaState.COMPENSATED);
                return;
            }
        } finally {
            chargeSpan.end();
        }

        // 3. CONFIRM ------------------------------------------------------
        SagaStep confirm = start(sagaId, StepName.CONFIRM_ORDER);
        boolean confirmed = false;
        Span confirmSpan = beginStep(StepName.CONFIRM_ORDER, saga);
        try (Scope ignored = confirmSpan.makeCurrent()) {
            try {
                OrderGateway.ConfirmResult c = retry(() ->
                        orderGateway.confirm("confirm:" + orderId, orderId, sagaId));
                if ("CONFIRMED".equals(c.currentStatus())) {
                    confirmed = true;
                    finish(confirm, StepStatus.SUCCEEDED, "status=" + c.currentStatus());
                } else {
                    confirmSpan.setStatus(StatusCode.ERROR, "status=" + c.currentStatus());
                    finish(confirm, StepStatus.FAILED, "status=" + c.currentStatus());
                }
            } catch (StatusRuntimeException e) {
                confirmSpan.setStatus(StatusCode.ERROR, technical(e));
                finish(confirm, StepStatus.FAILED, technical(e));
            }
        } finally {
            confirmSpan.end();
        }

        if (!confirmed) {
            // Compensation in reverse: refund payment, then release inventory.
            compensateRefund(saga, ctx);
            compensateRelease(saga, ctx);
            failSaga(saga, "ORDER_UPDATE_FAILED", SagaState.COMPENSATED);
            return;
        }

        // 4. NOTIFY (best-effort success) ---------------------------------
        notifyBestEffort(saga, "ORDER_CONFIRMED", "notify:" + orderId + ":success");
        saga.setState(SagaState.SUCCEEDED);
        sagaRepository.save(saga);
        log.info("saga {} SUCCEEDED for order {}", sagaId, orderId);
    }

    // --- compensations ---------------------------------------------------

    private void compensateRelease(Saga saga, Context ctx) {
        if (ctx.reservationId == null) {
            return;
        }
        SagaStep step = start(saga.getSagaId(), StepName.RELEASE_INVENTORY);
        Span span = beginStep(StepName.RELEASE_INVENTORY, saga);
        try (Scope ignored = span.makeCurrent()) {
            try {
                InventoryGateway.ReleaseResult r = retry(() -> inventoryGateway.release(
                        "release:" + ctx.reservationId, ctx.reservationId));
                if (!r.released()) {
                    span.setStatus(StatusCode.ERROR, r.failureReason());
                }
                finish(step, r.released() ? StepStatus.SUCCEEDED : StepStatus.FAILED,
                        "released=" + r.released() + " reason=" + r.failureReason());
            } catch (StatusRuntimeException e) {
                // Compensation failure is recorded but does not block FailOrder.
                span.setStatus(StatusCode.ERROR, technical(e));
                finish(step, StepStatus.FAILED, technical(e));
            }
        } finally {
            span.end();
        }
    }

    private void compensateRefund(Saga saga, Context ctx) {
        if (ctx.paymentId == null) {
            return;
        }
        SagaStep step = start(saga.getSagaId(), StepName.REFUND_PAYMENT);
        Span span = beginStep(StepName.REFUND_PAYMENT, saga);
        try (Scope ignored = span.makeCurrent()) {
            try {
                PaymentGateway.RefundResult r = retry(() -> paymentGateway.refund(
                        "refund:" + ctx.paymentId, ctx.paymentId, saga.getOrderId()));
                if (!r.refunded()) {
                    span.setStatus(StatusCode.ERROR, r.failureReason());
                }
                finish(step, r.refunded() ? StepStatus.SUCCEEDED : StepStatus.FAILED,
                        "refunded=" + r.refunded() + " reason=" + r.failureReason());
            } catch (StatusRuntimeException e) {
                span.setStatus(StatusCode.ERROR, technical(e));
                finish(step, StepStatus.FAILED, technical(e));
            }
        } finally {
            span.end();
        }
    }

    /** FailOrder + failure notification, then move the saga to its terminal state. */
    private void failSaga(Saga saga, String reason, SagaState terminal) {
        String orderId = saga.getOrderId();
        try {
            orderGateway.fail("fail:" + orderId, orderId, saga.getSagaId(), reason);
        } catch (StatusRuntimeException e) {
            // FailOrder itself is retried by the gateway deadline; a technical
            // failure here is logged but the saga still reaches its terminal state.
            log.warn("FailOrder technical failure for saga {}: {}", saga.getSagaId(), technical(e));
        }
        notifyBestEffort(saga, "ORDER_FAILED", "notify:" + orderId + ":failure");
        saga.setState(terminal);
        sagaRepository.save(saga);
        log.info("saga {} -> {} reason={}", saga.getSagaId(), terminal, reason);
    }

    private void notifyBestEffort(Saga saga, String kind, String idempotencyKey) {
        SagaStep step = start(saga.getSagaId(), StepName.NOTIFY);
        Span span = beginStep(StepName.NOTIFY, saga);
        span.setAttribute("notification.kind", kind);
        try (Scope ignored = span.makeCurrent()) {
            try {
                String subject = "Order " + kind.toLowerCase().replace("_", " ");
                String body = "Order " + saga.getOrderId() + " is " + kind;
                NotificationGateway.NotifyResult r = notificationGateway.notify(
                        idempotencyKey, saga.getOrderId(), saga.getRecipient(),
                        kind, subject, body);
                finish(step, StepStatus.SUCCEEDED, "accepted=" + r.accepted());
            } catch (RuntimeException e) {
                // Best-effort: record but never fail or compensate the saga.
                span.setStatus(StatusCode.ERROR, e.getMessage());
                finish(step, StepStatus.FAILED, "notify failed: " + e.getMessage());
            }
        } finally {
            span.end();
        }
    }

    // --- retry -----------------------------------------------------------

    /**
     * Retries a technical gRPC call up to {@code maxRetries} times (plus the
     * initial attempt) when the status is retryable. Business outcomes are
     * returned as fields and never reach this path.
     */
    private <T> T retry(Supplier<T> call) {
        int retriesLeft = maxRetries;
        while (true) {
            try {
                return call.get();
            } catch (StatusRuntimeException e) {
                if (!retryable(e.getStatus().getCode()) || retriesLeft <= 0) {
                    throw e;
                }
                retriesLeft--;
                sleep();
            }
        }
    }

    private static boolean retryable(Status.Code code) {
        return code == Status.Code.UNAVAILABLE
                || code == Status.Code.DEADLINE_EXCEEDED
                || code == Status.Code.INTERNAL;
    }

    private void sleep() {
        if (retryBackoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(retryBackoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Status.CANCELLED.withCause(e).asRuntimeException();
        }
    }

    private static String technical(StatusRuntimeException e) {
        return "technical " + e.getStatus().getCode() + ": " + e.getStatus().getDescription();
    }

    // --- step bookkeeping ------------------------------------------------

    private SagaStep start(String sagaId, String name) {
        SagaStep step = new SagaStep(sagaId, name);
        return stepRepository.save(step);
    }

    private void finish(SagaStep step, StepStatus status, String detail) {
        step.finish(status, detail);
        stepRepository.save(step);
    }
}
