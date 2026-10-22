package com.doiwsc.order;

import com.doiwsc.order.orchestrator.OrchestratorGateway;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test double for the orchestrator gateway so no real gRPC connection is made.
 * Records every StartSaga call for assertions.
 */
@TestConfiguration
public class FakeOrchestratorConfig {

    public static class RecordingGateway implements OrchestratorGateway {
        public final List<String> orderIds = new CopyOnWriteArrayList<>();
        public volatile boolean available = true;

        @Override
        public boolean startSaga(String orderId, String customerId, String recipient,
                                 List<Line> items, long amountCents, String currency) {
            orderIds.add(orderId);
            return available;
        }
    }

    @Bean
    @Primary
    public RecordingGateway recordingGateway() {
        return new RecordingGateway();
    }
}
