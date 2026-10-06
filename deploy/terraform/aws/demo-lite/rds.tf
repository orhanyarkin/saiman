resource "aws_db_subnet_group" "main" {
  name        = local.db_name
  description = "Demo RDS subnets (public subnets, instance is not publicly accessible)"
  subnet_ids  = aws_subnet.public[*].id
}

# Role bootstrap (per-service roles, ADR-0027) sends CREATE ROLE ... PASSWORD statements over SQL.
# log_statement = none and log_min_error_statement = panic keep those passwords out of the RDS logs
# even if a statement fails. Do not relax either without moving role bootstrap off plain SQL.
resource "aws_db_parameter_group" "main" {
  name        = local.db_name
  family      = "postgres17"
  description = "Demo RDS: never log statements, force TLS"

  parameter {
    name  = "log_statement"
    value = "none"
  }

  parameter {
    name  = "log_min_error_statement"
    value = "panic"
  }

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
}

resource "aws_db_instance" "main" {
  identifier     = local.db_name
  engine         = "postgres"
  engine_version = "17"
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "saiman"
  username = "saiman"
  # Write-only: the password is never stored in plan or state. password_wo_version is pinned because
  # the instance is recreated every session. manage_master_user_password stays off (no Secrets Manager).
  password_wo         = var.db_master_password
  password_wo_version = 1

  db_subnet_group_name   = aws_db_subnet_group.main.name
  parameter_group_name   = aws_db_parameter_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  backup_retention_period  = 0
  skip_final_snapshot      = true
  delete_automated_backups = true
  deletion_protection      = false

  performance_insights_enabled = false
  monitoring_interval          = 0

  auto_minor_version_upgrade = true
  apply_immediately          = true
}
