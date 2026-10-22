"""inventory.v1.InventoryService gRPC implementation (contract sections 3.3/3.4).

Business outcomes are always response fields; non-OK gRPC statuses are
reserved for technical failures (the servicer never raises, so every call
answers OK).

Concurrency: each RPC runs in its own transaction. SQLite transactions start
with BEGIN IMMEDIATE (see app.db), so writers serialise on the busy timeout,
and SQLAlchemy optimistic locking (``version_id_col``) with a bounded retry
loop (<= 5 attempts) covers the remaining lost-update window. That combination
guarantees the contract's "10 concurrent Reserves for the last unit -> exactly
1 success" property.
"""
from __future__ import annotations

import logging
import uuid
from concurrent import futures

import grpc
from grpc_health.v1 import health, health_pb2, health_pb2_grpc
from grpc_reflection.v1alpha import reflection
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session
from sqlalchemy.orm.exc import StaleDataError

from inventory.v1 import inventory_pb2, inventory_pb2_grpc

from . import idempotency
from .models import Reservation, ReservationLine, Stock

log = logging.getLogger(__name__)

# Contract section 3.4: "retry the whole reserve transaction up to 5 times".
MAX_ATTEMPTS = 5


class InventoryServicer(inventory_pb2_grpc.InventoryServiceServicer):
    def __init__(self, session_factory):
        self._session_factory = session_factory

    # ------------------------------------------------------------------ RPCs

    def Reserve(self, request, context):
        items = [(item.sku, item.quantity) for item in request.items]
        log.info(
            "Reserve key=%s order_id=%s items=%s",
            request.idempotency_key,
            request.order_id,
            items,
        )
        key = request.idempotency_key
        req_hash = idempotency.request_hash(request)
        for attempt in range(1, MAX_ATTEMPTS + 1):
            session = self._session_factory()
            try:
                response = self._reserve_tx(session, request, key, req_hash)
                session.commit()
                return response
            except (StaleDataError, IntegrityError) as exc:
                # StaleDataError: another writer committed between our read
                # and write (optimistic lock miss). IntegrityError: a
                # concurrent call won the race on the same idempotency key.
                # Both are retried; a replay lookup resolves the latter.
                session.rollback()
                log.warning(
                    "Reserve attempt %d/%d for key=%s lost a race (%s)",
                    attempt,
                    MAX_ATTEMPTS,
                    key,
                    type(exc).__name__,
                )
            finally:
                session.close()
        log.error("Reserve exhausted retries for key=%s", key)
        return inventory_pb2.ReserveResponse(
            reserved=False, failure_reason="CONFLICT"
        )

    def Release(self, request, context):
        log.info("Release key=%s reservation_id=%s",
                 request.idempotency_key, request.reservation_id)
        key = request.idempotency_key
        req_hash = idempotency.request_hash(request)
        for attempt in range(1, MAX_ATTEMPTS + 1):
            session = self._session_factory()
            try:
                response = self._release_tx(session, request, key, req_hash)
                session.commit()
                return response
            except (StaleDataError, IntegrityError) as exc:
                session.rollback()
                log.warning(
                    "Release attempt %d/%d for key=%s lost a race (%s)",
                    attempt,
                    MAX_ATTEMPTS,
                    key,
                    type(exc).__name__,
                )
            finally:
                session.close()
        log.error("Release exhausted retries for key=%s", key)
        return inventory_pb2.ReleaseResponse(
            released=False, failure_reason="CONFLICT"
        )

    def GetStock(self, request, context):
        session = self._session_factory()
        try:
            query = select(Stock)
            if request.skus:
                query = query.where(Stock.sku.in_(list(request.skus)))
            levels = [
                inventory_pb2.StockLevel(
                    sku=row.sku,
                    available=row.available,
                    reserved=row.reserved,
                    row_version=row.version,
                )
                for row in session.scalars(query)
            ]
            return inventory_pb2.GetStockResponse(levels=levels)
        finally:
            session.close()

    def SeedStock(self, request, context):
        session = self._session_factory()
        try:
            upserted = 0
            for level in request.seed:
                row = session.get(Stock, level.sku)
                if row is None:
                    session.add(
                        Stock(sku=level.sku, available=level.available, reserved=0)
                    )
                else:
                    row.available = level.quantity
                upserted += 1
            session.commit()
            log.info("SeedStock upserted %d rows", upserted)
            return inventory_pb2.SeedStockResponse(upserted=upserted)
        except Exception:
            session.rollback()
            raise
        finally:
            session.close()

    # ------------------------------------------------------------- internals

    def _reserve_tx(self, session, request, key, req_hash):
        """One Reserve transaction: idempotency check + validation + write."""
        if key:
            status, stored = idempotency.check(session, key, req_hash)
            if status == idempotency.LOOKUP_CONFLICT:
                return inventory_pb2.ReserveResponse(
                    reserved=False,
                    failure_reason=idempotency.IDEMPOTENCY_CONFLICT,
                )
            if status == idempotency.LOOKUP_REPLAY:
                return inventory_pb2.ReserveResponse.FromString(stored)

        response = self._validate_and_reserve(session, request)
        if key:
            idempotency.record(session, key, req_hash, response)
        return response

    def _validate_and_reserve(self, session, request):
        lines = _merge_lines(request.items)
        if lines is None:
            # Contract is silent about malformed requests; treat them as a
            # business failure (never a gRPC error).
            return inventory_pb2.ReserveResponse(
                reserved=False, failure_reason="INVALID_ARGUMENT"
            )

        rows = {
            row.sku: row
            for row in session.scalars(
                select(Stock).where(Stock.sku.in_([sku for sku, _ in lines]))
            )
        }

        # Atomic all-lines-or-nothing validation: collect every problem before
        # deciding, change nothing when any line is unsatisfiable.
        shortages = []
        unknown_sku = False
        for sku, quantity in lines:
            row = rows.get(sku)
            if row is None:
                unknown_sku = True
                shortages.append(
                    inventory_pb2.StockShortage(
                        sku=sku, requested=quantity, available=0
                    )
                )
            elif row.available < quantity:
                shortages.append(
                    inventory_pb2.StockShortage(
                        sku=sku, requested=quantity, available=row.available
                    )
                )
        if shortages:
            reason = "UNKNOWN_SKU" if unknown_sku else "INSUFFICIENT_STOCK"
            return inventory_pb2.ReserveResponse(
                reserved=False, failure_reason=reason, shortages=shortages
            )

        reservation_id = "res_" + uuid.uuid4().hex
        for sku, quantity in lines:
            row = rows[sku]
            row.available -= quantity
            row.reserved += quantity
        session.add(
            Reservation(
                reservation_id=reservation_id,
                order_id=request.order_id,
                state="RESERVED",
                lines=[
                    ReservationLine(sku=sku, quantity=quantity)
                    for sku, quantity in lines
                ],
            )
        )
        return inventory_pb2.ReserveResponse(
            reserved=True, reservation_id=reservation_id
        )

    def _release_tx(self, session, request, key, req_hash):
        """One Release transaction (compensation; no-op safe)."""
        if key:
            status, stored = idempotency.check(session, key, req_hash)
            if status == idempotency.LOOKUP_CONFLICT:
                return inventory_pb2.ReleaseResponse(
                    released=False,
                    failure_reason=idempotency.IDEMPOTENCY_CONFLICT,
                )
            if status == idempotency.LOOKUP_REPLAY:
                return inventory_pb2.ReleaseResponse.FromString(stored)

        reservation = session.get(Reservation, request.reservation_id)
        if reservation is None:
            response = inventory_pb2.ReleaseResponse(
                released=False, failure_reason="NOT_FOUND"
            )
        elif reservation.state == "RELEASED":
            # Releasing twice is a no-op success (contract section 3.4).
            response = inventory_pb2.ReleaseResponse(released=True)
        else:
            for line in reservation.lines:
                stock = session.get(Stock, line.sku)
                if stock is not None:
                    stock.available += line.quantity
                    stock.reserved -= line.quantity
            reservation.state = "RELEASED"
            response = inventory_pb2.ReleaseResponse(released=True)

        if key:
            idempotency.record(session, key, req_hash, response)
        return response


def _merge_lines(items):
    """Merge duplicate SKUs; return [(sku, qty)] or None when malformed."""
    merged: dict[str, int] = {}
    for item in items:
        if item.quantity <= 0:
            return None
        merged[item.sku] = merged.get(item.sku, 0) + item.quantity
    if not merged:
        return None
    return list(merged.items())


def build_server(session_factory, port: int, max_workers: int = 16):
    """Build a gRPC server bound to *port* (0 = random) and return it.

    Registers the InventoryService implementation, the standard gRPC health
    service and server reflection (contract section 3.3). The caller starts it.
    """
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=max_workers))
    inventory_pb2_grpc.add_InventoryServiceServicer_to_server(
        InventoryServicer(session_factory), server
    )

    health_servicer = health.HealthServicer()
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
    health_servicer.set("", health_pb2.HealthCheckResponse.SERVING)

    reflection.enable_server_reflection(
        (
            inventory_pb2.DESCRIPTOR.services_by_name["InventoryService"].full_name,
            health.SERVICE_NAME,
            reflection.SERVICE_NAME,
        ),
        server,
    )

    bound_port = server.add_insecure_port(f"[::]:{port}")
    return server, bound_port
