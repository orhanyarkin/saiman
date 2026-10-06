variable "image_tag" {
  description = "GHCR image tag of every service, sha-<12 hex> (ADR-0007)."
  type        = string

  validation {
    condition     = can(regex("^sha-[0-9a-f]{12}$", var.image_tag))
    error_message = "image_tag must look like sha-0123456789ab."
  }
}

variable "session_id" {
  description = "Identifier of this demo session; becomes the saiman:session tag."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$", var.session_id))
    error_message = "session_id must be 1-63 characters of letters, digits, '.', '_' or '-'."
  }
}

variable "expires_at" {
  description = "RFC 3339 UTC (Z) timestamp at which the demo expires; the Scheduler backstop fires 30 minutes later."
  type        = string

  validation {
    # formatdate keeps a numeric offset's wall clock, so the Scheduler at() expression would be off by
    # the offset. Requiring UTC ("Z") keeps the backstop exact.
    condition     = can(regex("Z$", var.expires_at)) && can(formatdate("YYYY-MM-DD'T'hh:mm:ss", var.expires_at))
    error_message = "expires_at must be an RFC 3339 UTC timestamp such as 2026-10-06T18:00:00Z."
  }
}

variable "x402_seller_payto_address" {
  description = "Base Sepolia (eip155:84532) address that receives test USDC. Public, not a secret."
  type        = string

  validation {
    condition     = can(regex("^0x[0-9a-fA-F]{40}$", var.x402_seller_payto_address))
    error_message = "x402_seller_payto_address must be a 0x-prefixed 20-byte hex address."
  }
}

variable "auth_digests" {
  description = "SHA-256 digests (64 hex) of the static role tokens (ADR-0023). Digests only, never the tokens."
  type        = map(string)

  validation {
    condition = (
      length(var.auth_digests) == 3
      && alltrue([
        for k in ["reader", "operator", "service_ledger"] :
        contains(keys(var.auth_digests), k) && can(regex("^[0-9a-fA-F]{64}$", var.auth_digests[k]))
      ])
    )
    error_message = "auth_digests needs exactly the keys reader, operator and service_ledger, each a 64-character hex digest."
  }
}

variable "db_master_password" {
  description = "RDS master password. Ephemeral and write-only: never stored in plan or state."
  type        = string
  sensitive   = true
  ephemeral   = true
  nullable    = true
  default     = null
}

variable "state_bucket_name" {
  description = "Name of the bootstrap state bucket; the task role may read its artifacts/ and demo/ prefixes."
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.state_bucket_name))
    error_message = "state_bucket_name must be a valid S3 bucket name."
  }
}

variable "corpus_object_key" {
  description = "Key of the private corpus dump in the state bucket; must live under artifacts/."
  type        = string
  default     = "artifacts/corpus/latest.dump"

  validation {
    condition     = can(regex("^artifacts/[A-Za-z0-9._/-]+\\.dump$", var.corpus_object_key)) && !can(regex("\\.\\.", var.corpus_object_key))
    error_message = "corpus_object_key must be a key like artifacts/corpus/latest.dump (letters, digits, . _ / -; ending in .dump; no ..)."
  }
}

variable "task_cpu" {
  description = "Fargate task CPU units (ADR-0028: 2 vCPU)."
  type        = number
  default     = 2048

  validation {
    condition     = contains([1024, 2048, 4096], var.task_cpu)
    error_message = "task_cpu must be 1024, 2048 or 4096."
  }
}

variable "task_memory" {
  description = "Fargate task memory in MiB (ADR-0028: 8 GB)."
  type        = number
  default     = 8192

  validation {
    condition     = var.task_memory >= 2048 && var.task_memory <= 30720 && var.task_memory % 1024 == 0
    error_message = "task_memory must be a multiple of 1024 between 2048 and 30720."
  }
}

variable "grafana_otlp_endpoint" {
  description = "Grafana Cloud OTLP endpoint (https://<stack>.grafana.net/otlp); null disables the collector export."
  type        = string
  default     = null

  validation {
    condition     = var.grafana_otlp_endpoint == null || can(regex("^https://[a-z0-9.-]+\\.grafana\\.net/otlp$", var.grafana_otlp_endpoint))
    error_message = "grafana_otlp_endpoint must be null or match https://<host>.grafana.net/otlp."
  }
}

variable "assets_manifest_sha256" {
  description = "Aggregate sha256 of the runtime asset bundle: the `manifest-sha256` line printed by assets/build-assets.sh. The assets container fails the task on mismatch."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{64}$", var.assets_manifest_sha256))
    error_message = "assets_manifest_sha256 must be 64 lowercase hex characters."
  }
}

variable "corpus_sha256" {
  description = "sha256 of the corpus dump at corpus_object_key; checked on the fetched file before anything is restored."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{64}$", var.corpus_sha256))
    error_message = "corpus_sha256 must be 64 lowercase hex characters."
  }
}
