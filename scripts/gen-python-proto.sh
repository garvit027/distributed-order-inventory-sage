#!/usr/bin/env bash
# Generates Python gRPC stubs for ALL protos into <repo>/gen/python.
# Output is committed so services never need protoc/grpcio-tools at runtime.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="${PYTHON:-$ROOT/.venv/bin/python}"

if ! "$PY" -c "import grpc_tools.protoc" >/dev/null 2>&1; then
  echo "grpcio-tools missing — run: python3 -m venv .venv && .venv/bin/pip install -r requirements.txt" >&2
  exit 1
fi

OUT="$ROOT/gen/python"
mkdir -p "$OUT"
find "$OUT" -name '*_pb2*.py' -delete 2>/dev/null || true

"$PY" -m grpc_tools.protoc \
  -I "$ROOT/proto" \
  --python_out="$OUT" \
  --grpc_python_out="$OUT" \
  "$ROOT"/proto/*/*/*.proto

# protoc does not emit __init__.py for intermediate packages.
for pkg in inventory payment notification order orchestrator; do
  for sub in v1; do
    mkdir -p "$OUT/$pkg/$sub"
    touch "$OUT/$pkg/__init__.py" "$OUT/$pkg/$sub/__init__.py"
  done
done

echo "Generated:"
find "$OUT" -name '*_pb2*.py' | sed "s|$ROOT/||" | sort
