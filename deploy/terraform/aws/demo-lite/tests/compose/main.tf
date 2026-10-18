# Test-only helper: reads deploy/compose/docker-compose.yml and tests/compose-parity-allowlist.json and publishes, per app
# service, the environment and secret names compose gives it, in one comparable spelling. tests/task.tftest.hcl compares
# them with the ECS container definitions (run "ecs_names_match_compose").
#
# Spring maps SPRING_DATASOURCE_PASSWORD (ECS, an env var), spring.datasource.password (compose, a secret file read through
# configtree) and spring.datasource.password (property) to the same setting, so every name is reduced to lower case without
# "." "_" "-" first. A name the two sides spell differently on purpose is an alias in the allowlist, with its reason.
locals {
  compose   = yamldecode(file("${path.module}/../../../../../compose/docker-compose.yml"))
  allow     = jsondecode(file("${path.module}/../compose-parity-allowlist.json"))
  services  = keys(local.allow.services)
  alias_ecs = { for k, a in local.allow.aliases : k => a.ecs }

  # compose: environment keys plus secret targets (short syntax: the secret name, mounted as /run/secrets/<name>)
  raw = { for s in local.services : s => concat(
    keys(local.compose.services[s].environment),
    [for x in local.compose.services[s].secrets : try(x.target, x)],
  ) }
  expanded = { for s, names in local.raw : s => flatten([for n in names : lookup(local.alias_ecs, n, [n])]) }
  by_canon = { for s, names in local.expanded : s => { for n in names : lower(replace(replace(replace(n, ".", ""), "_", ""), "-", "")) => n } }

  canon = { for k in ["compose_only", "ecs_only"] : k => {
    for s in local.services : s => [for n in keys(local.allow.services[s][k]) : lower(replace(replace(replace(n, ".", ""), "_", ""), "-", ""))]
  } }
}

output "compose_names" {
  description = "service -> { comparable spelling = name as compose writes it (alias targets: as ECS writes it) }"
  value       = local.by_canon
}

output "compose_only" {
  description = "service -> comparable spellings that compose has and ECS deliberately does not"
  value       = local.canon.compose_only
}

output "ecs_only" {
  description = "service -> comparable spellings that ECS has and compose deliberately does not"
  value       = local.canon.ecs_only
}

output "aliases" {
  description = "compose name -> the ECS names that replace it"
  value       = local.alias_ecs
}

output "raw_names" {
  description = "service -> the names compose writes, before aliases"
  value       = local.raw
}
