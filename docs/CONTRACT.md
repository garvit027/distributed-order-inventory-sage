# CONTRACT — non-negotiable interface & layout agreement

This repo is being built by three agents in parallel. **Everything in this file is
frozen.** If you believe something here is wrong, work around it — do not change
shared files (`proto/**`, `gen/python/**`, `scripts/gen-python-proto.sh`, this file).

## 1. Repository layout

```
doiwsc/
├── proto/                      # shared gRPC contract (done — read-only)
│   ├── inventory/v1/inventory.proto
│   ├── payment/v1/payment.proto
│   ├── notification/v1/notification.proto
│   ├── order/v1/order.proto
│   └── orchestrator/v1/orchestrator.proto
├── gen/python/                 # generated Python gRPC stubs (committed, read-only;
│                               # regenerate ONLY via scripts/gen-python-proto.sh)
├── services/
│   ├── order-service/          # Spring Boot  — REST edge + gRPC server  [Agent A]
│   ├── orchestrator/           # Spring Boot  — saga orchestration       [Agent A]
│   ├── inventory/              # FastAPI + gRPC                            [Agent B]
│   ├── payment/                # FastAPI + gRPC                            [Agent B]
│   └── notification/           # FastAPI + gRPC                            [Agent B]
├── contracts/                  # Pact artifacts (consumer tests + pacts/)  [A + B]
├── deploy/helm/                # Helm chart "order-inventory"              [Agent C]
├── docker-compose.yaml                                                     [Agent C]
├── scripts/                    # e2e + helpers                             [Agent C]
├── docs/ARCHITECTURE.md                                                    [Agent C]
├── Makefile, README.md, requirements.txt, .github/workflows/ci.yml         [Agent C]
└── .venv/                      # shared Python venv (created by integrator)
```

**Ownership:** do not create/modify files outside your own subtree, except as
listed above. Do not edit `proto/**`, `gen/python/**`, `requirements.txt`
(ask the integrator by finishing your work; append only if you truly need a new
package), or other agents' services.

## 2. Topology & ports

All internal traffic is gRPC. REST exists **only** on order-service (public edge).
The only other HTTP endpoints are liveness/readiness probes.

| Service          | Kind        | HTTP (local default) | gRPC (local default) | Container ports        |
|------------------|-------------|----------------------|----------------------|------------------------|
| order-service    | Spring Boot | 8080 (`SERVER_PORT`) | 50051 (`GRPC_PORT`)  | HTTP 8080, gRPC 50051  |
| orchestrator     | Spring Boot | 8084 (`SERVER_PORT`) | 50052 (`GRPC_PORT`)  | HTTP 8080, gRPC 50051  |
| inventory        | FastAPI     | 8081 (`HTTP_PORT`)   | 50053 (`GRPC_PORT`)  | HTTP 8080, gRPC 50051  |
| payment          | FastAPI     | 8082 (`HTTP_PORT`)   | 50054 (`GRPC_PORT`)  | HTTP 8080, gRPC 50051  |
| notification     | FastAPI     | 8083 (`HTTP_PORT`)   | 50055 (`GRPC_PORT`)  | HTTP 8080, gRPC 50051  |

Inside docker-compose / Kubernetes every service listens on **8080 (HTTP) and
50051 (gRPC)**; compose/Helm override the local defaults above. Each service
must read its ports from env with the local defaults baked in, so `make dev`
works with no configuration.

### Environment variables (all optional, defaults = local dev)

**order-service**: `SERVER_PORT=8080`, `GRPC_PORT=50051`,
`SPRING_DATASOURCE_URL=jdbc:h2:file:./data/order-db`,
`SPRING_DATASOURCE_USERNAME=sa`, `SPRING_DATASOURCE_PASSWORD=`,
`ORCHESTRATOR_SERVICE_HOST=localhost`, `ORCHESTRATOR_SERVICE_PORT=50052`,
`OTEL_SERVICE_NAME=order-service`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`

**orchestrator**: `SERVER_PORT=8084`, `GRPC_PORT=50052`,
`SPRING_DATASOURCE_URL=jdbc:h2:file:./data/orchestrator-db`,
`ORDER_SERVICE_HOST=localhost`, `ORDER_SERVICE_PORT=50051`,
`INVENTORY_SERVICE_HOST=localhost`, `INVENTORY_SERVICE_PORT=50053`,
`PAYMENT_SERVICE_HOST=localhost`, `PAYMENT_SERVICE_PORT=50054`,
`NOTIFICATION_SERVICE_HOST=localhost`, `NOTIFICATION_SERVICE_PORT=50055`,
`ORCHESTRATOR_GRPC_DEADLINE_MS=10000`, `ORCHESTRATOR_MAX_RETRIES=3`,
`ORCHESTRATOR_RETRY_BACKOFF_MS=250`, `ORCHESTRATOR_WORKERS=4`,
`OTEL_SERVICE_NAME=orchestrator`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`

**inventory**: `HTTP_PORT=8081`, `GRPC_PORT=50053`,
`INVENTORY_DB_PATH=data/inventory.db`,
`INVENTORY_SEED=WIDGET-RED:10,WIDGET-BLUE:3`,
`OTEL_SERVICE_NAME=inventory-service`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`

**payment**: `HTTP_PORT=8082`, `GRPC_PORT=50054`, `PAYMENT_DB_PATH=data/payment.db`,
`PAYMENT_DECLINE_CUSTOMERS=cus_decline` (comma-separated customer ids that always
get `CARD_DECLINED`), `PAYMENT_DECLINE_ABOVE_CENTS=-1` (>=0 enables amount rule),
`OTEL_SERVICE_NAME=payment-service`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`

**notification**: `HTTP_PORT=8083`, `GRPC_PORT=50055`,
`NOTIFICATION_DB_PATH=data/notification.db`,
`OTEL_SERVICE_NAME=notification-service`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317`

## 3. Behavioural contract

### 3.1 Saga (orchestrator)

Happy path: `RESERVE_INVENTORY → CHARGE_PAYMENT → CONFIRM_ORDER → NOTIFY` → state `SUCCEEDED`.

Failure handling (compensation in reverse):
- Reserve fails (business: `INSUFFICIENT_STOCK`) → no compensation needed →
  `FailOrder(reason=INSUFFICIENT_STOCK)` + `NOTIFY(kind=ORDER_FAILED)` → state `FAILED`.
- Charge fails (business: `CARD_DECLINED`) → `RELEASE_INVENTORY` (compensate) →
  `FailOrder(reason=PAYMENT_DECLINED)` + `NOTIFY(kind=ORDER_FAILED)` → state `COMPENSATED`.
- Confirm fails → `REFUND_PAYMENT` → `RELEASE_INVENTORY` → `FailOrder(reason=ORDER_UPDATE_FAILED)`
  + notify failure → state `COMPENSATED`.
- Technical gRPC failure (`UNAVAILABLE`, `DEADLINE_EXCEEDED`, `INTERNAL`) → retry
  `ORCHESTRATOR_MAX_RETRIES` times with `ORCHESTRATOR_RETRY_BACKOFF_MS` backoff
  (idempotency keys make retries safe); then treat as failure of that step and
  compensate whatever succeeded. Reserve unavailability →
  `FailOrder(reason=INVENTORY_UNAVAILABLE)`, charge unavailability → compensate
  reserve, then `FailOrder(reason=PAYMENT_UNAVAILABLE)`.
- `NOTIFY` is best-effort: failures are recorded in the step detail but never fail
  or compensate the saga.
- `StartSaga` is idempotent per order: `saga_id = "saga_" + order_id`; a duplicate
  call returns `accepted=true` with the existing saga id.

Business outcomes ride in **response fields**, never in gRPC status codes. Only
technical errors use non-OK statuses. Every RPC carries an `idempotency_key`;
generate it deterministically where possible (e.g. `charge:<order_id>`), so
transport-level retries can never double-execute.

### 3.2 order-service REST (public edge)

`POST /api/orders`
- Header `Idempotency-Key` (optional; strongly recommended). Replay with the same
  key returns the original order with **200** (instead of 201) and must not create
  a second order. Same key + different body → **409** with
  `{"error":{"code":"IDEMPOTENCY_CONFLICT","message":"..."}}`.
- Body (camelCase JSON):
  `{"customerId":"cus_ok","contact":"buyer@example.com","currency":"USD","amountCents":2500,"items":[{"sku":"WIDGET-RED","quantity":2}]}`
  - `customerId`, `amountCents` (>0), `items` (non-empty, quantity ≥1, sku non-blank) required; `contact` optional (default `<customerId>@example.com`), `currency` default `USD`.
- **201** order JSON on creation; **400** `{"error":{"code":..,"message":..}}` on validation.
- On creation: store order `PENDING`, then call orchestrator `StartSaga` (gRPC)
  with `idempotency_key = "saga:" + orderId`. If StartSaga is technically
  unavailable, leave the order `PENDING` (do not fail it) — the orchestrator
  accepts asynchronously anyway.

`GET /api/orders/{orderId}` → **200** order JSON, **404** if unknown.
`GET /api/orders` → `{"orders":[...]}`.
`GET /actuator/health` → 200 (Spring Actuator).

Order JSON shape:
```json
{"orderId":"ord_...","customerId":"cus_ok","status":"PENDING|CONFIRMED|FAILED",
 "failureReason":null,"sagaId":"saga_ord_...","amountCents":2500,"currency":"USD",
 "items":[{"sku":"WIDGET-RED","quantity":2}],
 "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}
```
`failureReason` values (stable machine codes): `INSUFFICIENT_STOCK`,
`PAYMENT_DECLINED`, `INVENTORY_UNAVAILABLE`, `PAYMENT_UNAVAILABLE`,
`ORDER_UPDATE_FAILED`, `ORCHESTRATOR_ERROR`.

The orchestrator drives `PENDING → CONFIRMED|FAILED` via gRPC
(`ConfirmOrder`/`FailOrder`), both idempotent (second call with same/new key →
`updated=false`, returns `current_status`; a CONFIRMED order can never go FAILED
and vice versa).

### 3.3 FastAPI service skeleton (each of inventory / payment / notification)

- `app/main.py` — FastAPI app with `GET /healthz` (liveness) and `GET /readyz`
  (DB roundtrip), uvicorn bound to `HTTP_PORT`.
- `app/grpc_server.py` — synchronous `grpc` server bound to `GRPC_PORT`,
  registered in a FastAPI `lifespan` (start on startup, graceful stop on
  shutdown). Register: the service's own impl, `grpc_health.v1.Health` and
  server reflection.
- `app/db.py` + `app/models.py` — SQLAlchemy (sync engine, SQLite file at
  `<service>_DB_PATH`), `create_all` on startup (no alembic needed).
- `app/idempotency.py` — reusable idempotency helper: table
  `idempotency_records(key TEXT PRIMARY KEY, request_hash TEXT NOT NULL,
  response_proto BLOB NOT NULL, created_at TIMESTAMP)`. Lookup key → same hash →
  return stored response; different hash → business failure
  `IDEMPOTENCY_CONFLICT`. The write of business row + idempotency record must be
  in **one transaction** (idempotency table lives in the same SQLite DB).
- Every service returns business outcomes in response fields (never non-OK gRPC
  status for business logic) and processes every RPC's `idempotency_key`.
- Logging to stdout; `python -m app.main` starts everything.
- Import generated stubs as `from inventory.v1 import inventory_pb2,
  inventory_pb2_grpc` (repo-root `gen/python` is on `PYTHONPATH`; it is
  `PYTHONPATH=<repo>/gen/python` locally and `/app/gen/python` in Docker).

### 3.4 Service-specific rules

**inventory**
- Table `stock(sku PK, available INT, reserved INT, version BIGINT)` — SQLAlchemy
  optimistic locking via `version_id_col="version"` (`mapper_args`). On
  `StaleDataError`, retry the whole reserve transaction up to 5 times, then
  return `reserved=false, failure_reason="CONFLICT"`.
- Table `reservations(reservation_id PK, order_id, state, created_at)` +
  `reservation_lines(reservation_id, sku, quantity)`.
- `Reserve`: validate all lines atomically — any shortage/unknown SKU →
  `reserved=false, failure_reason="INSUFFICIENT_STOCK"` (or `UNKNOWN_SKU`) with
  `shortages`, **no rows changed**. Else `available -= q`, `reserved += q`,
  insert reservation `state=RESERVED`, idempotent by key.
- `Release`: reservation `RESERVED → RELEASED`, `available += q`, `reserved -= q`;
  releasing a RELEASED reservation or replayed key → `released=true` (no-op).
  Unknown reservation_id → `released=false, failure_reason="NOT_FOUND"` only on
  first sight of that key.
- Seed `INVENTORY_SEED` (`SKU:qty,...`) at startup: upsert-if-absent only.
- **Must-have test**: stock=1, 10 concurrent `Reserve` calls for qty 1 → exactly
  1 `reserved=true`, others `INSUFFICIENT_STOCK`, final `available=0`.

**payment**
- Tables `payments(payment_id PK, order_id, amount_cents, currency, state,
  created_at)`.
- `Charge`: decline rules (→ `charged=false, failure_reason="CARD_DECLINED"`)
  when `customer_id` ∈ `PAYMENT_DECLINE_CUSTOMERS` or
  `PAYMENT_DECLINE_ABOVE_CENTS >= 0 && amount_cents > PAYMENT_DECLINE_ABOVE_CENTS`;
  `amount_cents <= 0` → `INVALID_AMOUNT`. On success create `payment_id`
  (`pay_<uuid>`), state `CAPTURED`. Idempotent by key (same key+hash → original
  response incl. original failure; different payload → `IDEMPOTENCY_CONFLICT`).
- `Refund`: `CAPTURED → REFUNDED`; idempotent. Unknown payment →
  `refunded=false, failure_reason="NOT_FOUND"` (first sight of key).
- **Must-have test**: same-key Charge twice → one row, same `payment_id`; decline
  replay returns the same `CARD_DECLINED`.

**notification**
- Table `notifications(notification_id PK, order_id, kind, recipient, subject,
  body, created_at)`, idempotent by key.
- Log every notification to stdout too. **Must-have test**: duplicate Notify →
  one row.

### 3.5 Spring Boot services (Agent A)

- Spring Boot **3.5.x**, Java release **21**, Maven, groupId `com.doiwsc`.
- Two independent Maven projects: `services/order-service/pom.xml`,
  `services/orchestrator/pom.xml`. Both add `protobuf-maven-plugin`
  (protoSourceRoot `${project.basedir}/../../proto`, `os-maven-plugin` extension,
  protoc 3.25.5, gRPC 1.65.0 stubs) so all five protos compile into each project.
- gRPC: plain grpc-java (`grpc-netty-shaded`, `grpc-protobuf`, `grpc-stub`,
  `grpc-services` for health + reflection) — **no** spring-boot gRPC starter
  libraries. Expose `Server` via Spring `SmartLifecycle` and inject managed
  channels with deadline/`NAME_RESOLVER` defaults; targets from
  `*_SERVICE_HOST`/`*_SERVICE_PORT` props.
- order-service: JPA + H2 (file), `@RestController` edge API, idempotency via a
  unique `idempotency_key` column (find-or-create inside one transaction) and
  request-hash check → 409. gRPC server implements `order.v1.OrderService`.
  Calls orchestrator via gRPC client interface (`OrchestratorGateway`) — a bean
  mocked in tests.
- orchestrator: JPA + H2, saga + step entities, executes async on a bounded
  executor (`ORCHESTRATOR_WORKERS`). All downstream calls behind interfaces
  (`InventoryGateway`, `PaymentGateway`, `OrderGateway`, `NotificationGateway`)
  with a real gRPC impl and test fakes, so the saga engine is unit-testable
  without sockets. Implement the state machine exactly as §3.1.
- Both services: Actuator (`/actuator/health`), OTel — zero-code via the
  opentelemetry-javaagent attached by `JAVA_TOOL_OPTIONS` in Docker (nothing to
  do in application code beyond sane span names if desired), structured-ish
  logging with order/saga ids.
- Tests must pass with `mvn -q test` (JUnit 5 + Spring test). Cover: saga success,
  payment-declined → compensation release called, confirm-fail → refund+release,
  retry on `UNAVAILABLE`, order REST idempotent replay/conflict, confirm/fail
  idempotency.

### 3.6 Dockerfiles (Agent A writes Java, Agent B writes Python)

- Build context is **repo root** for every service; Dockerfile lives at
  `services/<name>/Dockerfile`. Compose/Helm build with
  `context: .`, `dockerfile: services/<name>/Dockerfile`.
- Java: multi-stage `maven:3.9-eclipse-temurin-21` (build both jars you own? no —
  one Dockerfile per service, building that service only) →
  `eclipse-temurin:21-jre`, download
  `https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v1.49.0/opentelemetry-javaagent.jar`,
  `ENV JAVA_TOOL_OPTIONS=-javaagent:/otel/opentelemetry-javaagent.jar`,
  `ENTRYPOINT java -jar /app/app.jar`. Non-root user. Expose 8080/50051.
- Python: `python:3.13-slim`, `COPY services/<name> /app/service`,
  `COPY gen/python /app/gen/python`, `WORKDIR /app/service`,
  `ENV PYTHONPATH=/app/gen/python`, pip install only what that service needs
  (grpcio, grpcio-health-checking, grpcio-reflection, fastapi, uvicorn,
  sqlalchemy, pydantic, opentelemetry-sdk, opentelemetry-exporter-otlp-proto-grpc,
  opentelemetry-instrumentation-fastapi, opentelemetry-instrumentation-grpc),
  `CMD ["python","-m","app.main"]`. Non-root user. Healthcheck uses Python urllib
  against `http://localhost:8080/healthz`.

### 3.7 Distributed tracing (all five services)

- OTLP/gRPC exporter → `OTEL_EXPORTER_OTLP_ENDPOINT` (Jaeger all-in-one accepts
  OTLP on 4317; compose/Helm set it; default `http://localhost:4317`).
- Never crash or block when Jaeger is absent (batch exporter, generous timeout).
- Spans must cover the saga: order-service HTTP → StartSaga → each orchestrator
  step (`RESERVE_INVENTORY` etc. as spans/attributes) → downstream gRPC calls,
  so a trace in Jaeger shows the full causal chain across all four languages
  boundaries. Propagate W3C trace context in gRPC metadata (OTel grpc
  instrumentation does this automatically).

### 3.8 Pact contract tests

- **Consumer side (Python)** lives in `contracts/` (Agent B owns the Python
  consumer): a small `checkout_client` package + pytest consumer test that
  defines pacts for `order-service` REST:
  - `POST /api/orders` → 201 order JSON; `POST` replay w/ same key → 200;
    `GET /api/orders/{orderId}` → 200; unknown order → 404; bad body → 400.
  - Pact written to `pacts/order_service-checkout_web.json`
    (consumer `checkout-web`, provider `order-service`).
  - Preferred: `pact-python` (already in `.venv`). If it fails to install/run on
    Python 3.13, fall back to a tiny spec-compliant pact JSON writer +
    contract assertions in `contracts/pact_writer.py` (documented in README).
- **Provider side (Java, Agent A)**: verify the generated pact file(s) in
  `pacts/` from `services/order-service` — preferred
  `au.com.dius.pact:pact-jvm-provider-junit5` programmatic verifier pointed at a
  `@SpringBootTest(webEnvironment=RANDOM_PORT)` instance, provider states served
  by a `@Profile("pact")` controller `POST /pact/state/{state}` that seeds H2.
  States needed: `an order that is pending`, `a confirmed order`,
  `an order that does not exist`, `invalid order payload`.
  If pact-jvm cannot run on this JVM, fall back to
  `contracts/verify_pacts.py` (python: replay each interaction against the
  running service and assert against the pact file) — wire it into the Makefile.
- Contract tests must fail when a response field is renamed/removed — that is the
  point. `make test` runs them.

## 4. E2E scenarios (`scripts/e2e.sh`, Agent C) — exact assertions

All against `docker compose up -d --build` (Jaeger at 16686, order REST at
localhost:8080). Use `curl -sf` + the repo `.venv` python for gRPC reads
(`scripts/get_stock.py <sku>` → prints `available reserved`).

1. **Happy path**: `Idempotency-Key: e2e-happy-1`; order `cus_ok`, `WIDGET-RED`
   ×2, `amountCents=2500`. Poll `GET /api/orders/{id}` every 1s until terminal
   (max 30s) → assert `status=CONFIRMED`. Assert stock `WIDGET-RED` available
   `10→8` (fetch before/after). Repeat the same POST (same key, same body) →
   assert **200** and the **same orderId**.
2. **Compensating release**: `Idempotency-Key: e2e-decline-1`; order `cus_decline`,
   `WIDGET-RED` ×1. Poll → assert `status=FAILED`,
   `failureReason=PAYMENT_DECLINED`. Assert stock available is back to **8**
   (inventory was reserved then released — the saga's compensation).
3. **No oversell / insufficient stock**: order `cus_ok`, `WIDGET-BLUE` ×999 →
   assert `status=FAILED`, `failureReason=INSUFFICIENT_STOCK`, and
   `WIDGET-BLUE` available unchanged at 3.
4. **Idempotency conflict**: same key as (1) but different body → assert HTTP 409.
5. **Tracing**: query Jaeger API `GET http://localhost:16686/api/traces?service=orchestrator&limit=1`
   (and `order-service`) → assert ≥1 trace containing a span for the saga
   (span `RESERVE_INVENTORY` or operation containing it) — proves OTel pipeline works.

Print PASS/FAIL per scenario; exit non-zero on any failure.

## 5. Quality gates (run these before you finish)

- Agent A: `cd services/order-service && mvn -q test` and
  `cd services/orchestrator && mvn -q test` — must pass. Both
  `mvn -q -DskipTests package` must produce runnable jars.
- Agent B: `cd services/<each> && ../../.venv/bin/python -m pytest` must pass
  (run from repo root: `.venv/bin/python -m pytest services/inventory services/payment services/notification`).
  Concurrency/idempotency tests listed in §3.4 are mandatory.
- Docker builds: `docker build -f services/<name>/Dockerfile .` must succeed.
- Do not commit secrets, `target/`, `__pycache__`, `.venv`, `data/*.db`.
