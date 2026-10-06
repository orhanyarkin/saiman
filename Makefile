# Saiman root Makefile. See CLAUDE.md, ADR-0006, ADR-0007, ADR-0008, ADR-0009.
# Add cost-report targets together with the thing they run.

COMPOSE := docker compose -f deploy/compose/docker-compose.yml

# Default target for the x402 console buyer's `buy`/`replay`/`testnet-check` commands:
# seller-api's paid disclosure summary endpoint (M1).
X402_URL ?= http://localhost:8081/v1/disclosures/THYAO/summary
# Payment flow probed by `make x402-testnet-check` (ADR-0021).
X402_FLOW ?= authorization

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
	secrets-check secrets-from-dotenv ingest-backfill ingest-status ingest-retry-dlq rag-ask \
	research-run research-approve research-status web-build e2e e2e-live lighthouse gen-api \
	shellcheck recon-run recon-report ledger-balance ledger-tamper-demo db-roles db-migrate psql auth-tokens auth-token-copy auth-token-show eval capture-demo capture-demo-selftest

help: ## Show this help.
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'

web/node_modules: web/pnpm-lock.yaml
	pnpm --dir web install --frozen-lockfile
	touch $@

images: ## Build all service images for the host architecture (./gradlew bootBuildImage).
	./gradlew bootBuildImage

SHELLCHECK_IMAGE ?= koalaman/shellcheck@sha256:2097951f02e735b613f4a34de20c40f937a6c8f18ecb170612c88c34517221fb

shellcheck: ## ShellCheck every tracked shell script (warning and above) with the pinned official image; same check as CI.
	git ls-files '*.sh' | xargs docker run --rm -v "$$PWD":/mnt -w /mnt $(SHELLCHECK_IMAGE) -x -S warning

check-x402-env: ## Verify X402_SELLER_PAYTO_ADDRESS is a valid address (required by `make up`, not `infra-up`).
	scripts/check-x402-env.sh

# `docker compose up --wait` reports the one-shot db-init (exit 0) as an error on Compose v5, so the
# long-running infra services are listed explicitly and db-init runs afterwards (idempotent).
infra-up: ## Start postgres, kafka, redis, otel-collector, jaeger, wait for health, then create the per-service DB roles.
	scripts/ensure-secret-files.sh
	$(COMPOSE) up -d --wait postgres kafka redis otel-collector jaeger
	$(COMPOSE) run --rm db-init

db-roles: ## (Re)run the idempotent per-service Postgres role bootstrap (db-init); applies rotated pg_* secrets (ADR-0024).
	scripts/ensure-secret-files.sh
	$(COMPOSE) up -d --wait postgres
	$(COMPOSE) run --rm db-init

# Per-service Flyway one-shots (ADR-0027). `make up` runs them through depends_on
# (service_completed_successfully); this target runs them by hand against a running postgres,
# e.g. to migrate before starting a service from the IDE. Needs the images (`make images`).
MIGRATE_SERVICES := orchestrator seller-api ledger ingest

db-migrate: ## Run the four <svc>-migrate one-shots (Flyway as <schema>_owner, ADR-0027); needs built images (make images).
	scripts/ensure-secret-files.sh
	$(COMPOSE) up -d --wait postgres
	$(COMPOSE) run --rm db-init
	@set -e; for svc in $(MIGRATE_SERVICES); do \
	  echo "db-migrate: $$svc-migrate"; \
	  $(COMPOSE) run --rm --no-deps $$svc-migrate; \
	done

.PHONY: corpus-export test-bootstrap-roles
CORPUS_DIR ?= build/corpus

corpus-export: ## Export the ingest corpus (data-only dump + JSON metadata) from the local postgres to CORPUS_DIR (default build/corpus/) (ADR-0028).
	scripts/ensure-secret-files.sh
	$(COMPOSE) up -d --wait postgres
	mkdir -p $(CORPUS_DIR)
	$(COMPOSE) run --rm --no-deps --user "$$(id -u):$$(id -g)" -v "$$PWD/$(CORPUS_DIR):/out" \
		-e PGPASSWORD_FILE=/run/secrets/pg_superuser_password --entrypoint bash db-init /bootstrap/corpus-export.sh /out

test-bootstrap-roles: ## Self-test of the Postgres role bootstrap (superuser + RDS-style) and corpus export/restore in throwaway containers.
	scripts/test-bootstrap-roles.sh

psql: ## Open a psql shell as the superuser inside the postgres container (unix socket, no password in argv or env).
	$(COMPOSE) exec postgres psql -U saiman -d saiman

auth-tokens: ## Show where the API token files are (never prints a token) and how to use one (ADR-0023).
	@scripts/auth-tokens.sh

auth-token-copy: ## Copy a human API token to the clipboard. Usage: make auth-token-copy ROLE=reader|operator
	@scripts/auth-tokens.sh copy "$(ROLE)"

auth-token-show: ## Print a human API token to stdout (explicit request). Usage: make auth-token-show ROLE=reader|operator
	@scripts/auth-tokens.sh show "$(ROLE)"

up: ## Verify payTo, create secret files (generates DB passwords and API tokens), build images and the dashboard, start the full stack, wait for app health.
	scripts/check-x402-env.sh
	scripts/ensure-secret-files.sh
	@[ "$$(stat -c %a secrets)" = "700" ] || { echo "up: secrets/ must be mode 700 (it is what protects the 0644 secret files inside); run: chmod 700 secrets" >&2; exit 1; }
	$(MAKE) images
	$(MAKE) web-build
	@# No --wait: Compose fails it on exit-0 one-shots. The <svc>-migrate one-shots run through depends_on
	@# (service_completed_successfully), so `up -d` itself exits non-zero if a migration fails (ADR-0027).
	scripts/with-auth-digests.sh $(COMPOSE) --profile apps up -d
	@if [ -s secrets/x402_buyer_private_key ]; then \
	  scripts/wait-for-health.sh 8080 8081 8082 8083; \
	else \
	  echo "up: secrets/x402_buyer_private_key is empty: the orchestrator fails closed at startup (blank x402.client.private-key, ADR-0008) and stays down until you fill it; waiting for the other apps only." >&2; \
	  scripts/wait-for-health.sh 8081 8082 8083; \
	fi
	@echo "Dashboard: http://localhost:8088 (needs an API token: make auth-tokens)"

down: ## Stop and remove all containers (volumes kept).
	$(COMPOSE) --profile apps --profile evals down

clean: ## Stop everything and drop volumes (postgres/kafka/redis data).
	$(COMPOSE) --profile apps --profile evals down -v

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
	pnpm --dir web gen:api --check

format: web/node_modules ## Apply backend + web formatting.
	./gradlew spotlessApply
	pnpm --dir web exec prettier --write .

web-dev: web/node_modules ## Run the Vite dev server against a running `make up` stack.
	pnpm --dir web dev

# Dashboard (M5, ADR-0022): `make up` serves web/dist through the compose `web` service (nginx) on
# http://localhost:8088. Playwright's Chromium is installed once by the human
# (`pnpm --dir web exec playwright install chromium`); CI installs it itself.

web-build: web/node_modules ## Build the dashboard into web/dist for the compose `web` service (browser tracing on, same-origin /otlp).
	VITE_OTEL_ENABLED=true VITE_OTEL_TRACES_URL=/otlp/v1/traces pnpm --dir web build

e2e: web/node_modules ## Playwright e2e against the fixture server (no stack needed; needs Playwright's Chromium).
	pnpm --dir web e2e

e2e-live: web/node_modules ## Playwright e2e against the running stack at http://localhost:8088 (needs `make up`).
	SAIMAN_E2E_TOKEN_FILE=secrets/api_operator_token SAIMAN_E2E_BASE_URL=http://localhost:8088 pnpm --dir web e2e:live

lighthouse: web/node_modules ## Lighthouse accessibility audit of the dashboard routes (needs Chromium).
	pnpm --dir web lighthouse

gen-api: web/node_modules ## Regenerate the typed API clients from docs/api/*.openapi.json.
	pnpm --dir web gen:api

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

x402-testnet-check: x402-publish-local ## Read-only /verify call against x402.org (never calls /settle); X402_FLOW=authorization|upfront. Local only, not run in CI.
	./gradlew -p libs/x402-spring-boot-starter/samples/console-buyer bootRun --args="testnet-check --flow=$(X402_FLOW)"

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

ingest-backfill: ## Run ingest locally in backfill mode against `make infra-up` (needs secrets/mkk_credentials + openai_api_key; connects as ingest_app, migrates as ingest_owner).
	@bash -c '(exec 3<>/dev/tcp/127.0.0.1/5432)' 2>/dev/null || { echo "Postgres is not reachable on 127.0.0.1:5432; run 'make infra-up' first." >&2; exit 1; }
	scripts/prepare-ingest-secrets.sh $(CURDIR)/secrets $(CURDIR)/build/ingest-secrets
	set +e; SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/saiman SPRING_DATASOURCE_USERNAME=ingest_app SPRING_FLYWAY_USER=ingest_owner SPRING_DATA_REDIS_URL=redis://localhost:$${REDIS_HOST_PORT:-16380} ./gradlew :services:ingest:bootRun --args="--saiman.ingest.backfill.enabled=true --saiman.secrets-dir=$(CURDIR)/build/ingest-secrets/"; rc=$$?; rm -rf $(CURDIR)/build/ingest-secrets; exit $$rc

ingest-status: ## Show per-ticker ingest status from the running ingest container (loopback only).
	@out=$$(curl -sf $(INGEST_URL)/internal/v1/tickers) || { echo "ingest not reachable on $(INGEST_URL) (is 'make up' running?)" >&2; exit 1; }; \
	if command -v jq >/dev/null 2>&1; then printf '%s\n' "$$out" | jq .; else printf '%s\n' "$$out"; fi

ingest-retry-dlq: ## Re-drive dead-lettered ingest documents (POST /internal/v1/admin/retry-dlq).
	curl -sf -X POST -H 'X-Saiman-Internal: 1' $(INGEST_URL)/internal/v1/admin/retry-dlq

rag-ask: x402-publish-local ## PAY for a RAG question (test USDC). Override RAG_URL / RAG_QUESTION. Needs the console buyer's --method/--json-file flags (seller-api task).
	@command -v jq >/dev/null 2>&1 || { echo "rag-ask needs jq to build the request body; install jq." >&2; exit 1; }
	mkdir -p $(CURDIR)/libs/x402-spring-boot-starter/samples/console-buyer/build
	jq -n --arg q "$$RAG_QUESTION" '{question:$$q}' > $(CURDIR)/libs/x402-spring-boot-starter/samples/console-buyer/build/rag-question.json
	./gradlew -p libs/x402-spring-boot-starter/samples/console-buyer bootRun --args="buy --url=$(RAG_URL) --method=POST --json-file=$(CURDIR)/libs/x402-spring-boot-starter/samples/console-buyer/build/rag-question.json"

# M3 orchestrator runs (docs/design/m3-orchestrator.md "Orchestrator HTTP"); since M6 every call carries a
# bearer token from secrets/api_{reader,operator}_token via scripts/curl-auth.sh (ADR-0023). These spend test
# USDC through the orchestrator's own wallet; the spend limits live in the orchestrator, not
# here. Needs `make up` with a filled secrets/x402_buyer_private_key and secrets/openai_api_key.

ORCH_URL ?= http://localhost:8080
RUN_QUESTION ?=
RUN_BUDGET_ATOMIC ?=
RUN_ID ?=
APPROVAL_ID ?=
DECISION ?=
export RUN_QUESTION RUN_BUDGET_ATOMIC DECISION

research-run: ## Start a research run and stream its events. Usage: make research-run RUN_QUESTION='...' [RUN_BUDGET_ATOMIC=50000]
	@command -v jq >/dev/null 2>&1 || { echo "research-run needs jq to build the request body; install jq." >&2; exit 1; }
	@[ -n "$$RUN_QUESTION" ] || { echo "Set RUN_QUESTION (3..500 chars), e.g. make research-run RUN_QUESTION='THYAO 2023 ozel durum aciklamalari neler?'" >&2; exit 1; }
	@resp=$$(jq -n --arg q "$$RUN_QUESTION" --arg b "$$RUN_BUDGET_ATOMIC" 'if $$b == "" then {question:$$q} else {question:$$q, budgetAtomic:($$b|tonumber)} end' \
		| scripts/curl-auth.sh operator -sS --fail-with-body -X POST -H 'Content-Type: application/json' -H 'X-Saiman-Csrf: 1' --data @- $(ORCH_URL)/api/v1/runs) || { echo "$$resp" >&2; exit 1; }; \
	printf '%s\n' "$$resp" | jq .; \
	url=$$(printf '%s' "$$resp" | jq -r '.eventsUrl'); \
	case "$$url" in /[!/]*) url="$(ORCH_URL)$$url";; *) echo "research-run: refusing eventsUrl that is not a path on $(ORCH_URL) (the token is never sent elsewhere)" >&2; exit 1;; esac; \
	echo "--- streaming $$url (ends with the run's terminal event; approve with make research-approve) ---"; \
	scripts/curl-auth.sh reader -sS -N -H 'Accept: text/event-stream' "$$url"

research-approve: ## Decide a pending approval. Usage: make research-approve RUN_ID=<id> APPROVAL_ID=<id> DECISION=APPROVE|REJECT
	@command -v jq >/dev/null 2>&1 || { echo "research-approve needs jq to build the request body; install jq." >&2; exit 1; }
	@[ -n "$(RUN_ID)" ] && [ -n "$(APPROVAL_ID)" ] || { echo "Set RUN_ID and APPROVAL_ID." >&2; exit 1; }
	@case "$$DECISION" in APPROVE|REJECT) ;; *) echo "Set DECISION=APPROVE or DECISION=REJECT." >&2; exit 1;; esac
	@jq -n --arg d "$$DECISION" '{decision:$$d}' \
		| scripts/curl-auth.sh operator -sS --fail-with-body -X POST -H 'Content-Type: application/json' -H 'X-Saiman-Csrf: 1' --data @- $(ORCH_URL)/api/v1/runs/$(RUN_ID)/approvals/$(APPROVAL_ID) \
		| jq .

research-status: ## Show a run's summary (status, cost). Usage: make research-status RUN_ID=<id>
	@command -v jq >/dev/null 2>&1 || { echo "research-status needs jq; install jq." >&2; exit 1; }
	@[ -n "$(RUN_ID)" ] || { echo "Set RUN_ID." >&2; exit 1; }
	@scripts/curl-auth.sh reader -sS --fail-with-body $(ORCH_URL)/api/v1/runs/$(RUN_ID) | jq .

# M4 ledger and reconciliation (docs/design/m4-ledger.md "Ledger HTTP", ADR-0016, ADR-0018).
# The ledger API is guarded like the orchestrator's (Host allowlist, JSON + X-Saiman-Csrf), bound to
# loopback and, since M6, needs a bearer token (ADR-0023): the calls go through scripts/curl-auth.sh,
# which sends it from a 0600 temporary header file (never argv). Needs `make up`.

LEDGER_URL ?= http://localhost:8082

recon-run: ## Trigger a reconciliation run on the ledger and print its runId.
	@command -v jq >/dev/null 2>&1 || { echo "recon-run needs jq; install jq." >&2; exit 1; }
	@resp=$$(scripts/curl-auth.sh operator -sS --fail-with-body -X POST -H 'Content-Type: application/json' -H 'X-Saiman-Csrf: 1' --data '{}' $(LEDGER_URL)/api/v1/reconciliation/runs) || { echo "$$resp" >&2; exit 1; }; \
	printf '%s\n' "$$resp" | jq -r '"runId: \(.runId)"'

recon-report: ## Wait for the latest reconciliation run to finish (up to 2 min), show its report and save it to build/reports/reconciliation/latest.json.
	@command -v jq >/dev/null 2>&1 || { echo "recon-report needs jq; install jq." >&2; exit 1; }
	@mkdir -p build/reports/reconciliation
	@for i in $$(seq 1 60); do \
	  resp=$$(scripts/curl-auth.sh reader -sS --fail-with-body $(LEDGER_URL)/api/v1/reconciliation/runs/latest) || { echo "$$resp" >&2; exit 1; }; \
	  [ "$$(printf '%s' "$$resp" | jq -r '.status')" != "RUNNING" ] && break; \
	  sleep 2; \
	done; \
	printf '%s\n' "$$resp" | jq . | tee build/reports/reconciliation/latest.json

ledger-balance: ## Print the ledger trial balance as a table.
	@command -v jq >/dev/null 2>&1 || { echo "ledger-balance needs jq; install jq." >&2; exit 1; }
	@resp=$$(scripts/curl-auth.sh reader -sS --fail-with-body $(LEDGER_URL)/api/v1/ledger/trial-balance) || { echo "$$resp" >&2; exit 1; }; \
	printf '%s\n' "$$resp" | jq -r 'if length == 0 then "(no accounts)" else (.[0] | keys_unsorted) as $$cols | ($$cols | join("\t")), (.[] | [.[$$cols[]] | if type == "object" then (.atomicUnits // tojson) else . end] | map(tostring) | join("\t")) end' \
		| column -t -s "$$(printf '\t')"

ledger-tamper-demo: ## LOCAL COMPOSE ONLY: bypass the immutability triggers and inflate the latest SALE entry by 5000 atomic, for the reconciliation demo.
	scripts/ledger-tamper-demo.sh

# M6 evals (ADR-0025) and demo capture (ADR-0026). Both need `make up` (apps healthy).
EVAL_ANSWERS ?=

eval: ## Run the golden-set evals in a compose one-shot and publish docs/evals/{latest.md,latest.json,runs/<date>-<sha>.json}. EVAL_ANSWERS=1 adds the LLM answer tier (real spend, day-capped).
	scripts/run-evals.sh

capture-demo: ## Capture the running stack's runs/ledger/spend as build/capture/capture-<ts>.json (reader token; CAPTURE_OUT=<path> overrides; publish to web/public/demo/capture.json).
	scripts/capture-demo/capture-demo.sh

capture-demo-selftest: ## Self-test the capture scrubber against fixtures (no stack needed).
	scripts/capture-demo/test-scrub.sh

profile-pro: ## Switch agent usage to the Pro-plan profile (lean mode; .claude/profiles/pro.json).
	scripts/usage-profile.sh pro

profile-max: ## Switch agent usage to the Max-plan profile (.claude/profiles/max.json).
	scripts/usage-profile.sh max

profile-show: ## Print the active agent usage profile.
	@scripts/usage-profile.sh --show
