# Public subnets only: no NAT Gateway (about $33/month) and no load balancer (ADR-0028). Nothing is
# reachable from the internet because the task security group has no ingress rule at all; the human
# connects through an SSM port-forward.

resource "aws_vpc" "main" {
  cidr_block           = "10.42.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = local.name_prefix }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = local.name_prefix }
}

resource "aws_subnet" "public" {
  count = length(local.azs)

  vpc_id                  = aws_vpc.main.id
  availability_zone       = local.azs[count.index]
  cidr_block              = cidrsubnet(aws_vpc.main.cidr_block, 8, count.index)
  map_public_ip_on_launch = false # the ECS service assigns a public IP explicitly (egress only)

  tags = { Name = "${local.name_prefix}-public-${count.index}" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${local.name_prefix}-public" }
}

resource "aws_route" "internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}

resource "aws_route_table_association" "public" {
  count = length(local.azs)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# Gateway endpoints are free; S3 traffic (corpus dump) stays off the internet path.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${local.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [aws_route_table.public.id]

  tags = { Name = "${local.name_prefix}-s3" }
}

# --- Security groups -----------------------------------------------------------------------------
# aws_security_group without inline rules makes Terraform remove AWS's default allow-all egress, so
# only the explicit rule resources below exist. Separate rule resources also avoid the dependency
# cycle between the two groups. tests/check-foundation.sh pins "exactly one ingress rule".

resource "aws_security_group" "task" {
  name        = "${local.name_prefix}-task"
  description = "Demo Fargate task: no ingress, egress 443 and Postgres only"
  vpc_id      = aws_vpc.main.id
}

resource "aws_security_group" "rds" {
  name        = "${local.name_prefix}-rds"
  description = "Demo RDS: Postgres from the task security group only"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_egress_rule" "task_https" {
  security_group_id = aws_security_group.task.id
  description       = "HTTPS: GHCR, SSM, CloudWatch Logs, S3, chain RPC, LLM and Grafana endpoints"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "task_postgres" {
  security_group_id            = aws_security_group.task.id
  description                  = "Postgres to RDS"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.rds.id
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_task" {
  security_group_id            = aws_security_group.rds.id
  description                  = "Postgres from the demo task"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.task.id
}
