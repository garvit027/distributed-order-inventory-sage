package com.doiwsc.orchestrator.fakes;

import com.doiwsc.orchestrator.gateway.OrderGateway;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.List;

/** In-memory OrderGateway with configurable behaviour and call recording. */
public class FakeOrderGateway implements OrderGateway {

    public enum ConfirmMode { CONFIRMED, NOT_CONFIRMED, UNAVAILABLE }

    public volatile ConfirmMode confirmMode = ConfirmMode.CONFIRMED;
    public volatile int confirmAttempts = 0;
    public final List<String> failedReasons = new ArrayList<>();
    public final List<String> confirmKeys = new ArrayList<>();

    @Override
    public ConfirmResult confirm(String idempotencyKey, String orderId, String sagaId) {
        confirmAttempts++;
        confirmKeys.add(idempotencyKey);
        return switch (confirmMode) {
            case CONFIRMED -> new ConfirmResult(true, "CONFIRMED");
            case NOT_CONFIRMED -> new ConfirmResult(false, "PENDING");
            case UNAVAILABLE -> throw new StatusRuntimeException(Status.UNAVAILABLE);
        };
    }

    @Override
    public FailResult fail(String idempotencyKey, String orderId, String sagaId, String reason) {
        failedReasons.add(reason);
        return new FailResult(true, "FAILED");
    }
}
