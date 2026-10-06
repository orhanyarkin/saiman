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
      == ["repo:orhanyarkin/saiman:pull_request", "repo:orhanyarkin/saiman:ref:refs/heads/main"]
    )
    error_message = "plan role trust sub values changed"
  }

  assert {
    condition = (
      jsondecode(aws_iam_role.apply.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"]
      == ["repo:orhanyarkin/saiman:environment:demo-apply"]
    )
    error_message = "apply role must trust only the demo-apply environment"
  }

  assert {
    condition = (
      jsondecode(aws_iam_role.destroy.assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"]
      == ["repo:orhanyarkin/saiman:environment:demo-destroy"]
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

run "apply_role_creates_roles_only_with_boundary" {
  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
      try(s.Effect == "Allow" && contains(s.Action, "iam:CreateRole")
        && s.Condition.StringEquals["iam:PermissionsBoundary"] == "arn:aws:iam::123456789012:policy/saiman/bootstrap/saiman-demo-boundary"
      && s.Resource == "arn:aws:iam::123456789012:role/saiman/demo/*", false)
    ])
    error_message = "iam:CreateRole must be conditioned on the boundary and limited to /saiman/demo/"
  }

  assert {
    condition = length([
      for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
      s if s.Effect == "Allow" && try(s.Condition == null, true) && (contains(try(tolist(s.Action), []), "iam:CreateRole") || contains(try(tolist(s.Action), []), "iam:PassRole"))
    ]) == 0
    error_message = "CreateRole and PassRole must never be allowed unconditionally"
  }

  assert {
    condition = alltrue([
      for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
      !contains(try(tolist(s.Action), []), "iam:DeleteRolePermissionsBoundary")
    ])
    error_message = "apply role must not be able to remove the boundary"
  }
}

run "destroy_role_cannot_create" {
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
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.destroy.policy).Statement :
      try(s.Effect == "Allow" && contains(s.Action, "ecs:UpdateService"), false)
    ])
    error_message = "destroy role needs ecs:UpdateService to scale to zero"
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
