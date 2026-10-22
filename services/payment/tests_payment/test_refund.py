"""Refund behaviour (contract section 3.4)."""
from __future__ import annotations

from payment.v1 import payment_pb2


def _charge(payment_stub, key: str, amount: int = 2500):
    return payment_stub.Charge(
        payment_pb2.ChargeRequest(
            idempotency_key=key,
            order_id="ord_1",
            customer_id="cus_ok",
            amount_cents=amount,
            currency="USD",
        )
    )


def _refund(payment_stub, key: str, payment_id: str):
    return payment_stub.Refund(
        payment_pb2.RefundRequest(
            idempotency_key=key, payment_id=payment_id, order_id="ord_1"
        )
    )


def test_refund_is_idempotent(payment_stub, payment_get_payment):
    charge = _charge(payment_stub, "rf-src-1")
    assert charge.charged is True

    first = _refund(payment_stub, "rf-1", charge.payment_id)
    replay = _refund(payment_stub, "rf-1", charge.payment_id)

    assert first.refunded is True
    assert replay.refunded is True
    assert payment_get_payment(charge.payment_id).state == "REFUNDED"

    # A fresh key against an already-refunded payment: no-op success and no
    # double transition.
    fresh_key = _refund(payment_stub, "rf-1-again", charge.payment_id)
    assert fresh_key.refunded is True
    assert payment_get_payment(charge.payment_id).state == "REFUNDED"


def test_refund_unknown_payment_is_not_found(payment_stub, payment_get_payment):
    first = _refund(payment_stub, "rf-missing", "pay_unknown")
    assert first.refunded is False
    assert first.failure_reason == "NOT_FOUND"

    # First sight of the key stores the failure; the replay is stable.
    replay = _refund(payment_stub, "rf-missing", "pay_unknown")
    assert replay.refunded is False
    assert replay.failure_reason == "NOT_FOUND"


def test_refund_idempotency_conflict_on_same_key_different_payment(payment_stub):
    charge = _charge(payment_stub, "rf-src-2")
    first = _refund(payment_stub, "rf-conflict", charge.payment_id)
    assert first.refunded is True

    conflicting = _refund(payment_stub, "rf-conflict", "pay_someone_elses")
    assert conflicting.refunded is False
    assert conflicting.failure_reason == "IDEMPOTENCY_CONFLICT"
