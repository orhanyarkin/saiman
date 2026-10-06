data "aws_caller_identity" "current" {}

locals {
  account_id    = data.aws_caller_identity.current.account_id
  bucket_arn    = "arn:aws:s3:::${var.state_bucket_name}"
  role_path     = "/saiman/bootstrap/"
  boundary_name = "saiman-demo-boundary"
  boundary_arn  = "arn:aws:iam::${local.account_id}:policy${local.role_path}${local.boundary_name}"
  oidc_host     = "token.actions.githubusercontent.com"

  # State keys (ADR-0028). The demo-lite stack and its lock file are the only objects the CI roles touch.
  demo_state_key  = "demo-lite/terraform.tfstate"
  demo_state_arn  = "${local.bucket_arn}/${local.demo_state_key}"
  demo_lock_arn   = "${local.bucket_arn}/${local.demo_state_key}.tflock"
  demo_state_glob = "${local.bucket_arn}/demo-lite/*"

  ssm_all_arn  = "arn:aws:ssm:${var.region}:${local.account_id}:parameter/saiman/*"
  ssm_demo_arn = "arn:aws:ssm:${var.region}:${local.account_id}:parameter/saiman/demo/*"

  # Services without a regional endpoint are exempt from the region lock.
  global_service_actions = ["iam:*", "sts:*", "budgets:*"]

  region_lock = {
    Sid       = "DenyOutsideRegion"
    Effect    = "Deny"
    NotAction = local.global_service_actions
    Resource  = "*"
    Condition = {
      StringNotEquals = { "aws:RequestedRegion" = var.region }
    }
  }
}

# --- State bucket -------------------------------------------------------------------------------

resource "aws_s3_bucket" "state" {
  bucket = var.state_bucket_name

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # SSE-S3: no KMS key, so no per-key monthly charge
    }
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_policy" "state" {
  bucket     = aws_s3_bucket.state.id
  depends_on = [aws_s3_bucket_public_access_block.state]

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [local.bucket_arn, "${local.bucket_arn}/*"]
      Condition = {
        Bool = { "aws:SecureTransport" = "false" }
      }
    }]
  })
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket     = aws_s3_bucket.state.id
  depends_on = [aws_s3_bucket_versioning.state]

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 3
    }
  }

  # Demo exports are short-lived; the private corpus dump lives under artifacts/ and is kept.
  rule {
    id     = "expire-demo-prefix"
    status = "Enabled"

    filter {
      prefix = "demo/"
    }

    expiration {
      days = 3
    }

    noncurrent_version_expiration {
      noncurrent_days = 1
    }
  }
}
