# Offline tests of the two Fargate task definitions (ecs.tf, containers.tf): the long-running `main` stack and
# the run-to-completion `init`. The container definitions are decoded from the task definition JSON
# (local.decoded_tasks), so the assertions see exactly what ECS would receive. A mock provider does not apply
# ECS's own rules (at most 10 containers per task definition, at least one essential container, dependsOn
# targets inside the same task definition), so those are asserted here explicitly.
# Things a mock provider cannot see (no IAM/SSM resources, no ssm data sources) live in tests/check-task.sh.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
}

variables {
  image_tag = "sha-0123456789ab"
  image_digests = {
    "orchestrator" = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    "seller-api"   = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    "ledger"       = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    "ingest"       = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    "evals"        = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
  }
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

run "ecs_register_task_definition_rules" {
  # NOTE: this run is first on purpose. `terraform test` skips the remaining runs after a run that ERRORS (as opposed to
  # an assertion that fails), so a mutation that makes a later run error must not hide these rule messages.
  # The rules of the ECS RegisterTaskDefinition API that a mock provider never applies, checked on exactly what ECS would
  # receive, for BOTH task definitions. Numbers are from the API reference (HealthCheck, ContainerDefinition,
  # ContainerDependency, ContainerRestartPolicy). A violation otherwise costs a whole demo-up (minutes of AWS time):
  # demo-up #3 failed on `retries = 20` ("Health check retries must be less than or equal to ... 10").

  # --- task level ---
  assert {
    condition     = alltrue([for td in [aws_ecs_task_definition.main, aws_ecs_task_definition.init] : can(regex("^[A-Za-z0-9_-]{1,255}$", td.family))])
    error_message = "family: up to 255 letters, numbers, hyphens and underscores"
  }

  assert {
    condition = alltrue([
      for td in [aws_ecs_task_definition.main, aws_ecs_task_definition.init] : (
        (tonumber(td.cpu) == 256 && contains([512, 1024, 2048], tonumber(td.memory)))
        || (tonumber(td.cpu) == 512 && tonumber(td.memory) >= 1024 && tonumber(td.memory) <= 4096 && tonumber(td.memory) % 1024 == 0)
        || (tonumber(td.cpu) == 1024 && tonumber(td.memory) >= 2048 && tonumber(td.memory) <= 8192 && tonumber(td.memory) % 1024 == 0)
        || (tonumber(td.cpu) == 2048 && tonumber(td.memory) >= 4096 && tonumber(td.memory) <= 16384 && tonumber(td.memory) % 1024 == 0)
        || (tonumber(td.cpu) == 4096 && tonumber(td.memory) >= 8192 && tonumber(td.memory) <= 30720 && tonumber(td.memory) % 1024 == 0)
      )
    ])
    error_message = "task cpu/memory must be a valid Fargate combination (256-4096 cpu units)"
  }

  # Quota "Task definition size: 64 KiB" (not adjustable). The container definitions are the bulk of it; keep a margin
  # for the rest of the registered document (family, roles, volumes).
  assert {
    condition     = length(aws_ecs_task_definition.main.container_definitions) < 56000 && length(aws_ecs_task_definition.init.container_definitions) < 56000
    error_message = "a task definition may be at most 64 KiB; its container definitions must stay under 56000 bytes"
  }

  # --- container names ---
  assert {
    condition     = alltrue([for c in local.all_decoded_containers : can(regex("^[A-Za-z0-9_-]{1,255}$", c.name))])
    error_message = "container name: up to 255 letters, numbers, hyphens and underscores"
  }

  assert {
    condition     = alltrue([for cs in values(local.decoded_tasks) : length(distinct([for c in cs : c.name])) == length(cs)])
    error_message = "container names must be unique within a task definition"
  }

  # --- health checks (HealthCheck) ---
  assert {
    condition     = alltrue([for c in local.all_decoded_containers : !can(c.healthCheck) || (contains(["CMD", "CMD-SHELL"], c.healthCheck.command[0]) && length(c.healthCheck.command) > 1)])
    error_message = "healthCheck.command must start with CMD or CMD-SHELL and have a command"
  }

  assert {
    condition     = alltrue([for c in local.all_decoded_containers : !can(c.healthCheck) || (try(c.healthCheck.interval, 30) >= 5 && try(c.healthCheck.interval, 30) <= 300)])
    error_message = "healthCheck.interval must be 5-300 seconds"
  }

  assert {
    condition     = alltrue([for c in local.all_decoded_containers : !can(c.healthCheck) || (try(c.healthCheck.timeout, 5) >= 2 && try(c.healthCheck.timeout, 5) <= 60)])
    error_message = "healthCheck.timeout must be 2-60 seconds (the API reference says 60, not 120)"
  }

  assert {
    condition     = alltrue([for c in local.all_decoded_containers : !can(c.healthCheck) || (try(c.healthCheck.retries, 3) >= 1 && try(c.healthCheck.retries, 3) <= 10)])
    error_message = "healthCheck.retries must be 1-10"
  }

  assert {
    condition     = alltrue([for c in local.all_decoded_containers : !can(c.healthCheck) || (try(c.healthCheck.startPeriod, 0) >= 0 && try(c.healthCheck.startPeriod, 0) <= 300)])
    error_message = "healthCheck.startPeriod must be 0-300 seconds"
  }

  # --- start/stop timeouts, memory, restart policy, ports ---
  assert {
    condition     = alltrue([for c in local.all_decoded_containers : (!can(c.startTimeout) || (c.startTimeout >= 2 && c.startTimeout <= 120)) && (!can(c.stopTimeout) || (c.stopTimeout >= 2 && c.stopTimeout <= 120))])
    error_message = "startTimeout and stopTimeout must be 2-120 seconds on Fargate"
  }

  assert {
    condition     = alltrue([for c in local.all_decoded_containers : c.memory >= 6 && (!can(c.memoryReservation) || c.memoryReservation < c.memory)])
    error_message = "container memory must be at least 6 MiB, and memoryReservation (if any) below memory"
  }

  assert {
    condition     = alltrue([for c in local.all_decoded_containers : !can(c.restartPolicy) || (c.restartPolicy.enabled == true || c.restartPolicy.enabled == false) && try(c.restartPolicy.restartAttemptPeriod, 300) >= 60 && try(c.restartPolicy.restartAttemptPeriod, 300) <= 1800])
    error_message = "restartPolicy.restartAttemptPeriod must be 60-1800 seconds"
  }

  assert {
    condition     = alltrue([for c in local.all_decoded_containers : !can(c.portMappings) || alltrue([for p in c.portMappings : p.containerPort >= 1 && p.containerPort <= 65535 && (!can(p.hostPort) || p.hostPort == p.containerPort)])])
    error_message = "awsvpc port mappings: containerPort 1-65535 and hostPort absent or equal"
  }

  # --- dependencies (ContainerDependency) ---
  assert {
    condition     = alltrue([for c in local.all_decoded_containers : alltrue([for d in c.dependsOn : contains(["START", "COMPLETE", "SUCCESS", "HEALTHY"], d.condition)])])
    error_message = "a dependency condition must be START, COMPLETE, SUCCESS or HEALTHY"
  }

  assert {
    condition = alltrue([
      for cs in values(local.decoded_tasks) :
      alltrue([for c in cs : alltrue([for d in c.dependsOn : d.containerName != c.name && contains([for x in cs : x.name], d.containerName)])])
    ])
    error_message = "a dependency must name another container of the same task definition"
  }

  assert {
    condition = alltrue([
      for cs in values(local.decoded_tasks) :
      alltrue([for c in cs : alltrue([for d in c.dependsOn : d.condition != "HEALTHY" || try([for x in cs : can(x.healthCheck) if x.name == d.containerName][0], false)])])
    ])
    error_message = "HEALTHY may only target a container that has a health check"
  }

  assert {
    condition = alltrue([
      for t, g in local.dep_reach4 : alltrue([for n, reach in g : !contains(reach, n)])
    ])
    error_message = "circular container dependency"
  }

  # --- volumes and mounts ---
  assert {
    condition = alltrue([
      for t, td in { main = aws_ecs_task_definition.main, init = aws_ecs_task_definition.init } :
      alltrue([for c in local.decoded_tasks[t] : alltrue([for m in c.mountPoints : contains([for v in td.volume : v.name], m.sourceVolume) && startswith(m.containerPath, "/")])])
    ])
    error_message = "a mount point must name a volume of its own task definition and use an absolute path"
  }

  # --- environment and secrets ---
  assert {
    condition     = alltrue([for c in local.all_decoded_containers : length(distinct(concat([for e in c.environment : e.name], [for s in c.secrets : s.name]))) == length(c.environment) + length(c.secrets)])
    error_message = "environment and secret names must be unique within a container (and not appear in both)"
  }

  # --- logging and images ---
  assert {
    condition     = alltrue([for c in local.all_decoded_containers : alltrue([for k in ["awslogs-group", "awslogs-region", "awslogs-stream-prefix"] : contains(keys(c.logConfiguration.options), k)])])
    error_message = "the awslogs driver needs awslogs-group, awslogs-region and awslogs-stream-prefix"
  }

  assert {
    condition     = alltrue([for c in local.all_decoded_containers : length(split("@", c.image)[0]) <= 255])
    error_message = "an image reference (without its digest) is at most 255 characters"
  }
}

run "task_shape" {
  assert {
    condition = (
      startswith(aws_ecs_task_definition.main.family, "saiman-demo") && startswith(aws_ecs_task_definition.init.family, "saiman-demo")
      && aws_ecs_task_definition.main.family != aws_ecs_task_definition.init.family
      && aws_ecs_service.main.name == local.service_name && aws_ecs_cluster.main.name == "saiman-demo"
    )
    error_message = "both families must start with saiman-demo and differ; service and cluster names are fixed"
  }

  assert {
    condition = alltrue([
      for td in [aws_ecs_task_definition.main, aws_ecs_task_definition.init] :
      td.runtime_platform[0].cpu_architecture == "ARM64" && td.runtime_platform[0].operating_system_family == "LINUX" && td.network_mode == "awsvpc" && contains(td.requires_compatibilities, "FARGATE")
    ])
    error_message = "both tasks must be Fargate ARM64 Linux awsvpc"
  }

  assert {
    condition = alltrue([
      for td in [aws_ecs_task_definition.main, aws_ecs_task_definition.init] :
      td.execution_role_arn == "arn:aws:iam::123456789012:role/saiman/demo/saiman-demo-task-execution" && td.task_role_arn == "arn:aws:iam::123456789012:role/saiman/demo/saiman-demo-task"
    ])
    error_message = "roles must be the fixed bootstrap role ARNs"
  }

  assert {
    condition     = aws_ecs_service.main.enable_execute_command == true && aws_ecs_service.main.force_delete == true && aws_ecs_service.main.wait_for_steady_state == false && aws_ecs_service.main.desired_count == 0
    error_message = "the service needs ECS Exec, force_delete, no steady-state wait and starts at zero tasks (demo-up scales it after the init task)"
  }

  assert {
    condition     = aws_ecs_service.main.task_definition == aws_ecs_task_definition.main.arn
    error_message = "the service runs the stack task definition, never the init one"
  }

  assert {
    condition     = aws_ecs_service.main.network_configuration[0].assign_public_ip == true && length(aws_ecs_service.main.network_configuration[0].subnets) == 2
    error_message = "the task needs a public IP in the two public subnets"
  }

  assert {
    condition = (
      sum([for c in local.decoded_tasks.main : c.memory]) <= aws_ecs_task_definition.main.memory
      && sum([for c in local.decoded_tasks.init : c.memory]) <= aws_ecs_task_definition.init.memory
    )
    error_message = "container memory limits must fit each task's memory"
  }

  assert {
    condition     = alltrue([for c in local.all_decoded_containers : c.logConfiguration.logDriver == "awslogs" && c.logConfiguration.options["awslogs-group"] == "/saiman-demo-lite" && c.logConfiguration.options["awslogs-stream-prefix"] == c.name])
    error_message = "every container logs to the demo log group with its own stream prefix"
  }

  assert {
    condition     = output.init_task_definition_arn == aws_ecs_task_definition.init.arn
    error_message = "demo-up reads the init task definition from this output"
  }
}

run "ecs_rules_the_mock_provider_does_not_apply" {
  # ECS rejects a task definition with more than 10 containers ("Too many containers") when it is registered; a mock
  # provider never does. Counted statically on exactly what ECS would receive, per task definition.
  assert {
    condition     = length(jsondecode(aws_ecs_task_definition.main.container_definitions)) <= 10
    error_message = "the stack task definition has more than 10 containers (ECS limit)"
  }

  assert {
    condition     = length(jsondecode(aws_ecs_task_definition.init.container_definitions)) <= 10
    error_message = "the init task definition has more than 10 containers (ECS limit)"
  }

  assert {
    condition     = local.max_containers_per_task == 10
    error_message = "the guard constant must stay the real ECS limit"
  }

  # Every task definition needs at least one essential container.
  assert {
    condition     = length([for c in local.decoded_tasks.main : c if c.essential]) >= 1 && length([for c in local.decoded_tasks.init : c if c.essential]) >= 1
    error_message = "each task definition needs an essential container (the init task ends with corpus-restore)"
  }

  # dependsOn cannot cross task definitions: every target must be a container of the same one.
  assert {
    condition = alltrue([
      for cs in values(local.decoded_tasks) :
      alltrue([for c in cs : alltrue([for d in c.dependsOn : contains([for x in cs : x.name], d.containerName)])])
    ])
    error_message = "a dependsOn target must be in the same task definition"
  }

  # SUCCESS/COMPLETE cannot be set on an essential container.
  assert {
    condition = alltrue([
      for cs in values(local.decoded_tasks) :
      alltrue([for c in cs : alltrue([for d in c.dependsOn : d.condition == "HEALTHY" || !try([for x in cs : x.essential if x.name == d.containerName][0], false)])])
    ])
    error_message = "a SUCCESS dependency must not point at an essential container"
  }

  # The init task ends only when every other container succeeded: the essential corpus-restore starts last.
  assert {
    condition     = jsonencode(sort([for d in [for c in local.decoded_tasks.init : c if c.name == "corpus-restore"][0].dependsOn : d.containerName])) == jsonencode(sort([for c in local.decoded_tasks.init : c.name if c.name != "corpus-restore"]))
    error_message = "corpus-restore (the essential last init container) must wait for every other init container"
  }
}

run "work_is_split_as_designed" {
  # Master and owner secrets live only in the init task; apps, kafka, redis and the collector only in the stack.
  assert {
    condition     = length([for c in local.decoded_tasks.init : c if length(c.name) > 0 && contains(["orchestrator", "seller-api", "ledger", "ingest", "kafka", "redis", "web", "readiness", "otel-collector"], c.name)]) == 0
    error_message = "no long-running container may be in the init task"
  }

  assert {
    condition     = length([for c in local.decoded_tasks.main : c if contains(["db-init", "corpus-restore"], c.name) || endswith(c.name, "-migrate")]) == 0
    error_message = "no migrator, db-init or corpus-restore in the long-running task"
  }

  # No secret of the init task (master, owner, PW_*) reaches the long-running task.
  assert {
    condition = alltrue([
      for c in local.decoded_tasks.main :
      alltrue([for s in c.secrets : !can(regex("pg_master_password|_owner_password$", s.valueFrom))])
    ])
    error_message = "the long-running task must not see the master or owner passwords"
  }

  # The apps no longer wait for a migrator or the corpus restore: the service only starts after the init task.
  assert {
    condition = alltrue([
      for c in local.decoded_tasks.main :
      jsonencode(sort([for d in c.dependsOn : d.containerName])) == jsonencode(["kafka", "redis"])
      if contains(["orchestrator", "seller-api", "ledger", "ingest"], c.name)
    ])
    error_message = "apps depend on kafka and redis only"
  }
}

run "exact_secret_map" {
  # EXACT map per container and task: secret env name -> SSM parameter name. Anything extra or missing fails.
  assert {
    condition = jsonencode({
      for t, cs in local.decoded_tasks : t => {
        for c in cs : c.name => { for s in c.secrets : s.name => trimprefix(s.valueFrom, "arn:aws:ssm:eu-central-1:123456789012:parameter/saiman/demo/") }
      }
      }) == jsonencode({
      init = {
        assets                 = {}
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
      }
      main = {
        assets           = {}
        kafka            = {}
        web              = {}
        readiness        = {}
        "otel-collector" = {}
        redis            = { REDIS_PASSWORD = "redis_password" }
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
      }
    })
    error_message = "the secret split must match the exact per-container map of each task (master and owner passwords only in init; buyer key, OpenAI key and Redis password only where listed)"
  }
}

run "exact_env_names" {
  # EXACT environment-name allowlist per container and task (migrators: exactly the four of ADR-0027).
  assert {
    condition = jsonencode({
      for t, cs in local.decoded_tasks : t => { for c in cs : c.name => sort([for e in c.environment : e.name]) }
      }) == jsonencode({
      init = {
        assets                 = ["AWS_DEFAULT_REGION", "AWS_PAGER"]
        "db-init"              = ["BOOTSTRAP_RDS", "BOOTSTRAP_SECRET_SOURCE", "PGDATABASE", "PGHOST", "PGSSLMODE", "PGUSER"]
        "corpus-restore"       = ["PGDATABASE", "PGHOST", "PGSSLMODE", "PGUSER"]
        "orchestrator-migrate" = ["BPL_JVM_THREAD_COUNT", "SAIMAN_RUN_MODE", "SPRING_DATASOURCE_URL", "SPRING_FLYWAY_USER"]
        "seller-api-migrate"   = ["BPL_JVM_THREAD_COUNT", "SAIMAN_RUN_MODE", "SPRING_DATASOURCE_URL", "SPRING_FLYWAY_USER"]
        "ledger-migrate"       = ["BPL_JVM_THREAD_COUNT", "SAIMAN_RUN_MODE", "SPRING_DATASOURCE_URL", "SPRING_FLYWAY_USER"]
        "ingest-migrate"       = ["BPL_JVM_THREAD_COUNT", "SAIMAN_RUN_MODE", "SPRING_DATASOURCE_URL", "SPRING_FLYWAY_USER"]
      }
      main = {
        assets           = ["AWS_DEFAULT_REGION", "AWS_PAGER"]
        web              = []
        readiness        = []
        redis            = []
        "otel-collector" = []
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

      }
    })
    error_message = "the environment names must match the exact per-container allowlist of each task"
  }
}

run "no_injection_or_secret_like_names" {
  # Port of the compose-policy normalisation: upper case, "." and "-" to "_", runs of "_" collapsed,
  # a trailing list index ("_0", "_0_") stripped. Applied to environment AND secret names.
  assert {
    condition = alltrue([
      for c in local.all_decoded_containers :
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
      for c in local.all_decoded_containers :
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
      for c in local.all_decoded_containers :
      alltrue([for e in c.environment : !can(regex("0x[0-9a-fA-F]{64}", e.value)) && !can(regex("://[^/@]*:[^/@]*@", e.value))])
    ])
    error_message = "no private key or credentialed URL in an environment value"
  }

  # Every secret is an SSM ARN under /saiman/demo/ (the only prefix the execution role may read).
  assert {
    condition = alltrue([
      for c in local.all_decoded_containers :
      alltrue([for s in c.secrets : startswith(s.valueFrom, "arn:aws:ssm:eu-central-1:123456789012:parameter/saiman/demo/")])
    ])
    error_message = "secrets must be SSM parameter ARNs under /saiman/demo/"
  }
}

run "no_namespace_sharing_or_privileges" {
  assert {
    condition = alltrue([
      for td in [aws_ecs_task_definition.main, aws_ecs_task_definition.init] : td.pid_mode == null && td.ipc_mode == null
    ])
    error_message = "pid_mode and ipc_mode must stay unset (no shared PID/IPC namespaces)"
  }

  assert {
    condition = alltrue([
      for c in local.all_decoded_containers :
      !can(c.linuxParameters) && !can(c.privileged) && !can(c.user) && !can(c.dockerSecurityOptions) && !can(c.environmentFiles)
    ])
    error_message = "no linuxParameters (capabilities, devices), privileged, user, security options or environment files"
  }

  assert {
    condition     = jsonencode(sort([for c in local.decoded_tasks.init : c.name])) == jsonencode(sort(["assets", "db-init", "orchestrator-migrate", "seller-api-migrate", "ledger-migrate", "ingest-migrate", "corpus-restore"]))
    error_message = "the init container set is fixed"
  }

  assert {
    condition     = jsonencode(sort([for c in local.decoded_tasks.main : c.name])) == jsonencode(sort(["assets", "kafka", "redis", "otel-collector", "web", "readiness", "orchestrator", "seller-api", "ledger", "ingest"]))
    error_message = "the stack container set is fixed"
  }

  assert {
    condition     = aws_ecs_cluster.main.configuration[0].execute_command_configuration[0].logging == "NONE"
    error_message = "ECS Exec logging must be NONE (not OVERRIDE)"
  }
}

run "integrity_checks_in_assets_containers" {
  # Stack: the manifest digest is verified before anything is copied into a served volume; no corpus handling.
  assert {
    condition = (
      can(regex("sha256sum", [for c in local.decoded_tasks.main : c.command[0] if c.name == "assets"][0]))
      && can(regex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", [for c in local.decoded_tasks.main : c.command[0] if c.name == "assets"][0]))
      && !can(regex("fedcba9876543210", [for c in local.decoded_tasks.main : c.command[0] if c.name == "assets"][0]))
      && length(regexall("digest mismatch", [for c in local.decoded_tasks.main : c.command[0] if c.name == "assets"][0])) == 1
      && length(split("cp -R", [for c in local.decoded_tasks.main : c.command[0] if c.name == "assets"][0])[0]) > length(split("manifest digest mismatch", [for c in local.decoded_tasks.main : c.command[0] if c.name == "assets"][0])[0])
    )
    error_message = "the stack assets container must verify the manifest digest before any copy, and must not fetch the corpus"
  }

  # Init: manifest AND corpus digests are verified; the corpus is fetched only after the manifest check; no web copy.
  assert {
    condition = (
      can(regex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", [for c in local.decoded_tasks.init : c.command[0] if c.name == "assets"][0]))
      && can(regex("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", [for c in local.decoded_tasks.init : c.command[0] if c.name == "assets"][0]))
      && length(regexall("digest mismatch", [for c in local.decoded_tasks.init : c.command[0] if c.name == "assets"][0])) == 2
      && length(split("ingest-corpus.dump", [for c in local.decoded_tasks.init : c.command[0] if c.name == "assets"][0])[0]) > length(split("manifest digest mismatch", [for c in local.decoded_tasks.init : c.command[0] if c.name == "assets"][0])[0])
      && !can(regex("cp -R", [for c in local.decoded_tasks.init : c.command[0] if c.name == "assets"][0]))
    )
    error_message = "the init assets container must verify the manifest and corpus digests (manifest first) and copy nothing into a served volume"
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
      for c in local.all_decoded_containers :
      [for e in c.environment : e.value if e.name == "SPRING_FLYWAY_ENABLED"] == ["false"]
      if contains(["orchestrator", "seller-api", "ledger", "ingest"], c.name)
    ])
    error_message = "SPRING_FLYWAY_ENABLED=false on every app"
  }

  assert {
    condition = alltrue([
      for c in local.all_decoded_containers :
      length([for e in c.environment : e if e.name == "SAIMAN_RUN_MODE" && e.value == "migrate"]) == (endswith(c.name, "-migrate") ? 1 : 0) && length([for e in c.environment : e if e.name == "SAIMAN_RUN_MODE"]) == (endswith(c.name, "-migrate") ? 1 : 0)
    ])
    error_message = "SAIMAN_RUN_MODE=migrate exactly on the four migrators"
  }

  assert {
    condition = alltrue([
      for c in local.all_decoded_containers :
      length([for e in c.environment : e if can(regex("^(SPRING_FLYWAY_URL|SPRING_APPLICATION_JSON|JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|JAVA_OPTS|SPRING_AUTOCONFIGURE_EXCLUDE)$|^SPRING_(CONFIG|MAIN)_", e.name))]) == 0
    ])
    error_message = "config-injection environment keys are forbidden"
  }

  assert {
    condition = alltrue([
      for c in local.decoded_tasks.init :
      [for e in c.environment : e.value if e.name == "SPRING_DATASOURCE_URL"] == [for a in local.decoded_tasks.main : [for e2 in a.environment : e2.value if e2.name == "SPRING_DATASOURCE_URL"][0] if a.name == trimsuffix(c.name, "-migrate")]
      if endswith(c.name, "-migrate")
    ])
    error_message = "a migrator (init task) uses the same JDBC URL as its app (stack task)"
  }

  assert {
    condition = alltrue([
      for c in local.all_decoded_containers :
      alltrue([for e in c.environment : startswith(e.value, "jdbc:postgresql://") && endswith(e.value, ":5432/saiman?sslmode=require") if e.name == "SPRING_DATASOURCE_URL"])
    ])
    error_message = "JDBC URLs must be plain RDS URLs with sslmode=require and no credentials"
  }

  assert {
    condition = alltrue([
      for c in local.all_decoded_containers :
      [for e in c.environment : e.value if e.name == "SAIMAN_CHAIN_RPC_URL"] == ["https://sepolia.base.org"]
      if contains(["orchestrator", "ledger"], c.name)
    ])
    error_message = "chain RPC is the public Base Sepolia endpoint"
  }
}

run "ordering_and_images" {
  # Within each task: every dependency on a non-essential container is SUCCESS; every HEALTHY target is essential and
  # has a health check.
  assert {
    condition = alltrue([
      for cs in values(local.decoded_tasks) :
      alltrue([
        for c in cs : alltrue([
          for d in c.dependsOn :
          d.condition == ([for t in cs : t.essential if t.name == d.containerName][0] ? "HEALTHY" : "SUCCESS")
        ])
      ])
    ])
    error_message = "dependencies on one-shots must be SUCCESS, on essential kafka/redis HEALTHY"
  }

  assert {
    condition = alltrue([
      for cs in values(local.decoded_tasks) :
      alltrue([for c in cs : alltrue([for d in c.dependsOn : d.condition != "HEALTHY" || [for t in cs : can(t.healthCheck.command) if t.name == d.containerName][0]])])
    ])
    error_message = "HEALTHY dependencies need a health check"
  }

  assert {
    condition = alltrue([
      for c in local.decoded_tasks.init :
      contains([for d in c.dependsOn : d.containerName], "db-init")
      if endswith(c.name, "-migrate")
    ]) && contains([for d in [for c in local.decoded_tasks.init : c if c.name == "corpus-restore"][0].dependsOn : d.containerName], "ingest-migrate")
    error_message = "migrators wait for db-init; corpus-restore waits for ingest-migrate"
  }

  assert {
    condition = alltrue([
      for c in local.all_decoded_containers :
      startswith(c.image, "ghcr.io/orhanyarkin/saiman-") ? can(regex("^ghcr.io/orhanyarkin/saiman-[a-z-]+@sha256:[0-9a-f]{64}$", c.image)) : startswith(c.image, "public.ecr.aws/") ? can(regex(":[0-9][^:@]*(@sha256:[0-9a-f]{64})?$", c.image)) : can(regex("^[a-z0-9./-]+:[0-9][0-9.]*@sha256:[0-9a-f]{64}$", c.image))
    ])
    error_message = "service images are pinned by @sha256 digest, mirror images a version tag, Docker Hub images tag@sha256 digests"
  }

  assert {
    condition = (
      startswith([for c in local.decoded_tasks.main : c.image if c.name == "kafka"][0], "apache/kafka:4.3.1@sha256:")
      && startswith([for c in local.decoded_tasks.main : c.image if c.name == "otel-collector"][0], "otel/opentelemetry-collector:0.162.0@sha256:")
      && startswith([for c in local.decoded_tasks.init : c.image if c.name == "db-init"][0], "public.ecr.aws/docker/library/postgres:17")
    )
    error_message = "third-party pins must match compose (db-init needs a bash-capable postgres 17 image)"
  }

  assert {
    condition = (
      alltrue([for c in local.all_decoded_containers : c.essential == false if contains(["assets", "db-init", "readiness", "otel-collector"], c.name) || endswith(c.name, "-migrate")])
      && alltrue([for c in local.decoded_tasks.main : c.essential == true if contains(["kafka", "redis", "web", "orchestrator", "seller-api", "ledger", "ingest"], c.name)])
      && [for c in local.decoded_tasks.init : c.essential if c.name == "corpus-restore"][0] == true
    )
    error_message = "one-shots, readiness and the collector are non-essential; the long-running services and the init corpus-restore container are essential"
  }
}

run "redis_bounded_and_not_evicting" {
  command = plan

  assert {
    condition = alltrue([
      for c in local.decoded_tasks.main :
      c.name != "redis" || (strcontains(join(" ", c.command), "--maxmemory 96mb") && strcontains(join(" ", c.command), "--maxmemory-policy noeviction"))
    ])
    error_message = "redis must be capped at 96mb with noeviction (a full Redis must reject writes, never drop nonce claims or day-cap counters)"
  }
}

run "otel_collector_config" {
  assert {
    condition     = [for c in local.decoded_tasks.main : c.command if c.name == "otel-collector"][0] == ["--config=/assets/otel/config.yaml"]
    error_message = "without a Grafana endpoint only the base collector config is used"
  }

  assert {
    condition     = length([for c in local.decoded_tasks.main : c if c.name == "otel-collector" && length(c.secrets) == 0 && length(c.environment) == 0]) == 1
    error_message = "no Grafana env or secret when the endpoint is unset"
  }
}

run "otel_collector_grafana" {
  variables {
    grafana_otlp_endpoint = "https://otlp-gateway-prod-eu-west-0.grafana.net/otlp"
  }

  assert {
    condition     = [for c in local.decoded_tasks.main : c.command if c.name == "otel-collector"][0] == ["--config=/assets/otel/config.yaml", "--config=/assets/otel/grafana.yaml"]
    error_message = "the Grafana overlay is added when the endpoint is set"
  }

  assert {
    condition = (
      [for c in local.decoded_tasks.main : [for s in c.secrets : s.name] if c.name == "otel-collector"][0] == ["GRAFANA_OTLP_AUTH"]
      && [for c in local.decoded_tasks.main : [for e in c.environment : e.name] if c.name == "otel-collector"][0] == ["GRAFANA_OTLP_ENDPOINT"]
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
