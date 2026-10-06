# GitHub OIDC provider, three CI roles (plan / apply / destroy) and the permissions boundary (ADR-0028).
#
# Shape of the trust model:
#   plan    - read-only, assumable from PRs and main. PR code runs during `terraform plan`, so the role
#             can never read secrets (SSM) or private artifacts (S3), enforced with explicit Denies.
#   apply   - assumable only from the `demo-apply` GitHub environment (human approval). Creates and
#             changes saiman-demo* resources. IAM: iam:PassRole of the fixed demo roles
#             (demo_roles.tf) and nothing else; it cannot create, edit or re-trust any role.
#   destroy - assumable only from `demo-destroy`. Deletes, stops and modifies existing saiman-demo*
#             resources; it has no create/register/run/put action (it can still change what exists).

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://${local.oidc_host}"
  client_id_list = ["sts.amazonaws.com"]
  # thumbprint_list is omitted on purpose: AWS validates GitHub's chain against its trusted CAs.
}

locals {
  repo = var.github_repo

  sub_plan    = ["repo:${local.repo}:pull_request", "repo:${local.repo}:ref:refs/heads/main"]
  sub_apply   = ["repo:${local.repo}:environment:${var.apply_environment}"]
  sub_destroy = ["repo:${local.repo}:environment:${var.destroy_environment}"]

  # Read access Terraform needs to refresh the services demo-lite uses. Deliberately no ssm:Get*.
  read_actions = [
    "ec2:Describe*",
    "ecs:Describe*",
    "ecs:List*",
    "rds:Describe*",
    "rds:ListTagsForResource",
    "logs:Describe*",
    "logs:ListTagsForResource",
    "logs:ListTagsLogGroup",
    "scheduler:Get*",
    "scheduler:List*",
    "events:Describe*",
    "events:List*",
    "ssm:DescribeParameters",
    "ssm:ListTagsForResource",
    "budgets:ViewBudget",
  ]

  iam_demo_role_arn = "arn:aws:iam::${local.account_id}:role/saiman/demo/*"

  stmt_read = {
    Sid      = "ReadDemoLiteServices"
    Effect   = "Allow"
    Action   = local.read_actions
    Resource = "*"
  }

  stmt_iam_read = {
    Sid      = "ReadDemoRoles"
    Effect   = "Allow"
    Action   = ["iam:Get*", "iam:List*"]
    Resource = local.iam_demo_role_arn
  }

  # PR code runs during plan, so secrets and private artifacts stay unreadable for every CI role.
  stmt_deny_ssm_values = {
    Sid      = "DenyReadingSecretValues"
    Effect   = "Deny"
    Action   = ["ssm:GetParameter*"]
    Resource = local.ssm_all_arn
  }

  stmt_deny_private_objects = {
    Sid      = "DenyReadingPrivateObjects"
    Effect   = "Deny"
    Action   = ["s3:GetObject", "s3:GetObjectVersion"]
    Resource = ["${local.bucket_arn}/artifacts/*", "${local.bucket_arn}/demo/*"]
  }

  stmt_state_list = {
    Sid      = "ListDemoLiteState"
    Effect   = "Allow"
    Action   = ["s3:ListBucket"]
    Resource = local.bucket_arn
    Condition = {
      StringLike = { "s3:prefix" = ["demo-lite/*"] }
    }
  }

  # Read/write the state and lock objects; delete only the lock object (never the state itself).
  stmt_state_rw = {
    Sid      = "ReadWriteDemoLiteState"
    Effect   = "Allow"
    Action   = ["s3:GetObject", "s3:PutObject"]
    Resource = local.demo_state_glob
  }

  stmt_lock_delete = {
    Sid      = "DeleteDemoLiteLock"
    Effect   = "Allow"
    Action   = ["s3:DeleteObject"]
    Resource = local.demo_lock_arn
  }
}

# --- plan ----------------------------------------------------------------------------------------

resource "aws_iam_role" "plan" {
  name                 = "saiman-gha-plan"
  path                 = local.role_path
  description          = "terraform plan from GitHub Actions: read-only, no secrets"
  max_session_duration = 3600

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_host}:aud" = "sts.amazonaws.com"
          "${local.oidc_host}:sub" = local.sub_plan
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "plan" {
  name = "plan"
  role = aws_iam_role.plan.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      local.stmt_read,
      local.stmt_iam_read,
      local.stmt_state_list,
      {
        Sid      = "ReadDemoLiteState"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = local.demo_state_arn
      },
      local.stmt_deny_ssm_values,
      local.stmt_deny_private_objects,
      local.region_lock,
    ]
  })
}

# --- apply ---------------------------------------------------------------------------------------

resource "aws_iam_role" "apply" {
  name                 = "saiman-gha-apply"
  path                 = local.role_path
  description          = "demo-up: creates and updates saiman-demo* resources"
  max_session_duration = 3600

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_host}:aud" = "sts.amazonaws.com"
          "${local.oidc_host}:sub" = local.sub_apply
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "apply" {
  name = "apply"
  role = aws_iam_role.apply.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      local.stmt_read,
      local.stmt_iam_read,
      local.stmt_state_list,
      local.stmt_state_rw,
      local.stmt_lock_delete,
      {
        Sid    = "EcsDemoResources"
        Effect = "Allow"
        Action = [
          "ecs:CreateCluster", "ecs:UpdateCluster", "ecs:DeleteCluster",
          "ecs:CreateService", "ecs:UpdateService", "ecs:DeleteService",
          "ecs:DeregisterTaskDefinition", "ecs:DeleteTaskDefinitions",
          "ecs:TagResource", "ecs:UntagResource", "ecs:StopTask"
        ]
        Resource = [
          "arn:aws:ecs:${var.region}:${local.account_id}:cluster/saiman-demo*",
          "arn:aws:ecs:${var.region}:${local.account_id}:service/saiman-demo*/*",
          "arn:aws:ecs:${var.region}:${local.account_id}:task/saiman-demo*/*",
          "arn:aws:ecs:${var.region}:${local.account_id}:task-definition/saiman-demo*:*",
        ]
      },
      {
        # RegisterTaskDefinition does not support resource-level permissions.
        Sid      = "EcsRegisterTaskDefinition"
        Effect   = "Allow"
        Action   = ["ecs:RegisterTaskDefinition"]
        Resource = "*"
      },
      {
        Sid    = "RdsDemoResources"
        Effect = "Allow"
        Action = [
          "rds:CreateDBInstance", "rds:ModifyDBInstance", "rds:DeleteDBInstance",
          "rds:StartDBInstance", "rds:StopDBInstance", "rds:RebootDBInstance",
          "rds:CreateDBSubnetGroup", "rds:ModifyDBSubnetGroup", "rds:DeleteDBSubnetGroup",
          "rds:CreateDBParameterGroup", "rds:ModifyDBParameterGroup", "rds:DeleteDBParameterGroup",
          "rds:AddTagsToResource", "rds:RemoveTagsFromResource",
        ]
        Resource = [
          "arn:aws:rds:${var.region}:${local.account_id}:db:saiman-demo*",
          "arn:aws:rds:${var.region}:${local.account_id}:subgrp:saiman-demo*",
          "arn:aws:rds:${var.region}:${local.account_id}:pg:saiman-demo*",
          "arn:aws:rds:${var.region}:${local.account_id}:og:default:*",
          "arn:aws:rds:${var.region}:${local.account_id}:secgrp:*",
        ]
      },
      {
        # EC2 networking has no name-based resource scoping; the region lock is the guard rail.
        Sid    = "Ec2NetworkingDemo"
        Effect = "Allow"
        Action = [
          "ec2:CreateVpc", "ec2:DeleteVpc", "ec2:ModifyVpcAttribute",
          "ec2:CreateSubnet", "ec2:DeleteSubnet", "ec2:ModifySubnetAttribute",
          "ec2:CreateInternetGateway", "ec2:DeleteInternetGateway",
          "ec2:AttachInternetGateway", "ec2:DetachInternetGateway",
          "ec2:CreateRouteTable", "ec2:DeleteRouteTable", "ec2:CreateRoute", "ec2:DeleteRoute",
          "ec2:AssociateRouteTable", "ec2:DisassociateRouteTable",
          "ec2:CreateSecurityGroup", "ec2:DeleteSecurityGroup",
          "ec2:AuthorizeSecurityGroupIngress", "ec2:AuthorizeSecurityGroupEgress",
          "ec2:RevokeSecurityGroupIngress", "ec2:RevokeSecurityGroupEgress",
          "ec2:UpdateSecurityGroupRuleDescriptionsIngress", "ec2:UpdateSecurityGroupRuleDescriptionsEgress",
          "ec2:CreateTags", "ec2:DeleteTags",
        ]
        Resource = "*"
      },
      {
        Sid    = "LogsDemoResources"
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup", "logs:DeleteLogGroup", "logs:PutRetentionPolicy",
          "logs:TagResource", "logs:UntagResource", "logs:TagLogGroup", "logs:UntagLogGroup",
        ]
        Resource = [
          "arn:aws:logs:${var.region}:${local.account_id}:log-group:/ecs/saiman-demo*",
          "arn:aws:logs:${var.region}:${local.account_id}:log-group:/ecs/saiman-demo*:*",
          "arn:aws:logs:${var.region}:${local.account_id}:log-group:/saiman-demo*",
          "arn:aws:logs:${var.region}:${local.account_id}:log-group:/saiman-demo*:*",
        ]
      },
      {
        Sid    = "SchedulerDemoResources"
        Effect = "Allow"
        Action = [
          "scheduler:CreateSchedule", "scheduler:UpdateSchedule", "scheduler:DeleteSchedule",
          "scheduler:TagResource", "scheduler:UntagResource",
        ]
        Resource = "arn:aws:scheduler:${var.region}:${local.account_id}:schedule/default/saiman-demo*"
      },
      {
        # Parameters are created or deleted here but their values are never readable (Deny below).
        Sid    = "SsmDemoParameters"
        Effect = "Allow"
        Action = [
          "ssm:PutParameter", "ssm:DeleteParameter", "ssm:DeleteParameters",
          "ssm:AddTagsToResource", "ssm:RemoveTagsFromResource",
        ]
        Resource = local.ssm_demo_arn
      },
      {
        Sid      = "BudgetsDemo"
        Effect   = "Allow"
        Action   = ["budgets:ModifyBudget", "budgets:DeleteBudget", "budgets:TagResource", "budgets:UntagResource"]
        Resource = "arn:aws:budgets::${local.account_id}:budget/saiman-demo*"
      },
      {
        Sid      = "BudgetsCreate"
        Effect   = "Allow"
        Action   = ["budgets:CreateBudget"]
        Resource = "*"
      },
      {
        # The only IAM write. The roles are fixed in bootstrap (demo_roles.tf), carry the boundary and
        # trust service principals only; this role cannot create, edit or re-trust any role.
        Sid      = "PassDemoRoles"
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = local.iam_demo_role_arn
        Condition = {
          StringEquals = { "iam:PassedToService" = ["ecs-tasks.amazonaws.com", "scheduler.amazonaws.com"] }
        }
      },
      {
        Sid      = "ServiceLinkedRoles"
        Effect   = "Allow"
        Action   = ["iam:CreateServiceLinkedRole"]
        Resource = "arn:aws:iam::${local.account_id}:role/aws-service-role/*"
        Condition = {
          StringEquals = { "iam:AWSServiceName" = ["ecs.amazonaws.com", "rds.amazonaws.com"] }
        }
      },
      local.stmt_deny_ssm_values,
      local.stmt_deny_private_objects,
      local.region_lock,
    ]
  })
}

# --- destroy -------------------------------------------------------------------------------------

resource "aws_iam_role" "destroy" {
  name                 = "saiman-gha-destroy"
  path                 = local.role_path
  description          = "demo-down and the reaper: delete, stop and scale down only"
  max_session_duration = 3600

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_host}:aud" = "sts.amazonaws.com"
          "${local.oidc_host}:sub" = local.sub_destroy
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "destroy" {
  name = "destroy"
  role = aws_iam_role.destroy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      local.stmt_read,
      local.stmt_iam_read,
      local.stmt_state_list,
      local.stmt_state_rw,
      local.stmt_lock_delete,
      {
        # No UpdateService: the reaper uses `ecs delete-service --force`, and the Scheduler role covers
        # scale-to-zero. No Create*/Register*/RunTask, so nothing new can start.
        Sid    = "EcsDelete"
        Effect = "Allow"
        Action = [
          "ecs:DeleteService", "ecs:DeleteCluster", "ecs:StopTask",
          "ecs:DeregisterTaskDefinition", "ecs:DeleteTaskDefinitions", "ecs:UntagResource",
        ]
        Resource = [
          "arn:aws:ecs:${var.region}:${local.account_id}:cluster/saiman-demo*",
          "arn:aws:ecs:${var.region}:${local.account_id}:service/saiman-demo*/*",
          "arn:aws:ecs:${var.region}:${local.account_id}:task/saiman-demo*/*",
          "arn:aws:ecs:${var.region}:${local.account_id}:task-definition/saiman-demo*:*",
        ]
      },
      {
        Sid    = "RdsDeleteStop"
        Effect = "Allow"
        Action = [
          "rds:StopDBInstance", "rds:ModifyDBInstance", "rds:DeleteDBInstance",
          "rds:DeleteDBSubnetGroup", "rds:DeleteDBParameterGroup", "rds:RemoveTagsFromResource",
        ]
        Resource = [
          "arn:aws:rds:${var.region}:${local.account_id}:db:saiman-demo*",
          "arn:aws:rds:${var.region}:${local.account_id}:subgrp:saiman-demo*",
          "arn:aws:rds:${var.region}:${local.account_id}:pg:saiman-demo*",
        ]
      },
      {
        Sid    = "Ec2NetworkingDelete"
        Effect = "Allow"
        Action = [
          "ec2:DeleteVpc", "ec2:DeleteSubnet", "ec2:DeleteInternetGateway", "ec2:DetachInternetGateway",
          "ec2:DeleteRouteTable", "ec2:DeleteRoute", "ec2:DisassociateRouteTable",
          "ec2:DeleteSecurityGroup", "ec2:RevokeSecurityGroupIngress", "ec2:RevokeSecurityGroupEgress",
          "ec2:DeleteTags",
        ]
        Resource = "*"
      },
      {
        Sid    = "LogsDelete"
        Effect = "Allow"
        Action = ["logs:DeleteLogGroup"]
        Resource = [
          "arn:aws:logs:${var.region}:${local.account_id}:log-group:/ecs/saiman-demo*",
          "arn:aws:logs:${var.region}:${local.account_id}:log-group:/ecs/saiman-demo*:*",
          "arn:aws:logs:${var.region}:${local.account_id}:log-group:/saiman-demo*",
          "arn:aws:logs:${var.region}:${local.account_id}:log-group:/saiman-demo*:*",
        ]
      },
      {
        Sid      = "SchedulerDelete"
        Effect   = "Allow"
        Action   = ["scheduler:DeleteSchedule"]
        Resource = "arn:aws:scheduler:${var.region}:${local.account_id}:schedule/default/saiman-demo*"
      },
      {
        Sid      = "SsmDeleteDemoParameters"
        Effect   = "Allow"
        Action   = ["ssm:DeleteParameter", "ssm:DeleteParameters"]
        Resource = local.ssm_demo_arn
      },
      {
        Sid      = "BudgetsDelete"
        Effect   = "Allow"
        Action   = ["budgets:DeleteBudget"]
        Resource = "arn:aws:budgets::${local.account_id}:budget/saiman-demo*"
      },
      {
        Sid      = "ListDemoExports"
        Effect   = "Allow"
        Action   = ["s3:ListBucket", "s3:ListBucketVersions"]
        Resource = local.bucket_arn
        Condition = {
          StringLike = { "s3:prefix" = ["demo/*"] }
        }
      },
      {
        Sid      = "DeleteDemoExports"
        Effect   = "Allow"
        Action   = ["s3:DeleteObject", "s3:DeleteObjectVersion"]
        Resource = "${local.bucket_arn}/demo/*"
      },
      local.stmt_deny_ssm_values,
      local.stmt_deny_private_objects,
      local.region_lock,
    ]
  })
}

# --- permissions boundary ------------------------------------------------------------------------
# Attached to the three fixed demo roles (demo_roles.tf). It bounds what those roles can DO (an allow-list
# ceiling plus explicit denies), not who can assume them: a malicious approved apply can still deploy a
# workload that reads /saiman/demo/* secrets (inherent; testnet keys and a capped OpenAI key only).

resource "aws_iam_policy" "demo_boundary" {
  name        = local.boundary_name
  path        = local.role_path
  description = "Permissions boundary for roles created by demo-lite"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "CeilingLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "arn:aws:logs:${var.region}:${local.account_id}:log-group:/*saiman-demo*:*"
      },
      {
        Sid      = "CeilingSecrets"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath"]
        Resource = local.ssm_demo_arn
      },
      {
        Sid    = "CeilingEcsExec"
        Effect = "Allow"
        Action = [
          "ssmmessages:CreateControlChannel", "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel", "ssmmessages:OpenDataChannel",
        ]
        Resource = "*"
      },
      {
        Sid      = "CeilingScheduler"
        Effect   = "Allow"
        Action   = ["ecs:UpdateService", "ecs:DescribeServices"]
        Resource = "arn:aws:ecs:${var.region}:${local.account_id}:service/saiman-demo*/*"
      },
      {
        Sid      = "CeilingRdsStop"
        Effect   = "Allow"
        Action   = ["rds:StartDBInstance", "rds:StopDBInstance", "rds:DescribeDBInstances"]
        Resource = "arn:aws:rds:${var.region}:${local.account_id}:db:saiman-demo*"
      },
      {
        Sid      = "CeilingObjects"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = local.demo_s3_read_arns
      },
      {
        Sid      = "CeilingDemoWrites"
        Effect   = "Allow"
        Action   = ["s3:PutObject"]
        Resource = "${local.bucket_arn}/demo/*"
      },
      {
        Sid      = "CeilingList"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = local.bucket_arn
      },
      {
        Sid      = "DecryptSsmSecureStrings"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = "*"
        Condition = {
          StringEquals = { "kms:ViaService" = "ssm.${var.region}.amazonaws.com" }
        }
      },
      {
        Sid    = "DenyIamUsersAndKeys"
        Effect = "Deny"
        Action = [
          "iam:CreateUser", "iam:CreateAccessKey", "iam:CreateLoginProfile", "iam:UpdateLoginProfile",
          "iam:PutUserPolicy", "iam:AttachUserPolicy", "iam:CreateGroup", "iam:AddUserToGroup",
          "iam:CreateOpenIDConnectProvider", "iam:CreateSAMLProvider",
        ]
        Resource = "*"
      },
      {
        Sid    = "DenyRoleChangesOutsideDemoPath"
        Effect = "Deny"
        Action = [
          "iam:CreateRole", "iam:PutRolePolicy", "iam:AttachRolePolicy", "iam:UpdateAssumeRolePolicy",
          "iam:PutRolePermissionsBoundary", "iam:PassRole",
        ]
        NotResource = local.iam_demo_role_arn
      },
      {
        Sid      = "DenyBoundaryRemovalAndEdits"
        Effect   = "Deny"
        Action   = ["iam:DeleteRolePermissionsBoundary", "iam:DeleteUserPermissionsBoundary"]
        Resource = "*"
      },
      {
        Sid    = "DenyBoundaryPolicyEdits"
        Effect = "Deny"
        Action = [
          "iam:CreatePolicyVersion", "iam:SetDefaultPolicyVersion", "iam:DeletePolicy", "iam:DeletePolicyVersion",
        ]
        Resource = local.boundary_arn
      },
      {
        # Defence in depth: whatever a demo role is granted, secrets outside /saiman/demo/ stay unreadable.
        Sid         = "DenySecretsOutsideDemo"
        Effect      = "Deny"
        Action      = ["ssm:GetParameter*"]
        NotResource = local.ssm_demo_arn
      },
      {
        Sid      = "DenyOrganizationsAndBilling"
        Effect   = "Deny"
        Action   = ["organizations:*", "account:*", "billing:*", "aws-portal:*", "payments:*", "budgets:*", "ce:*"]
        Resource = "*"
      },
      local.region_lock,
    ]
  })
}
