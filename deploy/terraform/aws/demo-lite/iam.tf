# Three roles, all under /saiman/demo/ and all carrying the bootstrap permissions boundary (the apply
# role can only create roles that do). Policies are inline (aws_iam_role_policy): the apply role may
# PutRolePolicy but not create managed policies. No wildcard actions anywhere.
#
# The roles do not depend on the ECS cluster; ecs.tf (A3a) only consumes their ARNs.

locals {
  ecs_tasks_trust = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
      }
    }]
  })
}

# --- ECS task execution role: pulls secrets and writes logs on behalf of the agent ----------------
# Images come from public GHCR packages, so there is no registry credential and no ECR access.

resource "aws_iam_role" "task_execution" {
  name                 = "${local.name_prefix}-task-execution"
  path                 = local.role_path
  description          = "ECS agent: inject SSM SecureStrings, write the demo log group"
  permissions_boundary = local.boundary_arn
  assume_role_policy   = local.ecs_tasks_trust
}

resource "aws_iam_role_policy" "task_execution" {
  name = "execution"
  role = aws_iam_role.task_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadDemoParameters"
        Effect   = "Allow"
        Action   = ["ssm:GetParameters"]
        Resource = local.ssm_prefix_arn
      },
      {
        # SecureStrings use the AWS-managed aws/ssm key; decrypt is only allowed through SSM.
        Sid      = "DecryptViaSsm"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = "*"
        Condition = {
          StringEquals = { "kms:ViaService" = "ssm.${local.region}.amazonaws.com" }
        }
      },
      {
        Sid      = "WriteDemoLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${local.log_group_arn}:*"
      },
    ]
  })
}

# --- ECS task role: what the running containers may do -------------------------------------------

resource "aws_iam_role" "task" {
  name                 = "${local.name_prefix}-task"
  path                 = local.role_path
  description          = "Demo containers: read the corpus dump and demo artifacts, ECS Exec"
  permissions_boundary = local.boundary_arn
  assume_role_policy   = local.ecs_tasks_trust
}

resource "aws_iam_role_policy" "task" {
  name = "task"
  role = aws_iam_role.task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadArtifactsAndDemoExports"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = local.s3_read_arns
      },
      {
        # ssmmessages does not support resource-level permissions. Needed for ECS Exec and the
        # SSM port-forward the human uses to reach the web container.
        Sid    = "EcsExec"
        Effect = "Allow"
        Action = [
          "ssmmessages:CreateControlChannel",
          "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel",
          "ssmmessages:OpenDataChannel",
        ]
        Resource = "*"
      },
    ]
  })
}

# --- EventBridge Scheduler role: the expiry backstop (scheduler.tf) -------------------------------

resource "aws_iam_role" "scheduler" {
  name                 = "${local.name_prefix}-scheduler"
  path                 = local.role_path
  description          = "Scheduler backstop: scale the demo service to zero and stop the demo RDS instance"
  permissions_boundary = local.boundary_arn

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "scheduler.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
      }
    }]
  })
}

resource "aws_iam_role_policy" "scheduler" {
  name = "scheduler"
  role = aws_iam_role.scheduler.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ScaleDemoServiceToZero"
        Effect   = "Allow"
        Action   = ["ecs:UpdateService"]
        Resource = local.service_arn
      },
      {
        Sid      = "StopDemoDatabase"
        Effect   = "Allow"
        Action   = ["rds:StopDBInstance"]
        Resource = local.rds_arn
      },
    ]
  })
}
