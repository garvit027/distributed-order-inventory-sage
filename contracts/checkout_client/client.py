"""A minimal checkout-web client for the order-service REST edge API.

The REST edge contract lives in docs/CONTRACT.md section 3.2 (order-service
is the only public REST surface in the system). The client deliberately keeps
responses as ``httpx.Response`` so consumer tests can assert the full
contractual shape.
"""
from __future__ import annotations

from typing import Any

import httpx

DEFAULT_TIMEOUT_SECONDS = 10.0


class CheckoutClient:
    """Consumer-side client used by the checkout-web application."""

    def __init__(
        self, base_url: str, timeout: float = DEFAULT_TIMEOUT_SECONDS
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def build_create_request(
        self, order: dict[str, Any], idempotency_key: str | None = None
    ) -> httpx.Request:
        """Build the POST /api/orders request (contract section 3.2).

        Exposed so tests can assert the exact wire request (including that
        idempotent replays are sent verbatim).
        """
        headers: dict[str, str] = {}
        if idempotency_key is not None:
            headers["Idempotency-Key"] = idempotency_key
        return httpx.Request(
            "POST",
            f"{self._base_url}/api/orders",
            json=order,
            headers=headers,
        )

    def create_order(
        self, order: dict[str, Any], idempotency_key: str | None = None
    ) -> httpx.Response:
        """POST /api/orders (contract section 3.2).

        With an Idempotency-Key the provider replays the original order with
        200 instead of creating a second one (201 on first creation).
        """
        with httpx.Client(timeout=self._timeout) as http:
            return http.send(self.build_create_request(order, idempotency_key))

    def get_order(self, order_id: str) -> httpx.Response:
        """GET /api/orders/{orderId} -> 200 order JSON, 404 when unknown."""
        return httpx.get(
            f"{self._base_url}/api/orders/{order_id}", timeout=self._timeout
        )

    def get_orders(self) -> httpx.Response:
        """GET /api/orders -> {"orders": [...]}."""
        return httpx.get(f"{self._base_url}/api/orders", timeout=self._timeout)
