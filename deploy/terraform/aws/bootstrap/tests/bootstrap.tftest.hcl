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

  # Each fixed role may be passed to exactly one service: task + execution roles to ECS tasks, the scheduler role to
  # EventBridge Scheduler. No wildcard resource, no role passable to both.
  assert {
    condition = (
      length([for s in jsondecode(aws_iam_role_policy.apply.policy).Statement : s if s.Effect == "Allow" && try(s.Action == ["iam:PassRole"], false)]) == 2
      && anytrue([
        for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
        try(s.Effect == "Allow" && s.Action == ["iam:PassRole"]
          && s.Resource == ["arn:aws:iam::123456789012:role/saiman/demo/saiman-demo-task", "arn:aws:iam::123456789012:role/saiman/demo/saiman-demo-task-execution"]
        && s.Condition.StringEquals["iam:PassedToService"] == "ecs-tasks.amazonaws.com", false)
      ])
      && anytrue([
        for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
        try(s.Effect == "Allow" && s.Action == ["iam:PassRole"]
          && s.Resource == "arn:aws:iam::123456789012:role/saiman/demo/saiman-demo-scheduler"
        && s.Condition.StringEquals["iam:PassedToService"] == "scheduler.amazonaws.com", false)
      ])
    )
    error_message = "PassRole: task and execution roles only to ecs-tasks, the scheduler role only to scheduler, nothing wildcard"
  }
}

run "apply_role_runs_only_saiman_demo_tasks" {
  # demo-up starts the init task with ecs:RunTask. Only the apply role may, only for saiman-demo* task definitions in
  # the saiman-demo* cluster, never with a wildcard resource, and ecs:StartTask / ecs:* stay out of every CI role.
  assert {
    condition = length([
      for s in jsondecode(aws_iam_role_policy.apply.policy).Statement : s
      if s.Effect == "Allow" && contains(try(tolist(s.Action), [s.Action]), "ecs:RunTask")
    ]) == 1
    error_message = "exactly one apply statement allows ecs:RunTask"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
      try(s.Effect == "Allow" && s.Action == ["ecs:RunTask"]
        && s.Resource == "arn:aws:ecs:eu-central-1:123456789012:task-definition/saiman-demo*:*"
      && s.Condition.ArnLike["ecs:cluster"] == "arn:aws:ecs:eu-central-1:123456789012:cluster/saiman-demo*", false)
    ])
    error_message = "ecs:RunTask only for saiman-demo* task definitions, conditioned on the saiman-demo* cluster"
  }

  assert {
    condition = alltrue(flatten([
      for pol in [aws_iam_role_policy.plan.policy, aws_iam_role_policy.destroy.policy] :
      [for s in jsondecode(pol).Statement : !anytrue([for a in try(tolist(s.Action), [s.Action]) : a == "ecs:RunTask" || a == "ecs:StartTask" || a == "ecs:*"]) if s.Effect == "Allow"]
    ]))
    error_message = "plan and destroy must not be able to start tasks"
  }

  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
      [for a in try(tolist(s.Action), [s.Action]) : a != "ecs:StartTask" && a != "ecs:*"] if s.Effect == "Allow"
    ]))
    error_message = "the apply role has neither ecs:StartTask nor ecs:*"
  }
}

run "session_durations" {
  # Only the apply role may hold a two-hour session (demo-up: apply + init task + readiness wait); the plan role runs on pull
  # requests and the destroy role is used by the reaper, so both stay at one hour.
  assert {
    condition     = aws_iam_role.apply.max_session_duration == 7200 && aws_iam_role.plan.max_session_duration == 3600 && aws_iam_role.destroy.max_session_duration == 3600
    error_message = "apply 7200 s, plan and destroy 3600 s"
  }
}

run "init_task_flow_permissions" {
  # demo-up waits for the init task (ecs:DescribeTasks), demo-destroy lists and stops a leftover one (ecs:ListTasks,
  # ecs:StopTask). They come from the shared read statement and the delete/resource statements; pin them so that narrowing
  # either statement cannot silently break the flow.
  assert {
    condition = alltrue([
      for pol in [aws_iam_role_policy.apply.policy, aws_iam_role_policy.destroy.policy] :
      anytrue([
        for s in jsondecode(pol).Statement :
        try(s.Sid == "ReadDemoLiteServices" && s.Effect == "Allow" && s.Resource == "*" && contains(s.Action, "ecs:Describe*") && contains(s.Action, "ecs:List*"), false)
      ])
    ])
    error_message = "apply and destroy need ecs:Describe* (DescribeTasks) and ecs:List* (ListTasks) on *"
  }

  assert {
    condition = alltrue([
      for pol in [aws_iam_role_policy.apply.policy, aws_iam_role_policy.destroy.policy] :
      anytrue([
        for s in jsondecode(pol).Statement :
        try(s.Effect == "Allow" && contains(s.Action, "ecs:StopTask") && contains(s.Resource, "arn:aws:ecs:eu-central-1:123456789012:task/saiman-demo*/*"), false)
      ])
    ])
    error_message = "apply and destroy need ecs:StopTask on saiman-demo* tasks"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
      try(s.Effect == "Allow" && contains(s.Action, "ecs:UpdateService") && contains(s.Resource, "arn:aws:ecs:eu-central-1:123456789012:service/saiman-demo*/*"), false)
    ])
    error_message = "apply needs ecs:UpdateService on the saiman-demo* service (demo-up scales it to 1)"
  }
}

run "inline_policies_fit_the_iam_limit" {
  # IAM caps the inline policies of a role at 10,240 characters in total; a bootstrap re-apply that exceeds it fails late.
  assert {
    condition     = length(aws_iam_role_policy.apply.policy) < 10240 && length(aws_iam_role_policy.plan.policy) < 10240 && length(aws_iam_role_policy.destroy.policy) < 10240
    error_message = "a CI role's inline policy exceeds the 10,240 character IAM limit"
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

run "facts" {
  # Loads tests/iam-action-facts.json and tests/required-actions.json once (see tests/facts/main.tf).
  command = plan

  module {
    source = "./tests/facts"
  }
}

run "iam_action_names_exist" {
  # Facts from AWS's Service Reference (ops/refresh-iam-facts.py). An action IAM does not know silently grants nothing, for
  # example budgets:DeleteBudget (the real action is budgets:ModifyBudget). Services outside the facts file are not checked.
  assert {
    condition = length(distinct(flatten([
      for pol in [aws_iam_role_policy.plan.policy, aws_iam_role_policy.apply.policy, aws_iam_role_policy.destroy.policy, aws_iam_role_policy.demo_task.policy, aws_iam_role_policy.demo_task_execution.policy, aws_iam_role_policy.demo_scheduler.policy, aws_iam_policy.demo_boundary.policy] : [
        for s in jsondecode(pol).Statement : [
          for p in try(tolist(s.Action), try([s.Action], [])) : p
          if contains(run.facts.services, split(":", p)[0])
          && (endswith(p, "*") ? !anytrue([for k in run.facts.known : startswith(k, trimsuffix(p, "*"))]) : !lookup(run.facts.known_map, p, false))
        ]
      ]
    ]))) == 0
    error_message = "actions (or wildcard patterns) the Service Reference does not know: ${join(", ", distinct(flatten([
      for pol in [aws_iam_role_policy.plan.policy, aws_iam_role_policy.apply.policy, aws_iam_role_policy.destroy.policy, aws_iam_role_policy.demo_task.policy, aws_iam_role_policy.demo_task_execution.policy, aws_iam_role_policy.demo_scheduler.policy, aws_iam_policy.demo_boundary.policy] : [
        for s in jsondecode(pol).Statement : [
          for p in try(tolist(s.Action), try([s.Action], [])) : p
          if contains(run.facts.services, split(":", p)[0])
          && (endswith(p, "*") ? !anytrue([for k in run.facts.known : startswith(k, trimsuffix(p, "*"))]) : !lookup(run.facts.known_map, p, false))
        ]
      ]
    ])))}"
  }
}

run "star_only_actions_are_never_in_scoped_statements" {
  # An action without resource types only works with Resource "*". Listed in a statement that names ARNs it is never granted:
  # ecs:DeregisterTaskDefinition in a task-definition-scoped statement made demo-down fail ("on resource: *").
  assert {
    condition = length(distinct(flatten([
      for pol in [aws_iam_role_policy.plan.policy, aws_iam_role_policy.apply.policy, aws_iam_role_policy.destroy.policy, aws_iam_role_policy.demo_task.policy, aws_iam_role_policy.demo_task_execution.policy, aws_iam_role_policy.demo_scheduler.policy, aws_iam_policy.demo_boundary.policy] : [
        for s in jsondecode(pol).Statement : [
          for p in try(tolist(s.Action), try([s.Action], [])) : "${try(s.Sid, "?")}: ${p}"
          if s.Effect == "Allow" && !contains(try(tolist(s.Resource), try([s.Resource], ["*"])), "*")
          && (endswith(p, "*") ? anytrue([for so in run.facts.star_only : startswith(so, trimsuffix(p, "*"))]) : lookup(run.facts.star_map, p, false))
        ]
      ]
    ]))) == 0
    error_message = "actions that only support Resource \"*\" inside scoped statements: ${join(", ", distinct(flatten([
      for pol in [aws_iam_role_policy.plan.policy, aws_iam_role_policy.apply.policy, aws_iam_role_policy.destroy.policy, aws_iam_role_policy.demo_task.policy, aws_iam_role_policy.demo_task_execution.policy, aws_iam_role_policy.demo_scheduler.policy, aws_iam_policy.demo_boundary.policy] : [
        for s in jsondecode(pol).Statement : [
          for p in try(tolist(s.Action), try([s.Action], [])) : "${try(s.Sid, "?")}: ${p}"
          if s.Effect == "Allow" && !contains(try(tolist(s.Resource), try([s.Resource], ["*"])), "*")
          && (endswith(p, "*") ? anytrue([for so in run.facts.star_only : startswith(so, trimsuffix(p, "*"))]) : lookup(run.facts.star_map, p, false))
        ]
      ]
    ])))}"
  }
}

run "demo_lite_resources_have_their_api_actions" {
  # tests/required-actions.json lists, per demo-lite resource type, what the provider calls to create, update, replace, tag and
  # delete it. Every such action must be allowed for that role (and, if it only works with "*", by a statement with "*").
  assert {
    condition = length(flatten([
      for name, req in run.facts.resources : [
        for a in req.apply : "${name}: ${a}"
        if !anytrue([
          for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
          s.Effect == "Allow"
          && anytrue([for p in try(tolist(s.Action), try([s.Action], [])) : p == a || (endswith(p, "*") && startswith(a, trimsuffix(p, "*")))])
          && (!lookup(run.facts.star_map, a, false) || contains(try(tolist(s.Resource), try([s.Resource], ["*"])), "*"))
        ])
      ]
    ])) == 0
    error_message = "the apply role lacks: ${join(", ", flatten([
      for name, req in run.facts.resources : [
        for a in req.apply : "${name}: ${a}"
        if !anytrue([
          for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
          s.Effect == "Allow"
          && anytrue([for p in try(tolist(s.Action), try([s.Action], [])) : p == a || (endswith(p, "*") && startswith(a, trimsuffix(p, "*")))])
          && (!lookup(run.facts.star_map, a, false) || contains(try(tolist(s.Resource), try([s.Resource], ["*"])), "*"))
        ])
      ]
    ]))}"
  }

  assert {
    condition = length(flatten([
      for name, req in run.facts.resources : [
        for a in req.destroy : "${name}: ${a}"
        if !anytrue([
          for s in jsondecode(aws_iam_role_policy.destroy.policy).Statement :
          s.Effect == "Allow"
          && anytrue([for p in try(tolist(s.Action), try([s.Action], [])) : p == a || (endswith(p, "*") && startswith(a, trimsuffix(p, "*")))])
          && (!lookup(run.facts.star_map, a, false) || contains(try(tolist(s.Resource), try([s.Resource], ["*"])), "*"))
        ])
      ]
    ])) == 0
    error_message = "the destroy role lacks: ${join(", ", flatten([
      for name, req in run.facts.resources : [
        for a in req.destroy : "${name}: ${a}"
        if !anytrue([
          for s in jsondecode(aws_iam_role_policy.destroy.policy).Statement :
          s.Effect == "Allow"
          && anytrue([for p in try(tolist(s.Action), try([s.Action], [])) : p == a || (endswith(p, "*") && startswith(a, trimsuffix(p, "*")))])
          && (!lookup(run.facts.star_map, a, false) || contains(try(tolist(s.Resource), try([s.Resource], ["*"])), "*"))
        ])
      ]
    ]))}"
  }

  assert {
    condition = length(flatten([
      for name, req in run.facts.ops : [
        for a in req.apply : "${name}: ${a}"
        if !anytrue([
          for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
          s.Effect == "Allow"
          && anytrue([for p in try(tolist(s.Action), try([s.Action], [])) : p == a || (endswith(p, "*") && startswith(a, trimsuffix(p, "*")))])
          && (!lookup(run.facts.star_map, a, false) || contains(try(tolist(s.Resource), try([s.Resource], ["*"])), "*"))
        ])
      ]
    ])) == 0
    error_message = "the apply role lacks what the workflows call: ${join(", ", flatten([
      for name, req in run.facts.ops : [
        for a in req.apply : "${name}: ${a}"
        if !anytrue([
          for s in jsondecode(aws_iam_role_policy.apply.policy).Statement :
          s.Effect == "Allow"
          && anytrue([for p in try(tolist(s.Action), try([s.Action], [])) : p == a || (endswith(p, "*") && startswith(a, trimsuffix(p, "*")))])
          && (!lookup(run.facts.star_map, a, false) || contains(try(tolist(s.Resource), try([s.Resource], ["*"])), "*"))
        ])
      ]
    ]))}"
  }

  assert {
    condition = length(flatten([
      for name, req in run.facts.ops : [
        for a in req.destroy : "${name}: ${a}"
        if !anytrue([
          for s in jsondecode(aws_iam_role_policy.destroy.policy).Statement :
          s.Effect == "Allow"
          && anytrue([for p in try(tolist(s.Action), try([s.Action], [])) : p == a || (endswith(p, "*") && startswith(a, trimsuffix(p, "*")))])
          && (!lookup(run.facts.star_map, a, false) || contains(try(tolist(s.Resource), try([s.Resource], ["*"])), "*"))
        ])
      ]
    ])) == 0
    error_message = "the destroy role lacks what the workflows call: ${join(", ", flatten([
      for name, req in run.facts.ops : [
        for a in req.destroy : "${name}: ${a}"
        if !anytrue([
          for s in jsondecode(aws_iam_role_policy.destroy.policy).Statement :
          s.Effect == "Allow"
          && anytrue([for p in try(tolist(s.Action), try([s.Action], [])) : p == a || (endswith(p, "*") && startswith(a, trimsuffix(p, "*")))])
          && (!lookup(run.facts.star_map, a, false) || contains(try(tolist(s.Resource), try([s.Resource], ["*"])), "*"))
        ])
      ]
    ]))}"
  }
}

run "task_role_lists_only_the_demo_assets" {
  # `aws s3 sync s3://<bucket>/demo/assets/<session>/` lists with ListObjectsV2 and a prefix (demo-up #6 failed on the missing
  # s3:ListBucket). The grant is the bucket ARN with an s3:prefix condition; the state objects (bootstrap/, demo-lite/) and the
  # corpus prefix match no pattern, and a list without a prefix has no s3:prefix value, so none of them can be listed.
  assert {
    condition = (
      length([for s in jsondecode(aws_iam_role_policy.demo_task.policy).Statement : s if s.Effect == "Allow" && contains(try(tolist(s.Action), []), "s3:ListBucket")]) == 1
      && anytrue([
        for s in jsondecode(aws_iam_role_policy.demo_task.policy).Statement :
        s.Effect == "Allow" && s.Action == ["s3:ListBucket"] && s.Resource == "arn:aws:s3:::saiman-test-tfstate"
        && try(s.Condition.StringLike["s3:prefix"] == ["demo/assets/*"] && length(keys(s.Condition)) == 1, false)
      ])
    )
    error_message = "the task role must have exactly one s3:ListBucket grant: the bucket ARN, condition StringLike s3:prefix = [demo/assets/*]"
  }

  assert {
    condition = alltrue(flatten([
      for p in [
        jsondecode(aws_iam_role_policy.demo_task.policy),
        jsondecode(aws_iam_role_policy.demo_task_execution.policy),
        jsondecode(aws_iam_policy.demo_boundary.policy),
        ] : [
        for s in p.Statement :
        try(s.Effect == "Allow" && contains(tolist(s.Action), "s3:ListBucket"), false) ? (
          can(s.Condition.StringLike["s3:prefix"]) && alltrue([for v in s.Condition.StringLike["s3:prefix"] : startswith(v, "demo/assets/")])
        ) : true
      ]
    ]))
    error_message = "every s3:ListBucket grant (task role, execution role, boundary) needs an s3:prefix condition whose patterns all start with demo/assets/ (no bare *, bootstrap*, demo-lite*, artifacts/)"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_policy.demo_boundary.policy).Statement :
      s.Sid == "CeilingList" && s.Action == ["s3:ListBucket"] && s.Resource == "arn:aws:s3:::saiman-test-tfstate" && try(s.Condition.StringLike["s3:prefix"] == ["demo/assets/*"], false)
    ])
    error_message = "the boundary ceiling must allow listing only under demo/assets/ as well"
  }
}

run "workload_has_the_actions_its_containers_use" {
  # tests/required-actions.json "workload": what the AWS CLI commands in the container definitions (tests/check-workload-actions.sh
  # keeps that list complete) and the ECS agent call. Each action must be granted by the role the containers run as AND by the
  # permissions boundary, otherwise the effective permission is empty and the first demo-up finds out.
  assert {
    condition = length(flatten([
      for name, w in run.facts.workload : [
        for a in w.actions : "${name}: ${a}"
        if !anytrue([
          for s in jsondecode(w.role == "task" ? aws_iam_role_policy.demo_task.policy : aws_iam_role_policy.demo_task_execution.policy).Statement :
          s.Effect == "Allow" && anytrue([for p in try(tolist(s.Action), try([s.Action], [])) : p == a || (endswith(p, "*") && startswith(a, trimsuffix(p, "*")))])
        ])
      ]
    ])) == 0
    error_message = "a demo role lacks what its containers use: ${join("; ", flatten([
      for name, w in run.facts.workload : [
        for a in w.actions : "${w.role} role, ${name}: ${a}"
        if !anytrue([
          for s in jsondecode(w.role == "task" ? aws_iam_role_policy.demo_task.policy : aws_iam_role_policy.demo_task_execution.policy).Statement :
          s.Effect == "Allow" && anytrue([for p in try(tolist(s.Action), try([s.Action], [])) : p == a || (endswith(p, "*") && startswith(a, trimsuffix(p, "*")))])
        ])
      ]
    ]))}"
  }

  assert {
    condition = length(flatten([
      for name, w in run.facts.workload : [
        for a in w.actions : "${name}: ${a}"
        if !anytrue([
          for s in jsondecode(aws_iam_policy.demo_boundary.policy).Statement :
          s.Effect == "Allow" && anytrue([for p in try(tolist(s.Action), try([s.Action], [])) : p == a || (endswith(p, "*") && startswith(a, trimsuffix(p, "*")))])
        ])
      ]
    ])) == 0
    error_message = "the permissions boundary does not allow what the containers use: ${join("; ", flatten([
      for name, w in run.facts.workload : [
        for a in w.actions : "${name}: ${a}"
        if !anytrue([
          for s in jsondecode(aws_iam_policy.demo_boundary.policy).Statement :
          s.Effect == "Allow" && anytrue([for p in try(tolist(s.Action), try([s.Action], [])) : p == a || (endswith(p, "*") && startswith(a, trimsuffix(p, "*")))])
        ])
      ]
    ]))}"
  }
}
