# syntax=docker/dockerfile:1
# Build context is the REPO ROOT (contract section 3.6):
#   docker build -f services/payment/Dockerfile .
FROM python:3.13-slim

# Only what this service needs (contract section 3.6).
RUN pip install --no-cache-dir \
    "grpcio>=1.66" \
    "grpcio-health-checking>=1.66" \
    "grpcio-reflection>=1.66" \
    "fastapi>=0.115" \
    "uvicorn>=0.32" \
    "sqlalchemy>=2.0" \
    "pydantic>=2.9" \
    "opentelemetry-sdk>=1.28" \
    "opentelemetry-exporter-otlp-proto-grpc>=1.28" \
    "opentelemetry-instrumentation-fastapi>=0.49b0" \
    "opentelemetry-instrumentation-grpc>=0.49b0"

WORKDIR /app/service

COPY services/payment /app/service
COPY gen/python /app/gen/python

# Container ports are always 8080 (HTTP) / 50051 (gRPC) per contract
# section 2; compose/Helm may override any of these.
ENV PYTHONPATH=/app/gen/python \
    HTTP_PORT=8080 \
    GRPC_PORT=50051 \
    PAYMENT_DB_PATH=data/payment.db \
    OTEL_SERVICE_NAME=payment-service \
    OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317

RUN useradd --system --create-home --uid 10001 appuser \
    && mkdir -p /app/service/data \
    && chown -R appuser:appuser /app
USER appuser

EXPOSE 8080 50051

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8080/healthz', timeout=3)"

CMD ["python", "-m", "app.main"]
