"""Shared fixtures for the notification service tests (fresh ``app`` package
per test, bound to a temp SQLite DB — see services/inventory/tests)."""
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
def notification_svc(tmp_path, monkeypatch):
    monkeypatch.setenv("NOTIFICATION_DB_PATH", str(tmp_path / "notification.db"))
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
def notification_session_factory(notification_svc):
    return notification_svc.db.get_session_factory()


@pytest.fixture()
def notification_count_notifications(notification_session_factory, notification_svc):
    def _count() -> int:
        from sqlalchemy import func, select

        session = notification_session_factory()
        try:
            return session.scalar(
                select(func.count()).select_from(notification_svc.models.Notification)
            )
        finally:
            session.close()

    return _count


@pytest.fixture()
def notification_channel(notification_svc, notification_session_factory):
    """Boot the REAL gRPC server on a random port against the test DB."""
    import grpc

    server, port = notification_svc.grpc_server.build_server(notification_session_factory, port=0)
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
def notification_stub(notification_channel):
    from notification.v1 import notification_pb2_grpc

    return notification_pb2_grpc.NotificationServiceStub(notification_channel)
