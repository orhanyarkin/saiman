#!/usr/bin/env python3
"""Mutation check for tests/task.tftest.hcl (offline, no AWS).

For each rule of the ECS RegisterTaskDefinition test it breaks the module in exactly the way the rule is meant to catch,
runs `terraform test -filter=tests/task.tftest.hcl` in a temporary copy and requires BOTH a failing run AND the rule's own
error message in the output (engine: ops/tf_mutation.py). The repository is never modified.

Usage: python3 deploy/terraform/aws/demo-lite/tests/mutation-check.py [rule-id ...]   (default: all; ~10 s each)
Exit 0 every mutation was caught, 1 a mutation survived or the baseline is red, 2 setup problem.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(HERE)
LONG = "x" * 300

# (id, file, old, new, expected text from the rule's error_message; whitespace-insensitive)
MUTATIONS = [
    ("retries-above-10", "containers.tf", "        retries     = 10\n        startPeriod = 120", "        retries     = 11\n        startPeriod = 120", "healthCheck.retries must be 1-10"),
    ("retries-zero", "containers.tf", "        retries     = 10\n        startPeriod = 10", "        retries     = 0\n        startPeriod = 10", "healthCheck.retries must be 1-10"),
    ("interval-below-5", "containers.tf", "        interval    = 20\n        timeout     = 20", "        interval    = 4\n        timeout     = 20", "healthCheck.interval must be 5-300"),
    ("interval-above-300", "containers.tf", "        interval    = 20\n        timeout     = 20", "        interval    = 301\n        timeout     = 20", "healthCheck.interval must be 5-300"),
    ("timeout-above-60", "containers.tf", "        interval    = 20\n        timeout     = 20", "        interval    = 20\n        timeout     = 61", "healthCheck.timeout must be 2-60"),
    ("timeout-below-2", "containers.tf", "        interval    = 20\n        timeout     = 20", "        interval    = 20\n        timeout     = 1", "healthCheck.timeout must be 2-60"),
    ("startperiod-above-300", "containers.tf", "        retries     = 10\n        startPeriod = 120", "        retries     = 10\n        startPeriod = 301", "healthCheck.startPeriod must be 0-300"),
    ("health-command-prefix", "containers.tf", '["CMD-SHELL", "REDISCLI_AUTH', '["SH", "REDISCLI_AUTH', "healthCheck.command must start with CMD or CMD-SHELL"),
    ("start-timeout-above-120", "containers.tf", "    startTimeout = 120\n  }\n\n  migrate_specs", "    startTimeout = 121\n  }\n\n  migrate_specs", "startTimeout and stopTimeout must be 2-120"),
    ("stop-timeout-above-120", "containers.tf", "      image         = local.images.nginx\n", "      image         = local.images.nginx\n      stopTimeout   = 130\n", "startTimeout and stopTimeout must be 2-120"),
    ("memory-below-6", "containers.tf", "      memory        = 64\n", "      memory        = 5\n", "container memory must be at least 6 MiB"),
    ("memory-reservation-not-below-memory", "containers.tf", "      memory        = 64\n", "      memory        = 64\n      memoryReservation = 64\n", "memoryReservation (if any) below memory"),
    ("restart-period-below-60", "containers.tf", "restartAttemptPeriod = 60", "restartAttemptPeriod = 30", "restartPolicy.restartAttemptPeriod must be 60-1800"),
    ("host-port-differs", "containers.tf", "portMappings  = [{ containerPort = 80, protocol = \"tcp\" }]", "portMappings  = [{ containerPort = 80, hostPort = 8080, protocol = \"tcp\" }]", "awsvpc port mappings"),
    ("invalid-dependency-condition", "containers.tf", '      redis = "HEALTHY"\n    }', '      redis = "READY"\n    }', "dependency condition must be START, COMPLETE, SUCCESS or HEALTHY"),
    ("dependency-on-missing-container", "containers.tf", '      redis = "HEALTHY"\n    }', '      redis = "HEALTHY"\n      nonexistent = "SUCCESS"\n    }', "dependency must name another container of the same task definition"),
    ("self-dependency", "containers.tf", "      image         = local.images.kafka\n", '      image         = local.images.kafka\n      depends       = { kafka = "START" }\n', "dependency must name another container of the same task definition"),
    ("healthy-without-health-check", "containers.tf", '      depends = { assets = "SUCCESS" }\n    }\n\n    # Runs assets/scripts/readiness.sh', '      depends = { assets = "SUCCESS", "otel-collector" = "HEALTHY" }\n    }\n\n    # Runs assets/scripts/readiness.sh', "HEALTHY may only target a container that has a health check"),
    ("dependency-cycle", "containers.tf", "      image         = local.images.kafka\n", '      image         = local.images.kafka\n      depends       = { orchestrator = "START" }\n', "circular container dependency"),
    ("mount-of-undeclared-volume", "containers.tf", 'volume_names_stack = ["assets", "web-html", "web-conf"]', 'volume_names_stack = ["assets", "web-html"]', "mount point must name a volume of its own task definition"),
    ("duplicate-container-names", "containers.tf", "      name        = name\n", '      name        = "assets"\n', "container names must be unique within a task definition|||Duplicate object key"),
    ("invalid-container-name", "containers.tf", "      name        = name\n", '      name        = "${name}.x"\n', "container name: up to 255 letters, numbers, hyphens and underscores"),
    ("env-and-secret-share-a-name", "containers.tf", '      X402_CLIENT_PRIVATE_KEY      = "x402_buyer_private_key" # x402.client.private-key', '      X402_CLIENT_PRIVATE_KEY      = "x402_buyer_private_key" # x402.client.private-key\n      SPRING_DATA_REDIS_HOST       = "redis_password"', "environment and secret names must be unique within a container"),
    ("awslogs-option-missing", "containers.tf", '          "awslogs-stream-prefix" = name\n', "", "the awslogs driver needs awslogs-group, awslogs-region and awslogs-stream-prefix"),
    ("invalid-fargate-size", "ecs.tf", "init_task_memory = 4096", "init_task_memory = 3000", "task cpu/memory must be a valid Fargate combination"),
    ("invalid-family", "ecs.tf", 'family                   = "${local.name_prefix}-init"', 'family                   = "${local.name_prefix} init"', "family: up to 255 letters, numbers, hyphens and underscores|||invalid value for family"),
    ("no-essential-container", "containers.tf", "      image      = local.images.postgres\n      essential  = true\n      memory     = 256", "      image      = local.images.postgres\n      essential  = false\n      memory     = 256", "each task definition needs an essential container"),
    ("last-init-container-skips-a-migrator", "containers.tf", 'concat(["assets", "db-init"], [for a in keys(local.apps) : "${a}-migrate"])', 'concat(["assets", "db-init"], [for a in ["orchestrator"] : "${a}-migrate"])', "must wait for every other init container"),
    ("more-than-ten-containers", "containers.tf", "  stack_specs = merge(local.stack_fixed, local.app_specs)", "  stack_specs = merge(local.stack_fixed, local.app_specs, { extra1 = local.stack_fixed.web, extra2 = local.stack_fixed.web })", "at most 10 containers"),
    ("task-definition-over-64-kib", "containers.tf", '        KAFKA_HEAP_OPTS                                = "-Xms256m -Xmx512m"\n', '        KAFKA_HEAP_OPTS                                = "-Xms256m -Xmx512m"\n        PAD                                            = "' + "y" * 60000 + '"\n', "its container definitions must stay under 56000 bytes"),
    ("image-reference-too-long", "containers.tf", 'nginx    = "public.ecr.aws/docker/library/nginx:1.31.0-alpine"', 'nginx    = "public.ecr.aws/docker/library/nginx:1.31.0-alpine-' + LONG + '"', "at most 255 characters"),
    # The provider reads a task definition back without its empty lists (dependsOn, environment, ...); a mock echoes the input.
    # The tests carry every container once more as `*_as_returned` (empty values dropped) to catch rules that assume a key.
    ("dep-graph-assumes-depends-on", "ecs.tf", "[for d in try(c.dependsOn, []) : d.containerName]", "[for d in c.dependsOn : d.containerName]", 'does not have an attribute named "dependsOn"'),
    ("rule-assumes-depends-on", "tests/task.tftest.hcl", "for d in try(c.dependsOn, []) : contains([\"START\"", "for d in c.dependsOn : contains([\"START\"", 'does not have an attribute named "dependsOn"'),
    ("rule-assumes-environment", "tests/task.tftest.hcl", "length(try(c.environment, [])) + length(try(c.secrets, []))", "length(c.environment) + length(try(c.secrets, []))", 'does not have an attribute named "environment"'),
    ("as-returned-copies-keep-empty-values", "ecs.tf", "if v != null && try(length(v) > 0, true)", "if true", "the as-returned copies must lack the empty keys"),
    ("as-returned-copies-drop-a-container", "ecs.tf", "\"${t}_as_returned\" => [for c in cs :", "\"${t}_as_returned\" => [for c in slice(cs, 1, length(cs)) :", "dropping empty values must neither drop nor reorder containers"),
    # demo-up #8: Redis requires a password, the apps got spring.data.redis.url (which makes Boot ignore the password property).
    ("redis-url-back-next-to-the-password", "containers.tf", '      SPRING_DATA_REDIS_HOST                  = "127.0.0.1"\n', '      SPRING_DATA_REDIS_HOST                  = "127.0.0.1"\n      SPRING_DATA_REDIS_URL                   = "redis://127.0.0.1:6379"\n', "an app must not set spring.data.redis.url|||ECS gives an app these names"),
    ("redis-password-not-given-to-the-apps", "containers.tf", '      SPRING_DATA_REDIS_PASSWORD = "redis_password" # spring.data.redis.password\n', "", "every app gets the Redis password|||compose gives an app these names"),
    ("redis-host-moves-away-from-loopback", "containers.tf", 'SPRING_DATA_REDIS_HOST                  = "127.0.0.1"', 'SPRING_DATA_REDIS_HOST                  = "redis"', "every app reaches Redis on SPRING_DATA_REDIS_HOST=127.0.0.1"),
    ("redis-listens-on-another-port", "containers.tf", "--bind 127.0.0.1 --save", "--bind 127.0.0.1 --port 6380 --save", "redis listens on 127.0.0.1:6379"),
    ("redis-loses-its-password", "containers.tf", ' --requirepass \\"$REDIS_PASSWORD\\"', "", "redis listens on 127.0.0.1:6379 (the apps' host and port) and requires the same SSM redis_password"),
    # Parity with docker-compose: a name compose gives an app and ECS forgot, one ECS invents, and allowlist entries gone stale.
    ("ecs-forgets-a-name-compose-gives-the-app", "containers.tf", '      SAIMAN_ROUTER_REQUIRE_COST_SCOPE                    = "true"\n', "", "compose gives an app these names and ECS does not"),
    ("ecs-forgets-the-kafka-servers", "containers.tf", '      SPRING_KAFKA_BOOTSTRAP_SERVERS = "127.0.0.1:9092"\n', "", "compose gives an app these names and ECS does not"),
    ("ecs-forgets-the-openai-key", "containers.tf", '      SAIMAN_ROUTER_OPENAI_API_KEY = "openai_api_key"         # saiman.router.openai.api-key\n', "", "compose gives an app these names and ECS does not"),
    ("ecs-invents-a-name-compose-does-not-have", "containers.tf", '      SELLER_DISCLOSURES_SOURCE                = "rag"\n', '      SELLER_DISCLOSURES_SOURCE                = "rag"\n      SELLER_SOMETHING_NEW                     = "x"\n', "ECS gives an app these names"),
    ("compose-gains-a-name-ecs-does-not-have", "../../../compose/docker-compose.yml", "      SAIMAN_SELLER_BASE_URL: http://seller-api:8081\n", "      SAIMAN_SELLER_BASE_URL: http://seller-api:8081\n      SAIMAN_SELLER_NEW_SETTING: x\n", "compose gives an app these names and ECS does not"),
    ("allowlist-entry-gone-stale", "tests/compose-parity-allowlist.json", '"SAIMAN_AUTH_SERVICE_TOKENS_EVALS_SHA256": "The evals', '"SAIMAN_AUTH_SERVICE_TOKENS_GONE_SHA256": "The evals', "stale compose_only entries"),
    ("allowlist-alias-without-the-ecs-names", "tests/compose-parity-allowlist.json", '"ecs": ["SPRING_DATA_REDIS_HOST", "SPRING_DATA_REDIS_PORT"]', '"ecs": ["SPRING_DATA_REDIS_HOST"]', "compose gives an app these names and ECS does not|||ECS gives an app these names"),
    # readiness.sh explains a failed demo-up from /actuator/health (component names and statuses, never details).
    ("health-components-not-shown", "containers.tf", '      MANAGEMENT_ENDPOINT_HEALTH_SHOW_COMPONENTS = "always"\n', "", "every app shows health component names"),
    ("health-details-switched-on", "containers.tf", '      MANAGEMENT_ENDPOINT_HEALTH_SHOW_COMPONENTS = "always"\n', '      MANAGEMENT_ENDPOINT_HEALTH_SHOW_COMPONENTS = "always"\n      MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS = "always"\n', "none shows details"),
]


def main():
    sys.path.insert(0, os.path.join(HERE, "..", "..", "ops"))
    import tf_mutation

    repo = os.path.abspath(os.path.join(MODULE, "..", "..", "..", ".."))
    return tf_mutation.run(MODULE, "tests/task.tftest.hcl", MUTATIONS, sys.argv[1:], repo_root=repo, extra_dirs=["deploy/compose"])


if __name__ == "__main__":
    sys.exit(main())
