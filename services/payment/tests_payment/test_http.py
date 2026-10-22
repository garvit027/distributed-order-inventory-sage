"""FastAPI endpoints and lifespan wiring (contract section 3.3)."""
from __future__ import annotations

from fastapi.testclient import TestClient


def test_healthz_and_readyz(payment_svc):
    with TestClient(payment_svc.main.app) as client:
        assert client.get("/healthz").json() == {"status": "alive"}

        ready = client.get("/readyz")
        assert ready.status_code == 200
        assert ready.json() == {"status": "ready"}


def test_lifespan_boots_grpc_and_schema(payment_svc, payment_count_payments):
    with TestClient(payment_svc.main.app):
        pass  # startup ran: schema created, gRPC server started and stopped
    assert payment_count_payments() == 0
