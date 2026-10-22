"""FastAPI endpoints and the full `python -m app.main` lifespan wiring
(schema creation, seed, gRPC server in lifespan) via TestClient."""
from __future__ import annotations

from fastapi.testclient import TestClient


def test_healthz_and_readyz(inventory_svc):
    with TestClient(inventory_svc.main.app) as client:
        assert client.get("/healthz").json() == {"status": "alive"}

        ready = client.get("/readyz")
        assert ready.status_code == 200
        assert ready.json() == {"status": "ready"}


def test_lifespan_seeds_default_inventory(inventory_svc, inventory_session_factory):
    from sqlalchemy import select

    with TestClient(inventory_svc.main.app):
        pass  # startup ran: schema + INVENTORY_SEED defaults applied

    session = inventory_session_factory()
    try:
        rows = {
            row.sku: (row.available, row.reserved)
            for row in session.scalars(select(inventory_svc.models.Stock))
        }
    finally:
        session.close()
    assert rows == {"WIDGET-RED": (10, 0), "WIDGET-BLUE": (3, 0)}
