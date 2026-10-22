package com.doiwsc.orchestrator.saga;

import com.doiwsc.orchestrator.domain.Saga;
import com.doiwsc.orchestrator.domain.SagaItem;
import com.doiwsc.orchestrator.gateway.StockLine;
import io.opentelemetry.context.Context;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Creates a saga on StartSaga and dispatches its execution onto the bounded
 * worker pool, returning immediately (docs/CONTRACT.md §3.1). Idempotent per
 * order: {@code saga_id = "saga_" + order_id}; a duplicate returns the existing
 * saga id.
 */
@Component
public class SagaStarter {

    private static final Logger log = LoggerFactory.getLogger(SagaStarter.class);

    private final SagaStore store;
    private final SagaEngine engine;
    private final Executor workerExecutor;

    public SagaStarter(SagaStore store, SagaEngine engine, Executor sagaWorkerExecutor) {
        this.store = store;
        this.engine = engine;
        this.workerExecutor = sagaWorkerExecutor;
    }

    /** Result of a StartSaga attempt. */
    public record StartResult(boolean accepted, String sagaId, boolean duplicate) {
    }

    public StartResult start(String orderId, String customerId, String recipient,
                             List<StockLine> items, long amountCents, String currency) {
        String sagaId = "saga_" + orderId;
        List<SagaItem> sagaItems = items.stream()
                .map(i -> new SagaItem(i.sku(), i.quantity()))
                .toList();

        if (!store.createIfAbsent(sagaId, orderId, customerId, recipient,
                amountCents, currency, sagaItems)) {
            log.info("duplicate StartSaga for order {} -> {}", orderId, sagaId);
            return new StartResult(true, sagaId, true);
        }

        // Capture the OTel context of the StartSaga gRPC call and re-attach it
        // inside the worker thread, so the async saga execution (step spans and
        // every downstream gRPC call) stays part of the same distributed trace
        // as the REST request that created the order (CONTRACT §3.7).
        Context traceContext = Context.current();
        workerExecutor.execute(traceContext.wrap(() -> {
            try {
                engine.execute(sagaId);
            } catch (RuntimeException e) {
                log.error("saga {} execution crashed", sagaId, e);
            }
        }));
        log.info("accepted StartSaga order={} saga={}", orderId, sagaId);
        return new StartResult(true, sagaId, false);
    }
}
