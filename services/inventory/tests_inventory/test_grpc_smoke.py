"""Integration smoke test: boot the REAL gRPC server in-process (random port,
real SQLite DB) and exercise it with the generated client stubs, plus the
standard health and reflection services (contract section 3.3)."""
from __future__ import annotations

from grpc_health.v1 import health_pb2, health_pb2_grpc
from grpc_reflection.v1alpha import reflection_pb2, reflection_pb2_grpc
from inventory.v1 import inventory_pb2


def test_grpc_wiring_end_to_end(inventory_stub, inventory_channel):
    seeded = inventory_stub.SeedStock(
        inventory_pb2.SeedStockRequest(
            seed=[
                inventory_pb2.StockLevel(
                    sku="WIDGET-RED", available=10, reserved=0
                )
            ]
        )
    )
    assert seeded.upserted == 1

    levels = inventory_stub.GetStock(
        inventory_pb2.GetStockRequest(skus=["WIDGET-RED"])
    ).levels
    assert [(level.sku, level.available, level.reserved) for level in levels] == [
        ("WIDGET-RED", 10, 0)
    ]

    reserve = inventory_stub.Reserve(
        inventory_pb2.ReserveRequest(
            idempotency_key="smoke-1",
            order_id="ord_smoke",
            items=[inventory_pb2.StockLine(sku="WIDGET-RED", quantity=3)],
        )
    )
    assert reserve.reserved is True
    assert reserve.reservation_id.startswith("res_")

    level = inventory_stub.GetStock(
        inventory_pb2.GetStockRequest(skus=["WIDGET-RED"])
    ).levels[0]
    assert (level.available, level.reserved) == (7, 3)
    assert level.row_version >= 1

    release = inventory_stub.Release(
        inventory_pb2.ReleaseRequest(
            idempotency_key="smoke-1-release",
            reservation_id=reserve.reservation_id,
        )
    )
    assert release.released is True

    level = inventory_stub.GetStock(
        inventory_pb2.GetStockRequest(skus=["WIDGET-RED"])
    ).levels[0]
    assert (level.available, level.reserved) == (10, 0)

    # Standard gRPC health service answers SERVING.
    health = health_pb2_grpc.HealthStub(inventory_channel).Check(
        health_pb2.HealthCheckRequest(service="")
    )
    assert health.status == health_pb2.HealthCheckResponse.SERVING

    # Server reflection exposes the service, health and reflection itself.
    reflection = reflection_pb2_grpc.ServerReflectionStub(inventory_channel)
    responses = list(
        reflection.ServerReflectionInfo(
            iter([reflection_pb2.ServerReflectionRequest(list_services="")])
        )
    )
    names = {
        service.name
        for service in responses[0].list_services_response.service
    }
    assert "inventory.v1.InventoryService" in names
    assert "grpc.health.v1.Health" in names
