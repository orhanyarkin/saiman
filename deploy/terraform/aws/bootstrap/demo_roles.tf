# The three roles the demo workload runs as. They are FIXED here, in the human-applied bootstrap stack,
# so the CI apply role never needs iam:CreateRole / PutRolePolicy / AttachRolePolicy / UpdateAssumeRolePolicy
# (those would let a compromised apply run mint or re-trust an arbitrary role). demo-lite only references
# these roles by ARN and passes them to ECS and Scheduler (iam:PassRole is the apply role's only IAM write).
#
# All three live under /saiman/demo/, carry saiman-demo-boundary and trust service principals only
# (+ aws:SourceAccount against the confused deputy). Policies are inline and wildcard-free in actions.
#
# What the boundary does and does not do: it bounds what these roles can DO. It does not bound WHO can
# use them: an approved apply can still deploy a workload that runs as one of them and reads
# /saiman/demo/* (inherent to running the demo; those hold testnet keys and a capped OpenAI key only).
#
# Single-task design (ADR-0028) weakens the ADR-0009 key separation: every container shares the task
# role. Hence the TASK role has no ssm permissions at all; only the EXECUTION role (the ECS agent,
# which injects ECS `secrets[].valueFrom`) can read /saiman/demo/*.

locals {
  demo_role_path   = "/saiman/demo/"
  demo_name_prefix = "saiman-demo"

  # Fixed names of what demo-lite creates (keep in sync with demo-lite/locals.tf).
  demo_service_arn   = "arn:aws:ecs:${var.region}:${local.account_id}:service/${local.demo_name_prefix}/${local.demo_name_prefix}-lite"
  demo_rds_arn       = "arn:aws:rds:${var.region}:${local.account_id}:db:${local.demo_name_prefix}"
  demo_log_group_arn = "arn:aws:logs:${var.region}:${local.account_id}:log-group:/${local.demo_name_prefix}-lite"

  # Read scope of the task role (and the boundary ceiling): the corpus dump and the per-session assets only.
  demo_s3_read_arns = ["${local.bucket_arn}/artifacts/corpus/*", "${local.bucket_arn}/demo/assets/*"]

  demo_trust = { for svc in ["ecs-tasks", "scheduler"] : svc => jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "${svc}.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
      }
    }]
  }) }
}

# --- ECS task execution role: injects secrets and writes logs on behalf of the agent -------------

resource "aws_iam_role" "demo_task_execution" {
  name                 = "${local.demo_name_prefix}-task-execution"
  path                 = local.demo_role_path
  description          = "ECS agent: inject SSM SecureStrings (secrets[].valueFrom), write the demo log group"
  permissions_boundary = local.boundary_arn
  depends_on           = [aws_iam_policy.demo_boundary]
  assume_role_policy   = local.demo_trust["ecs-tasks"]
}

resource "aws_iam_role_policy" "demo_task_execution" {
  name = "execution"
  role = aws_iam_role.demo_task_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadDemoParameters"
        Effect   = "Allow"
        Action   = ["ssm:GetParameters"]
        Resource = local.ssm_demo_arn
      },
      {
        # SecureStrings use the AWS-managed aws/ssm key; decrypt is only allowed through SSM.
        Sid      = "DecryptViaSsm"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = "*"
        Condition = {
          StringEquals = { "kms:ViaService" = "ssm.${var.region}.amazonaws.com" }
        }
      },
      {
        Sid      = "WriteDemoLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${local.demo_log_group_arn}:*"
      },
    ]
  })
}

# --- ECS task role: what the running containers may do (no SSM, no secrets) -----------------------

resource "aws_iam_role" "demo_task" {
  name                 = "${local.demo_name_prefix}-task"
  path                 = local.demo_role_path
  description          = "Demo containers: read the corpus dump and demo artifacts, ECS Exec"
  permissions_boundary = local.boundary_arn
  depends_on           = [aws_iam_policy.demo_boundary]
  assume_role_policy   = local.demo_trust["ecs-tasks"]
}

resource "aws_iam_role_policy" "demo_task" {
  name = "task"
  role = aws_iam_role.demo_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Read-only: the task cannot write to artifacts/.
        Sid      = "ReadArtifactsAndDemoExports"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = local.demo_s3_read_arns
      },
      {
        # ssmmessages has no resource-level permissions. Needed for ECS Exec and the SSM
        # port-forward the human uses to reach the web container.
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

# --- EventBridge Scheduler role: the expiry backstop ---------------------------------------------

resource "aws_iam_role" "demo_scheduler" {
  name                 = "${local.demo_name_prefix}-scheduler"
  path                 = local.demo_role_path
  description          = "Scheduler backstop: scale the demo service to zero and stop the demo RDS instance"
  permissions_boundary = local.boundary_arn
  depends_on           = [aws_iam_policy.demo_boundary]
  assume_role_policy   = local.demo_trust["scheduler"]
}

resource "aws_iam_role_policy" "demo_scheduler" {
  name = "scheduler"
  role = aws_iam_role.demo_scheduler.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ScaleDemoServiceToZero"
        Effect   = "Allow"
        Action   = ["ecs:UpdateService"]
        Resource = local.demo_service_arn
      },
      {
        Sid      = "StopDemoDatabase"
        Effect   = "Allow"
        Action   = ["rds:StopDBInstance"]
        Resource = local.demo_rds_arn
      },
    ]
  })
}
