package com.doiwsc.order.orchestrator;

import com.doiwsc.order.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Kicks off the saga after the order row is committed. Runs on a detached thread
 * so the REST response is not blocked by the orchestrator round-trip, and any
 * technical failure is swallowed (order stays PENDING per §3.2).
 */
@Component
public class SagaStarter {

    private static final Logger log = LoggerFactory.getLogger(SagaStarter.class);

    private final OrchestratorGateway gateway;

    public SagaStarter(OrchestratorGateway gateway) {
        this.gateway = gateway;
    }

    public void startAsync(Order order) {
        Thread thread = new Thread(() -> {
            try {
                gateway.startSaga(order.getOrderId(), order.getCustomerId(),
                        order.getContact(), OrchestratorGateway.linesFrom(order),
                        order.getAmountCents(), order.getCurrency());
            } catch (RuntimeException e) {
                log.warn("StartSaga failed for orderId={}; leaving order PENDING",
                        order.getOrderId(), e);
            }
        }, "saga-starter-" + order.getOrderId());
        thread.setDaemon(true);
        thread.start();
    }
}
