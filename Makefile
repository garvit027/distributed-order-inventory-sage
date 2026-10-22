# Makefile — build/test/run entry points for the order & inventory system.
# See docs/CONTRACT.md and README.md. Default target: `test`.

PY := .venv/bin/python
PIP := .venv/bin/pip

.DEFAULT_GOAL := test

.PHONY: venv protos test-python test-java test up down e2e helm-lint clean

## venv: create the shared Python venv and install requirements.txt
venv:
	python3 -m venv .venv
	.venv/bin/pip install -r requirements.txt

## protos: regenerate the Python gRPC stubs in gen/python
protos:
	scripts/gen-python-proto.sh

## test-python: pytest for the FastAPI services and the Pact consumer contracts
test-python:
	$(PY) -m pytest services contracts -q

## test-java: Maven unit tests for both Spring Boot services
test-java:
	cd services/order-service && mvn -q test
	cd services/orchestrator && mvn -q test

## test: everything (default)
test: test-python test-java

## up: build images and start the full docker compose stack
up:
	docker compose up -d --build

## down: stop the stack (add -v in docker compose to also drop volumes)
down:
	docker compose down

## e2e: run the five contract end-to-end scenarios against the running stack
e2e:
	bash scripts/e2e.sh

## helm-lint: validate the Helm chart (lint + render)
helm-lint:
	helm lint deploy/helm
	helm template test deploy/helm > /dev/null

## clean: remove build/test artifacts (keeps .venv, pacts/ and runtime volumes)
clean:
	find . -type d -name __pycache__ -prune -exec rm -rf {} +
	rm -rf .pytest_cache reports
	rm -rf services/order-service/target services/orchestrator/target
