"""Notify behaviour (contract section 3.4)."""
from __future__ import annotations

import logging

from notification.v1 import notification_pb2


def _notify(notification_stub, key: str, kind: str = "ORDER_CONFIRMED"):
    return notification_stub.Notify(
        notification_pb2.NotifyRequest(
            idempotency_key=key,
            order_id="ord_1",
            recipient="buyer@example.com",
            kind=kind,
            subject="Order confirmed",
            body="Your order was confirmed.",
        )
    )


def test_duplicate_notify_creates_one_row(notification_stub, notification_count_notifications):
    """MANDATORY (contract section 3.4): duplicate Notify -> one row."""
    first = _notify(notification_stub, "ntf-1")
    duplicate = _notify(notification_stub, "ntf-1")

    assert first.accepted is True
    assert first.notification_id.startswith("ntf_")
    assert duplicate.accepted is True
    assert duplicate.notification_id == first.notification_id

    assert notification_count_notifications() == 1


def test_distinct_keys_create_distinct_rows(notification_stub, notification_count_notifications):
    _notify(notification_stub, "ntf-a", kind="ORDER_CONFIRMED")
    _notify(notification_stub, "ntf-b", kind="ORDER_FAILED")
    assert notification_count_notifications() == 2


def test_conflicting_key_payload_is_rejected(notification_stub, notification_count_notifications):
    first = _notify(notification_stub, "ntf-c", kind="ORDER_CONFIRMED")
    assert first.accepted is True

    conflicting = _notify(notification_stub, "ntf-c", kind="ORDER_FAILED")
    assert conflicting.accepted is False
    assert notification_count_notifications() == 1


def test_every_notification_is_logged(notification_stub, caplog):
    caplog.set_level(logging.INFO)
    response = _notify(notification_stub, "ntf-log")
    assert response.accepted is True

    logged = [record for record in caplog.records if "NOTIFICATION" in record.message]
    assert len(logged) == 1
    assert response.notification_id in logged[0].message
    assert "ord_1" in logged[0].message
    assert "ORDER_CONFIRMED" in logged[0].message
    assert "buyer@example.com" in logged[0].message
