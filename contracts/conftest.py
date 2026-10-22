"""pytest configuration for the contract consumer tests.

Adds ``contracts/`` to ``sys.path`` so ``checkout_client`` and
``order_contract`` import cleanly regardless of the invocation directory.
"""
from __future__ import annotations

import pathlib
import sys

CONTRACTS_DIR = pathlib.Path(__file__).resolve().parent

if str(CONTRACTS_DIR) not in sys.path:
    sys.path.insert(0, str(CONTRACTS_DIR))
