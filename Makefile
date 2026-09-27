# Saiman root Makefile (M0 skeleton). See CLAUDE.md, ADR-0006, ADR-0007.
# Add eval/cost-report/capture-demo targets together with the thing they run.

COMPOSE := docker compose -f deploy/compose/docker-compose.yml

.DEFAULT_GOAL := help

.PHONY: help images infra-up up down clean ps logs test lint format web-dev verify-trace

help: ## Show this help.
	@grep -hE '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-14s\033[0m %s\n", $$1, $$2}'

web/node_modules: web/pnpm-lock.yaml
	pnpm --dir web install --frozen-lockfile
	touch $@

images: ## Build all service images for the host architecture (./gradlew bootBuildImage).
	./gradlew bootBuildImage

infra-up: ## Start postgres, redpanda, valkey, otel-collector, jaeger and wait for health.
	$(COMPOSE) up -d --wait

up: images ## Build images, start the full stack (infra + apps) and wait for app health.
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

test: web/node_modules ## Run backend + web tests.
	./gradlew check
	pnpm --dir web test

lint: web/node_modules ## Run backend + web linters (no formatting changes).
	./gradlew spotlessCheck
	pnpm --dir web lint
	pnpm --dir web typecheck

format: web/node_modules ## Apply backend + web formatting.
	./gradlew spotlessApply
	pnpm --dir web exec prettier --write .

web-dev: web/node_modules ## Run the Vite dev server against a running `make up` stack.
	pnpm --dir web dev

verify-trace: ## Verify a trace in Jaeger. Usage: make verify-trace TRACE_ID=<id> (or omit to self-generate one).
	scripts/verify-trace.sh $${TRACE_ID:+"$$TRACE_ID"}
