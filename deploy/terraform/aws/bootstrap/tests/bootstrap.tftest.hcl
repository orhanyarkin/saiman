# Offline tests: mock_provider needs no credentials. Policies are jsonencode()d locals, so every
# assertion decodes the rendered JSON. prevent_destroy is not visible here; see check-prevent-destroy.sh.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
}

variables {
  state_bucket_name = "saiman-test-tfstate"
}

run "trust_subjects_are_exact" {
  assert {
    condition = (
      jsondecode(aws_iam_role.plan.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"]
      == ["repo:orhanyarkin@44910233/saiman@1390957366:pull_request", "repo:orhanyarkin@44910233/saiman@1390957366:ref:refs/heads/main"]
    )
    error_message = "plan role trust sub values changed"
  }

  assert {
    condition = (
      jsondecode(aws_iam_role.apply.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"]
      == ["repo:orhanyarkin@44910233/saiman@1390957366:environment:demo-apply"]
    )
    error_message = "apply role must trust only the demo-apply environment"
  }

  assert {
    condition = (
      jsondecode(aws_iam_role.destroy.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"]
      == ["repo:orhanyarkin@44910233/saiman@1390957366:environment:demo-destroy"]
    )
    error_message = "destroy role must trust only the demo-destroy environment"
  }

  assert {
    condition = alltrue([
      for r in [aws_iam_role.plan, aws_iam_role.apply, aws_iam_role.destroy] :
      jsondecode(r.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
      && r.path == "/saiman/bootstrap/"
    ])
    error_message = "every role needs aud sts.amazonaws.com and path /saiman/bootstrap/"
  }

  assert {
    condition     = aws_iam_openid_connect_provider.github.client_id_list == toset(["sts.amazonaws.com"])
    error_message = "OIDC provider audience must be sts.amazonaws.com"
  }
}

run "plan_role_cannot_read_secrets" {
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.plan.policy).Statement :
      try(s.Effect == "Deny" && s.Action == ["ssm:GetParameter*"] && s.Resource == "arn:aws:ssm:eu-central-1:123456789012:parameter/saiman/*", false)
    ])
    error_message = "plan role needs an explicit Deny on ssm:GetParameter* for /saiman/*"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.plan.policy).Statement :
      try(s.Effect == "Deny" && contains(s.Action, "s3:GetObject")
        && contains(s.Resource, "arn:aws:s3:::saiman-test-tfstate/artifacts/*")
      && contains(s.Resource, "arn:aws:s3:::saiman-test-tfstate/demo/*"), false)
    ])
    error_message = "plan role needs an explicit Deny on s3:GetObject for artifacts/* and demo/*"
  }

  assert {
    condition = alltrue([
      for s in jsondecode(aws_iam_role_policy.plan.policy).Statement :
      s.Effect == "Deny" || !anytrue([for a in try(tolist(s.Action), [s.Action]) : can(regex("^(ssm:Get|iam:Create|ec2:Create|ecs:Create|rds:Create)", a))])
    ])
    error_message = "plan role must not allow ssm:Get* or any create action"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.plan.policy).Statement :
      try(s.Effect == "Deny" && s.Condition.StringNotEquals["aws:RequestedRegion"] == "eu-central-1", false)
    ])
    error_message = "plan role must be region-locked"
  }
}

run "plan_role_has_no_s3_write" {
  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.plan.policy).Statement :
      [for a in try(tolist(s.Action), []) : !can(regex("^s3:(Put|Delete|Create|Replicate|Restore|Abort)", a))] if s.Effect == "Allow"
    ]))
    error_message = "plan role must have no S3 write at all (plan workflows use -lock=false)"
  }
}

run "ci_roles_keep_secret_and_region_denies" {
  assert {
    condition = alltrue([
      for pol in [aws_iam_role_policy.plan.policy, aws_iam_role_policy.apply.policy, aws_iam_role_policy.destroy.policy] :
      anytrue([
        for s in jsondecode(pol).Statement :
        try(s.Effect == "Deny" && s.Action == ["ssm:GetParameter*"] && s.Resource == "arn:aws:ssm:eu-central-1:123456789012:parameter/saiman/*", false)
      ])
      && anytrue([
        for s in jsondecode(pol).Statement :
        try(s.Effect == "Deny" && s.Condition.StringNotEquals["aws:RequestedRegion"] == "eu-central-1", false)
      ])
    ])
    error_message = "plan, apply and destroy each need the SSM value Deny and the region lock"
  }
}

run "apply_role_cannot_mint_or_retrust_roles" {
  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
      [for a in try(tolist(s.Action), []) : !can(regex("^iam:(CreateRole|UpdateAssumeRolePolicy|PutRolePolicy|AttachRolePolicy|DeleteRole|UpdateRole|TagRole|CreatePolicy|CreatePolicyVersion|PutRolePermissionsBoundary|DeleteRolePermissionsBoundary)", a))] if s.Effect == "Allow"
    ]))
    error_message = "apply role must have no role/policy write; roles are fixed in bootstrap"
  }

  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
      [for a in try(tolist(s.Action), []) : a == "iam:PassRole" || a == "iam:CreateServiceLinkedRole" || !startswith(a, "iam:") || startswith(a, "iam:Get") || startswith(a, "iam:List")] if s.Effect == "Allow"
    ]))
    error_message = "the only IAM writes left are PassRole and service-linked roles"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
      try(s.Effect == "Allow" && s.Action == ["iam:PassRole"] && s.Resource == "arn:aws:iam::123456789012:role/saiman/demo/*"
      && s.Condition.StringEquals["iam:PassedToService"] == ["ecs-tasks.amazonaws.com", "scheduler.amazonaws.com"], false)
    ])
    error_message = "PassRole only for /saiman/demo/ and ecs-tasks + scheduler"
  }
}

run "only_apply_can_write_demo_assets" {
  # apply may PutObject on demo/* (assets) and the demo-lite state; nothing on artifacts/* or bootstrap keys
  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
      [for r in try(tolist(s.Resource), [s.Resource]) : contains(["arn:aws:s3:::saiman-test-tfstate/demo/*", "arn:aws:s3:::saiman-test-tfstate/demo-lite/*"], r)]
      if s.Effect == "Allow" && anytrue([for a in try(tolist(s.Action), []) : startswith(a, "s3:Put")])
    ]))
    error_message = "apply s3:PutObject only on demo/* and demo-lite/*"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
      try(s.Effect == "Allow" && s.Action == ["s3:PutObject"] && s.Resource == "arn:aws:s3:::saiman-test-tfstate/demo/*", false)
    ])
    error_message = "apply needs PutObject on demo/* exactly"
  }

  assert {
    condition = alltrue(flatten([
      for pol in [aws_iam_role_policy.plan.policy, aws_iam_role_policy.demo_task.policy, aws_iam_role_policy.demo_task_execution.policy, aws_iam_role_policy.demo_scheduler.policy] : [
        for s in jsondecode(pol).Statement :
        [for a in try(tolist(s.Action), []) : !startswith(a, "s3:Put") && a != "s3:*"] if s.Effect == "Allow"
      ]
    ]))
    error_message = "plan and the three demo roles must have no S3 write"
  }

  # destroy writes only the demo-lite state/lock (terraform destroy), never demo/*
  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.destroy.policy).Statement :
      [for r in try(tolist(s.Resource), [s.Resource]) : r == "arn:aws:s3:::saiman-test-tfstate/demo-lite/*"]
      if s.Effect == "Allow" && anytrue([for a in try(tolist(s.Action), []) : startswith(a, "s3:Put")])
    ]))
    error_message = "destroy may PutObject only on demo-lite/*"
  }
}

run "state_object_cannot_be_deleted_by_ci" {
  assert {
    condition = alltrue(flatten([
      for pol in [aws_iam_role_policy.apply.policy, aws_iam_role_policy.destroy.policy] : [
        for s in jsondecode(pol).Statement :
        [for r in try(tolist(s.Resource), [s.Resource]) : endswith(r, ".tflock") || endswith(r, "/demo/*")]
        if s.Effect == "Allow" && anytrue([for a in try(tolist(s.Action), []) : startswith(a, "s3:Delete")])
      ]
    ]))
    error_message = "s3:Delete* is only allowed on the lock object and demo/*, never the state key"
  }
}

run "destroy_role_has_no_create_actions" {
  assert {
    condition = alltrue([
      for s in jsondecode(aws_iam_role_policy.destroy.policy).Statement :
      s.Effect == "Deny" || !anytrue([
        for a in try(tolist(s.Action), [s.Action]) :
        can(regex(":(Create|Register|Run|Put|Attach|Authorize)", a)) && !startswith(a, "s3:Put")
      ])
    ])
    error_message = "destroy role must not carry any create-like action"
  }

  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.destroy.policy).Statement :
      [for a in try(tolist(s.Action), []) : !startswith(a, "iam:") || startswith(a, "iam:Get") || startswith(a, "iam:List")] if s.Effect == "Allow"
    ]))
    error_message = "destroy role has no IAM writes (roles are fixed in bootstrap)"
  }

  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.destroy.policy).Statement :
      [for a in try(tolist(s.Action), []) : a != "ecs:UpdateService"] if s.Effect == "Allow"
    ]))
    error_message = "destroy role relies on ecs delete-service --force, not UpdateService"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.destroy.policy).Statement :
      try(contains(s.Action, "rds:ModifyDBInstance") && s.Effect == "Allow", false)
    ])
    error_message = "destroy needs rds:ModifyDBInstance (deletion protection)"
  }
}

run "demo_roles_trust_service_principals_only" {
  assert {
    condition = alltrue([
      for r in [aws_iam_role.demo_task_execution, aws_iam_role.demo_task] :
      jsondecode(r.assume_role_policy).Statement == [{
        Effect    = "Allow"
        Principal = { Service = "ecs-tasks.amazonaws.com" }
        Action    = "sts:AssumeRole"
        Condition = { StringEquals = { "aws:SourceAccount" = "123456789012" } }
      }]
    ])
    error_message = "ecs roles trust ecs-tasks.amazonaws.com + aws:SourceAccount only"
  }

  assert {
    condition = jsondecode(aws_iam_role.demo_scheduler.assume_role_policy).Statement == [{
      Effect    = "Allow"
      Principal = { Service = "scheduler.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = { StringEquals = { "aws:SourceAccount" = "123456789012" } }
    }]
    error_message = "scheduler role trusts scheduler.amazonaws.com + aws:SourceAccount only"
  }

  assert {
    condition = alltrue([
      for r in [aws_iam_role.demo_task_execution, aws_iam_role.demo_task, aws_iam_role.demo_scheduler] :
      r.path == "/saiman/demo/" && r.permissions_boundary == "arn:aws:iam::123456789012:policy/saiman/bootstrap/saiman-demo-boundary"
    ])
    error_message = "demo roles need path /saiman/demo/ and the boundary"
  }
}

run "task_role_has_no_ssm_and_execution_role_reads_demo_only" {
  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.demo_task.policy).Statement :
      [for a in s.Action : !startswith(a, "ssm:") && !startswith(a, "kms:") && !strcontains(a, "*")]
    ]))
    error_message = "task role: no ssm, no kms, no wildcard actions"
  }

  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.demo_task.policy).Statement :
      [for a in s.Action : a != "s3:PutObject"] if s.Effect == "Allow"
    ]))
    error_message = "task role is read-only on artifacts/"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.demo_task.policy).Statement :
      s.Action == ["s3:GetObject"] && sort(tolist(s.Resource)) == sort(["arn:aws:s3:::saiman-test-tfstate/artifacts/corpus/*", "arn:aws:s3:::saiman-test-tfstate/demo/assets/*"])
    ])
    error_message = "task role may read only artifacts/corpus/* and demo/assets/* (L6)"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.demo_task_execution.policy).Statement :
      s.Sid == "ReadDemoParameters" && s.Action == ["ssm:GetParameters"] && s.Resource == "arn:aws:ssm:eu-central-1:123456789012:parameter/saiman/demo/*"
    ])
    error_message = "execution role reads /saiman/demo/* only"
  }
}

run "boundary_blocks_escalation" {
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_policy.demo_boundary.policy).Statement :
      try(s.Effect == "Deny" && contains(s.Action, "iam:DeleteRolePermissionsBoundary"), false)
    ])
    error_message = "boundary must deny removing permissions boundaries"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_policy.demo_boundary.policy).Statement :
      try(s.Effect == "Deny" && contains(s.Action, "iam:CreateRole") && s.NotResource == "arn:aws:iam::123456789012:role/saiman/demo/*", false)
    ])
    error_message = "boundary must deny role creation outside /saiman/demo/"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_policy.demo_boundary.policy).Statement :
      try(s.Effect == "Deny" && contains(s.Action, "organizations:*") && contains(s.Action, "billing:*"), false)
    ])
    error_message = "boundary must deny organizations and billing"
  }

  assert {
    condition = alltrue([
      for s in jsondecode(aws_iam_policy.demo_boundary.policy).Statement :
      s.Effect == "Deny" || !anytrue([for a in try(tolist(s.Action), []) : can(regex("^(iam|organizations|account|billing):", a))])
    ])
    error_message = "boundary must not allow IAM or organizations actions"
  }

  assert {
    condition     = length(aws_iam_policy.demo_boundary.policy) < 6144
    error_message = "managed policy size limit is 6144 characters"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_policy.demo_boundary.policy).Statement :
      try(s.Sid == "CeilingSecrets" && s.Effect == "Allow" && s.Resource == "arn:aws:ssm:eu-central-1:123456789012:parameter/saiman/demo/*", false)
    ])
    error_message = "CeilingSecrets must be exactly /saiman/demo/*"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_policy.demo_boundary.policy).Statement :
      try(s.Effect == "Deny" && s.Action == ["ssm:GetParameter*"] && s.NotResource == "arn:aws:ssm:eu-central-1:123456789012:parameter/saiman/demo/*", false)
    ])
    error_message = "boundary must deny reading any parameter outside /saiman/demo/"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_policy.demo_boundary.policy).Statement :
      try(s.Effect == "Deny" && s.Condition.StringNotEquals["aws:RequestedRegion"] == "eu-central-1", false)
    ])
    error_message = "boundary must be region-locked"
  }
}

run "state_bucket_is_locked_down" {
  assert {
    condition     = aws_s3_bucket_versioning.state.versioning_configuration[0].status == "Enabled"
    error_message = "state bucket must be versioned"
  }

  assert {
    condition = (
      aws_s3_bucket_public_access_block.state.block_public_acls
      && aws_s3_bucket_public_access_block.state.block_public_policy
      && aws_s3_bucket_public_access_block.state.ignore_public_acls
      && aws_s3_bucket_public_access_block.state.restrict_public_buckets
    )
    error_message = "all four public access blocks must be on"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_s3_bucket_policy.state.policy).Statement :
      try(s.Effect == "Deny" && s.Condition.Bool["aws:SecureTransport"] == "false" && s.Action == "s3:*", false)
    ])
    error_message = "bucket policy must deny non-TLS requests"
  }

  assert {
    condition     = one(aws_s3_bucket_server_side_encryption_configuration.state.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "AES256"
    error_message = "state bucket must use SSE-S3 (AES256), not KMS"
  }

  assert {
    condition = (
      one([for r in aws_s3_bucket_lifecycle_configuration.state.rule : r if r.id == "expire-noncurrent-versions"]).noncurrent_version_expiration[0].noncurrent_days == 30
      && one([for r in aws_s3_bucket_lifecycle_configuration.state.rule : r if r.id == "expire-demo-prefix"]).expiration[0].days == 3
      && one([for r in aws_s3_bucket_lifecycle_configuration.state.rule : r if r.id == "expire-demo-prefix"]).filter[0].prefix == "demo/"
    )
    error_message = "lifecycle: noncurrent 30 days, demo/ prefix 3 days"
  }
}
