"""Release compensation behaviour (contract section 3.4)."""
from __future__ import annotations

from inventory.v1 import inventory_pb2


def _reserve(inventory_stub, key: str, order_id: str, sku: str, quantity: int):
    return inventory_stub.Reserve(
        inventory_pb2.ReserveRequest(
            idempotency_key=key,
            order_id=order_id,
            items=[inventory_pb2.StockLine(sku=sku, quantity=quantity)],
        )
    )


def _release(inventory_stub, key: str, reservation_id: str):
    return inventory_stub.Release(
        inventory_pb2.ReleaseRequest(
            idempotency_key=key, reservation_id=reservation_id
        )
    )


def test_release_restores_stock_and_is_noop_safe(inventory_stub, inventory_seed_stock, inventory_get_stock):
    inventory_seed_stock({"WIDGET-RED": 10})
    reserve = _reserve(inventory_stub, "rel-1", "ord-1", "WIDGET-RED", 2)
    assert reserve.reserved is True
    assert (inventory_get_stock("WIDGET-RED").available, inventory_get_stock("WIDGET-RED").reserved) == (
        8,
        2,
    )

    release = _release(inventory_stub, "rel-1-release", reserve.reservation_id)
    assert release.released is True
    assert release.failure_reason == ""
    stock = inventory_get_stock("WIDGET-RED")
    assert (stock.available, stock.reserved) == (10, 0)

    # Releasing an already-RELEASED reservation with a fresh key: no-op
    # success, and stock must NOT be credited twice.
    again = _release(inventory_stub, "rel-1-release-again", reserve.reservation_id)
    assert again.released is True
    stock = inventory_get_stock("WIDGET-RED")
    assert (stock.available, stock.reserved) == (10, 0)

    # Replaying the original release key returns the stored response.
    replay = _release(inventory_stub, "rel-1-release", reserve.reservation_id)
    assert replay.released is True
    stock = inventory_get_stock("WIDGET-RED")
    assert (stock.available, stock.reserved) == (10, 0)


def test_release_unknown_reservation_is_not_found(inventory_stub, inventory_seed_stock):
    inventory_seed_stock({"WIDGET-RED": 10})
    response = _release(inventory_stub, "missing-1", "res_does_not_exist")
    assert response.released is False
    assert response.failure_reason == "NOT_FOUND"

    # Replay with the same key returns the stored first-sight response.
    replay = _release(inventory_stub, "missing-1", "res_does_not_exist")
    assert replay.released is False
    assert replay.failure_reason == "NOT_FOUND"


def test_release_idempotency_conflict_on_same_key_different_payload(inventory_stub):
    first = _release(inventory_stub, "rel-conflict", "res_a")
    assert first.released is False  # NOT_FOUND for res_a

    conflicting = _release(inventory_stub, "rel-conflict", "res_b")
    assert conflicting.released is False
    assert conflicting.failure_reason == "IDEMPOTENCY_CONFLICT"
