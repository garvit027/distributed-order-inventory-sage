"""INVENTORY_SEED startup behaviour: upsert-if-absent only (contract 3.4)."""
from __future__ import annotations


def test_seed_if_absent_never_overwrites_existing_rows(inventory_svc, inventory_session_factory):
    from sqlalchemy import select

    monkey = inventory_svc.models.Stock

    assert inventory_svc.main.seed_if_absent() == 2  # default seed: RED:10, BLUE:3

    def levels():
        session = inventory_session_factory()
        try:
            return {
                row.sku: (row.available, row.reserved)
                for row in session.scalars(select(monkey))
            }
        finally:
            session.close()

    assert levels() == {"WIDGET-RED": (10, 0), "WIDGET-BLUE": (3, 0)}

    # Someone changed RED's stock at runtime...
    session = inventory_session_factory()
    try:
        session.get(monkey, "WIDGET-RED").available = 42
        session.commit()
    finally:
        session.close()

    # ...a later startup seed must not touch it, but still adds new SKUs.
    import os

    os.environ["INVENTORY_SEED"] = "WIDGET-RED:99,WIDGET-GREEN:7"
    try:
        assert inventory_svc.main.seed_if_absent() == 1
    finally:
        del os.environ["INVENTORY_SEED"]

    assert levels() == {
        "WIDGET-RED": (42, 0),  # untouched
        "WIDGET-BLUE": (3, 0),
        "WIDGET-GREEN": (7, 0),
    }


def test_parse_seed_handles_whitespace_and_blank_entries(inventory_svc):
    assert inventory_svc.main.parse_seed("A:1, B:2 , ,C:x") == {"A": 1, "B": 2}
