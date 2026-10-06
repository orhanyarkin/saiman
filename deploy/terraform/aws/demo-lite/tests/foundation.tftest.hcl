# Offline tests (mock_provider, no credentials). Provider default_tags are invisible here; tests/check-foundation.sh covers them.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
    }
  }
}

variables {
  image_tag = "sha-0123456789ab"
  image_digests = {
    "orchestrator" = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    "seller-api"   = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    "ledger"       = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    "ingest"       = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    "evals"        = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
  }
  session_id                = "test-session"
  expires_at                = "2026-10-06T18:00:00Z"
  x402_seller_payto_address = "0x1111111111111111111111111111111111111111"
  state_bucket_name         = "saiman-test-tfstate"
  auth_digests = {
    reader         = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    operator       = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    service_ledger = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  }
  db_master_password     = "unit-test-only"
  assets_manifest_sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  corpus_sha256          = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"
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

# The roles are fixed in bootstrap (bootstrap/demo_roles.tf, tested there); demo-lite only references them.
run "roles_are_referenced_not_managed" {
  assert {
    condition = (
      output.task_execution_role_arn == "arn:aws:iam::123456789012:role/saiman/demo/saiman-demo-task-execution"
      && output.task_role_arn == "arn:aws:iam::123456789012:role/saiman/demo/saiman-demo-task"
      && output.scheduler_role_arn == "arn:aws:iam::123456789012:role/saiman/demo/saiman-demo-scheduler"
    )
    error_message = "role ARNs must point at the fixed /saiman/demo/ roles from bootstrap"
  }

  assert {
    condition = (
      one(aws_scheduler_schedule.stop_service.target).role_arn == output.scheduler_role_arn
      && one(aws_scheduler_schedule.stop_database.target).role_arn == output.scheduler_role_arn
    )
    error_message = "both backstop schedules run as the fixed scheduler role"
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

run "rejects_image_digest_that_is_a_tag" {
  command = plan

  variables {
    image_digests = {
      "orchestrator" = "sha-0123456789ab"
      "seller-api"   = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
      "ledger"       = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
      "ingest"       = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
      "evals"        = "sha256:1111111111111111111111111111111111111111111111111111111111111111"
    }
  }

  expect_failures = [var.image_digests]
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
