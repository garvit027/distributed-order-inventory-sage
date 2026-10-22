"""Shared fixtures for the payment service tests (fresh ``app`` package per
test, bound to a temp SQLite DB — see services/inventory/tests/conftest.py)."""
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
def payment_svc(tmp_path, monkeypatch):
    monkeypatch.setenv("PAYMENT_DB_PATH", str(tmp_path / "payment.db"))
    monkeypatch.setenv("GRPC_PORT", "0")
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

        db.get_engine()
        db.init_schema()
        namespace = types.SimpleNamespace(
            db=db,
            models=models,
            idempotency=idempotency,
            grpc_server=grpc_server,
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
def payment_session_factory(payment_svc):
    return payment_svc.db.get_session_factory()


@pytest.fixture()
def payment_count_payments(payment_session_factory, payment_svc):
    def _count() -> int:
        from sqlalchemy import func, select

        session = payment_session_factory()
        try:
            return session.scalar(
                select(func.count()).select_from(payment_svc.models.Payment)
            )
        finally:
            session.close()

    return _count


@pytest.fixture()
def payment_get_payment(payment_session_factory, payment_svc):
    def _get(payment_id: str):
        session = payment_session_factory()
        try:
            return session.get(payment_svc.models.Payment, payment_id)
        finally:
            session.close()

    return _get


@pytest.fixture()
def payment_channel(payment_svc, payment_session_factory):
    """Boot the REAL gRPC server on a random port against the test DB."""
    import grpc

    server, port = payment_svc.grpc_server.build_server(payment_session_factory, port=0)
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
def payment_stub(payment_channel):
    from payment.v1 import payment_pb2_grpc

    return payment_pb2_grpc.PaymentServiceStub(payment_channel)
