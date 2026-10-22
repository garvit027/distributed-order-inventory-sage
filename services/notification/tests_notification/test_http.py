"""FastAPI endpoints and lifespan wiring (contract section 3.3)."""
from __future__ import annotations

from fastapi.testclient import TestClient


def test_healthz_and_readyz(notification_svc):
    with TestClient(notification_svc.main.app) as client:
        assert client.get("/healthz").json() == {"status": "alive"}

        ready = client.get("/readyz")
        assert ready.status_code == 200
        assert ready.json() == {"status": "ready"}


def test_lifespan_boots_grpc_and_schema(notification_svc, notification_count_notifications):
    with TestClient(notification_svc.main.app):
        pass  # startup ran: schema created, gRPC server started and stopped
    assert notification_count_notifications() == 0
