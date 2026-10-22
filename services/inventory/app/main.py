"""Inventory service entry point (contract section 3.3).

``python -m app.main`` boots everything: SQLite schema + seed, the gRPC server
(contract section 3.3: started in the FastAPI lifespan, graceful stop on
shutdown) and uvicorn on ``HTTP_PORT`` (local default 8081; Docker images set
8080). Liveness: ``GET /healthz``. Readiness: ``GET /readyz`` (DB roundtrip).
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
from .models import Stock

log = logging.getLogger(__name__)

HTTP_PORT = int(os.environ.get("HTTP_PORT", "8081"))
GRPC_PORT = int(os.environ.get("GRPC_PORT", "50053"))
DEFAULT_SEED = "WIDGET-RED:10,WIDGET-BLUE:3"


def configure_logging() -> None:
    # Contract section 3.3: log to stdout.
    logging.basicConfig(
        level=logging.INFO,
        stream=sys.stdout,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )


def parse_seed(spec: str) -> dict[str, int]:
    """Parse ``INVENTORY_SEED`` (``SKU:qty,SKU:qty``) into a mapping."""
    seed: dict[str, int] = {}
    for chunk in spec.split(","):
        chunk = chunk.strip()
        if not chunk:
            continue
        sku, _, quantity = chunk.partition(":")
        try:
            seed[sku.strip()] = int(quantity.strip())
        except ValueError:
            log.warning("INVENTORY_SEED: ignoring malformed entry %r", chunk)
    return seed


def seed_if_absent() -> int:
    """Apply ``INVENTORY_SEED``: upsert-if-absent only (contract section 3.4)."""
    spec = os.environ.get("INVENTORY_SEED", DEFAULT_SEED)
    session = db.get_session_factory()()
    try:
        seeded = 0
        for sku, quantity in parse_seed(spec).items():
            if session.get(Stock, sku) is None:
                session.add(Stock(sku=sku, available=quantity, reserved=0))
                seeded += 1
        session.commit()
        return seeded
    finally:
        session.close()


@asynccontextmanager
async def lifespan(app: FastAPI):
    telemetry.setup_telemetry("inventory-service")
    telemetry.instrument_grpc()
    db.get_engine()
    db.init_schema()
    seeded = seed_if_absent()
    log.info("inventory service up (seeded %d stock rows, gRPC port %d)",
             seeded, GRPC_PORT)

    server, bound_port = grpc_server.build_server(
        db.get_session_factory(), port=GRPC_PORT
    )
    server.start()
    log.info("gRPC InventoryService listening on port %s", bound_port)
    try:
        yield
    finally:
        server.stop(3).wait(timeout=10)
        db.dispose()
        log.info("inventory service stopped")


app = FastAPI(title="inventory-service", lifespan=lifespan)
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
    log.info("starting inventory HTTP server on port %d", HTTP_PORT)
    uvicorn.run(app, host="0.0.0.0", port=HTTP_PORT, log_level="info")


if __name__ == "__main__":
    main()
