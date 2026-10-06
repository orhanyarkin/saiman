# Offline tests of the single Fargate task (ecs.tf, containers.tf). The container definitions are
# decoded from the task definition JSON, so the assertions see exactly what ECS would receive.
# Things a mock provider cannot see (no IAM/SSM resources, no ssm data sources) live in tests/check-task.sh.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
}

variables {
  image_tag                 = "sha-0123456789ab"
  session_id                = "test-session"
  expires_at                = "2026-10-06T18:00:00Z"
  x402_seller_payto_address = "0x1111111111111111111111111111111111111111"
  state_bucket_name         = "saiman-test-tfstate"
  auth_digests = {
    reader         = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    operator       = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    service_ledger = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  }
  db_master_password     = "unit-test-only"
  assets_manifest_sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  corpus_sha256          = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
}

run "task_shape" {
  assert {
    condition     = startswith(aws_ecs_task_definition.main.family, "saiman-demo") && aws_ecs_service.main.name == local.service_name && aws_ecs_cluster.main.name == "saiman-demo"
    error_message = "family must start with saiman-demo; service and cluster names are fixed"
  }

  assert {
    condition     = aws_ecs_task_definition.main.runtime_platform[0].cpu_architecture == "ARM64" && aws_ecs_task_definition.main.runtime_platform[0].operating_system_family == "LINUX" && aws_ecs_task_definition.main.network_mode == "awsvpc" && contains(aws_ecs_task_definition.main.requires_compatibilities, "FARGATE")
    error_message = "task must be Fargate ARM64 Linux awsvpc"
  }

  assert {
    condition     = aws_ecs_task_definition.main.execution_role_arn == "arn:aws:iam::123456789012:role/saiman/demo/saiman-demo-task-execution" && aws_ecs_task_definition.main.task_role_arn == "arn:aws:iam::123456789012:role/saiman/demo/saiman-demo-task"
    error_message = "roles must be the fixed bootstrap role ARNs"
  }

  assert {
    condition     = aws_ecs_service.main.enable_execute_command == true && aws_ecs_service.main.force_delete == true && aws_ecs_service.main.wait_for_steady_state == false && aws_ecs_service.main.desired_count == 1
    error_message = "service needs ECS Exec, force_delete, no steady-state wait and one task"
  }

  assert {
    condition     = aws_ecs_service.main.network_configuration[0].assign_public_ip == true && length(aws_ecs_service.main.network_configuration[0].subnets) == 2
    error_message = "the task needs a public IP in the two public subnets"
  }

  assert {
    condition     = sum([for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.memory]) <= aws_ecs_task_definition.main.memory
    error_message = "container memory limits must fit the task memory"
  }

  assert {
    condition     = alltrue([for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.logConfiguration.logDriver == "awslogs" && c.logConfiguration.options["awslogs-group"] == "/saiman-demo-lite" && c.logConfiguration.options["awslogs-stream-prefix"] == c.name])
    error_message = "every container logs to the demo log group with its own stream prefix"
  }
}

run "exact_secret_map" {
  # EXACT map per container: secret env name -> SSM parameter name. Anything extra or missing fails.
  assert {
    condition = jsonencode({
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      c.name => { for s in c.secrets : s.name => trimprefix(s.valueFrom, "arn:aws:ssm:eu-central-1:123456789012:parameter/saiman/demo/") }
      }) == jsonencode({
      assets                 = {}
      kafka                  = {}
      web                    = {}
      readiness              = {}
      "otel-collector"       = {}
      redis                  = { REDIS_PASSWORD = "redis_password" }
      "corpus-restore"       = { PGPASSWORD = "pg_ingest_owner_password" }
      "orchestrator-migrate" = { SPRING_FLYWAY_PASSWORD = "pg_orchestrator_owner_password" }
      "seller-api-migrate"   = { SPRING_FLYWAY_PASSWORD = "pg_seller_api_owner_password" }
      "ledger-migrate"       = { SPRING_FLYWAY_PASSWORD = "pg_ledger_owner_password" }
      "ingest-migrate"       = { SPRING_FLYWAY_PASSWORD = "pg_ingest_owner_password" }
      "db-init" = {
        PGPASSWORD            = "pg_master_password"
        PW_ORCHESTRATOR_OWNER = "pg_orchestrator_owner_password"
        PW_ORCHESTRATOR_APP   = "pg_orchestrator_app_password"
        PW_LEDGER_OWNER       = "pg_ledger_owner_password"
        PW_LEDGER_APP         = "pg_ledger_app_password"
        PW_SELLER_API_OWNER   = "pg_seller_api_owner_password"
        PW_SELLER_API_APP     = "pg_seller_api_app_password"
        PW_INGEST_OWNER       = "pg_ingest_owner_password"
        PW_INGEST_APP         = "pg_ingest_app_password"
      }
      orchestrator = {
        SPRING_DATASOURCE_PASSWORD   = "pg_orchestrator_app_password"
        SPRING_DATA_REDIS_PASSWORD   = "redis_password"
        SAIMAN_ROUTER_OPENAI_API_KEY = "openai_api_key"
        X402_CLIENT_PRIVATE_KEY      = "x402_buyer_private_key"
      }
      seller-api = {
        SPRING_DATASOURCE_PASSWORD   = "pg_seller_api_app_password"
        SPRING_DATA_REDIS_PASSWORD   = "redis_password"
        SAIMAN_ROUTER_OPENAI_API_KEY = "openai_api_key"
      }
      ledger = {
        SPRING_DATASOURCE_PASSWORD         = "pg_ledger_app_password"
        SPRING_DATA_REDIS_PASSWORD         = "redis_password"
        SAIMAN_LEDGER_SELLER_SERVICE_TOKEN = "seller_service_token_ledger"
      }
      ingest = {
        SPRING_DATASOURCE_PASSWORD   = "pg_ingest_app_password"
        SPRING_DATA_REDIS_PASSWORD   = "redis_password"
        SAIMAN_ROUTER_OPENAI_API_KEY = "openai_api_key"
      }
    })
    error_message = "the secret split must match the exact per-container map (master password, owner passwords, buyer key, OpenAI key and Redis password only where listed)"
  }
}

run "exact_env_names" {
  # EXACT environment-name allowlist per container (migrators: exactly the four of ADR-0027).
  assert {
    condition = jsonencode({
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      c.name => sort([for e in c.environment : e.name])
      }) == jsonencode({
      assets                 = ["AWS_DEFAULT_REGION", "AWS_PAGER"]
      web                    = []
      readiness              = []
      redis                  = []
      "otel-collector"       = []
      "corpus-restore"       = ["PGDATABASE", "PGHOST", "PGSSLMODE", "PGUSER"]
      "db-init"              = ["BOOTSTRAP_RDS", "BOOTSTRAP_SECRET_SOURCE", "PGDATABASE", "PGHOST", "PGSSLMODE", "PGUSER"]
      "orchestrator-migrate" = ["BPL_JVM_THREAD_COUNT", "SAIMAN_RUN_MODE", "SPRING_DATASOURCE_URL", "SPRING_FLYWAY_USER"]
      "seller-api-migrate"   = ["BPL_JVM_THREAD_COUNT", "SAIMAN_RUN_MODE", "SPRING_DATASOURCE_URL", "SPRING_FLYWAY_USER"]
      "ledger-migrate"       = ["BPL_JVM_THREAD_COUNT", "SAIMAN_RUN_MODE", "SPRING_DATASOURCE_URL", "SPRING_FLYWAY_USER"]
      "ingest-migrate"       = ["BPL_JVM_THREAD_COUNT", "SAIMAN_RUN_MODE", "SPRING_DATASOURCE_URL", "SPRING_FLYWAY_USER"]
      kafka = sort([
        "CLUSTER_ID", "KAFKA_NODE_ID", "KAFKA_PROCESS_ROLES", "KAFKA_CONTROLLER_QUORUM_VOTERS", "KAFKA_CONTROLLER_LISTENER_NAMES",
        "KAFKA_LISTENERS", "KAFKA_ADVERTISED_LISTENERS", "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "KAFKA_INTER_BROKER_LISTENER_NAME",
        "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "KAFKA_TRANSACTION_STATE_LOG_MIN_ISR",
        "KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "KAFKA_AUTO_CREATE_TOPICS_ENABLE", "KAFKA_LOG_DIRS", "KAFKA_HEAP_OPTS",
      ])
      orchestrator = sort(concat(
        ["BPL_JVM_THREAD_COUNT", "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED", "MANAGEMENT_TRACING_SAMPLING_PROBABILITY", "OTEL_EXPORTER_OTLP_ENDPOINT", "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATA_REDIS_URL", "SPRING_FLYWAY_ENABLED", "SPRING_KAFKA_BOOTSTRAP_SERVERS"],
        ["SAIMAN_AUTH_READER_TOKEN_SHA256", "SAIMAN_AUTH_OPERATOR_TOKEN_SHA256", "X402_CLIENT_ALLOWED_PAY_TO", "X402_CLIENT_ALLOWED_PLAINTEXT_HOSTS", "SPRING_HTTP_CLIENTS_REDIRECTS", "SAIMAN_SELLER_BASE_URL", "SAIMAN_ROUTER_REQUIRE_COST_SCOPE", "SAIMAN_ROUTER_MAX_SCOPE_BUDGET_USD_MICROS", "SAIMAN_ORCHESTRATOR_API_ALLOWED_HOSTS", "SAIMAN_ORCHESTRATOR_SPEND_APPROVAL_THRESHOLD_ATOMIC", "SAIMAN_CHAIN_RPC_URL", "SAIMAN_CHAIN_ALLOWED_HOSTS"],
      ))
      seller-api = sort(concat(
        ["BPL_JVM_THREAD_COUNT", "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED", "MANAGEMENT_TRACING_SAMPLING_PROBABILITY", "OTEL_EXPORTER_OTLP_ENDPOINT", "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATA_REDIS_URL", "SPRING_FLYWAY_ENABLED", "SPRING_KAFKA_BOOTSTRAP_SERVERS"],
        ["SAIMAN_AUTH_SERVICE_TOKENS_LEDGER_SHA256", "SELLER_INGEST_BASE_URL", "SELLER_DISCLOSURES_SOURCE", "X402_SELLER_PAYTO_ADDRESS", "SELLER_INTERNAL_ALLOWED_HOSTS"],
      ))
      ledger = sort(concat(
        ["BPL_JVM_THREAD_COUNT", "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED", "MANAGEMENT_TRACING_SAMPLING_PROBABILITY", "OTEL_EXPORTER_OTLP_ENDPOINT", "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATA_REDIS_URL", "SPRING_FLYWAY_ENABLED", "SPRING_KAFKA_BOOTSTRAP_SERVERS"],
        ["SAIMAN_AUTH_READER_TOKEN_SHA256", "SAIMAN_AUTH_OPERATOR_TOKEN_SHA256", "SAIMAN_CHAIN_RPC_URL", "SAIMAN_CHAIN_ALLOWED_HOSTS", "SAIMAN_LEDGER_API_ALLOWED_HOSTS", "SAIMAN_LEDGER_SELLER_BASE_URL", "SAIMAN_LEDGER_SELLER_ALLOWED_HOSTS"],
      ))
      ingest = sort(["BPL_JVM_THREAD_COUNT", "MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED", "MANAGEMENT_TRACING_SAMPLING_PROBABILITY", "OTEL_EXPORTER_OTLP_ENDPOINT", "SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME", "SPRING_DATA_REDIS_URL", "SPRING_FLYWAY_ENABLED", "SPRING_KAFKA_BOOTSTRAP_SERVERS"])
    })
    error_message = "the environment names must match the exact per-container allowlist"
  }
}

run "no_injection_or_secret_like_names" {
  # Port of the compose-policy normalisation: upper case, "." and "-" to "_", runs of "_" collapsed,
  # a trailing list index ("_0", "_0_") stripped. Applied to environment AND secret names.
  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      alltrue([
        for n in concat([for e in c.environment : e.name], [for s in c.secrets : s.name]) :
        !can(regex("^(SPRING_FLYWAY_URL|SPRING_APPLICATION_JSON|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|JAVA_OPTS|SPRING_AUTOCONFIGURE_EXCLUDE|SAIMAN_SECRETS_DIR|OPENAI_API_KEY|OPENAI_BASE_URL|AZURE_OPENAI_BASE_URL|OPENAI_LOG)$|^SPRING_(CONFIG|MAIN)_|^BPL_(DEBUG|JMX)_|^(SPRING_AI_OPENAI_|SAIMAN_INGEST_MKK_|SPRINGDOC_)", replace(replace(upper(replace(n, "/[.-]/", "_")), "/_+/", "_"), "/_[0-9]+_?$/", "")))
      ])
    ])
    error_message = "a forbidden config-injection name is present (after normalisation)"
  }

  # The matcher itself: each of these spellings must be recognised as forbidden.
  assert {
    condition = alltrue([
      for n in ["SPRING__CONFIG_IMPORT", "SPRING_AUTOCONFIGURE_EXCLUDE_0", "BPL_DEBUG_ENABLED", "SPRING_FLYWAY__URL", "spring.main.lazy-initialization", "JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "SAIMAN_SECRETS_DIR", "BPL_JMX_ENABLED", "SPRING_APPLICATION_JSON_1_"] :
      can(regex("^(SPRING_FLYWAY_URL|SPRING_APPLICATION_JSON|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|JAVA_OPTS|SPRING_AUTOCONFIGURE_EXCLUDE|SAIMAN_SECRETS_DIR|OPENAI_API_KEY|OPENAI_BASE_URL|AZURE_OPENAI_BASE_URL|OPENAI_LOG)$|^SPRING_(CONFIG|MAIN)_|^BPL_(DEBUG|JMX)_|^(SPRING_AI_OPENAI_|SAIMAN_INGEST_MKK_|SPRINGDOC_)", replace(replace(upper(replace(n, "/[.-]/", "_")), "/_+/", "_"), "/_[0-9]+_?$/", "")))
    ]) && local.region == "eu-central-1"
    error_message = "the injection matcher must catch collapsed, indexed and dotted spellings (local.region is only here to anchor the assert to the configuration)"
  }

  # No secret-looking environment name (the three digest variables and the BOOTSTRAP_SECRET_SOURCE=env mode switch are explicit exceptions).
  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      alltrue([
        for e in c.environment :
        !can(regex("PASSWORD|TOKEN|PRIVATE|SECRET|KEY", e.name)) || contains(["SAIMAN_AUTH_READER_TOKEN_SHA256", "SAIMAN_AUTH_OPERATOR_TOKEN_SHA256", "SAIMAN_AUTH_SERVICE_TOKENS_LEDGER_SHA256", "BOOTSTRAP_SECRET_SOURCE"], e.name)
      ])
    ])
    error_message = "no environment entry may look like a secret"
  }

  # No key material or password-looking value in any environment value.
  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      alltrue([for e in c.environment : !can(regex("0x[0-9a-fA-F]{64}", e.value)) && !can(regex("://[^/@]*:[^/@]*@", e.value))])
    ])
    error_message = "no private key or credentialed URL in an environment value"
  }

  # Every secret is an SSM ARN under /saiman/demo/ (the only prefix the execution role may read).
  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      alltrue([for s in c.secrets : startswith(s.valueFrom, "arn:aws:ssm:eu-central-1:123456789012:parameter/saiman/demo/")])
    ])
    error_message = "secrets must be SSM parameter ARNs under /saiman/demo/"
  }
}

run "no_namespace_sharing_or_privileges" {
  assert {
    condition     = aws_ecs_task_definition.main.pid_mode == null && aws_ecs_task_definition.main.ipc_mode == null
    error_message = "pid_mode and ipc_mode must stay unset (no shared PID/IPC namespaces)"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      !can(c.linuxParameters) && !can(c.privileged) && !can(c.user) && !can(c.dockerSecurityOptions) && !can(c.environmentFiles)
    ])
    error_message = "no linuxParameters (capabilities, devices), privileged, user, security options or environment files"
  }

  assert {
    condition     = jsonencode(sort([for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.name])) == jsonencode(sort(["assets", "db-init", "orchestrator-migrate", "seller-api-migrate", "ledger-migrate", "ingest-migrate", "corpus-restore", "kafka", "redis", "otel-collector", "orchestrator", "seller-api", "ledger", "ingest", "web", "readiness"]))
    error_message = "the container set is fixed"
  }

  assert {
    condition     = aws_ecs_cluster.main.configuration[0].execute_command_configuration[0].logging == "NONE"
    error_message = "ECS Exec logging must be NONE (not OVERRIDE)"
  }
}

run "integrity_checks_in_assets_container" {
  assert {
    condition = (
      can(regex("sha256sum", [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.command[0] if c.name == "assets"][0]))
      && can(regex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.command[0] if c.name == "assets"][0]))
      && can(regex("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.command[0] if c.name == "assets"][0]))
    )
    error_message = "the assets container must verify the manifest and corpus digests"
  }

  # The verification comes before the first copy into a served volume.
  assert {
    condition = (
      length(regexall("digest mismatch", [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.command[0] if c.name == "assets"][0])) == 2
      && length(split("cp -R", [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.command[0] if c.name == "assets"][0])[0]) > length(split("corpus digest mismatch", [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.command[0] if c.name == "assets"][0])[0])
    )
    error_message = "both digests must be checked before any copy into web-html/web-conf"
  }
}

run "rejects_bad_digests" {
  command = plan

  variables {
    assets_manifest_sha256 = "NOT-A-DIGEST"
  }

  expect_failures = [var.assets_manifest_sha256]
}

run "rejects_bad_corpus_digest" {
  command = plan

  variables {
    corpus_sha256 = "abc"
  }

  expect_failures = [var.corpus_sha256]
}

run "rejects_corpus_key_without_dump_suffix" {
  command = plan

  variables {
    corpus_object_key = "artifacts/corpus/latest.tar"
  }

  expect_failures = [var.corpus_object_key]
}

run "rejects_corpus_key_with_traversal" {
  command = plan

  variables {
    corpus_object_key = "artifacts/../demo/assets/x.dump"
  }

  expect_failures = [var.corpus_object_key]
}

run "rejects_foreign_grafana_endpoint" {
  command = plan

  variables {
    grafana_otlp_endpoint = "https://evil.example.com/otlp"
  }

  expect_failures = [var.grafana_otlp_endpoint]
}

run "app_hardening" {
  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      [for e in c.environment : e.value if e.name == "SPRING_FLYWAY_ENABLED"] == ["false"]
      if contains(["orchestrator", "seller-api", "ledger", "ingest"], c.name)
    ])
    error_message = "SPRING_FLYWAY_ENABLED=false on every app"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      length([for e in c.environment : e if e.name == "SAIMAN_RUN_MODE" && e.value == "migrate"]) == (endswith(c.name, "-migrate") ? 1 : 0) && length([for e in c.environment : e if e.name == "SAIMAN_RUN_MODE"]) == (endswith(c.name, "-migrate") ? 1 : 0)
    ])
    error_message = "SAIMAN_RUN_MODE=migrate exactly on the four migrators"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      length([for e in c.environment : e if can(regex("^(SPRING_FLYWAY_URL|SPRING_APPLICATION_JSON|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|JAVA_OPTS|SPRING_AUTOCONFIGURE_EXCLUDE)$|^SPRING_(CONFIG|MAIN)_", e.name))]) == 0
    ])
    error_message = "config-injection environment keys are forbidden"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      [for e in c.environment : e.value if e.name == "SPRING_DATASOURCE_URL"] == [for a in jsondecode(aws_ecs_task_definition.main.container_definitions) : [for e2 in a.environment : e2.value if e2.name == "SPRING_DATASOURCE_URL"][0] if a.name == trimsuffix(c.name, "-migrate")]
      if endswith(c.name, "-migrate")
    ])
    error_message = "a migrator uses the same JDBC URL as its app"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      alltrue([for e in c.environment : startswith(e.value, "jdbc:postgresql://") && endswith(e.value, ":5432/saiman?sslmode=require") if e.name == "SPRING_DATASOURCE_URL"])
    ])
    error_message = "JDBC URLs must be plain RDS URLs with sslmode=require and no credentials"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      [for e in c.environment : e.value if e.name == "SAIMAN_CHAIN_RPC_URL"] == ["https://sepolia.base.org"]
      if contains(["orchestrator", "ledger"], c.name)
    ])
    error_message = "chain RPC is the public Base Sepolia endpoint"
  }
}

run "ordering_and_images" {
  # Every dependency on a non-essential container is SUCCESS; every HEALTHY target has a health check.
  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      alltrue([
        for d in c.dependsOn :
        d.condition == (
          [for t in jsondecode(aws_ecs_task_definition.main.container_definitions) : t.essential if t.name == d.containerName][0] ? "HEALTHY" : "SUCCESS"
        )
      ])
    ])
    error_message = "dependencies on one-shots must be SUCCESS, on essential kafka/redis HEALTHY"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      alltrue([for d in c.dependsOn : d.condition != "HEALTHY" || [for t in jsondecode(aws_ecs_task_definition.main.container_definitions) : can(t.healthCheck.command) if t.name == d.containerName][0]])
    ])
    error_message = "HEALTHY dependencies need a health check"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      contains([for d in c.dependsOn : d.containerName], "${c.name}-migrate") && contains([for d in c.dependsOn : d.containerName], "kafka") && contains([for d in c.dependsOn : d.containerName], "redis")
      if contains(["orchestrator", "seller-api", "ledger", "ingest"], c.name)
    ])
    error_message = "apps depend on their migrator, kafka and redis"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      contains([for d in c.dependsOn : d.containerName], "db-init")
      if endswith(c.name, "-migrate")
    ]) && contains([for d in [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c if c.name == "ingest"][0].dependsOn : d.containerName], "corpus-restore")
    error_message = "migrators wait for db-init; ingest waits for corpus-restore"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      startswith(c.image, "ghcr.io/orhanyarkin/saiman-") ? endswith(c.image, ":sha-0123456789ab") : startswith(c.image, "public.ecr.aws/") ? can(regex(":[0-9][^:@]*$", c.image)) : can(regex("^[a-z0-9./-]+:[0-9][0-9.]*@sha256:[0-9a-f]{64}$", c.image))
    ])
    error_message = "service images use the sha- tag, mirror images a version tag, Docker Hub images tag@sha256 digests"
  }

  assert {
    condition = (
      startswith([for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.image if c.name == "kafka"][0], "apache/kafka:4.3.1@sha256:")
      && startswith([for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.image if c.name == "otel-collector"][0], "otel/opentelemetry-collector:0.162.0@sha256:")
      && startswith([for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.image if c.name == "db-init"][0], "public.ecr.aws/docker/library/postgres:17")
    )
    error_message = "third-party pins must match compose (db-init needs a bash-capable postgres 17 image)"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      c.essential == false
      if contains(["assets", "db-init", "corpus-restore", "readiness", "otel-collector"], c.name) || endswith(c.name, "-migrate")
    ])
    error_message = "one-shots, readiness and the collector are non-essential"
  }
}

run "otel_collector_config" {
  assert {
    condition     = [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.command if c.name == "otel-collector"][0] == ["--config=/assets/otel/config.yaml"]
    error_message = "without a Grafana endpoint only the base collector config is used"
  }

  assert {
    condition     = length([for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c if c.name == "otel-collector" && length(c.secrets) == 0 && length(c.environment) == 0]) == 1
    error_message = "no Grafana env or secret when the endpoint is unset"
  }
}

run "otel_collector_grafana" {
  variables {
    grafana_otlp_endpoint = "https://otlp-gateway-prod-eu-west-0.grafana.net/otlp"
  }

  assert {
    condition     = [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.command if c.name == "otel-collector"][0] == ["--config=/assets/otel/config.yaml", "--config=/assets/otel/grafana.yaml"]
    error_message = "the Grafana overlay is added when the endpoint is set"
  }

  assert {
    condition = (
      [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : [for s in c.secrets : s.name] if c.name == "otel-collector"][0] == ["GRAFANA_OTLP_AUTH"]
      && [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : [for e in c.environment : e.name] if c.name == "otel-collector"][0] == ["GRAFANA_OTLP_ENDPOINT"]
    )
    error_message = "auth only via secrets, endpoint via environment"
  }
}

run "memory_must_fit" {
  command = plan

  variables {
    task_memory = 4096
  }

  expect_failures = [aws_ecs_task_definition.main]
}
