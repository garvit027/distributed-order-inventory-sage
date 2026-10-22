"""Shared fixtures for the inventory service tests.

Every test imports a *fresh* copy of the ``app`` package (purge + re-import)
bound to a temp SQLite DB, because the engine reads ``INVENTORY_DB_PATH`` at
import time and sibling services in this repo own a package with the same
``app`` name.
"""
from __future__ import annotations

import pathlib
import sys
import types

import pytest

SERVICE_DIR = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = SERVICE_DIR.parents[1]
GEN_PYTHON = REPO_ROOT / "gen" / "python"

if str(GEN_PYTHON) not in sys.path:
    sys.path.insert(0, str(GEN_PYTHON))


def _purge_app_modules() -> None:
    for name in [
        module for module in list(sys.modules)
        if module == "app" or module.startswith("app.")
    ]:
        del sys.modules[name]


@pytest.fixture()
def inventory_svc(tmp_path, monkeypatch):
    monkeypatch.setenv("INVENTORY_DB_PATH", str(tmp_path / "inventory.db"))
    monkeypatch.setenv("GRPC_PORT", "0")  # random port for any in-lifespan server
    monkeypatch.setenv("OTEL_SDK_DISABLED", "true")
    _purge_app_modules()
    sys.path.insert(0, str(SERVICE_DIR))
    namespace = None
    try:
        import app.db as db
        import app.grpc_server as grpc_server
        import app.idempotency as idempotency
        import app.main as main
        import app.models as models
        import app.telemetry as telemetry

        db.get_engine()
        db.init_schema()
        namespace = types.SimpleNamespace(
            db=db,
            models=models,
            idempotency=idempotency,
            grpc_server=grpc_server,
            telemetry=telemetry,
            main=main,
        )
        yield namespace
    finally:
        if namespace is not None:
            namespace.db.dispose()
        if str(SERVICE_DIR) in sys.path:
            sys.path.remove(str(SERVICE_DIR))
        _purge_app_modules()


@pytest.fixture()
def inventory_session_factory(inventory_svc):
    return inventory_svc.db.get_session_factory()


@pytest.fixture()
def inventory_seed_stock(inventory_svc, inventory_session_factory):
    """Insert stock rows: ``inventory_seed_stock({"WIDGET-RED": 10})``."""

    def _seed(quantities: dict[str, int]) -> None:
        session = inventory_session_factory()
        try:
            for sku, quantity in quantities.items():
                session.add(
                    inventory_svc.models.Stock(sku=sku, available=quantity, reserved=0)
                )
            session.commit()
        finally:
            session.close()

    return _seed


@pytest.fixture()
def inventory_get_stock(inventory_svc, inventory_session_factory):
    """Fetch the Stock row for *sku* (detached, attributes loaded)."""

    def _get(sku: str):
        session = inventory_session_factory()
        try:
            return session.get(inventory_svc.models.Stock, sku)
        finally:
            session.close()

    return _get


@pytest.fixture()
def inventory_channel(inventory_svc, inventory_session_factory):
    """Boot the REAL gRPC server on a random port against the test DB."""
    import grpc

    server, port = inventory_svc.grpc_server.build_server(inventory_session_factory, port=0)
    server.start()
    try:
        channel = grpc.insecure_channel(f"127.0.0.1:{port}")
        try:
            yield channel
        finally:
            channel.close()
    finally:
        server.stop(0).wait(timeout=10)


@pytest.fixture()
def inventory_stub(inventory_channel):
    from inventory.v1 import inventory_pb2_grpc

    return inventory_pb2_grpc.InventoryServiceStub(inventory_channel)
