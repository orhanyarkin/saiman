# Two Fargate task definitions (ARM64, ADR-0028): a long-running `stack` (every service on localhost, behind
# one ECS service) and a run-to-completion `init` (roles, migrations, corpus restore). ECS allows at most 10
# containers per task definition, which is why the work is split; containers.tf explains the split.
# The task and execution roles are the fixed ones from the bootstrap stack (locals.tf): this module
# declares no IAM resources. The container lists live in containers.tf.

locals {
  # Hard ECS limit; a task definition above it is rejected at RegisterTaskDefinition ("Too many containers").
  max_containers_per_task = 10

  # The init task is short-lived (a few minutes) and runs four JVM migrators in parallel next to psql/aws-cli containers.
  # A dependent waits at most 120 s (Fargate startTimeout limit) for each of them, so give the JVMs two vCPUs.
  init_task_cpu    = 2048
  init_task_memory = 4096
}

resource "aws_ecs_cluster" "main" {
  name = local.cluster_name

  setting {
    name  = "containerInsights"
    value = "disabled" # Insights metrics cost money and the demo ships its own OTel telemetry
  }

  # ECS Exec sessions are not logged to CloudWatch/S3 (never OVERRIDE: that would need extra
  # permissions on the task role); CloudTrail records every ExecuteCommand/StartSession call.
  configuration {
    execute_command_configuration {
      logging = "NONE"
    }
  }
}

resource "aws_ecs_task_definition" "main" {
  family                   = "${local.name_prefix}-lite"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.task_cpu
  memory                   = var.task_memory
  execution_role_arn       = local.task_execution_role_arn
  task_role_arn            = local.task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  # Task-scoped (ephemeral) volumes shared by the containers of this task.
  dynamic "volume" {
    for_each = local.volume_names_stack
    content {
      name = volume.value
    }
  }

  # Secrets appear only as secrets[].valueFrom (an SSM ARN string), never as environment values, so
  # nothing sensitive is in this JSON or in state. tests/task.tftest.hcl and tests/check-task.sh pin it.
  container_definitions = local.container_json.main

  lifecycle {
    precondition {
      condition     = sum([for c in local.container_definitions_main : c.memory]) <= var.task_memory
      error_message = "The hard memory limits of all containers must not exceed task_memory."
    }

    precondition {
      condition     = length(local.container_definitions_main) <= local.max_containers_per_task
      error_message = "ECS allows at most 10 containers per task definition; move work to the init task."
    }
  }
}

# Run once per session by demo-up (`aws ecs run-task`, ops/run-init-task.sh) before the service is started. It
# holds the RDS master password and the schema owner passwords, which the long-running task never sees.
resource "aws_ecs_task_definition" "init" {
  family                   = "${local.name_prefix}-init"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = local.init_task_cpu
  memory                   = local.init_task_memory
  execution_role_arn       = local.task_execution_role_arn
  task_role_arn            = local.task_role_arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  dynamic "volume" {
    for_each = local.volume_names_init
    content {
      name = volume.value
    }
  }

  container_definitions = local.container_json.init

  lifecycle {
    precondition {
      condition     = sum([for c in local.container_definitions_init : c.memory]) <= local.init_task_memory
      error_message = "The hard memory limits of all init containers must not exceed the init task memory."
    }

    precondition {
      condition     = length(local.container_definitions_init) <= local.max_containers_per_task
      error_message = "ECS allows at most 10 containers per task definition."
    }
  }
}

resource "aws_ecs_service" "main" {
  name            = local.service_name
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.main.arn
  # Created at 0: demo-up runs the init task first and scales to 1 only when every init container exited 0
  # (the replacement of the old `<svc>-migrate` dependsOn, which cannot cross task definitions). The Scheduler
  # backstop also sets 0, so Terraform must not fight the count.
  desired_count = 0
  launch_type   = "FARGATE"

  # The task is the whole stack: never run two at once (two would double the RDS connections and
  # the paid-call budget), so a deployment replaces it in place.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.task.id]
    assign_public_ip = true # egress only: there is no NAT, and the SG has no ingress rule
  }

  # The human reaches the stack with `aws ecs execute-command` / an SSM port-forward (no ALB). Port 80 of `web`
  # is the convention, not a control: a port-forward reaches every loopback port of the task.
  enable_execute_command = true

  # Terraform's DeleteService with force makes teardown work for the destroy role, which can delete
  # but not UpdateService (scale to zero is the Scheduler role's job).
  force_delete = true

  # demo-up waits for the readiness container itself; a slow first pull must not fail the apply.
  wait_for_steady_state = false

  lifecycle {
    ignore_changes = [desired_count]
  }
}

locals {
  # The container definitions as registered: built once, so the task definitions send exactly these strings and every
  # check below decodes them. NEVER read aws_ecs_task_definition.*.container_definitions in a local, output or condition:
  # after apply the provider holds what the ECS API returned, which omits empty lists such as `dependsOn` (a mock
  # provider just echoes the configuration, so tests cannot see the difference). tests/check-task.sh enforces this.
  container_json = {
    main = jsonencode(local.container_definitions_main)
    init = jsonencode(local.container_definitions_init)
  }

  # What ECS receives, decoded again, and (`*_as_returned`) the same containers the way the provider reads them back:
  # every null and empty value dropped, a superset of what ECS really omits. tests/task.tftest.hcl runs every rule over
  # all four, so a rule that assumes an optional key exists fails offline. Only the tests read these.
  decoded_registered = { for t, j in local.container_json : t => jsondecode(j) }
  decoded_tasks = merge(
    local.decoded_registered,
    { for t, cs in local.decoded_registered : "${t}_as_returned" => [for c in cs : { for k, v in c : k => v if v != null && try(length(v) > 0, true) }] },
  )
  all_decoded_containers = flatten(values(local.decoded_tasks))

  # Environment and secret names of the four app containers in the spelling Spring's relaxed binding sees (lower case, no
  # "." "_" "-"). tests/task.tftest.hcl compares them with docker-compose (run ecs_names_match_compose). Only the tests read this.
  app_property_names = {
    for c in local.decoded_registered.main : c.name => [
      for n in concat([for e in try(c.environment, []) : e.name], [for s in try(c.secrets, []) : s.name]) :
      lower(replace(replace(replace(n, ".", ""), "_", ""), "-", ""))
    ] if contains(keys(local.apps), c.name)
  }
}

locals {
  # Container dependency graph of each task definition, closed under reachability by path doubling (paths of up to 16
  # edges after four rounds, far above the 10 containers a task may have). tests/task.tftest.hcl asserts that no
  # container reaches itself: ECS refuses circular container dependencies.
  dep_graph  = { for t, cs in local.decoded_tasks : t => { for c in cs : c.name => [for d in try(c.dependsOn, []) : d.containerName] } }
  dep_reach1 = { for t, g in local.dep_graph : t => { for n, ds in g : n => distinct(flatten([ds, [for d in ds : try(g[d], [])]])) } }
  dep_reach2 = { for t, g in local.dep_reach1 : t => { for n, ds in g : n => distinct(flatten([ds, [for d in ds : try(g[d], [])]])) } }
  dep_reach3 = { for t, g in local.dep_reach2 : t => { for n, ds in g : n => distinct(flatten([ds, [for d in ds : try(g[d], [])]])) } }
  dep_reach4 = { for t, g in local.dep_reach3 : t => { for n, ds in g : n => distinct(flatten([ds, [for d in ds : try(g[d], [])]])) } }
}
