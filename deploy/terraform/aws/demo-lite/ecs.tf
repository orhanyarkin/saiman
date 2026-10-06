# One Fargate task (ARM64) that runs every container on localhost (ADR-0028), behind one ECS service.
# The task and execution roles are the fixed ones from the bootstrap stack (locals.tf): this module
# declares no IAM resources. The container list lives in containers.tf.

resource "aws_ecs_cluster" "main" {
  name = local.cluster_name

  setting {
    name  = "containerInsights"
    value = "disabled" # Insights metrics cost money and the demo ships its own OTel telemetry
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

  # Task-scoped (ephemeral) volumes shared by the init containers and the long-running ones.
  dynamic "volume" {
    for_each = local.volume_names
    content {
      name = volume.value
    }
  }

  # Secrets appear only as secrets[].valueFrom (an SSM ARN string), never as environment values, so
  # nothing sensitive is in this JSON or in state. tests/task.tftest.hcl and tests/check-task.sh pin it.
  container_definitions = jsonencode(local.container_definitions)

  lifecycle {
    precondition {
      condition     = sum([for c in local.container_definitions : c.memory]) <= var.task_memory
      error_message = "The hard memory limits of all containers must not exceed task_memory."
    }
  }
}

resource "aws_ecs_service" "main" {
  name            = local.service_name
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.main.arn
  desired_count   = 1
  launch_type     = "FARGATE"

  # The task is the whole stack: never run two at once (two would double the RDS connections and
  # the paid-call budget), so a deployment replaces it in place.
  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  network_configuration {
    subnets          = aws_subnet.public[*].id
    security_groups  = [aws_security_group.task.id]
    assign_public_ip = true # egress only: there is no NAT, and the SG has no ingress rule
  }

  # The human reaches the stack with `aws ecs execute-command` / an SSM port-forward (no ALB).
  enable_execute_command = true

  # Terraform's DeleteService with force makes teardown work for the destroy role, which can delete
  # but not UpdateService (scale to zero is the Scheduler role's job).
  force_delete = true

  # demo-up waits for the readiness container itself; a slow first pull must not fail the apply.
  wait_for_steady_state = false
}
