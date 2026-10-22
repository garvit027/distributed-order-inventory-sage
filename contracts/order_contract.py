"""Shared order-service contract fixtures for the checkout-web Pact tests.

Everything here is derived from docs/CONTRACT.md section 3.2 (order-service
REST edge). The provider states match the names the order-service provider
verification serves: "an order that is pending", "a confirmed order",
"an order that does not exist" and "invalid order payload".
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any

from pact import match

CONSUMER = "checkout-web"
PROVIDER = "order-service"

REPO_ROOT = Path(__file__).resolve().parents[1]
PACTS_DIR = REPO_ROOT / "pacts"
# Pact filename mandated by docs/CONTRACT.md section 3.8.
PACT_FILENAME = "order_service-checkout_web.json"

CREATE_IDEMPOTENCY_KEY = "pact-create-order-1"

# Stable machine codes from contract section 3.2.
FAILURE_REASONS = {
    "INSUFFICIENT_STOCK",
    "PAYMENT_DECLINED",
    "INVENTORY_UNAVAILABLE",
    "PAYMENT_UNAVAILABLE",
    "ORDER_UPDATE_FAILED",
    "ORCHESTRATOR_ERROR",
}
ORDER_STATUSES = {"PENDING", "CONFIRMED", "FAILED"}

# Exact key set of the order JSON shape (contract section 3.2). A removed or
# renamed field must fail the consumer test AND the provider verification.
ORDER_FIELDS = {
    "orderId",
    "customerId",
    "status",
    "failureReason",
    "sagaId",
    "amountCents",
    "currency",
    "items",
    "createdAt",
    "updatedAt",
}

# Valid order body used for POST /api/orders (camelCase JSON).
VALID_ORDER: dict[str, Any] = {
    "customerId": "cus_ok",
    "contact": "buyer@example.com",
    "currency": "USD",
    "amountCents": 2500,
    "items": [{"sku": "WIDGET-RED", "quantity": 2}],
}

# Invalid on every required field (no customerId, negative amount, no items).
INVALID_ORDER: dict[str, Any] = {
    "amountCents": -100,
    "items": [],
}

_ISO_8601 = (
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$"
)
_ORDER_ID = r"^ord_[A-Za-z0-9_-]+$"
_SAGA_ID = r"^saga_ord_[A-Za-z0-9_-]+$"
_STATUS = r"^(PENDING|CONFIRMED|FAILED)$"
_ERROR_CODE = r"^[A-Z][A-Z0-9_]{2,}$"


def order_example(
    order_id: str, status: str, failure_reason: str | None = None
) -> dict[str, Any]:
    """A contract-shaped order JSON body (used as matcher example values)."""
    return {
        "orderId": order_id,
        "customerId": VALID_ORDER["customerId"],
        "status": status,
        "failureReason": failure_reason,
        "sagaId": f"saga_{order_id}",
        "amountCents": VALID_ORDER["amountCents"],
        "currency": VALID_ORDER["currency"],
        "items": [dict(item) for item in VALID_ORDER["items"]],
        "createdAt": "2026-01-01T00:00:00Z",
        "updatedAt": "2026-01-01T00:00:00Z",
    }


def order_matcher_body(example: dict[str, Any]) -> dict[str, Any]:
    """The same shape, with matchers on provider-generated values.

    Literal values pin what the consumer sends/expects (customerId, amount,
    currency, items, status); regex matchers let the provider generate its
    own order ids / timestamps while still enforcing the field names and
    formats. A *removed or renamed field still fails verification*: pact
    matches every key present in the expected body against the actual one.
    """
    return {
        "orderId": match.regex(example["orderId"], regex=_ORDER_ID),
        "customerId": example["customerId"],
        "status": match.regex(example["status"], regex=_STATUS),
        "failureReason": None,
        "sagaId": match.regex(example["sagaId"], regex=_SAGA_ID),
        "amountCents": example["amountCents"],
        "currency": example["currency"],
        "items": example["items"],
        "createdAt": match.regex(example["createdAt"], regex=_ISO_8601),
        "updatedAt": match.regex(example["updatedAt"], regex=_ISO_8601),
    }


ERROR_ENVELOPE_EXAMPLE = {
    "error": {
        "code": "VALIDATION_ERROR",
        "message": "request body failed validation",
    }
}


def error_envelope_matcher_body() -> dict[str, Any]:
    """The 400 error envelope ``{"error": {"code", "message"}}``.

    The contract fixes the envelope shape but not the exact code for a given
    validation problem, so the code is matched with a conservative regex
    (stable machine-code style) while the *keys* are pinned exactly —
    removing/renaming ``error``, ``code`` or ``message`` fails.
    """
    return {
        "error": {
            "code": match.regex(
                ERROR_ENVELOPE_EXAMPLE["error"]["code"], regex=_ERROR_CODE
            ),
            "message": match.string(
                ERROR_ENVELOPE_EXAMPLE["error"]["message"]
            ),
        }
    }


def assert_order_shape(order: dict[str, Any]) -> None:
    """Assert the FULL contract shape (section 3.2).

    Fails if a field is removed, renamed or mistyped — the whole point of the
    contract test.
    """
    assert set(order.keys()) == ORDER_FIELDS, (
        f"order JSON shape changed: expected exactly {sorted(ORDER_FIELDS)}, "
        f"got {sorted(order.keys())}"
    )
    assert re.match(_ORDER_ID, order["orderId"]), order["orderId"]
    assert isinstance(order["customerId"], str) and order["customerId"]
    assert order["status"] in ORDER_STATUSES, order["status"]
    assert (
        order["failureReason"] is None
        or order["failureReason"] in FAILURE_REASONS
    ), order["failureReason"]
    assert re.match(_SAGA_ID, order["sagaId"]), order["sagaId"]
    assert order["sagaId"] == f"saga_{order['orderId']}"
    assert isinstance(order["amountCents"], int) and order["amountCents"] > 0
    assert isinstance(order["currency"], str) and order["currency"]
    assert isinstance(order["items"], list) and order["items"], order["items"]
    for item in order["items"]:
        assert set(item.keys()) == {"sku", "quantity"}, item
        assert isinstance(item["sku"], str) and item["sku"].strip()
        assert isinstance(item["quantity"], int) and item["quantity"] >= 1
    assert re.match(_ISO_8601, order["createdAt"]), order["createdAt"]
    assert re.match(_ISO_8601, order["updatedAt"]), order["updatedAt"]


def assert_error_envelope(payload: dict[str, Any]) -> None:
    """Assert the error envelope shape (``{"error": {"code", "message"}}``).

    Values may be concrete (from a real HTTP response) or pact matchers (from
    the declared response body); matchers are shape-checked only, concrete
    values are format-checked too.
    """
    assert set(payload.keys()) == {"error"}, sorted(payload.keys())
    error = payload["error"]
    assert set(error.keys()) == {"code", "message"}, sorted(error.keys())
    code = error["code"]
    if isinstance(code, str):
        assert re.match(_ERROR_CODE, code), code
    else:
        assert isinstance(code, match.AbstractMatcher), code
    message = error["message"]
    assert isinstance(message, (str, match.AbstractMatcher)), message
    if isinstance(message, str):
        assert message
