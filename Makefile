# Common tasks. Run `make help` for the list.
SHELL := /bin/bash
MVN ?= mvn
SERVICES := gateway-service identity-service vendor-service catalog-service inventory-service order-service \
            payment-service notification-service review-service analytics-service chat-service

.PHONY: help env up down reset logs ps seed e2e build test test-unit test-it frontend-check frontend-e2e \
        images dashboards load-smoke load-checkout load-chat clean

help: ## Show this help
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-16s %s\n", $$1, $$2}'

env: ## Create .env from .env.example with random secrets (does not overwrite)
	@test -f .env && echo ".env already exists" || ./scripts/init-env.sh

up: ## Build and start the whole stack in the background
	docker compose up --build -d

down: ## Stop the stack, keep volumes (data survives)
	docker compose down

reset: ## Stop the stack and DELETE all volumes (databases, Kafka log, Grafana)
	docker compose down -v

logs: ## Follow logs of all services (or SERVICE=order-service)
	docker compose logs -f $(SERVICE)

ps: ## Show container status and health
	docker compose ps

seed: ## Create demo sellers, products and customers through the public API
	node scripts/seed-demo.mjs

e2e: ## API end-to-end checks against the running stack, chat included (after make seed)
	node scripts/e2e-checkout.mjs
	MF_WS=ws://127.0.0.1:$${FRONTEND_HOST_PORT:-3000}/ws/chat node scripts/e2e-chat.mjs

build: ## Compile and package every backend module (no tests)
	$(MVN) -B -DskipTests clean package

test-unit: ## Backend unit tests (no Docker needed)
	$(MVN) -B test

test-it: ## Backend unit + integration tests (Testcontainers: needs Docker)
	$(MVN) -B verify

test: test-it frontend-check ## All backend and frontend tests

frontend-check: ## Frontend lint, typecheck, unit tests and production build
	cd frontend && npm ci && npm run lint && npm run typecheck && npm test && npm run build

frontend-e2e: ## Browser tests against the running stack (needs seeded data; E2E_ADMIN_PASSWORD optional)
	cd frontend && npx playwright test

images: ## Build all Docker images without starting them
	docker compose build

dashboards: ## Regenerate Grafana dashboards JSON
	python3 infrastructure/grafana/generate_dashboards.py

load-smoke: ## Short k6 smoke test of the read path
	k6 run load-tests/k6/browse.js -e VUS=5 -e DURATION=30s

load-checkout: ## k6 checkout load test (creates real orders in the target stack)
	k6 run load-tests/k6/checkout.js

load-chat: ## k6 chat load test over WebSockets (run load-tests/prepare-chat.mjs first; PAIRS=100)
	k6 run -e PAIRS=$${PAIRS:-100} -e DURATION=2m load-tests/k6/chat.js

clean: ## Remove build output
	$(MVN) -q clean; rm -rf frontend/dist
