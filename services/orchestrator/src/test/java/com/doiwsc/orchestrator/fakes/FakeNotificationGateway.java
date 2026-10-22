package com.doiwsc.orchestrator.fakes;

import com.doiwsc.orchestrator.gateway.NotificationGateway;
import java.util.ArrayList;
import java.util.List;

/** In-memory NotificationGateway; can be made to throw to test best-effort. */
public class FakeNotificationGateway implements NotificationGateway {

    public volatile boolean failing = false;
    public volatile int calls = 0;
    public final List<String> kinds = new ArrayList<>();
    public final List<String> keys = new ArrayList<>();

    @Override
    public NotifyResult notify(String idempotencyKey, String orderId, String recipient,
                               String kind, String subject, String body) {
        calls++;
        kinds.add(kind);
        keys.add(idempotencyKey);
        if (failing) {
            throw new RuntimeException("notification service down");
        }
        return new NotifyResult(true, "notif_1");
    }
}
