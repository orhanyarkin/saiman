# Test-only helper: reads the IAM fact files once and publishes them as lookup maps, so tests/bootstrap.tftest.hcl does not
# re-parse 60 KB of JSON and scan 1400 action names for every statement (that took over a minute per run).
locals {
  facts    = jsondecode(file("${path.module}/../iam-action-facts.json"))
  required = jsondecode(file("${path.module}/../required-actions.json"))
}

output "services" {
  value = local.facts.services
}

output "known" {
  value = local.facts.known
}

output "known_map" {
  value = { for k in local.facts.known : k => true }
}

output "star_only" {
  value = local.facts.star_only
}

output "star_map" {
  value = { for k in local.facts.star_only : k => true }
}

output "resources" {
  value = local.required.resources
}

output "ops" {
  value = local.required.ops
}
