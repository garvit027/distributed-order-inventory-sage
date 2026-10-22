# order-inventory — a distributed saga demo

Five microservices implementing a checkout flow with a **choreographed-by-
orchestrator saga**: placing an order reserves stock, charges a payment,
confirms the order and sends a notification — compensating in reverse
(reserve → release, refund) when any step fails. Every service keeps its own
embedded database, speaks **gRPC internally** (REST only at the order edge),
and exports **OpenTelemetry traces to Jaeger**, so one order shows up as a
single distributed trace across Java and Python process boundaries.

📖 **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — component diagram,
sequence diagrams (happy path + compensation), saga states, idempotency and
optimistic-locking story.
🔒 **[docs/CONTRACT.md](docs/CONTRACT.md)** — the frozen interface contract
(topology, env vars, behavioural rules, e2e scenarios).

## Repo layout

```
proto/                  shared gRPC contract (read-only, source of truth)
gen/python/             generated Python gRPC stubs (committed)
services/
  order-service/        Spring Boot — REST edge + gRPC server
  orchestrator/         Spring Boot — saga orchestration
  inventory/            FastAPI + gRPC — stock & reservations
  payment/              FastAPI + gRPC — charges & refunds
  notification/         FastAPI + gRPC — outbound messages
contracts/              Pact consumer tests (Python) — pacts land in pacts/
deploy/helm/            Helm chart "order-inventory"
scripts/                e2e.sh + gRPC helpers
docs/                   CONTRACT.md, ARCHITECTURE.md
```

## Quickstart (docker compose)

Requirements: Docker (Compose v2), Python 3.13.

```bash
make venv     # shared .venv for tests, e2e helpers and pact generation
make up       # build all five images and start the stack + Jaeger
```

Wait until `docker compose ps` shows everything healthy (Spring services take
~30–60s on first boot while H2 initializes), then place an order:

```bash
curl -i -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-1' \
  -d '{"customerId":"cus_ok",
       "contact":"buyer@example.com",
       "currency":"USD",
       "amountCents":2500,
       "items":[{"sku":"WIDGET-RED","quantity":2}]}'
# → 201 {"orderId":"ord_...", "status":"PENDING", ...}
```

Poll the order until it turns terminal:

```bash
curl http://localhost:8080/api/orders/ord_...
# → {"status":"CONFIRMED", ...}   (or FAILED + failureReason)
```

Replay the exact same request (same `Idempotency-Key`, same body): you get
**200** with the *same* orderId — no second order. A different body under the
same key returns **409**.

Try the failure paths:

```bash
# card always declines → order FAILED / PAYMENT_DECLINED, stock compensated
curl -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-2' \
  -d '{"customerId":"cus_decline","amountCents":2500,"items":[{"sku":"WIDGET-RED","quantity":1}]}'

# more than the 3 seeded units → FAILED / INSUFFICIENT_STOCK, stock untouched
curl -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: demo-3' \
  -d '{"customerId":"cus_ok","amountCents":2500,"items":[{"sku":"WIDGET-BLUE","quantity":999}]}'
```

**Traces**: open the Jaeger UI at <http://localhost:16686> and search for
services `order-service` and `orchestrator`.

Tear down with `make down` (add `docker compose down -v` to also drop the
database volumes and re-seed the stock to WIDGET-RED:10 / WIDGET-BLUE:3).

## Local development (no Docker)

Each service reads its ports and paths from environment variables whose
defaults (contract §2) match local dev, so nothing needs configuring:

| Service         | Kind        | HTTP           | gRPC           | Database                 |
|-----------------|-------------|----------------|----------------|--------------------------|
| order-service   | Spring Boot | 8080  (`SERVER_PORT`) | 50051 (`GRPC_PORT`) | H2 `./data/order-db`     |
| orchestrator    | Spring Boot | 8084 (`SERVER_PORT`)  | 50052 (`GRPC_PORT`) | H2 `./data/orchestrator-db` |
| inventory       | FastAPI     | 8081 (`HTTP_PORT`)    | 50053 (`GRPC_PORT`) | SQLite `data/inventory.db` |
| payment         | FastAPI     | 8082 (`HTTP_PORT`)    | 50054 (`GRPC_PORT`) | SQLite `data/payment.db` |
| notification    | FastAPI     | 8083 (`HTTP_PORT`)    | 50055 (`GRPC_PORT`) | SQLite `data/notification.db` |

Jaeger locally: run the all-in-one container and leave the services'
`OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317` default as-is:

```bash
docker run -d --name jaeger -p 16686:16686 -p 4317:4317 \
  -e COLLECTOR_OTLP_ENABLED=true jaegertracing/all-in-one:1.57
```

Python services (need the generated stubs first):

```bash
make venv
make protos        # regenerates gen/python via scripts/gen-python-proto.sh
PYTHONPATH=gen/python .venv/bin/python -m app.main   # run from services/<name>/
```

Java services (JDK 21 + Maven; protos are compiled by the build from ../proto):

```bash
cd services/order-service && mvn spring-boot:run
cd services/orchestrator  && mvn spring-boot:run
```

Inspect stock with the gRPC helper:

```bash
.venv/bin/python scripts/get_stock.py WIDGET-RED          # → 10 0
.venv/bin/python scripts/get_stock.py WIDGET-RED --host localhost --port 50053
```

## Testing

```bash
make test          # default: pytest (services + contracts) then mvn test (both Java services)
make test-python   # .venv/bin/python -m pytest services contracts -q
make test-java     # mvn -q test in services/order-service and services/orchestrator
make e2e           # five contract end-to-end scenarios against the running stack
```

`make e2e` requires the compose stack to be up (`make up`) and a seeded stock
level (reset with `docker compose down -v && make up`). It prints PASS/FAIL per
scenario and covers: happy path, payment-declined compensation, insufficient
stock, idempotency conflict, and the Jaeger trace check.

## Kubernetes (Helm)

```bash
# images must be visible to the cluster, e.g. for kind:
docker compose build
kind load docker-image doiwsc/order:0.1.0 doiwsc/orchestrator:0.1.0 \
     doiwsc/inventory:0.1.0 doiwsc/payment:0.1.0 doiwsc/notification:0.1.0

helm install demo deploy/helm
kubectl port-forward svc/demo-order 8080:8080       # REST edge
kubectl port-forward svc/demo-jaeger 16686:16686    # Jaeger UI
```

The chart (`deploy/helm`) mirrors compose: HTTP 8080 / gRPC 50051 in-cluster,
probes on the HTTP port (`/actuator/health` for Spring, `/healthz` for
FastAPI), OTel wired to the bundled Jaeger, and one emptyDir per data
directory. **replicaCount is pinned to 1 for every service** — each embeds a
stateful database (H2 file / SQLite) on an emptyDir; they share nothing, so
scaling out would corrupt state. Override values live in `values.yaml`
(per-service `image`, `env`, `resources`, `probes`; global `seedStock`,
`declineCustomers`, `otelEndpoint`).

## Contract testing

The checkout consumer (`contracts/`) defines **Pact** contracts against
order-service's REST API; the Python tests write them to
`pacts/order_service-checkout_web.json`. CI runs the provider side in
`services/order-service` (`mvn verify` replays the pacts from `pacts/` against
a live instance, seeded through `@Profile("pact")` state endpoints). Renaming
or removing a response field breaks the provider verification — that is the
point. Run the consumer side with `make test-python`.

## Tracing

All five services export OTLP/gRPC spans to Jaeger (`OTEL_SERVICE_NAME`:
`order-service`, `orchestrator`, `inventory-service`, `payment-service`,
`notification-service`). Java services attach the OpenTelemetry Java agent
zero-code (`JAVA_TOOL_OPTIONS`); Python services instrument FastAPI and gRPC in
process. W3C trace context rides along in gRPC metadata, so one order
generates one trace spanning `POST /api/orders` → `StartSaga` →
`RESERVE_INVENTORY`/`CHARGE_PAYMENT`/`CONFIRM_ORDER`/`NOTIFY` → downstream
calls. Browse at <http://localhost:16686>; `scripts/e2e.sh` scenario 5 asserts
it via the Jaeger API.
