"""Notification service entry point (contract section 3.3).

``python -m app.main`` boots everything: SQLite schema, the gRPC server (in
the FastAPI lifespan) and uvicorn on ``HTTP_PORT`` (local default 8083; Docker
images set 8080). Liveness: ``GET /healthz``. Readiness: ``GET /readyz``.
"""
from __future__ import annotations

import logging
import os
import sys
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from sqlalchemy import text

from . import db, grpc_server, telemetry

log = logging.getLogger(__name__)

HTTP_PORT = int(os.environ.get("HTTP_PORT", "8083"))
GRPC_PORT = int(os.environ.get("GRPC_PORT", "50055"))


def configure_logging() -> None:
    # Contract section 3.3: log to stdout.
    logging.basicConfig(
        level=logging.INFO,
        stream=sys.stdout,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


@asynccontextmanager
async def lifespan(app: FastAPI):
    telemetry.setup_telemetry("notification-service")
    telemetry.instrument_grpc()
    db.get_engine()
    db.init_schema()
    log.info("notification service up (gRPC port %d)", GRPC_PORT)

    server, bound_port = grpc_server.build_server(
        db.get_session_factory(), port=GRPC_PORT
    )
    server.start()
    log.info("gRPC NotificationService listening on port %s", bound_port)
    try:
        yield
    finally:
        server.stop(3).wait(timeout=10)
        db.dispose()
        log.info("notification service stopped")


app = FastAPI(title="notification-service", lifespan=lifespan)
telemetry.instrument_fastapi_app(app)


@app.get("/healthz")
def healthz() -> dict[str, str]:
    """Liveness probe: the process is up."""
    return {"status": "alive"}


@app.get("/readyz")
def readyz():
    """Readiness probe: a real DB roundtrip."""
    try:
        session = db.get_session_factory()()
        try:
            session.execute(text("SELECT 1"))
        finally:
            session.close()
        return {"status": "ready"}
    except Exception:
        log.warning("readyz failed: DB roundtrip error", exc_info=True)
        return JSONResponse(status_code=503, content={"status": "unavailable"})


def main() -> None:
    configure_logging()
    log.info("starting notification HTTP server on port %d", HTTP_PORT)
    uvicorn.run(app, host="0.0.0.0", port=HTTP_PORT, log_level="info")


if __name__ == "__main__":
    main()
