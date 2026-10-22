#!/usr/bin/env python3
"""gRPC helper: print current stock for one SKU via inventory.v1.GetStock.

Usage:
    scripts/get_stock.py <sku> [--host localhost] [--port 50053]

Prints `available reserved` (e.g. `10 0`) on success.
Exit codes: 0 = success, 1 = unknown SKU or connection/gRPC error.

Defaults target local dev (contract §2: inventory gRPC on localhost:50053).
Inside docker-compose the gRPC port is 50051; scripts/e2e.sh runs this helper
inside the inventory container with `--port 50051`.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Use the committed stubs in gen/python even without PYTHONPATH set.
_REPO_ROOT = Path(__file__).resolve().parents[1]
_GEN = _REPO_ROOT / "gen" / "python"
if _GEN.is_dir() and str(_GEN) not in sys.path:
    sys.path.insert(0, str(_GEN))

import grpc  # noqa: E402

from inventory.v1 import inventory_pb2, inventory_pb2_grpc  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Print `available reserved` for a SKU from inventory-service."
    )
    parser.add_argument("sku", help="SKU to look up, e.g. WIDGET-RED")
    parser.add_argument("--host", default="localhost", help="inventory gRPC host")
    parser.add_argument(
        "--port", type=int, default=50053, help="inventory gRPC port (local default 50053)"
    )
    args = parser.parse_args()

    target = f"{args.host}:{args.port}"
    try:
        with grpc.insecure_channel(target) as channel:
            stub = inventory_pb2_grpc.InventoryServiceStub(channel)
            response = stub.GetStock(
                inventory_pb2.GetStockRequest(skus=[args.sku]), timeout=5.0
            )
    except grpc.RpcError as exc:
        code = exc.code().name if exc.code() else "UNKNOWN"
        print(f"get_stock: gRPC error from {target}: {code}: {exc.details()}", file=sys.stderr)
        return 1
    except Exception as exc:  # pragma: no cover - defensive
        print(f"get_stock: {target} unreachable: {exc}", file=sys.stderr)
        return 1

    for level in response.levels:
        if level.sku == args.sku:
            print(f"{level.available} {level.reserved}")
            return 0

    print(f"get_stock: unknown sku {args.sku!r}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
