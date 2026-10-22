"""Telemetry must never crash when Jaeger is absent (contract section 3.7)."""
from __future__ import annotations


def test_setup_and_export_survive_unreachable_otlp_endpoint(inventory_svc, monkeypatch):
    monkeypatch.delenv("OTEL_SDK_DISABLED", raising=False)
    # Port 1 on localhost refuses connections instantly: export attempts fail,
    # but nothing may raise and the provider must still work.
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:1")

    provider = inventory_svc.telemetry.setup_telemetry("inventory-service")
    assert provider is not None

    from opentelemetry import trace

    tracer = trace.get_tracer("test")
    with tracer.start_as_current_span("smoke-span"):
        pass

    # Shutdown flushes the batch processor; failing exports are swallowed.
    provider.shutdown()


def test_setup_can_be_disabled(inventory_svc, monkeypatch):
    monkeypatch.setenv("OTEL_SDK_DISABLED", "true")
    assert inventory_svc.telemetry.setup_telemetry("inventory-service") is None
