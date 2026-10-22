"""Integration smoke test: boot the REAL gRPC server in-process (random port,
real SQLite DB) and exercise it with the generated client stubs, plus health
and reflection (contract section 3.3)."""
from __future__ import annotations

from grpc_health.v1 import health_pb2, health_pb2_grpc
from grpc_reflection.v1alpha import reflection_pb2, reflection_pb2_grpc
from notification.v1 import notification_pb2


def test_grpc_wiring_end_to_end(notification_stub, notification_channel, notification_count_notifications):
    response = notification_stub.Notify(
        notification_pb2.NotifyRequest(
            idempotency_key="smoke-1",
            order_id="ord_smoke",
            recipient="buyer@example.com",
            kind="ORDER_CONFIRMED",
            subject="Order confirmed",
            body="Your order ord_smoke was confirmed.",
        )
    )
    assert response.accepted is True
    assert response.notification_id.startswith("ntf_")
    assert notification_count_notifications() == 1

    health = health_pb2_grpc.HealthStub(notification_channel).Check(
        health_pb2.HealthCheckRequest(service="")
    )
    assert health.status == health_pb2.HealthCheckResponse.SERVING

    reflection = reflection_pb2_grpc.ServerReflectionStub(notification_channel)
    responses = list(
        reflection.ServerReflectionInfo(
            iter([reflection_pb2.ServerReflectionRequest(list_services="")])
        )
    )
    names = {
        service.name
        for service in responses[0].list_services_response.service
    }
    assert "notification.v1.NotificationService" in names
    assert "grpc.health.v1.Health" in names
