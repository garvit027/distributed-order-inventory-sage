"""Meta-test: the contract assertions MUST fail when a response field is
removed or renamed (contract section 3.8: "Contract tests must fail when a
response field is renamed/removed — that is the point")."""
from __future__ import annotations

import json

import pytest

from order_contract import (
    ORDER_FIELDS,
    assert_error_envelope,
    assert_order_shape,
    error_envelope_matcher_body,
    order_example,
    order_matcher_body,
)


def test_full_order_shape_passes_for_a_valid_order():
    assert_order_shape(order_example("ord_ok", "PENDING"))


def test_removed_field_fails():
    order = order_example("ord_ok", "PENDING")
    del order["sagaId"]
    with pytest.raises(AssertionError, match="shape changed"):
        assert_order_shape(order)


def test_renamed_field_fails():
    order = order_example("ord_ok", "PENDING")
    order["order_id"] = order.pop("orderId")  # snake_case rename
    with pytest.raises(AssertionError, match="shape changed"):
        assert_order_shape(order)


def test_extra_field_fails():
    order = order_example("ord_ok", "PENDING")
    order["surprise"] = True
    with pytest.raises(AssertionError, match="shape changed"):
        assert_order_shape(order)


def test_item_shape_is_pinned():
    order = order_example("ord_ok", "PENDING")
    order["items"] = [{"productCode": "WIDGET-RED", "quantity": 2}]
    with pytest.raises(AssertionError):
        assert_order_shape(order)


def test_error_envelope_shape_is_pinned():
    assert_error_envelope(error_envelope_matcher_body())

    for mutated in (
        {},
        {"error": {"code": "X"}},
        {"error": {"code": "X", "message": "y", "extra": 1}},
        {"errors": {"code": "X", "message": "y"}},
    ):
        with pytest.raises(AssertionError):
            assert_error_envelope(mutated)


def test_pact_file_pins_the_full_order_shape():
    """The written pact pins every order field, so provider verification fails
    if the provider drops or renames one."""
    from pathlib import Path

    from order_contract import PACT_FILENAME

    pact_file = Path(__file__).resolve().parents[1] / ".." / "pacts" / PACT_FILENAME
    if not pact_file.exists():
        pytest.skip("pact file not generated yet; run tests/test_order_pact.py")

    spec = json.loads(pact_file.read_text())
    order_response = next(
        interaction
        for interaction in spec["interactions"]
        if interaction["response"]["status"] == 201
    )
    assert set(order_response["response"]["body"]["content"].keys()) == (
        ORDER_FIELDS
    )
    assert set(order_response["response"]["body"]["content"].keys()) == (
        set(order_matcher_body(order_example("ord_x", "PENDING")).keys())
    )
