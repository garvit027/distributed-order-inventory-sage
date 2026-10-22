"""OpenTelemetry setup (contract section 3.7).

Tracing must never take the service down: every hook here swallows errors and
logs a warning instead. The OTLP exporter uses a BatchSpanProcessor, so an
absent Jaeger only produces dropped spans in the background (contract 3.7:
"never crash or block when Jaeger is absent").
"""
from __future__ import annotations

import logging
import os

log = logging.getLogger(__name__)


def setup_telemetry(default_service_name: str):
    """Configure the OTel SDK with an OTLP/gRPC exporter.

    Reads OTEL_SERVICE_NAME (default: *default_service_name*) and
    OTEL_EXPORTER_OTLP_ENDPOINT (default: http://localhost:4317).
    Returns the TracerProvider, or None when tracing is disabled/unavailable.
    """
    try:
        if os.environ.get("OTEL_SDK_DISABLED", "").lower() == "true":
            log.info("OpenTelemetry disabled via OTEL_SDK_DISABLED")
            return None

        from opentelemetry import trace
        from opentelemetry.exporter.otlp.proto.grpc.trace_exporter import (
            OTLPSpanExporter,
        )
        from opentelemetry.sdk.resources import Resource
        from opentelemetry.sdk.trace import TracerProvider
        from opentelemetry.sdk.trace.export import BatchSpanProcessor

        service_name = os.environ.get("OTEL_SERVICE_NAME", default_service_name)
        endpoint = os.environ.get(
            "OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317"
        )
        provider = TracerProvider(
            resource=Resource.create({"service.name": service_name})
        )
        exporter = OTLPSpanExporter(
            endpoint=endpoint,
            insecure=endpoint.startswith("http://"),
            timeout=10,
        )
        provider.add_span_processor(
            BatchSpanProcessor(
                exporter,
                schedule_delay_millis=5_000,
                max_export_batch_size=256,
                export_timeout_millis=5_000,
            )
        )
        trace.set_tracer_provider(provider)
        log.info(
            "OpenTelemetry ready (service.name=%s, endpoint=%s)",
            service_name,
            endpoint,
        )
        return provider
    except Exception:  # pragma: no cover - defensive
        log.warning("OpenTelemetry setup failed; continuing without tracing",
                    exc_info=True)
        return None


def instrument_fastapi_app(app) -> None:
    """Attach the FastAPI instrumentor to *app* (best effort)."""
    try:
        from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor

        FastAPIInstrumentor().instrument_app(app)
    except Exception:  # pragma: no cover - defensive
        log.warning("FastAPI instrumentation failed", exc_info=True)


def instrument_grpc() -> None:
    """Enable server-side gRPC instrumentation (global, best effort).

    Must be called before gRPC servers are created. The instrumentor is
    idempotent, so repeated calls are safe.
    """
    try:
        from opentelemetry.instrumentation.grpc import GrpcInstrumentorServer

        GrpcInstrumentorServer().instrument()
    except Exception:  # pragma: no cover - defensive
        log.warning("gRPC instrumentation failed", exc_info=True)
