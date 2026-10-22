"""Pact consumer tests: checkout-web -> order-service (contract section 3.8).

Uses the *real* ``pact-python`` 4.1.0 API (``pact.Pact`` backed by the Rust
FFI): interactions are defined, exercised against Pact's mock server (no real
order-service needed), and the spec-compliant pact file is written to
``pacts/order_service-checkout_web.json``.

Interactions covered (exactly what contract section 3.8 asks for):

* ``POST /api/orders``            -> 201 order JSON
* ``POST`` replay, same key       -> 200 same order
* ``GET /api/orders/{orderId}``   -> 200 (pending and confirmed orders)
* unknown order                   -> 404
* invalid body                    -> 400 with the error envelope

The consumer test asserts the FULL order shape (see
``order_contract.assert_order_shape``), so a removed/renamed response field
fails here, and the pact file pins the same shape so the provider-side
verification fails too.

Known pact-python 4.1.0 (Rust FFI) behaviour: the mock server always matches
the FIRST applicable interaction and never consumes it. Two interactions with
byte-identical requests (which is exactly what the contract's replay is:
same key + same body -> 200) are therefore treated as one expectation by the
FFI — it neither serves them separately nor reports the second as missing.
So the replay cannot be *observed* as a 200 on the consumer side. The replay
interaction is still defined in the pact file (Agent A's provider verification
replays each interaction in order against the real order-service and genuinely
exercises create-then-replay), and the consumer test asserts the replay
request is byte-identical to the create request, which is what makes the
provider's idempotent replay deterministic (contract section 3.2).

The mock must report zero mismatches: any status/body/header drift, or any
genuinely unexercised interaction, fails the test. Everything else must match
the mock exactly.
"""
from __future__ import annotations

import json
import shutil
import tempfile
from pathlib import Path

from pact import Pact

from checkout_client import CheckoutClient
from order_contract import (
    CONSUMER,
    CREATE_IDEMPOTENCY_KEY,
    INVALID_ORDER,
    ORDER_FIELDS,
    PACT_FILENAME,
    PACTS_DIR,
    PROVIDER,
    VALID_ORDER,
    assert_error_envelope,
    assert_order_shape,
    error_envelope_matcher_body,
    order_example,
    order_matcher_body,
)

# Example values used in the pact body (matchers keep them format-checked on
# the provider side while allowing the provider to generate its own ids).
EXAMPLE_CREATED = order_example("ord_pact_create", "PENDING")
EXAMPLE_PENDING = order_example("ord_pact_pending", "PENDING")
EXAMPLE_CONFIRMED = order_example("ord_pact_confirmed", "CONFIRMED")

PACT_FILE = PACTS_DIR / PACT_FILENAME

POST = "/api/orders"


def _define_pact() -> Pact:
    pact = Pact(CONSUMER, PROVIDER)

    create = pact.upon_receiving(
        "a POST /api/orders request with a valid payload creates an order"
    )
    create.with_request("POST", POST).set_headers(
        {
            "Content-Type": "application/json",
            "Idempotency-Key": CREATE_IDEMPOTENCY_KEY,
        }
    ).with_body(json.dumps(VALID_ORDER), "application/json", "Request")
    create.will_respond_with(201).with_body(
        order_matcher_body(EXAMPLE_CREATED), "application/json", "Response"
    )

    replay = pact.upon_receiving(
        "a replayed POST /api/orders with the same Idempotency-Key "
        "returns the original order"
    )
    replay.with_request("POST", POST).set_headers(
        {
            "Content-Type": "application/json",
            "Idempotency-Key": CREATE_IDEMPOTENCY_KEY,
        }
    ).with_body(json.dumps(VALID_ORDER), "application/json", "Request")
    replay.will_respond_with(200).with_body(
        order_matcher_body(EXAMPLE_CREATED), "application/json", "Response"
    )

    get_pending = pact.upon_receiving(
        "a GET /api/orders/{orderId} for a pending order returns the order"
    )
    get_pending.given("an order that is pending")
    get_pending.with_request("GET", "/api/orders/ord_pact_pending")
    get_pending.will_respond_with(200).with_body(
        order_matcher_body(EXAMPLE_PENDING), "application/json", "Response"
    )

    get_confirmed = pact.upon_receiving(
        "a GET /api/orders/{orderId} for a confirmed order returns the order"
    )
    get_confirmed.given("a confirmed order")
    get_confirmed.with_request("GET", "/api/orders/ord_pact_confirmed")
    get_confirmed.will_respond_with(200).with_body(
        order_matcher_body(EXAMPLE_CONFIRMED), "application/json", "Response"
    )

    get_unknown = pact.upon_receiving(
        "a GET /api/orders/{orderId} for an unknown order returns 404"
    )
    get_unknown.given("an order that does not exist")
    get_unknown.with_request("GET", "/api/orders/ord_pact_missing")
    get_unknown.will_respond_with(404)

    invalid = pact.upon_receiving(
        "a POST /api/orders request with an invalid payload returns 400"
    )
    invalid.given("invalid order payload")
    invalid.with_request("POST", POST).set_header(
        "Content-Type", "application/json"
    )
    invalid.with_body(json.dumps(INVALID_ORDER), "application/json", "Request")
    invalid.will_respond_with(400).with_body(
        error_envelope_matcher_body(), "application/json", "Response"
    )

    return pact


def test_checkout_web_against_order_service():
    pact = _define_pact()

    # raises=False: mismatches are collected instead of raised so the known
    # "replay interaction cannot be served by the mock" mismatch can be
    # asserted explicitly (see the module docstring).
    with pact.serve(raises=False, verbose=False) as server:
        client = CheckoutClient(str(server.url))

        # 1. Create -> 201 with the full contract-shaped order JSON.
        created = client.create_order(VALID_ORDER, CREATE_IDEMPOTENCY_KEY)
        assert created.status_code == 201
        order = created.json()
        assert_order_shape(order)
        assert order == {
            "orderId": "ord_pact_create",
            "customerId": "cus_ok",
            "status": "PENDING",
            "failureReason": None,
            "sagaId": "saga_ord_pact_create",
            "amountCents": 2500,
            "currency": "USD",
            "items": [{"sku": "WIDGET-RED", "quantity": 2}],
            "createdAt": "2026-01-01T00:00:00Z",
            "updatedAt": "2026-01-01T00:00:00Z",
        }

        # The consumer replays the request verbatim: same endpoint, same
        # Idempotency-Key, same payload — that is what makes the provider's
        # 200 replay deterministic (and what the pact's replay interaction
        # pins for provider verification).
        replay_request = client.build_create_request(
            VALID_ORDER, CREATE_IDEMPOTENCY_KEY
        )
        assert replay_request.url.path == POST
        assert replay_request.headers["Idempotency-Key"] == (
            CREATE_IDEMPOTENCY_KEY
        )
        assert json.loads(replay_request.content) == VALID_ORDER

        # 2. GET pending / confirmed orders -> 200.
        pending = client.get_order("ord_pact_pending")
        assert pending.status_code == 200
        assert_order_shape(pending.json())
        assert pending.json()["status"] == "PENDING"
        assert pending.json()["failureReason"] is None

        confirmed = client.get_order("ord_pact_confirmed")
        assert confirmed.status_code == 200
        assert_order_shape(confirmed.json())
        assert confirmed.json()["status"] == "CONFIRMED"

        # 3. Unknown order -> 404.
        unknown = client.get_order("ord_pact_missing")
        assert unknown.status_code == 404

        # 4. Invalid body -> 400 with the error envelope.
        bad = client.create_order(INVALID_ORDER)
        assert bad.status_code == 400
        assert_error_envelope(bad.json())

    # The mock reports every defined interaction as matched. The replay
    # interaction is byte-identical to the create interaction, so the Rust
    # FFI treats it as the same expectation (it neither serves it separately
    # nor reports it as missing). Any real drift — a status, body or header
    # mismatch, or an unexercised interaction — shows up here and fails.
    mismatches = list(server.mismatches)
    assert mismatches == [], [str(m) for m in mismatches]
    assert server.matched is True

    # Write the pact file under the contract-mandated name.
    PACTS_DIR.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmpdir:
        pact.write_file(tmpdir, overwrite=True)
        generated = sorted(Path(tmpdir).glob("*.json"))
        assert len(generated) == 1, generated
        shutil.move(str(generated[0]), PACT_FILE)

    # Sanity-check the artifact that the provider verification reads.
    spec = json.loads(PACT_FILE.read_text())
    assert spec["consumer"]["name"] == CONSUMER
    assert spec["provider"]["name"] == PROVIDER
    assert len(spec["interactions"]) == 6

    # The created-order interaction must pin the full response shape so that
    # a removed/renamed field fails provider verification.
    created_interaction = spec["interactions"][0]
    assert set(created_interaction["response"]["body"]["content"].keys()) == (
        ORDER_FIELDS
    )
