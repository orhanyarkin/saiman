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
  db_master_password = "unit-test-only"
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

run "secret_split" {
  # owner secrets only on *-migrate (and db-init, which holds every password like compose db-init)
  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      length([for s in c.secrets : s if can(regex("_owner_password$", s.valueFrom))]) == 0
      if !endswith(c.name, "-migrate") && !contains(["db-init", "corpus-restore"], c.name)
    ])
    error_message = "owner passwords may only reach *-migrate, db-init and corpus-restore (ingest_owner)"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      [for s in c.secrets : s.name] == ["SPRING_FLYWAY_PASSWORD"] && length(c.environment) == 4
      if endswith(c.name, "-migrate")
    ])
    error_message = "a migrate one-shot holds exactly SPRING_FLYWAY_PASSWORD and four plain env vars"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      length([for s in c.secrets : s if can(regex("_app_password$", s.valueFrom))]) == 0
      if !contains(["orchestrator", "seller-api", "ledger", "ingest", "db-init"], c.name)
    ])
    error_message = "app passwords only on the four apps (and db-init)"
  }

  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      length([for s in c.secrets : s if can(regex("_app_password$", s.valueFrom)) && s.name == "SPRING_DATASOURCE_PASSWORD"]) == 1
      && length([for s in c.secrets : s if can(regex("_owner_password$", s.valueFrom))]) == 0
      if contains(["orchestrator", "seller-api", "ledger", "ingest"], c.name)
    ])
    error_message = "each app holds exactly one app password and no owner password"
  }

  # The buyer key: orchestrator only, via secrets only.
  assert {
    condition = (
      [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.name if length([for s in c.secrets : s if can(regex("x402_buyer_private_key$", s.valueFrom))]) > 0] == ["orchestrator"]
      && alltrue([for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : length([for e in c.environment : e if can(regex("(?i)private|0x[0-9a-f]{64}", e.name)) || can(regex("(?i)0x[0-9a-f]{64}", e.value))]) == 0])
    )
    error_message = "the buyer private key reaches only the orchestrator and only through secrets"
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

  # Every secret is an SSM ARN under /saiman/demo/ (the only prefix the execution role may read).
  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      alltrue([for s in c.secrets : startswith(s.valueFrom, "arn:aws:ssm:eu-central-1:123456789012:parameter/saiman/demo/")])
    ])
    error_message = "secrets must be SSM parameter ARNs under /saiman/demo/"
  }

  # web and the data stores carry no secrets at all.
  assert {
    condition = alltrue([
      for c in jsondecode(aws_ecs_task_definition.main.container_definitions) :
      length(c.secrets) == 0 && length(c.environment) == 0 || !contains(["web", "redis", "readiness"], c.name)
    ])
    error_message = "web, redis and readiness hold no secrets"
  }
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
      startswith(c.image, "ghcr.io/orhanyarkin/saiman-") ? endswith(c.image, ":sha-0123456789ab") : can(regex(":[0-9][^:@]*$", c.image))
    ])
    error_message = "service images use the sha- tag, third-party images a pinned version"
  }

  assert {
    condition = (
      [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.image if c.name == "kafka"][0] == "apache/kafka:4.3.1"
      && [for c in jsondecode(aws_ecs_task_definition.main.container_definitions) : c.image if c.name == "otel-collector"][0] == "otel/opentelemetry-collector:0.162.0"
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
