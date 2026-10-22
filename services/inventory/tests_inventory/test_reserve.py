"""Reserve behaviour (contract section 3.4), including the mandatory
no-oversell concurrency test."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor

from inventory.v1 import inventory_pb2



def _reserve(inventory_stub, key: str, order_id: str, sku: str, quantity: int):
    return inventory_stub.Reserve(
        inventory_pb2.ReserveRequest(
            idempotency_key=key,
            order_id=order_id,
            items=[inventory_pb2.StockLine(sku=sku, quantity=quantity)],
        )
    )


def test_concurrent_reserve_for_last_unit_never_oversells(
    inventory_stub, inventory_seed_stock, inventory_get_stock
):
    """MANDATORY (contract section 3.4): stock=1, 10 concurrent Reserves of
    qty 1 -> exactly 1 reserved=true, the rest INSUFFICIENT_STOCK, final
    available=0."""
    inventory_seed_stock({"WIDGET-RED": 1})

    def attempt(index: int):
        return _reserve(inventory_stub, f"oversell-{index}", f"ord-{index}", "WIDGET-RED", 1)

    with ThreadPoolExecutor(max_workers=10) as pool:
        responses = list(pool.map(attempt, range(10)))

    successes = [response for response in responses if response.reserved]
    assert len(successes) == 1, [str(r) for r in responses]
    for response in responses:
        if not response.reserved:
            assert response.failure_reason == "INSUFFICIENT_STOCK"

    stock = inventory_get_stock("WIDGET-RED")
    assert stock.available == 0
    assert stock.reserved == 1


def test_reserve_is_idempotent_per_key(inventory_stub, inventory_seed_stock, inventory_get_stock):
    inventory_seed_stock({"WIDGET-RED": 10})
    first = _reserve(inventory_stub, "replay-1", "ord-1", "WIDGET-RED", 2)
    second = _reserve(inventory_stub, "replay-1", "ord-1", "WIDGET-RED", 2)

    assert first.reserved is True
    assert second.reserved is True
    assert first.reservation_id == second.reservation_id

    stock = inventory_get_stock("WIDGET-RED")
    assert stock.available == 8  # decremented exactly once
    assert stock.reserved == 2


def test_reserve_unknown_sku_reports_shortage(inventory_stub, inventory_seed_stock, inventory_get_stock):
    inventory_seed_stock({"WIDGET-RED": 10})
    response = _reserve(inventory_stub, "ghost-1", "ord-2", "GHOST-SKU", 3)

    assert response.reserved is False
    assert response.failure_reason == "UNKNOWN_SKU"
    assert [(s.sku, s.requested, s.available) for s in response.shortages] == [
        ("GHOST-SKU", 3, 0)
    ]

    stock = inventory_get_stock("WIDGET-RED")
    assert stock.available == 10  # untouched: atomic all-or-nothing


def test_reserve_shortage_is_all_or_nothing_across_lines(inventory_stub, inventory_seed_stock, inventory_get_stock):
    inventory_seed_stock({"WIDGET-RED": 5, "WIDGET-BLUE": 2})
    response = inventory_stub.Reserve(
        inventory_pb2.ReserveRequest(
            idempotency_key="mixed-1",
            order_id="ord-3",
            items=[
                inventory_pb2.StockLine(sku="WIDGET-RED", quantity=1),  # fine
                inventory_pb2.StockLine(sku="WIDGET-BLUE", quantity=5),  # short
            ],
        )
    )

    assert response.reserved is False
    assert response.failure_reason == "INSUFFICIENT_STOCK"
    assert [(s.sku, s.requested, s.available) for s in response.shortages] == [
        ("WIDGET-BLUE", 5, 2)
    ]

    red = inventory_get_stock("WIDGET-RED")
    blue = inventory_get_stock("WIDGET-BLUE")
    assert (red.available, red.reserved) == (5, 0)  # no partial reservation
    assert (blue.available, blue.reserved) == (2, 0)


def test_reserve_idempotency_conflict_on_same_key_different_payload(
    inventory_stub, inventory_seed_stock, inventory_get_stock
):
    inventory_seed_stock({"WIDGET-RED": 10})
    first = _reserve(inventory_stub, "conflict-1", "ord-4", "WIDGET-RED", 1)
    conflicting = _reserve(inventory_stub, "conflict-1", "ord-4", "WIDGET-RED", 2)

    assert first.reserved is True
    assert conflicting.reserved is False
    assert conflicting.failure_reason == "IDEMPOTENCY_CONFLICT"

    stock = inventory_get_stock("WIDGET-RED")
    assert stock.available == 9  # only the first reserve applied


def test_reserve_empty_request_is_a_business_failure(inventory_stub):
    response = inventory_stub.Reserve(
        inventory_pb2.ReserveRequest(idempotency_key="empty-1", order_id="ord-5")
    )
    assert response.reserved is False
    assert response.failure_reason == "INVALID_ARGUMENT"
