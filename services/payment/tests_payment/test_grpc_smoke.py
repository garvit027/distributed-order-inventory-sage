"""Integration smoke test: boot the REAL gRPC server in-process (random port,
real SQLite DB) and exercise it with the generated client stubs, plus health
and reflection (contract section 3.3)."""
from __future__ import annotations

from grpc_health.v1 import health_pb2, health_pb2_grpc
from grpc_reflection.v1alpha import reflection_pb2, reflection_pb2_grpc
from payment.v1 import payment_pb2


def test_grpc_wiring_end_to_end(payment_stub, payment_channel):
    charge = payment_stub.Charge(
        payment_pb2.ChargeRequest(
            idempotency_key="smoke-1",
            order_id="ord_smoke",
            customer_id="cus_ok",
            amount_cents=2500,
            currency="USD",
        )
    )
    assert charge.charged is True
    assert charge.payment_id.startswith("pay_")

    refund = payment_stub.Refund(
        payment_pb2.RefundRequest(
            idempotency_key="smoke-1-refund",
            payment_id=charge.payment_id,
            order_id="ord_smoke",
        )
    )
    assert refund.refunded is True

    health = health_pb2_grpc.HealthStub(payment_channel).Check(
        health_pb2.HealthCheckRequest(service="")
    )
    assert health.status == health_pb2.HealthCheckResponse.SERVING

    reflection = reflection_pb2_grpc.ServerReflectionStub(payment_channel)
    responses = list(
        reflection.ServerReflectionInfo(
            iter([reflection_pb2.ServerReflectionRequest(list_services="")])
        )
    )
    names = {
        service.name
        for service in responses[0].list_services_response.service
    }
    assert "payment.v1.PaymentService" in names
    assert "grpc.health.v1.Health" in names
