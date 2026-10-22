"""Charge behaviour (contract section 3.4)."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

from payment.v1 import payment_pb2


def _charge(payment_stub, key: str, customer_id: str = "cus_ok", amount: int = 2500):
    return payment_stub.Charge(
        payment_pb2.ChargeRequest(
            idempotency_key=key,
            order_id="ord_1",
            customer_id=customer_id,
            amount_cents=amount,
            currency="USD",
        )
    )


def test_same_key_charge_creates_one_payment(payment_stub, payment_count_payments, payment_get_payment):
    """MANDATORY (contract section 3.4): same-key Charge twice -> one row,
    same payment_id."""
    first = _charge(payment_stub, "ch-1")
    replay = _charge(payment_stub, "ch-1")

    assert first.charged is True
    assert first.payment_id.startswith("pay_")
    assert replay.charged is True
    assert replay.payment_id == first.payment_id

    assert payment_count_payments() == 1
    payment = payment_get_payment(first.payment_id)
    assert payment.state == "CAPTURED"
    assert (payment.amount_cents, payment.currency) == (2500, "USD")


def test_declined_charge_replays_the_same_failure(payment_stub, payment_count_payments):
    """MANDATORY: decline replay returns the same CARD_DECLINED."""
    first = _charge(payment_stub, "ch-decline", customer_id="cus_decline")
    replay = _charge(payment_stub, "ch-decline", customer_id="cus_decline")

    assert first.charged is False
    assert first.failure_reason == "CARD_DECLINED"
    assert replay.charged is False
    assert replay.failure_reason == "CARD_DECLINED"
    assert replay.payment_id == first.payment_id == ""

    assert payment_count_payments() == 0  # declines create no payment row


def test_decline_above_threshold_rule(monkeypatch, payment_stub, payment_count_payments):
    monkeypatch.setenv("PAYMENT_DECLINE_ABOVE_CENTS", "1000")

    declined = _charge(payment_stub, "ch-above", amount=2500)
    assert declined.charged is False
    assert declined.failure_reason == "CARD_DECLINED"

    accepted = _charge(payment_stub, "ch-below", amount=1000)  # equal is NOT above
    assert accepted.charged is True

    assert payment_count_payments() == 1


def test_decline_rule_disabled_by_default(payment_stub):
    # PAYMENT_DECLINE_ABOVE_CENTS defaults to -1 (disabled), so a large
    # amount for a normal customer succeeds.
    accepted = _charge(payment_stub, "ch-big", amount=1_000_000)
    assert accepted.charged is True


def test_invalid_amount_is_a_business_failure(payment_stub, payment_count_payments):
    for index, amount in enumerate((0, -5)):
        response = _charge(payment_stub, f"ch-invalid-{index}", amount=amount)
        assert response.charged is False
        assert response.failure_reason == "INVALID_AMOUNT"
    assert payment_count_payments() == 0


def test_charge_idempotency_conflict_on_same_key_different_payload(payment_stub):
    first = _charge(payment_stub, "ch-conflict", amount=2500)
    assert first.charged is True

    conflicting = _charge(payment_stub, "ch-conflict", amount=5000)
    assert conflicting.charged is False
    assert conflicting.failure_reason == "IDEMPOTENCY_CONFLICT"


def test_concurrent_same_key_charges_settle_on_one_payment(
    payment_stub, payment_count_payments, payment_get_payment
):
    def attempt(index: int):
        return _charge(payment_stub, "ch-race")

    with ThreadPoolExecutor(max_workers=8) as pool:
        responses = list(pool.map(attempt, range(8)))

    payment_ids = {response.payment_id for response in responses}
    assert all(response.charged for response in responses)
    assert len(payment_ids) == 1
    assert payment_count_payments() == 1
    assert payment_get_payment(payment_ids.pop()).state == "CAPTURED"
