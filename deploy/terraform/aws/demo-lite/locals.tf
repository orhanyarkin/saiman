data "aws_caller_identity" "current" {}

locals {
  # The region is fixed (ADR-0004); the bootstrap roles are locked to it as well.
  region     = "eu-central-1"
  azs        = ["${local.region}a", "${local.region}b"]
  account_id = data.aws_caller_identity.current.account_id

  # Chain RPC is public and testnet-only (rule 1).
  chain_rpc_url = "https://sepolia.base.org"
  chain_network = "eip155:84532"

  # Names must start with saiman-demo: the bootstrap apply role is scoped to that prefix.
  name_prefix  = "saiman-demo"
  cluster_name = local.name_prefix
  service_name = "${local.name_prefix}-lite"
  db_name      = local.name_prefix

  # Created by ecs.tf (A3a) but referenced here by name, so IAM stays independent of the cluster.
  service_arn = "arn:aws:ecs:${local.region}:${local.account_id}:service/${local.cluster_name}/${local.service_name}"
  rds_arn     = "arn:aws:rds:${local.region}:${local.account_id}:db:${local.db_name}"

  role_path    = "/saiman/demo/"
  boundary_arn = "arn:aws:iam::${local.account_id}:policy/saiman/bootstrap/saiman-demo-boundary"

  # SecureString parameters are created by the demo-up workflow, never by Terraform. ARN strings only.
  ssm_prefix_arn = "arn:aws:ssm:${local.region}:${local.account_id}:parameter/saiman/demo/*"

  # Matches the bootstrap apply role (/saiman-demo*) and the boundary (/*saiman-demo*).
  log_group_name = "/saiman-demo-lite"
  log_group_arn  = "arn:aws:logs:${local.region}:${local.account_id}:log-group:${local.log_group_name}"

  bucket_arn = "arn:aws:s3:::${var.state_bucket_name}"
  s3_read_arns = [
    "${local.bucket_arn}/artifacts/*",
    "${local.bucket_arn}/demo/*",
  ]

  # Scheduler backstop fires 30 minutes after expiry (the reaper workflow normally acts first).
  backstop_at = formatdate("YYYY-MM-DD'T'hh:mm:ss", timeadd(var.expires_at, "30m"))
}
