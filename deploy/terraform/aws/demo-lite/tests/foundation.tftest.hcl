# Offline tests (mock_provider, no credentials). IAM policies are jsonencode()d, so assertions decode
# the rendered JSON. Provider default_tags are invisible here; tests/check-foundation.sh covers them.

mock_provider "aws" {
  # The scheduler validates role_arn as an ARN, so the mocked role needs a well-formed one.
  mock_resource "aws_iam_role" {
    defaults = {
      arn = "arn:aws:iam::123456789012:role/saiman/demo/mock"
    }
  }

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

run "rds_is_private_cheap_and_disposable" {
  assert {
    condition     = aws_db_instance.main.publicly_accessible == false && aws_db_instance.main.storage_encrypted == true
    error_message = "RDS must be private and encrypted"
  }

  assert {
    condition     = aws_db_instance.main.backup_retention_period == 0 && aws_db_instance.main.skip_final_snapshot == true && aws_db_instance.main.delete_automated_backups == true && aws_db_instance.main.deletion_protection == false
    error_message = "RDS must leave nothing behind: no backups, no final snapshot, no deletion protection"
  }

  assert {
    condition     = aws_db_instance.main.instance_class == "db.t4g.micro" && aws_db_instance.main.allocated_storage == 20 && aws_db_instance.main.storage_type == "gp3" && aws_db_instance.main.engine == "postgres" && startswith(aws_db_instance.main.engine_version, "17")
    error_message = "RDS must be PostgreSQL 17 on db.t4g.micro with 20 GB gp3"
  }

  assert {
    condition     = aws_db_instance.main.performance_insights_enabled == false && aws_db_instance.main.monitoring_interval == 0
    error_message = "No Performance Insights or enhanced monitoring"
  }

  assert {
    condition     = aws_db_instance.main.db_name == "saiman" && aws_db_instance.main.username == "saiman"
    error_message = "database and master user are both saiman"
  }

  # password_wo is write-only and never visible in the plan, so the version pin and the absence of
  # the stored arguments are what a test can prove.
  assert {
    condition     = aws_db_instance.main.password_wo_version == 1 && aws_db_instance.main.password == null
    error_message = "the master password must go through password_wo only"
  }

  assert {
    condition     = aws_db_instance.main.manage_master_user_password == null
    error_message = "manage_master_user_password (Secrets Manager) must not be used"
  }

  assert {
    condition     = aws_db_instance.main.parameter_group_name == aws_db_parameter_group.main.name
    error_message = "the instance must use the pinned parameter group"
  }
}

run "parameter_group_hides_passwords_and_forces_tls" {
  assert {
    condition     = aws_db_parameter_group.main.family == "postgres17"
    error_message = "parameter group family must be postgres17"
  }

  assert {
    condition = (
      [for p in aws_db_parameter_group.main.parameter : p.value if p.name == "log_statement"] == ["none"]
      && [for p in aws_db_parameter_group.main.parameter : p.value if p.name == "log_min_error_statement"] == ["panic"]
      && [for p in aws_db_parameter_group.main.parameter : p.value if p.name == "rds.force_ssl"] == ["1"]
    )
    error_message = "log_statement=none, log_min_error_statement=panic and rds.force_ssl=1 must be pinned"
  }
}

run "network_has_no_ingress_and_narrow_egress" {
  assert {
    condition     = length(aws_security_group.task.ingress) == 0 && length(aws_security_group.task.egress) == 0
    error_message = "the task security group must have no inline rules"
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.rds_from_task.security_group_id == aws_security_group.rds.id
      && aws_vpc_security_group_ingress_rule.rds_from_task.referenced_security_group_id == aws_security_group.task.id
      && aws_vpc_security_group_ingress_rule.rds_from_task.from_port == 5432
      && aws_vpc_security_group_ingress_rule.rds_from_task.to_port == 5432
    )
    error_message = "RDS accepts 5432 from the task security group only"
  }

  assert {
    condition = (
      aws_vpc_security_group_egress_rule.task_https.cidr_ipv4 == "0.0.0.0/0"
      && aws_vpc_security_group_egress_rule.task_https.from_port == 443
      && aws_vpc_security_group_egress_rule.task_https.to_port == 443
      && aws_vpc_security_group_egress_rule.task_postgres.referenced_security_group_id == aws_security_group.rds.id
      && aws_vpc_security_group_egress_rule.task_postgres.from_port == 5432
      && aws_vpc_security_group_egress_rule.task_postgres.to_port == 5432
    )
    error_message = "task egress is 443 to the internet and 5432 to RDS only"
  }

  assert {
    condition     = length(aws_subnet.public) == 2 && length(toset([for s in aws_subnet.public : s.availability_zone])) == 2
    error_message = "two public subnets in two AZs"
  }
}

run "logs_expire_after_one_day" {
  assert {
    condition     = aws_cloudwatch_log_group.main.retention_in_days == 1 && aws_cloudwatch_log_group.main.name == "/saiman-demo-lite"
    error_message = "log group must retain 1 day"
  }
}

run "execution_role_reads_only_demo_parameters" {
  assert {
    condition = (
      toset([for s in jsondecode(aws_iam_role_policy.task_execution.policy).Statement : s.Sid])
      == toset(["ReadDemoParameters", "DecryptViaSsm", "WriteDemoLogs"])
    )
    error_message = "execution role must have exactly the three expected statements"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.task_execution.policy).Statement :
      s.Sid == "ReadDemoParameters" && s.Action == ["ssm:GetParameters"] && s.Resource == "arn:aws:ssm:eu-central-1:123456789012:parameter/saiman/demo/*"
    ])
    error_message = "SSM reads must be ssm:GetParameters on /saiman/demo/* only"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.task_execution.policy).Statement :
      s.Sid == "DecryptViaSsm" && s.Action == ["kms:Decrypt"] && s.Condition.StringEquals["kms:ViaService"] == "ssm.eu-central-1.amazonaws.com"
    ])
    error_message = "kms:Decrypt only through SSM"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.task_execution.policy).Statement :
      s.Sid == "WriteDemoLogs" && s.Resource == "arn:aws:logs:eu-central-1:123456789012:log-group:/saiman-demo-lite:*"
    ])
    error_message = "logs are written to the demo log group only"
  }
}

run "task_role_has_no_wildcard_actions" {
  assert {
    condition = alltrue(flatten([
      for s in jsondecode(aws_iam_role_policy.task.policy).Statement :
      [for a in s.Action : !strcontains(a, "*")]
    ]))
    error_message = "task role must not use wildcard actions"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.task.policy).Statement :
      s.Action == ["s3:GetObject"] && s.Resource == ["arn:aws:s3:::saiman-test-tfstate/artifacts/*", "arn:aws:s3:::saiman-test-tfstate/demo/*"]
    ])
    error_message = "S3 is GetObject on artifacts/* and demo/* only"
  }

  assert {
    condition = anytrue([
      for s in jsondecode(aws_iam_role_policy.task.policy).Statement :
      s.Sid == "EcsExec" && alltrue([for a in s.Action : startswith(a, "ssmmessages:")])
    ])
    error_message = "ssmmessages for ECS Exec expected"
  }

  assert {
    condition     = length(jsondecode(aws_iam_role_policy.task.policy).Statement) == 2
    error_message = "task role has nothing besides S3 reads and ECS Exec"
  }
}

run "scheduler_role_is_narrow" {
  assert {
    condition = (
      length(jsondecode(aws_iam_role_policy.scheduler.policy).Statement) == 2
      && anytrue([for s in jsondecode(aws_iam_role_policy.scheduler.policy).Statement : s.Action == ["ecs:UpdateService"] && s.Resource == "arn:aws:ecs:eu-central-1:123456789012:service/saiman-demo/saiman-demo-lite"])
      && anytrue([for s in jsondecode(aws_iam_role_policy.scheduler.policy).Statement : s.Action == ["rds:StopDBInstance"] && s.Resource == "arn:aws:rds:eu-central-1:123456789012:db:saiman-demo"])
    )
    error_message = "scheduler role: ecs:UpdateService on the demo service and rds:StopDBInstance on the demo instance only"
  }

  assert {
    condition     = jsondecode(aws_iam_role.scheduler.assume_role_policy).Statement[0].Principal.Service == "scheduler.amazonaws.com"
    error_message = "scheduler role must trust scheduler.amazonaws.com"
  }
}

run "every_role_has_boundary_and_path" {
  assert {
    condition = alltrue([
      for r in [aws_iam_role.task_execution, aws_iam_role.task, aws_iam_role.scheduler] :
      r.path == "/saiman/demo/" && r.permissions_boundary == "arn:aws:iam::123456789012:policy/saiman/bootstrap/saiman-demo-boundary" && startswith(r.name, "saiman-demo")
    ])
    error_message = "every role needs path /saiman/demo/, the saiman-demo-boundary and a saiman-demo* name"
  }
}

run "scheduler_backstop_fires_30_minutes_after_expiry" {
  assert {
    condition = (
      aws_scheduler_schedule.stop_service.schedule_expression == "at(2026-10-06T18:30:00)"
      && aws_scheduler_schedule.stop_database.schedule_expression == "at(2026-10-06T18:30:00)"
      && aws_scheduler_schedule.stop_service.schedule_expression_timezone == "UTC"
    )
    error_message = "schedules must be one-time, in UTC, at expires_at + 30 minutes"
  }

  assert {
    condition = (
      one(aws_scheduler_schedule.stop_service.target).arn == "arn:aws:scheduler:::aws-sdk:ecs:updateService"
      && jsondecode(one(aws_scheduler_schedule.stop_service.target).input) == { Cluster = "saiman-demo", Service = "saiman-demo-lite", DesiredCount = 0 }
      && one(aws_scheduler_schedule.stop_database.target).arn == "arn:aws:scheduler:::aws-sdk:rds:stopDBInstance"
      && jsondecode(one(aws_scheduler_schedule.stop_database.target).input) == { DbInstanceIdentifier = "saiman-demo" }
    )
    error_message = "universal targets: ECS UpdateService desiredCount 0 and RDS StopDBInstance"
  }
}

# A numeric offset would shift the at() wall clock (formatdate does not convert), so it is rejected.
run "rejects_expiry_with_utc_offset" {
  command = plan

  variables {
    expires_at = "2026-10-06T20:45:00+02:00"
  }

  expect_failures = [var.expires_at]
}

run "region_is_fixed" {
  assert {
    condition     = output.ssm_parameter_arn_prefix == "arn:aws:ssm:eu-central-1:123456789012:parameter/saiman/demo/*" && aws_subnet.public[0].availability_zone == "eu-central-1a"
    error_message = "everything is pinned to eu-central-1"
  }
}

run "rejects_bad_image_tag" {
  command = plan

  variables {
    image_tag = "latest"
  }

  expect_failures = [var.image_tag]
}

run "rejects_uppercase_or_short_image_sha" {
  command = plan

  variables {
    image_tag = "sha-0123456789A"
  }

  expect_failures = [var.image_tag]
}

run "rejects_bad_payto" {
  command = plan

  variables {
    x402_seller_payto_address = "0x123"
  }

  expect_failures = [var.x402_seller_payto_address]
}

run "rejects_bad_expires_at" {
  command = plan

  variables {
    expires_at = "tomorrow evening"
  }

  expect_failures = [var.expires_at]
}

run "rejects_incomplete_auth_digests" {
  command = plan

  variables {
    auth_digests = {
      reader   = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      operator = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
  }

  expect_failures = [var.auth_digests]
}

run "rejects_corpus_key_outside_artifacts" {
  command = plan

  variables {
    corpus_object_key = "demo/corpus.dump"
  }

  expect_failures = [var.corpus_object_key]
}
