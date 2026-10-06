terraform {
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = local.region

  # Every resource carries the session so check-demo-down and Cost Explorer can attribute it.
  default_tags {
    tags = {
      project          = "saiman"
      "saiman:stack"   = "demo-lite"
      "saiman:session" = var.session_id
    }
  }
}
