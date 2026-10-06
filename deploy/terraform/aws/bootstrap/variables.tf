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

variable "github_repo" {
  description = "GitHub repository (owner/name) allowed to assume the roles."
  type        = string
  default     = "orhanyarkin/saiman"
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
