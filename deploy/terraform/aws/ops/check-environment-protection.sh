#!/usr/bin/env bash
# Preflight for demo-up / demo-destroy (audit L3): the OIDC `sub` claim only names the GitHub environment, so the
# environment's protection IS the access control. Verifies through the GitHub API that the environment
#   - restricts deployments to the `main` branch (custom branch policy with exactly `main`),
#   - does not let administrators bypass (can_admins_bypass == false),
#   - has a required reviewer (only with --require-reviewer: demo-apply).
#
# Reading environment settings needs admin scope, which the workflow GITHUB_TOKEN does not have. Therefore:
#   - with ENV_AUDIT_TOKEN set (a fine-grained token with "Administration: read" on this repo, stored as a
#     repository secret) the check is STRICT: any non-compliance or read failure fails the job;
#   - without it the check is BEST EFFORT: a read failure only prints a loud ::warning::, a readable and
#     non-compliant environment still fails.
# Usage: check-environment-protection.sh <environment> [--require-reviewer]
# Env: GITHUB_REPOSITORY, GH_TOKEN, ENV_AUDIT_TOKEN (optional, overrides GH_TOKEN). Exit 0 ok, 1 not compliant, 2 usage.
set -euo pipefail

[[ $# -ge 1 && -n "${GITHUB_REPOSITORY:-}" ]] || {
  echo "usage: check-environment-protection.sh <environment> [--require-reviewer]" >&2
  exit 2
}
env_name="$1"
need_reviewer=0
[[ "${2:-}" == "--require-reviewer" ]] && need_reviewer=1
strict=0
if [[ -n "${ENV_AUDIT_TOKEN:-}" ]]; then
  strict=1
  export GH_TOKEN="${ENV_AUDIT_TOKEN}"
fi
work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-envcheck.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

unreadable() {
  if [[ "${strict}" -eq 1 ]]; then
    echo "::error::cannot read the settings of environment ${env_name} with ENV_AUDIT_TOKEN (needs Administration: read)"
    exit 1
  fi
  echo "::warning::environment ${env_name} protection NOT verified (the workflow token cannot read it). Set the repository secret ENV_AUDIT_TOKEN to make this check strict, and verify by hand: deployment branches = main only, admin bypass off$([[ ${need_reviewer} -eq 1 ]] && echo ', required reviewer')."
  exit 0
}

gh api "repos/${GITHUB_REPOSITORY}/environments/${env_name}" >"${work}/env.json" 2>/dev/null || unreadable
gh api "repos/${GITHUB_REPOSITORY}/environments/${env_name}/deployment-branch-policies" >"${work}/branches.json" 2>/dev/null || unreadable

problems=()
[[ "$(jq -r '.can_admins_bypass' "${work}/env.json")" == "false" ]] || problems+=("administrators can bypass the protection rules")
[[ "$(jq -r '.deployment_branch_policy.custom_branch_policies // false' "${work}/env.json")" == "true" ]] || problems+=("deployment branches are not restricted to selected branches")
[[ "$(jq -c '[.branch_policies[]?.name] | sort' "${work}/branches.json")" == '["main"]' ]] || problems+=("deployment branch policy is not exactly: main")
if [[ "${need_reviewer}" -eq 1 ]]; then
  [[ "$(jq -r '[.protection_rules[]? | select(.type == "required_reviewers")] | length' "${work}/env.json")" -ge 1 ]] || problems+=("no required reviewer")
fi
if [[ ${#problems[@]} -gt 0 ]]; then
  for p in "${problems[@]}"; do echo "::error::environment ${env_name}: ${p}"; done
  echo "Fix in Settings > Environments > ${env_name} (see deploy/terraform/aws/README.md, one-time bootstrap step 4)."
  exit 1
fi
echo "environment ${env_name}: protection verified"
