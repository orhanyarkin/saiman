# Saiman root Makefile. See CLAUDE.md, ADR-0006, ADR-0007, ADR-0008, ADR-0009.
# Add eval/cost-report/capture-demo targets together with the thing they run.

COMPOSE := docker compose -f deploy/compose/docker-compose.yml

# Default target for the x402 console buyer's `buy`/`replay`/`testnet-check` commands:
# seller-api's paid disclosure summary endpoint (M1).
X402_URL ?= http://localhost:8081/v1/disclosures/THYAO/summary

# The seller's payout address is public, so `make` reads it from the repo-root .env when it
# isn't exported (compose itself doesn't read that file). Only this one variable is read, via
# an allowlist in scripts/read-public-env.sh; secrets in .env never reach make (ADR-0009).
ENV_FILE ?= .env
ifeq ($(origin X402_SELLER_PAYTO_ADDRESS),undefined)
X402_SELLER_PAYTO_ADDRESS := $(shell scripts/read-public-env.sh X402_SELLER_PAYTO_ADDRESS $(ENV_FILE))
endif
export X402_SELLER_PAYTO_ADDRESS

.DEFAULT_GOAL := help

.PHONY: help images check-x402-env infra-up up down clean ps logs test lint format web-dev verify-trace \
	x402-publish-local x402-sample x402-new-wallet x402-buy x402-replay x402-testnet-check \
	secrets-check secrets-from-dotenv ingest-backfill ingest-status ingest-retry-dlq rag-ask

help: ## Show this help.
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

web/node_modules: web/pnpm-lock.yaml
	pnpm --dir web install --frozen-lockfile
	touch $@

images: ## Build all service images for the host architecture (./gradlew bootBuildImage).
	./gradlew bootBuildImage

check-x402-env: ## Verify X402_SELLER_PAYTO_ADDRESS is a valid address (required by `make up`, not `infra-up`).
	scripts/check-x402-env.sh

infra-up: ## Start postgres, redpanda, valkey, otel-collector, jaeger and wait for health.
	$(COMPOSE) up -d --wait

up: ## Verify payTo, create empty secret files if missing, build images, start the full stack and wait for app health.
	scripts/check-x402-env.sh
	scripts/ensure-secret-files.sh
	$(MAKE) images
	$(COMPOSE) --profile apps up -d
	scripts/wait-for-health.sh 8080 8081 8082 8083

down: ## Stop and remove all containers (volumes kept).
	$(COMPOSE) --profile apps down

clean: ## Stop everything and drop volumes (postgres/redpanda/valkey data).
	$(COMPOSE) --profile apps down -v

ps: ## List container status.
	$(COMPOSE) --profile apps ps

logs: ## Follow container logs.
	$(COMPOSE) --profile apps logs -f

test: web/node_modules ## Run backend + web tests, then build the x402 sample against the mavenLocal starter.
	./gradlew check
	$(MAKE) x402-sample
	pnpm --dir web test

lint: web/node_modules ## Run backend + web linters (no formatting changes).
	./gradlew spotlessCheck
	./gradlew -p libs/x402-spring-boot-starter/samples/console-buyer spotlessCheck
	pnpm --dir web lint
	pnpm --dir web typecheck

format: web/node_modules ## Apply backend + web formatting.
	./gradlew spotlessApply
	pnpm --dir web exec prettier --write .

web-dev: web/node_modules ## Run the Vite dev server against a running `make up` stack.
	pnpm --dir web dev

verify-trace: ## Verify a trace in Jaeger. Usage: make verify-trace TRACE_ID=<id> (or omit to self-generate one).
	scripts/verify-trace.sh $${TRACE_ID:+"$$TRACE_ID"}

# x402 console buyer sample (libs/x402-spring-boot-starter/samples/console-buyer): a
# standalone Gradle build that resolves the starter from mavenLocal(), run with the
# root wrapper (docs/design/m1-x402.md "Console buyer and make targets").
#
# Key source for buy/replay/testnet-check/new-wallet: export X402_BUYER_PRIVATE_KEY in
# the shell, or let the sample fall back to secrets/buyer.key. Never pass the key as a
# make variable (e.g. `make x402-buy X402_BUYER_PRIVATE_KEY=...`) — make variables end
# up in MAKEFLAGS and can leak into child processes' environments and shell history.

x402-publish-local: ## Publish the x402 starter to the local Maven repository (~/.m2).
	./gradlew :libs:x402-spring-boot-starter:publishToMavenLocal

x402-sample: x402-publish-local ## Build the console-buyer sample against the mavenLocal starter.
	./gradlew -p libs/x402-spring-boot-starter/samples/console-buyer build

x402-new-wallet: x402-publish-local ## Generate a throwaway testnet buyer wallet (writes secrets/buyer.key, prints only the address).
	./gradlew -p libs/x402-spring-boot-starter/samples/console-buyer bootRun --args="new-wallet"

x402-buy: x402-publish-local ## Pay for X402_URL with the console buyer (default: seller-api's disclosure summary).
	./gradlew -p libs/x402-spring-boot-starter/samples/console-buyer bootRun --args="buy --url=$(X402_URL)"

x402-replay: x402-publish-local ## Replay the last stored payment against X402_URL; succeeds only if the server answers 402.
	./gradlew -p libs/x402-spring-boot-starter/samples/console-buyer bootRun --args="replay --url=$(X402_URL)"

x402-testnet-check: x402-publish-local ## Read-only /verify call against x402.org (never calls /settle). Local only, not run in CI.
	./gradlew -p libs/x402-spring-boot-starter/samples/console-buyer bootRun --args="testnet-check"

# M2 RAG credentials and operations (docs/design/m2-rag.md, ADR-0009 amendment, ADR-0010,
# ADR-0012). Secrets are files under the ignored secrets/ dir; nothing here prints one.

INGEST_URL ?= http://127.0.0.1:8083
RAG_URL ?= http://localhost:8081/v1/disclosures/THYAO/questions
RAG_QUESTION ?= THYAO 2023 yılında hangi önemli özel durum açıklamalarını yaptı?
export RAG_QUESTION

secrets-check: ## Report present/empty/absent + file mode of the credential files (never contents).
	scripts/secrets-check.sh

secrets-from-dotenv: ## HUMAN ONLY: copy OPENAI_API_KEY from .env into secrets/openai_api_key (FORCE=1 to overwrite).
	scripts/secrets-from-dotenv.sh

ingest-backfill: ## Run ingest locally in backfill mode against `make infra-up` (needs secrets/mkk_credentials + openai_api_key).
	@bash -c '(exec 3<>/dev/tcp/127.0.0.1/5432)' 2>/dev/null || { echo "Postgres is not reachable on 127.0.0.1:5432; run 'make infra-up' first." >&2; exit 1; }
	SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/saiman SPRING_DATASOURCE_USERNAME=saiman SPRING_DATASOURCE_PASSWORD=saiman SPRING_DATA_REDIS_URL=redis://localhost:$${VALKEY_HOST_PORT:-16380} ./gradlew :services:ingest:bootRun --args="--saiman.ingest.backfill.enabled=true --saiman.secrets-dir=$(CURDIR)/secrets/"

ingest-status: ## Show per-ticker ingest status from the running ingest container (loopback only).
	@out=$$(curl -sf $(INGEST_URL)/internal/v1/tickers) || { echo "ingest not reachable on $(INGEST_URL) (is 'make up' running?)" >&2; exit 1; }; \
	if command -v jq >/dev/null 2>&1; then printf '%s\n' "$$out" | jq .; else printf '%s\n' "$$out"; fi

ingest-retry-dlq: ## Re-drive dead-lettered ingest documents (POST /internal/v1/admin/retry-dlq).
	curl -sf -X POST $(INGEST_URL)/internal/v1/admin/retry-dlq

rag-ask: x402-publish-local ## PAY for a RAG question (test USDC). Override RAG_URL / RAG_QUESTION. Needs the console buyer's --method/--json-file flags (seller-api task).
	@command -v jq >/dev/null 2>&1 || { echo "rag-ask needs jq to build the request body; install jq." >&2; exit 1; }
	mkdir -p $(CURDIR)/build
	jq -n --arg q "$$RAG_QUESTION" '{question:$$q}' > $(CURDIR)/build/rag-question.json
	./gradlew -p libs/x402-spring-boot-starter/samples/console-buyer bootRun --args="buy --url=$(RAG_URL) --method=POST --json-file=$(CURDIR)/build/rag-question.json"
