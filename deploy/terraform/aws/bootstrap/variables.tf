variable "region" {
  description = "The only region the stack and the roles may operate in (ADR-0004)."
  type        = string
  default     = "eu-central-1"

  validation {
    condition     = var.region == "eu-central-1"
    error_message = "The demo is locked to eu-central-1."
  }
}

variable "state_bucket_name" {
  description = "Globally unique name of the Terraform state bucket. Supplied via terraform.tfvars (gitignored), never hardcoded."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.state_bucket_name))
    error_message = "state_bucket_name must be a valid S3 bucket name."
  }
}

variable "oidc_subject_repo" {
  description = <<-EOT
    The repository exactly as GitHub writes it after "repo:" in the OIDC `sub` claim. Repositories with immutable
    subject claims (the default for newer repositories) use owner and repository IDs, for example
    "orhanyarkin@44910233/saiman@1390957366"; older repositories use "owner/name". Read the real value with
      gh api repos/<owner>/<repo>/actions/oidc/customization/sub --jq .sub_claim_prefix
    and drop the leading "repo:". A mismatch makes every role refuse the token ("Not authorized to perform
    sts:AssumeRoleWithWebIdentity").
  EOT
  type        = string
  default     = "orhanyarkin@44910233/saiman@1390957366"

  validation {
    condition     = can(regex("^[A-Za-z0-9._-]+(@[0-9]+)?/[A-Za-z0-9._-]+(@[0-9]+)?$", var.oidc_subject_repo))
    error_message = "oidc_subject_repo must look like owner/name or owner@<id>/name@<id>."
  }
}

variable "apply_environment" {
  description = "GitHub environment whose jobs may assume the apply role (must require human approval)."
  type        = string
  default     = "demo-apply"
}

variable "destroy_environment" {
  description = "GitHub environment whose jobs may assume the destroy role."
  type        = string
  default     = "demo-destroy"
}
