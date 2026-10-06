data "aws_caller_identity" "current" {}

locals {
  # The region is fixed (ADR-0004); the bootstrap roles are locked to it as well.
  region     = "eu-central-1"
  azs        = ["${local.region}a", "${local.region}b"]
  account_id = data.aws_caller_identity.current.account_id

  # Chain RPC is public and testnet-only (rule 1).
  chain_rpc_url = "https://sepolia.base.org"

  # Names must start with saiman-demo: the bootstrap apply role is scoped to that prefix.
  name_prefix  = "saiman-demo"
  cluster_name = local.name_prefix
  service_name = "${local.name_prefix}-lite"
  db_name      = local.name_prefix

  # The three roles are FIXED in the bootstrap stack (bootstrap/demo_roles.tf, path /saiman/demo/): the CI
  # apply role cannot create or edit roles, it can only pass these. Referenced by ARN, never managed here.
  # Names must match bootstrap/demo_roles.tf.
  role_arn_prefix         = "arn:aws:iam::${local.account_id}:role/saiman/demo"
  task_execution_role_arn = "${local.role_arn_prefix}/${local.name_prefix}-task-execution"
  task_role_arn           = "${local.role_arn_prefix}/${local.name_prefix}-task"
  scheduler_role_arn      = "${local.role_arn_prefix}/${local.name_prefix}-scheduler"

  # SecureString parameters are created by the demo-up workflow, never by Terraform (no value may enter
  # state). Containers get them only via ECS secrets[].valueFrom, never environment. ARN strings only.
  ssm_prefix_arn = "arn:aws:ssm:${local.region}:${local.account_id}:parameter/saiman/demo/*"

  # Matches the bootstrap apply role (/saiman-demo*) and the boundary (/*saiman-demo*).
  log_group_name = "/saiman-demo-lite"

  bucket_arn = "arn:aws:s3:::${var.state_bucket_name}"

  # Scheduler backstop fires 30 minutes after expiry (the reaper workflow normally acts first).
  backstop_at = formatdate("YYYY-MM-DD'T'hh:mm:ss", timeadd(var.expires_at, "30m"))
}
