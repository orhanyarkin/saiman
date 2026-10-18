#!/usr/bin/env bash
# Teardown check for demo-lite (ADR-0028): after `demo-down` nothing may remain except the bootstrap
# stack (state bucket, OIDC provider, the CI roles, the permissions boundary, the three fixed demo roles).
# Read-only: AWS CLI list/describe calls only. Prints resource ids (names, ARNs), never values.
#
# Usage: check-demo-down.sh --region eu-central-1 --state-bucket <bucket> [--strict | --allow-unverified]
#
# Every check ends as CLEAN, LEFTOVER or UNVERIFIED (the caller's role may not be allowed to list that
# service: the destroy role is not, see ops/README in deploy/terraform/aws/README.md).
#   --strict            (default) UNVERIFIED checks fail the run: use it with your own SSO admin profile.
#   --allow-unverified  UNVERIFIED checks are printed but do not fail: used by the demo-destroy workflow.
#
# Two more rows never fail the run (they are not leftovers):
#   STALE (tagging index)  the Tagging API still lists the ARN (it lags behind deletes by minutes to an hour) but
#                          the owning service's own describe call says it is gone.
#   INACTIVE               a deregistered ECS task definition: history that costs nothing (demo-cleanup.sh deletes it).
#
# Exit codes: 0 clean, 1 leftovers (or UNVERIFIED in --strict mode), 2 usage, credentials or AWS error.
set -euo pipefail

# Bootstrap allowlist (keep in sync with bootstrap/iam.tf and bootstrap/demo_roles.tf; the self-test greps both).
readonly ALLOWED_ROLES=(
  "/saiman/bootstrap/|saiman-gha-plan"
  "/saiman/bootstrap/|saiman-gha-apply"
  "/saiman/bootstrap/|saiman-gha-destroy"
  "/saiman/demo/|saiman-demo-task-execution"
  "/saiman/demo/|saiman-demo-task"
  "/saiman/demo/|saiman-demo-scheduler"
)
readonly ALLOWED_POLICIES=("/saiman/bootstrap/|saiman-demo-boundary")
readonly NAME_PREFIX="saiman-demo"
readonly DENIED_RE='AccessDenied|UnauthorizedOperation|not authorized|AuthorizationError|UnauthorizedAccess'

usage() {
  sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed '$d' | sed 's/^# \{0,1\}//' >&2
  exit 2
}

region=""
bucket=""
strict=1
ignore_expiry=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --region)
      [[ $# -ge 2 ]] || usage
      region="$2"
      shift 2
      ;;
    --state-bucket)
      [[ $# -ge 2 ]] || usage
      bucket="$2"
      shift 2
      ;;
    --strict)
      strict=1
      shift
      ;;
    --allow-unverified)
      strict=0
      shift
      ;;
    --ignore-expiry-param)
      # demo-destroy deletes /saiman/demo/expires-at only AFTER this check passed (it keeps the reaper armed).
      ignore_expiry=1
      shift
      ;;
    -h | --help) usage ;;
    *)
      echo "check-demo-down: unknown argument $1" >&2
      usage
      ;;
  esac
done
[[ -n "${region}" && -n "${bucket}" ]] || usage
[[ "${region}" =~ ^[a-z]{2}-[a-z]+-[0-9]$ ]] || {
  echo "check-demo-down: invalid region" >&2
  exit 2
}
[[ "${bucket}" =~ ^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$ ]] || {
  echo "check-demo-down: invalid bucket name" >&2
  exit 2
}
command -v aws >/dev/null 2>&1 || {
  echo "check-demo-down: aws CLI not found" >&2
  exit 2
}
command -v jq >/dev/null 2>&1 || {
  echo "check-demo-down: jq not found" >&2
  exit 2
}

export AWS_PAGER=""
work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-down-check.XXXXXX")"
trap 'rm -rf "${work}"' EXIT
out="${work}/out.json"
err="${work}/err.txt"

if ! aws --region "${region}" --output json sts get-caller-identity >/dev/null 2>"${err}"; then
  echo "check-demo-down: no usable AWS credentials (sts get-caller-identity failed)" >&2
  exit 2
fi

leftovers=()
unverified=()
errors=()
clean=()
stale=()    # tagged ARNs the owning service says are gone (Tagging API lag): reported, never a failure
inactive=() # deregistered ECS task definitions: history, reported, never a failure
notes=()    # informational checks that could not run: reported, never a failure

# fetch <label> <aws args...>: JSON result in ${out}. Returns 1 (after recording why) when the call failed.
# Runs in the main shell on purpose: results travel through the arrays and files, not through $(...).
fetch() {
  local label="$1"
  shift
  if aws --region "${region}" --output json "$@" >"${out}" 2>"${err}"; then
    return 0
  fi
  if grep -qiE "${DENIED_RE}" "${err}"; then
    unverified+=("${label}")
  else
    errors+=("${label}")
  fi
  return 1
}

# report <label> <jq filter that prints one id per leftover>
report() {
  local label="$1" filter="$2" line found=0
  # A jq failure (unparseable response) must never read as CLEAN.
  if ! jq -r "${filter}" "${out}" >"${work}/lines.txt" 2>/dev/null; then
    errors+=("${label} (unparseable response)")
    return 0
  fi
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    leftovers+=("${label}: ${line}")
    found=1
  done <"${work}/lines.txt"
  if [[ "${found}" -eq 0 ]]; then
    clean+=("${label}")
  fi
}

# check <label> <jq filter> <aws args...>
check() {
  local label="$1" filter="$2"
  shift 2
  if fetch "${label}" "$@"; then
    report "${label}" "${filter}"
  fi
}

prefix_filter() { # <jq expression yielding the name> -> jq filter listing names that start with saiman-demo
  printf '(%s // empty) | select(startswith("%s"))' "$1" "${NAME_PREFIX}"
}

# --- tag-based sweep (anything demo-lite created and tagged) ----------------------------------------
# The Resource Groups Tagging API is eventually consistent: it keeps listing deleted resources (security group
# rules, task definitions) for a while. So every tagged ARN is confirmed with the owning service's own describe call:
#   exists -> LEFTOVER   gone -> STALE (tagging index), not a failure   INACTIVE task definition -> INACTIVE row
# A type without a direct check, a denied check and an ambiguous answer all stay a LEFTOVER or an ERROR (fail
# closed): the sweep is never weaker than reading the index.
verdict=""
direct_arg=""

# direct <jq expression yielding the live objects ($n = ${direct_arg})> <aws args...>: one service's own describe
# call. Sets verdict to exists, gone, denied or failed; the response is left in ${out} when the call worked.
direct() {
  local live="$1" rc=0
  shift
  if aws --region "${region}" --output json "$@" >"${out}" 2>"${err}" </dev/null; then
    jq -e --arg n "${direct_arg}" "[${live}] | length > 0" "${out}" >/dev/null 2>&1 && rc=0 || rc=$?
    case "${rc}" in
      0) verdict=exists ;;
      1) verdict=gone ;;
      *) verdict=failed ;;
    esac
  elif grep -qiE "${DENIED_RE}" "${err}"; then
    verdict=denied
  elif grep -qE 'NotFound|Unable to describe task definition' "${err}"; then
    verdict=gone
  else
    verdict=failed
  fi
}

# confirm_tagged <arn>: sets verdict to exists, gone, inactive, denied, failed or unknown (no direct check known).
confirm_tagged() {
  local arn="$1" svc="" rest="" name=""
  IFS=: read -r _ _ svc _ _ rest <<<"${arn}"
  verdict=unknown
  direct_arg=""
  case "${svc}:${rest}" in
    ec2:security-group-rule/*) direct '.SecurityGroupRules[]?' ec2 describe-security-group-rules --security-group-rule-ids "${rest#*/}" ;;
    ec2:security-group/*) direct '.SecurityGroups[]?' ec2 describe-security-groups --group-ids "${rest#*/}" ;;
    ec2:vpc/*) direct '.Vpcs[]?' ec2 describe-vpcs --vpc-ids "${rest#*/}" ;;
    ec2:subnet/*) direct '.Subnets[]?' ec2 describe-subnets --subnet-ids "${rest#*/}" ;;
    ec2:internet-gateway/*) direct '.InternetGateways[]?' ec2 describe-internet-gateways --internet-gateway-ids "${rest#*/}" ;;
    ec2:route-table/*) direct '.RouteTables[]?' ec2 describe-route-tables --route-table-ids "${rest#*/}" ;;
    ec2:network-interface/*) direct '.NetworkInterfaces[]?' ec2 describe-network-interfaces --network-interface-ids "${rest#*/}" ;;
    ec2:elastic-ip/*) direct '.Addresses[]?' ec2 describe-addresses --allocation-ids "${rest#*/}" ;;
    ecs:task-definition/*)
      # A deregistered revision is still described (status INACTIVE, later DELETE_IN_PROGRESS); a deleted one is not.
      direct '.taskDefinition // empty' ecs describe-task-definition --task-definition "${arn}"
      if [[ "${verdict}" == exists && "$(jq -r '.taskDefinition.status // empty' "${out}")" != "ACTIVE" ]]; then
        verdict=inactive
      fi
      ;;
    ecs:cluster/*) direct '.clusters[]? | select(.status != "INACTIVE")' ecs describe-clusters --clusters "${arn}" ;;
    ecs:service/*/*)
      name="${rest#service/}"
      direct '.services[]? | select(.status != "INACTIVE")' ecs describe-services --cluster "${name%%/*}" --services "${arn}"
      ;;
    rds:db:*) direct '.DBInstances[]?' rds describe-db-instances --db-instance-identifier "${rest#db:}" ;;
    rds:subgrp:*) direct '.DBSubnetGroups[]?' rds describe-db-subnet-groups --db-subnet-group-name "${rest#subgrp:}" ;;
    rds:pg:*) direct '.DBParameterGroups[]?' rds describe-db-parameter-groups --db-parameter-group-name "${rest#pg:}" ;;
    logs:log-group:*)
      name="${rest#log-group:}"
      direct_arg="${name%:\*}"
      direct '.logGroups[]? | select(.logGroupName == $n)' logs describe-log-groups --log-group-name-prefix "${direct_arg}"
      ;;
    scheduler:schedule/*/*)
      name="${rest#schedule/}"
      direct '.Name // empty' scheduler get-schedule --group-name "${name%%/*}" --name "${name#*/}"
      ;;
    *) ;;
  esac
}

tag_label="tagged resources (saiman:stack=demo-lite)"
if fetch "${tag_label}" resourcegroupstaggingapi get-resources --tag-filters "Key=saiman:stack,Values=demo-lite"; then
  if jq -r '.ResourceTagMappingList[]?.ResourceARN' "${out}" >"${work}/tagged.txt" 2>/dev/null; then
    tag_problem=0
    while IFS= read -r arn; do
      [[ -n "${arn}" ]] || continue
      confirm_tagged "${arn}"
      case "${verdict}" in
        exists) leftovers+=("${tag_label}: ${arn}") ;;
        gone) stale+=("${arn}") ;;
        inactive) inactive+=("${arn}") ;;
        denied) leftovers+=("${tag_label}: ${arn} (tagging index only: this role may not describe it)") ;;
        unknown) leftovers+=("${tag_label}: ${arn} (unverified: no direct check for this resource type)") ;;
        *) errors+=("${tag_label}: could not confirm ${arn}") ;;
      esac
      [[ "${verdict}" == gone || "${verdict}" == inactive ]] || tag_problem=1
    done <"${work}/tagged.txt"
    [[ "${tag_problem}" -eq 0 ]] && clean+=("${tag_label}")
  else
    errors+=("${tag_label} (unparseable response)")
  fi
fi

# --- ECS ------------------------------------------------------------------------------------------
if fetch "ecs clusters" ecs list-clusters; then
  cluster_arns="${work}/clusters.txt"
  jq -r '.clusterArns[]? | select(test("/saiman-demo"))' "${out}" >"${cluster_arns}" || errors+=("ecs clusters (unparseable response)")
  if [[ -s "${cluster_arns}" ]]; then
    while IFS= read -r arn; do
      leftovers+=("ecs cluster: ${arn}")
      if fetch "ecs services" ecs list-services --cluster "${arn}"; then
        while IFS= read -r svc; do
          [[ -n "${svc}" ]] && leftovers+=("ecs service: ${svc}")
        done < <(jq -r '.serviceArns[]?' "${out}")
      fi
    done <"${cluster_arns}"
  else
    clean+=("ecs clusters")
  fi
fi
# INACTIVE task definitions are fine (they are history and cost nothing); ACTIVE ones are leftovers.
# Every family, not only saiman-demo*: an ACTIVE task definition of any name is not part of the bootstrap stack.
check "ecs task definitions (ACTIVE, any family)" '.taskDefinitionArns[]?' \
  ecs list-task-definitions --status ACTIVE
# Informational: deregistered saiman-demo* revisions stay listed as INACTIVE (then DELETE_IN_PROGRESS) until ECS has
# deleted them (demo-cleanup.sh asks for it). --family-prefix is an exact family name, so filter by name here instead.
for status in INACTIVE DELETE_IN_PROGRESS; do
  if aws --region "${region}" --output json ecs list-task-definitions --status "${status}" >"${out}" 2>"${err}" </dev/null; then
    if jq -r '.taskDefinitionArns[]? | select((split("/") | last) | startswith("'"${NAME_PREFIX}"'"))' "${out}" >"${work}/inactive.txt" 2>/dev/null; then
      while IFS= read -r arn; do
        [[ -n "${arn}" ]] && inactive+=("${arn}")
      done <"${work}/inactive.txt"
    else
      notes+=("${status} task definitions: unparseable response")
    fi
  else
    notes+=("${status} task definitions could not be listed")
  fi
done

# --- RDS ------------------------------------------------------------------------------------------
check "rds instances" "$(prefix_filter '.DBInstances[]?.DBInstanceIdentifier')" rds describe-db-instances
# No --snapshot-type: the default returns manual and automated snapshots.
check "rds snapshots" '.DBSnapshots[]? | select((.DBInstanceIdentifier // "") | startswith("'"${NAME_PREFIX}"'")) | .DBSnapshotIdentifier' \
  rds describe-db-snapshots
check "rds subnet groups" "$(prefix_filter '.DBSubnetGroups[]?.DBSubnetGroupName')" rds describe-db-subnet-groups
check "rds parameter groups" "$(prefix_filter '.DBParameterGroups[]?.DBParameterGroupName')" rds describe-db-parameter-groups

# --- networking (tag project=saiman) ----------------------------------------------------------------
tag_filter="Name=tag:project,Values=saiman"
check "vpcs (tagged saiman)" '.Vpcs[]?.VpcId' ec2 describe-vpcs --filters "${tag_filter}"
check "subnets (tagged saiman)" '.Subnets[]?.SubnetId' ec2 describe-subnets --filters "${tag_filter}"
check "internet gateways (tagged saiman)" '.InternetGateways[]?.InternetGatewayId' ec2 describe-internet-gateways --filters "${tag_filter}"
check "network interfaces (tagged saiman)" '.NetworkInterfaces[]?.NetworkInterfaceId' ec2 describe-network-interfaces --filters "${tag_filter}"
check "elastic ips (tagged saiman)" '.Addresses[]?.AllocationId' ec2 describe-addresses --filters "${tag_filter}"
check "security groups (tagged saiman)" '.SecurityGroups[]?.GroupId' ec2 describe-security-groups --filters "${tag_filter}"

# --- logs, parameters, schedules --------------------------------------------------------------------
check "log groups (/saiman*)" '.logGroups[]?.logGroupName' logs describe-log-groups --log-group-name-prefix /saiman
ssm_filter='.Parameters[]?.Name'
if [[ "${ignore_expiry}" -eq 1 ]]; then
  ssm_filter='.Parameters[]?.Name | select(. != "/saiman/demo/expires-at")'
fi
check "log groups (/ecs/saiman-demo*)" '.logGroups[]?.logGroupName' logs describe-log-groups --log-group-name-prefix /ecs/saiman-demo
check "ssm parameters (/saiman/demo/)" "${ssm_filter}" \
  ssm describe-parameters --parameter-filters "Key=Name,Option=BeginsWith,Values=/saiman/demo/"
check "scheduler schedules" "$(prefix_filter '.Schedules[]?.Name')" scheduler list-schedules

# --- S3: only the state bucket may exist; demo/ must be empty, artifacts/ is allowed -----------------
check "s3 buckets other than the state bucket" \
  '.Buckets[]?.Name | select(startswith("saiman")) | select(. != "'"${bucket}"'")' s3api list-buckets
check "s3 objects under demo/" '.Contents[]?.Key' s3api list-objects-v2 --bucket "${bucket}" --prefix demo/
check "s3 object versions under demo/" '.Versions[]? | "\(.Key) (\(.VersionId))"' \
  s3api list-object-versions --bucket "${bucket}" --prefix demo/

# --- IAM: the bootstrap roles are EXPECTED; any other saiman role or policy is a finding (audit M1) ---
if fetch "iam roles" iam list-roles; then
  allowed_roles="${work}/allowed-roles.txt"
  printf '%s\n' "${ALLOWED_ROLES[@]}" >"${allowed_roles}"
  roles_found=0
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    leftovers+=("iam role not in the bootstrap allowlist: ${line}")
    roles_found=1
  done < <(
    if jq -r '.Roles[]? | select((.Path | startswith("/saiman/")) or (.RoleName | startswith("saiman"))) | "\(.Path)|\(.RoleName)"' "${out}" >"${work}/roles.txt" 2>/dev/null; then
      grep -vxFf "${allowed_roles}" "${work}/roles.txt" || true
    else
      echo "unparseable response"
    fi
  )
  [[ "${roles_found}" -eq 1 ]] || clean+=("iam roles (only the bootstrap allowlist)")
fi
if fetch "iam policies" iam list-policies --scope Local --path-prefix /saiman/; then
  allowed_policies="${work}/allowed-policies.txt"
  printf '%s\n' "${ALLOWED_POLICIES[@]}" >"${allowed_policies}"
  policies_found=0
  while IFS= read -r line; do
    [[ -n "${line}" ]] || continue
    leftovers+=("iam policy not in the bootstrap allowlist: ${line}")
    policies_found=1
  done < <(
    if jq -r '.Policies[]? | "\(.Path)|\(.PolicyName)"' "${out}" >"${work}/policies.txt" 2>/dev/null; then
      grep -vxFf "${allowed_policies}" "${work}/policies.txt" || true
    else
      echo "unparseable response"
    fi
  )
  [[ "${policies_found}" -eq 1 ]] || clean+=("iam policies (only the bootstrap allowlist)")
fi

# --- services demo-lite must never use -------------------------------------------------------------
check "secrets manager secrets (incl. pending deletion)" '.SecretList[]?.Name' \
  secretsmanager list-secrets --include-planned-deletion
check "load balancers (elbv2)" '.LoadBalancers[]?.LoadBalancerArn' elbv2 describe-load-balancers
check "load balancers (classic)" '.LoadBalancerDescriptions[]?.LoadBalancerName' elb describe-load-balancers
check "ecr repositories" '.repositories[]?.repositoryName' ecr describe-repositories
check "cloud map namespaces" '.Namespaces[]?.Id' servicediscovery list-namespaces

# --- result -----------------------------------------------------------------------------------------
for item in "${clean[@]}"; do
  echo "CLEAN       ${item}"
done
for item in "${unverified[@]}"; do
  echo "UNVERIFIED  ${item} (access denied for this role)"
done
for item in "${errors[@]}"; do
  echo "ERROR       ${item} (the aws call failed, not a permission problem)"
done
for item in "${stale[@]}"; do
  echo "STALE       (tagging index) ${item} (gone on a direct check; the Tagging API lags behind deletes)"
done
inactive_count=0
while IFS= read -r item; do
  [[ -n "${item}" ]] || continue
  inactive_count=$((inactive_count + 1))
  echo "INACTIVE    ecs task definition: ${item} (history or being deleted, not a leftover; demo-cleanup.sh deletes it)"
done < <(printf '%s\n' "${inactive[@]}" | sort -u)
for item in "${notes[@]}"; do
  echo "NOTE        ${item}"
done
for item in "${leftovers[@]}"; do
  echo "LEFTOVER    ${item}"
done
if [[ ${#stale[@]} -gt 0 || "${inactive_count}" -gt 0 ]]; then
  echo "check-demo-down: ${#stale[@]} stale tagging-index entr(ies) and ${inactive_count} INACTIVE task definition(s) are not counted as leftovers"
fi

if [[ ${#errors[@]} -gt 0 ]]; then
  echo "check-demo-down: ${#errors[@]} AWS call(s) failed; result is unreliable" >&2
  exit 2
fi
if [[ ${#leftovers[@]} -gt 0 ]]; then
  echo "check-demo-down: ${#leftovers[@]} leftover(s) found" >&2
  exit 1
fi
if [[ ${#unverified[@]} -gt 0 ]]; then
  if [[ "${strict}" -eq 1 ]]; then
    echo "check-demo-down: ${#unverified[@]} check(s) could not run (--strict); rerun with an admin profile" >&2
    exit 1
  fi
  echo "check-demo-down: no leftovers among the checks this role may run; ${#unverified[@]} unverified (run 'make demo-down-check' with your SSO admin profile)"
  exit 0
fi
echo "check-demo-down: clean (only the bootstrap stack remains)"
