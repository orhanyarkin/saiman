# Expiry backstop (ADR-0028). The demo-reaper workflow normally destroys an expired demo; if GitHub
# cron is late, these one-time schedules stop the compute 30 minutes after expires_at. They use
# EventBridge Scheduler "universal targets" (direct AWS SDK calls), so there is no Lambda to run or pay for.
#
# Compute only: ECS desiredCount=0 and RDS stop. RDS storage (20 GB gp3) and the VPC remain until
# demo-down destroys the stack, and AWS restarts a stopped RDS instance after 7 days.

resource "aws_scheduler_schedule" "stop_service" {
  name                         = "${local.name_prefix}-stop-service"
  description                  = "Scale the demo ECS service to zero after expiry"
  schedule_expression          = "at(${local.backstop_at})"
  schedule_expression_timezone = "UTC"
  action_after_completion      = "DELETE"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:ecs:updateService"
    role_arn = aws_iam_role.scheduler.arn

    input = jsonencode({
      Cluster      = local.cluster_name
      Service      = local.service_name
      DesiredCount = 0
    })

    retry_policy {
      maximum_retry_attempts = 3
    }
  }
}

resource "aws_scheduler_schedule" "stop_database" {
  name                         = "${local.name_prefix}-stop-database"
  description                  = "Stop the demo RDS instance after expiry"
  schedule_expression          = "at(${local.backstop_at})"
  schedule_expression_timezone = "UTC"
  action_after_completion      = "DELETE"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = "arn:aws:scheduler:::aws-sdk:rds:stopDBInstance"
    role_arn = aws_iam_role.scheduler.arn

    input = jsonencode({
      DbInstanceIdentifier = local.db_name
    })

    retry_policy {
      maximum_retry_attempts = 3
    }
  }
}
