"""SQLAlchemy engine/session management (contract section 3.3).

SQLite file database at ``INVENTORY_DB_PATH`` (default ``data/inventory.db``).
``create_all`` is run on startup (no alembic, per contract).

SQLite needs a few pragmas to behave well under concurrent gRPC threads:

* ``isolation_level = None`` disables pysqlite's implicit BEGIN so that
  SQLAlchemy controls transactions (see the "begin" listener below).
* ``BEGIN IMMEDIATE`` takes the write lock when a transaction *starts*, so
  concurrent writers serialise on the busy timeout instead of failing with
  SQLITE_BUSY / stale-snapshot errors halfway through a transaction. Combined
  with SQLAlchemy optimistic locking (``version_id_col``) this is what makes
  the no-oversell guarantee hold.
* WAL keeps readers unblocked while a writer commits.
"""
from __future__ import annotations

import logging
import os
from pathlib import Path

from sqlalchemy import create_engine, event
from sqlalchemy.engine import Engine
from sqlalchemy.orm import sessionmaker

log = logging.getLogger(__name__)

DEFAULT_DB_PATH = "data/payment.db"
_BUSY_TIMEOUT_SECONDS = 30

_engine: Engine | None = None
_session_factory: sessionmaker | None = None


def db_path() -> str:
    return os.environ.get("PAYMENT_DB_PATH", DEFAULT_DB_PATH)


def _install_sqlite_tweaks(engine: Engine) -> None:
    @event.listens_for(engine, "connect")
    def _on_connect(dbapi_connection, _connection_record):
        dbapi_connection.isolation_level = None
        dbapi_connection.execute("PRAGMA journal_mode=WAL")
        dbapi_connection.execute("PRAGMA foreign_keys=ON")
        dbapi_connection.execute("PRAGMA synchronous=NORMAL")

    @event.listens_for(engine, "begin")
    def _on_begin(conn):
        conn.exec_driver_sql("BEGIN IMMEDIATE")


def get_engine() -> Engine:
    global _engine, _session_factory
    if _engine is None:
        path = db_path()
        if path != ":memory:":
            parent = Path(path).parent
            if str(parent) not in ("", "."):
                parent.mkdir(parents=True, exist_ok=True)
        _engine = create_engine(
            f"sqlite+pysqlite:///{path}",
            future=True,
            connect_args={
                "check_same_thread": False,
                "timeout": _BUSY_TIMEOUT_SECONDS,
            },
        )
        _install_sqlite_tweaks(_engine)
        _session_factory = sessionmaker(bind=_engine, expire_on_commit=False)
        log.info("payment database at %s", path)
    return _engine


def get_session_factory() -> sessionmaker:
    get_engine()
    assert _session_factory is not None
    return _session_factory


def init_schema() -> None:
    """Create all tables (contract: ``create_all`` on startup)."""
    from .models import Base

    Base.metadata.create_all(get_engine())


def dispose() -> None:
    global _engine, _session_factory
    if _engine is not None:
        _engine.dispose()
    _engine = None
    _session_factory = None
