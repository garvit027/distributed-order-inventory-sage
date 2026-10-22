package com.doiwsc.orchestrator.gateway;

/**
 * Notification boundary. Always best-effort: the saga never fails because of it.
 */
public interface NotificationGateway {

    NotifyResult notify(String idempotencyKey, String orderId, String recipient,
                        String kind, String subject, String body);

    record NotifyResult(boolean accepted, String notificationId) {
    }
}
