"""notification.v1.NotificationService gRPC implementation (contract 3.3/3.4).

``Notify`` writes exactly one notifications row per idempotency key (a replay
never creates a duplicate) and logs every notification to stdout. Notify is
best-effort for the saga, but still processes every ``idempotency_key``: a
same-hash replay returns the stored response, key reuse with a different
payload is a business failure (``accepted=false``; NotifyResponse carries no
failure-reason field, so the conflict is also logged).
"""
from __future__ import annotations

import logging
import uuid
from concurrent import futures

import grpc
from grpc_health.v1 import health, health_pb2, health_pb2_grpc
from grpc_reflection.v1alpha import reflection
from sqlalchemy.exc import IntegrityError

from notification.v1 import notification_pb2, notification_pb2_grpc

from . import idempotency
from .models import Notification

log = logging.getLogger(__name__)

MAX_ATTEMPTS = 5


class NotificationServicer(notification_pb2_grpc.NotificationServiceServicer):
    def __init__(self, session_factory):
        self._session_factory = session_factory

    def Notify(self, request, context):
        key = request.idempotency_key
        req_hash = idempotency.request_hash(request)
        for attempt in range(1, MAX_ATTEMPTS + 1):
            session = self._session_factory()
            try:
                response = self._notify_tx(session, request, key, req_hash)
                session.commit()
                return response
            except IntegrityError as exc:
                # A concurrent call with the same key won the insert; retrying
                # resolves it via the replay lookup.
                session.rollback()
                log.warning(
                    "Notify attempt %d/%d for key=%s lost a race (%s)",
                    attempt,
                    MAX_ATTEMPTS,
                    key,
                    type(exc).__name__,
                )
            finally:
                session.close()
        log.error("Notify exhausted retries for key=%s", key)
        return notification_pb2.NotifyResponse(accepted=False)

    def _notify_tx(self, session, request, key, req_hash):
        if key:
            status, stored = idempotency.check(session, key, req_hash)
            if status == idempotency.LOOKUP_CONFLICT:
                log.error(
                    "Notify IDEMPOTENCY_CONFLICT for key=%s (order_id=%s)",
                    key,
                    request.order_id,
                )
                return notification_pb2.NotifyResponse(accepted=False)
            if status == idempotency.LOOKUP_REPLAY:
                return notification_pb2.NotifyResponse.FromString(stored)

        notification_id = "ntf_" + uuid.uuid4().hex
        session.add(
            Notification(
                notification_id=notification_id,
                order_id=request.order_id,
                kind=request.kind,
                recipient=request.recipient,
                subject=request.subject,
                body=request.body,
            )
        )
        # Contract section 3.4: log every notification to stdout.
        log.info(
            "NOTIFICATION id=%s order_id=%s kind=%s recipient=%s subject=%r "
            "body=%r",
            notification_id,
            request.order_id,
            request.kind,
            request.recipient,
            request.subject,
            request.body,
        )
        response = notification_pb2.NotifyResponse(
            accepted=True, notification_id=notification_id
        )
        if key:
            idempotency.record(session, key, req_hash, response)
        return response


def build_server(session_factory, port: int, max_workers: int = 16):
    """Build a gRPC server bound to *port* (0 = random) and return it.

    Registers the NotificationService implementation, the standard gRPC health
    service and server reflection (contract section 3.3). The caller starts it.
    """
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=max_workers))
    notification_pb2_grpc.add_NotificationServiceServicer_to_server(
        NotificationServicer(session_factory), server
    )

    health_servicer = health.HealthServicer()
    health_pb2_grpc.add_HealthServicer_to_server(health_servicer, server)
    health_servicer.set("", health_pb2.HealthCheckResponse.SERVING)

    reflection.enable_server_reflection(
        (
            notification_pb2.DESCRIPTOR.services_by_name[
                "NotificationService"
            ].full_name,
            health.SERVICE_NAME,
            reflection.SERVICE_NAME,
        ),
        server,
    )

    bound_port = server.add_insecure_port(f"[::]:{port}")
    return server, bound_port
