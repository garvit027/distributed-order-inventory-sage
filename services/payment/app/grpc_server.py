"""payment.v1.PaymentService gRPC implementation (contract sections 3.3/3.4).

Business outcomes are response fields, never non-OK gRPC statuses.

* ``Charge``: decline rules from ``PAYMENT_DECLINE_CUSTOMERS`` (default
  ``cus_decline``) and ``PAYMENT_DECLINE_ABOVE_CENTS`` (default -1 = disabled);
  ``amount_cents <= 0`` -> ``INVALID_AMOUNT``; success creates a
  ``pay_<uuid>`` payment in state ``CAPTURED``.
* ``Refund``: ``CAPTURED -> REFUNDED``; unknown payment -> ``NOT_FOUND``.

Both RPCs are idempotent by key: a same-hash replay returns the *stored*
response verbatim (so a declined charge replays the same ``CARD_DECLINED``),
and key reuse with a different payload yields ``IDEMPOTENCY_CONFLICT``.
"""
from __future__ import annotations

import logging
import os
import uuid
from concurrent import futures

import grpc
from grpc_health.v1 import health, health_pb2, health_pb2_grpc
from grpc_reflection.v1alpha import reflection
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm.exc import StaleDataError

from payment.v1 import payment_pb2, payment_pb2_grpc

from . import idempotency
from .models import Payment

log = logging.getLogger(__name__)

MAX_ATTEMPTS = 5

DEFAULT_DECLINE_CUSTOMERS = "cus_decline"
DEFAULT_DECLINE_ABOVE_CENTS = "-1"  # >= 0 enables the amount rule

STATE_CAPTURED = "CAPTURED"
STATE_REFUNDED = "REFUNDED"


def decline_customers() -> set[str]:
    raw = os.environ.get("PAYMENT_DECLINE_CUSTOMERS", DEFAULT_DECLINE_CUSTOMERS)
    return {customer.strip() for customer in raw.split(",") if customer.strip()}


def decline_above_cents() -> int:
    raw = os.environ.get(
        "PAYMENT_DECLINE_ABOVE_CENTS", DEFAULT_DECLINE_ABOVE_CENTS
    )
    try:
        return int(raw)
    except ValueError:
        log.warning("invalid PAYMENT_DECLINE_ABOVE_CENTS=%r; disabled", raw)
        return -1


class PaymentServicer(payment_pb2_grpc.PaymentServiceServicer):
    def __init__(self, session_factory):
        self._session_factory = session_factory

    # ------------------------------------------------------------------ RPCs

    def Charge(self, request, context):
        log.info(
            "Charge key=%s order_id=%s customer_id=%s amount_cents=%d currency=%s",
            request.idempotency_key,
            request.order_id,
            request.customer_id,
            request.amount_cents,
            request.currency,
        )
        key = request.idempotency_key
        req_hash = idempotency.request_hash(request)
        for attempt in range(1, MAX_ATTEMPTS + 1):
            session = self._session_factory()
            try:
                response = self._charge_tx(session, request, key, req_hash)
                session.commit()
                return response
            except (IntegrityError, StaleDataError) as exc:
                # Typically a concurrent call with the same idempotency key
                # won the insert; retrying resolves it via replay lookup.
                session.rollback()
                log.warning(
                    "Charge attempt %d/%d for key=%s lost a race (%s)",
                    attempt,
                    MAX_ATTEMPTS,
                    key,
                    type(exc).__name__,
                )
            finally:
                session.close()
        log.error("Charge exhausted retries for key=%s", key)
        return payment_pb2.ChargeResponse(
            charged=False, failure_reason="CONFLICT"
        )

    def Refund(self, request, context):
        log.info(
            "Refund key=%s payment_id=%s order_id=%s",
            request.idempotency_key,
            request.payment_id,
            request.order_id,
        )
        key = request.idempotency_key
        req_hash = idempotency.request_hash(request)
        for attempt in range(1, MAX_ATTEMPTS + 1):
            session = self._session_factory()
            try:
                response = self._refund_tx(session, request, key, req_hash)
                session.commit()
                return response
            except (IntegrityError, StaleDataError) as exc:
                session.rollback()
                log.warning(
                    "Refund attempt %d/%d for key=%s lost a race (%s)",
                    attempt,
                    MAX_ATTEMPTS,
                    key,
                    type(exc).__name__,
                )
            finally:
                session.close()
        log.error("Refund exhausted retries for key=%s", key)
        return payment_pb2.RefundResponse(
            refunded=False, failure_reason="CONFLICT"
        )

    # ------------------------------------------------------------- internals

    def _charge_tx(self, session, request, key, req_hash):
        if key:
            status, stored = idempotency.check(session, key, req_hash)
            if status == idempotency.LOOKUP_CONFLICT:
                return payment_pb2.ChargeResponse(
                    charged=False,
                    failure_reason=idempotency.IDEMPOTENCY_CONFLICT,
                )
            if status == idempotency.LOOKUP_REPLAY:
                # Replays include original failures (e.g. CARD_DECLINED).
                return payment_pb2.ChargeResponse.FromString(stored)

        if request.amount_cents <= 0:
            response = payment_pb2.ChargeResponse(
                charged=False, failure_reason="INVALID_AMOUNT"
            )
        elif self._is_declined(request):
            response = payment_pb2.ChargeResponse(
                charged=False, failure_reason="CARD_DECLINED"
            )
        else:
            payment_id = "pay_" + uuid.uuid4().hex
            session.add(
                Payment(
                    payment_id=payment_id,
                    order_id=request.order_id,
                    amount_cents=request.amount_cents,
                    currency=request.currency,
                    state=STATE_CAPTURED,
                )
            )
            response = payment_pb2.ChargeResponse(
                charged=True, payment_id=payment_id
            )

        if key:
            idempotency.record(session, key, req_hash, response)
        return response

    @staticmethod
    def _is_declined(request) -> bool:
        if request.customer_id in decline_customers():
            return True
        threshold = decline_above_cents()
        return threshold >= 0 and request.amount_cents > threshold

    def _refund_tx(self, session, request, key, req_hash):
        if key:
            status, stored = idempotency.check(session, key, req_hash)
            if status == idempotency.LOOKUP_CONFLICT:
                return payment_pb2.RefundResponse(
                    refunded=False,
                    failure_reason=idempotency.IDEMPOTENCY_CONFLICT,
                )
            if status == idempotency.LOOKUP_REPLAY:
                return payment_pb2.RefundResponse.FromString(stored)

        payment = session.get(Payment, request.payment_id)
        if payment is None:
            response = payment_pb2.RefundResponse(
                refunded=False, failure_reason="NOT_FOUND"
            )
        else:
            # Refunding an already-refunded payment is a no-op success
            # (mirrors Release in inventory; keeps compensation retries safe).
            payment.state = STATE_REFUNDED
            response = payment_pb2.RefundResponse(refunded=True)

        if key:
            idempotency.record(session, key, req_hash, response)
        return response


def build_server(session_factory, port: int, max_workers: int = 16):
    """Build a gRPC server bound to *port* (0 = random) and return it.

    Registers the PaymentService implementation, the standard gRPC health
    service and server reflection (contract section 3.3). The caller starts it.
    """
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=max_workers))
    payment_pb2_grpc.add_PaymentServiceServicer_to_server(
        PaymentServicer(session_factory), server
    )

    health_servicer = health.HealthServicer()
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
    health_servicer.set("", health_pb2.HealthCheckResponse.SERVING)

    reflection.enable_server_reflection(
        (
            payment_pb2.DESCRIPTOR.services_by_name["PaymentService"].full_name,
            health.SERVICE_NAME,
            reflection.SERVICE_NAME,
        ),
        server,
    )

    bound_port = server.add_insecure_port(f"[::]:{port}")
    return server, bound_port
