#!/usr/bin/env bash
# Self-test for check-demo-down.sh with a fake `aws` executable on PATH (no AWS call, no credentials).
# The fake keys on "<service>_<operation>" and serves canned JSON from a fixtures directory:
#   <key>.json  response (default: {})     <key>.deny  AccessDenied, exit 254     <key>.fail  other error, exit 255
#   <key>.err   exit 254 with the file's text on stderr (for example a NotFound error)
# A call whose first flag is --f with value v is looked up as "<key>--f=v" (slashes in v become _), then "<key>--f", so the
# direct confirmation of a tagged ARN (--group-ids, --task-definition, ...) can answer differently from the sweep
# of the same operation (--filters, --log-group-name-prefix /saiman).
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
script="${here}/check-demo-down.sh"
work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-ops-test.XXXXXX")"
trap 'rm -rf "${work}"' EXIT

bin="${work}/bin"
fx="${work}/fx"
mkdir -p "${bin}" "${fx}"
cat >"${bin}/aws" <<'FAKE'
#!/usr/bin/env bash
# Fake aws: strips the global "--region R --output json", then serves fixtures.
args=("$@")
while [[ ${#args[@]} -gt 0 && "${args[0]}" == --* ]]; do args=("${args[@]:2}"); done
key="${args[0]}_${args[1]}"
echo "${args[0]} ${args[1]}" >>"${FAKE_AWS_DIR}/calls.log"
if [[ -n "${args[2]:-}" && "${args[2]}" == --* ]]; then
  val="${args[3]:-}"
  base="${key}"
  for cand in "${base}${args[2]}=${val//\//_}" "${base}${args[2]}"; do
    for ext in deny fail err json; do
      if [[ -e "${FAKE_AWS_DIR}/${cand}.${ext}" ]]; then key="${cand}"; break 2; fi
    done
  done
fi
if [[ -e "${FAKE_AWS_DIR}/${key}.deny" ]]; then
  echo "An error occurred (AccessDenied) when calling the ${args[1]} operation: not authorized" >&2
  exit 254
fi
if [[ -e "${FAKE_AWS_DIR}/${key}.fail" ]]; then
  echo "Could not connect to the endpoint URL" >&2
  exit 255
fi
if [[ -e "${FAKE_AWS_DIR}/${key}.err" ]]; then
  cat "${FAKE_AWS_DIR}/${key}.err" >&2
  exit 254
fi
# The real ListTaskDefinitions treats --family-prefix as an EXACT family name (`saiman-demo` matches nothing); model that.
serve() {
  local fam="" i
  if [[ "${args[0]}_${args[1]}" == ecs_list-task-definitions ]]; then
    for ((i = 2; i < ${#args[@]}; i++)); do [[ "${args[i]}" == --family-prefix ]] && fam="${args[i + 1]:-}"; done
    if [[ -n "${fam}" ]]; then
      jq --arg f "${fam}" '.taskDefinitionArns |= map(select((split("/") | last | split(":")[0]) == $f))'
      return
    fi
  fi
  cat
}
if [[ -e "${FAKE_AWS_DIR}/${key}.json" ]]; then
  serve <"${FAKE_AWS_DIR}/${key}.json"
else
  echo '{}'
fi
FAKE
chmod +x "${bin}/aws"
cat >"${fx}/sts_get-caller-identity.json" <<'EOF'
{"UserId": "AIDAEXAMPLE", "Account": "123456789012", "Arn": "arn:aws:iam::123456789012:role/saiman/bootstrap/saiman-gha-destroy"}
EOF
export FAKE_AWS_DIR="${fx}"
export PATH="${bin}:${PATH}"

fails=0
ok() { echo "ok:   $*"; }
bad() {
  echo "FAIL: $*" >&2
  fails=$((fails + 1))
}

reset() {
  find "${fx}" -type f ! -name 'sts_get-caller-identity.json' -delete
}

# run_case <description> <expected exit> <expected output substring or ''> [script args...]
run_case() {
  local desc="$1" want="$2" needle="$3" rc=0
  shift 3
  : >"${fx}/calls.log"
  "${script}" "$@" >"${work}/out.txt" 2>&1 || rc=$?
  if [[ "${rc}" -ne "${want}" ]]; then
    bad "${desc}: exit ${rc}, wanted ${want}"
    sed 's/^/      /' "${work}/out.txt" >&2
    return 0
  fi
  if [[ -n "${needle}" ]] && ! grep -qF -- "${needle}" "${work}/out.txt"; then
    bad "${desc}: output does not contain: ${needle}"
    sed 's/^/      /' "${work}/out.txt" >&2
    return 0
  fi
  ok "${desc}"
}

common=(--region eu-central-1 --state-bucket saiman-123456789012-tfstate)

# --- usage and credentials errors (exit 2) ---------------------------------------------------------
reset
run_case "no arguments is a usage error" 2 ""
run_case "missing bucket is a usage error" 2 "" --region eu-central-1
run_case "unknown flag is a usage error" 2 "unknown argument" "${common[@]}" --frobnicate
run_case "invalid bucket is a usage error" 2 "invalid bucket" --region eu-central-1 --state-bucket 'Bad_Bucket'
touch "${fx}/sts_get-caller-identity.fail"
run_case "credentials error is exit 2" 2 "no usable AWS credentials" "${common[@]}"
reset
touch "${fx}/ec2_describe-vpcs.fail"
run_case "a non-permission AWS failure is exit 2" 2 "ERROR" "${common[@]}"

# --- clean ----------------------------------------------------------------------------------------------
reset
run_case "clean account (strict)" 0 "clean (only the bootstrap stack remains)" "${common[@]}"
if grep -qvE '^[a-z0-9]+ (list|describe|get)-' "${fx}/calls.log"; then
  bad "check made a non-read call: $(grep -vE '^[a-z0-9]+ (list|describe|get)-' "${fx}/calls.log" | head -3 | tr '\n' ';')"
else
  ok "only list/describe/get calls were made"
fi

# --- the bootstrap stack is allowlisted ------------------------------------------------------------------
reset
cat >"${fx}/s3api_list-buckets.json" <<'EOF'
{"Buckets": [{"Name": "saiman-123456789012-tfstate"}, {"Name": "unrelated-bucket"}]}
EOF
cat >"${fx}/iam_list-roles.json" <<'EOF'
{"Roles": [
 {"Path": "/saiman/bootstrap/", "RoleName": "saiman-gha-plan"},
 {"Path": "/saiman/bootstrap/", "RoleName": "saiman-gha-apply"},
 {"Path": "/saiman/bootstrap/", "RoleName": "saiman-gha-destroy"},
 {"Path": "/saiman/demo/", "RoleName": "saiman-demo-task-execution"},
 {"Path": "/saiman/demo/", "RoleName": "saiman-demo-task"},
 {"Path": "/saiman/demo/", "RoleName": "saiman-demo-scheduler"},
 {"Path": "/aws-service-role/ecs.amazonaws.com/", "RoleName": "AWSServiceRoleForECS"}
]}
EOF
cat >"${fx}/iam_list-policies.json" <<'EOF'
{"Policies": [{"Path": "/saiman/bootstrap/", "PolicyName": "saiman-demo-boundary"}]}
EOF
run_case "bootstrap roles, boundary, state bucket and service-linked roles are not leftovers" 0 "clean" "${common[@]}"

# --- each leftover kind (exit 1, listed) --------------------------------------------------------------
expect_leftover() { # <description> <fixture key> <json> <expected substring>
  reset
  printf '%s\n' "$3" >"${fx}/$2.json"
  run_case "leftover: $1" 1 "$4" "${common[@]}" --allow-unverified
}
expect_leftover "ecs cluster" ecs_list-clusters '{"clusterArns":["arn:aws:ecs:eu-central-1:1:cluster/saiman-demo"]}' "ecs cluster: arn:aws:ecs:eu-central-1:1:cluster/saiman-demo"
expect_leftover "ecs task definition" ecs_list-task-definitions '{"taskDefinitionArns":["arn:aws:ecs:eu-central-1:1:task-definition/saiman-demo-lite:3"]}' "ecs task definitions (ACTIVE, any family)"
expect_leftover "rds instance" rds_describe-db-instances '{"DBInstances":[{"DBInstanceIdentifier":"saiman-demo"},{"DBInstanceIdentifier":"other"}]}' "rds instances: saiman-demo"
expect_leftover "rds snapshot (automated)" rds_describe-db-snapshots '{"DBSnapshots":[{"DBInstanceIdentifier":"saiman-demo","DBSnapshotIdentifier":"rds:saiman-demo-2026-10-06"}]}' "rds snapshots: rds:saiman-demo-2026-10-06"
expect_leftover "rds subnet group" rds_describe-db-subnet-groups '{"DBSubnetGroups":[{"DBSubnetGroupName":"saiman-demo-x"}]}' "rds subnet groups: saiman-demo-x"
expect_leftover "rds parameter group" rds_describe-db-parameter-groups '{"DBParameterGroups":[{"DBParameterGroupName":"default.postgres17"},{"DBParameterGroupName":"saiman-demo-pg"}]}' "rds parameter groups: saiman-demo-pg"
expect_leftover "vpc" ec2_describe-vpcs '{"Vpcs":[{"VpcId":"vpc-123"}]}' "vpcs (tagged saiman): vpc-123"
expect_leftover "network interface" ec2_describe-network-interfaces '{"NetworkInterfaces":[{"NetworkInterfaceId":"eni-1"}]}' "network interfaces (tagged saiman): eni-1"
expect_leftover "elastic ip" ec2_describe-addresses '{"Addresses":[{"AllocationId":"eipalloc-1"}]}' "elastic ips (tagged saiman): eipalloc-1"
expect_leftover "security group" ec2_describe-security-groups '{"SecurityGroups":[{"GroupId":"sg-1"}]}' "security groups (tagged saiman): sg-1"
expect_leftover "log group" logs_describe-log-groups '{"logGroups":[{"logGroupName":"/saiman-demo-lite"}]}' "log groups (/saiman*): /saiman-demo-lite"
expect_leftover "ssm parameter" ssm_describe-parameters '{"Parameters":[{"Name":"/saiman/demo/openai_api_key"}]}' "ssm parameters (/saiman/demo/): /saiman/demo/openai_api_key"
expect_leftover "scheduler schedule" scheduler_list-schedules '{"Schedules":[{"Name":"saiman-demo-stop"}]}' "scheduler schedules: saiman-demo-stop"
expect_leftover "extra bucket" s3api_list-buckets '{"Buckets":[{"Name":"saiman-123456789012-tfstate"},{"Name":"saiman-demo-assets"}]}' "saiman-demo-assets"
expect_leftover "demo/ object" s3api_list-objects-v2 '{"Contents":[{"Key":"demo/assets/s1/web/dist/index.html"}]}' "s3 objects under demo/: demo/assets/s1/web/dist/index.html"
expect_leftover "demo/ object version" s3api_list-object-versions '{"Versions":[{"Key":"demo/a","VersionId":"v1"}]}' "demo/a (v1)"
expect_leftover "unknown role under the demo path" iam_list-roles '{"Roles":[{"Path":"/saiman/demo/","RoleName":"saiman-demo-extra"}]}' "iam role not in the bootstrap allowlist: /saiman/demo/|saiman-demo-extra"
expect_leftover "role outside the allowlisted path" iam_list-roles '{"Roles":[{"Path":"/","RoleName":"saiman-demo-task"}]}' "iam role not in the bootstrap allowlist: /|saiman-demo-task"
expect_leftover "extra policy" iam_list-policies '{"Policies":[{"Path":"/saiman/demo/","PolicyName":"saiman-demo-extra"}]}' "iam policy not in the bootstrap allowlist"
expect_leftover "secrets manager secret" secretsmanager_list-secrets '{"SecretList":[{"Name":"db"}]}' "secrets manager secrets (incl. pending deletion): db"
expect_leftover "load balancer" elbv2_describe-load-balancers '{"LoadBalancers":[{"LoadBalancerArn":"arn:aws:elasticloadbalancing:x"}]}' "load balancers (elbv2)"
expect_leftover "ecr repository" ecr_describe-repositories '{"repositories":[{"repositoryName":"saiman"}]}' "ecr repositories: saiman"
expect_leftover "cloud map namespace" servicediscovery_list-namespaces '{"Namespaces":[{"Id":"ns-1"}]}' "cloud map namespaces: ns-1"

expect_leftover "ACTIVE task definition of a foreign family" ecs_list-task-definitions '{"taskDefinitionArns":["arn:aws:ecs:eu-central-1:1:task-definition/other-app:7"]}' "other-app:7"
expect_leftover "ECS log group" logs_describe-log-groups '{"logGroups":[{"logGroupName":"/ecs/saiman-demo-x"}]}' "/ecs/saiman-demo-x"
reset
echo 'this is not json' >"${fx}/rds_describe-db-instances.json"
run_case "an unparseable response is exit 2, never CLEAN" 2 "unparseable response" "${common[@]}"
reset
echo 'this is not json' >"${fx}/iam_list-roles.json"
run_case "an unparseable IAM response is never clean" 1 "unparseable response" "${common[@]}"

# --- tagged resources are confirmed with the service's own describe call --------------------------------
tagged() { # <arn>...: what the Tagging API returns
  local arn first=1
  printf '{"ResourceTagMappingList":['
  for arn in "$@"; do
    [[ "${first}" -eq 1 ]] || printf ','
    printf '{"ResourceARN":"%s"}' "${arn}"
    first=0
  done
  printf ']}\n'
}
acct="arn:aws:%s:eu-central-1:123456789012"
# shellcheck disable=SC2059 # the account prefix is a printf template on purpose
arn_of() { printf "${acct}:%s" "$1" "$2"; } # <service> <resource>
sgr="$(arn_of ec2 security-group-rule/sgr-0abc)"
sg="$(arn_of ec2 security-group/sg-0abc)"
td_init="$(arn_of ecs task-definition/saiman-demo-init:1)"
td_stack="$(arn_of ecs task-definition/saiman-demo-stack:2)"

reset
tagged "${sgr}" >"${fx}/resourcegroupstaggingapi_get-resources.json"
echo 'An error occurred (InvalidSecurityGroupRuleId.NotFound) when calling the DescribeSecurityGroupRules operation: The security group rule ID does not exist' >"${fx}/ec2_describe-security-group-rules--security-group-rule-ids.err"
run_case "a security group rule the Tagging API still lists but EC2 says is gone is STALE and does not fail (strict)" 0 "STALE       (tagging index) ${sgr}" "${common[@]}" --strict
if grep -q "LEFTOVER" "${work}/out.txt"; then bad "stale entry was also reported as LEFTOVER"; else ok "stale entry is not a LEFTOVER"; fi
if grep -qF "CLEAN       tagged resources (saiman:stack=demo-lite)" "${work}/out.txt"; then ok "tagged sweep is CLEAN when every entry is stale"; else bad "tagged sweep should be CLEAN when every entry is stale"; fi
if grep -qvE '^[a-z0-9]+ (list|describe|get)-' "${fx}/calls.log"; then bad "confirmation made a non-read call"; else ok "confirmation uses read calls only"; fi

reset
tagged "${sg}" >"${fx}/resourcegroupstaggingapi_get-resources.json"
echo '{"SecurityGroups":[{"GroupId":"sg-0abc"}]}' >"${fx}/ec2_describe-security-groups--group-ids.json"
run_case "a tagged security group that still exists is a LEFTOVER" 1 "LEFTOVER    tagged resources (saiman:stack=demo-lite): ${sg}" "${common[@]}" --strict

reset
tagged "${sg}" "${sgr}" >"${fx}/resourcegroupstaggingapi_get-resources.json"
echo '{"SecurityGroups":[{"GroupId":"sg-0abc"}]}' >"${fx}/ec2_describe-security-groups--group-ids.json"
echo 'An error occurred (InvalidSecurityGroupRuleId.NotFound)' >"${fx}/ec2_describe-security-group-rules--security-group-rule-ids.err"
run_case "a stale entry does not hide a real leftover next to it" 1 "STALE       (tagging index) ${sgr}" "${common[@]}" --strict
grep -qF "LEFTOVER    tagged resources (saiman:stack=demo-lite): ${sg}" "${work}/out.txt" && ok "the live one is still a LEFTOVER" || bad "the live one lost its LEFTOVER row"

reset
tagged "${td_init}" >"${fx}/resourcegroupstaggingapi_get-resources.json"
echo '{"taskDefinition":{"status":"INACTIVE","family":"saiman-demo-init","revision":1}}' >"${fx}/ecs_describe-task-definition--task-definition.json"
run_case "an INACTIVE task definition is its own row and does not fail (strict)" 0 "INACTIVE    ecs task definition: ${td_init}" "${common[@]}" --strict
grep -q "LEFTOVER" "${work}/out.txt" && bad "INACTIVE task definition was reported as LEFTOVER" || ok "INACTIVE task definition is not a LEFTOVER"

reset
tagged "${td_init}" >"${fx}/resourcegroupstaggingapi_get-resources.json"
echo '{"taskDefinition":{"status":"DELETE_IN_PROGRESS"}}' >"${fx}/ecs_describe-task-definition--task-definition.json"
run_case "a tagged DELETE_IN_PROGRESS task definition gets its own row, not a failure" 0 "DELETE_IN_PROGRESS ecs task definition: ${td_init}" "${common[@]}" --strict

reset
tagged "${td_init}" >"${fx}/resourcegroupstaggingapi_get-resources.json"
echo 'An error occurred (ClientException) when calling the DescribeTaskDefinition operation: Unable to describe task definition.' >"${fx}/ecs_describe-task-definition--task-definition.err"
run_case "a deleted task definition the index still lists is STALE" 0 "STALE       (tagging index) ${td_init}" "${common[@]}" --strict

reset
tagged "${td_stack}" >"${fx}/resourcegroupstaggingapi_get-resources.json"
echo '{"taskDefinition":{"status":"ACTIVE"}}' >"${fx}/ecs_describe-task-definition--task-definition.json"
run_case "a tagged ACTIVE task definition is a LEFTOVER" 1 "LEFTOVER    tagged resources (saiman:stack=demo-lite): ${td_stack}" "${common[@]}" --strict

reset
echo '{"taskDefinitionArns":["'"${td_init}"'","'"${td_stack}"'","arn:aws:ecs:eu-central-1:123456789012:task-definition/other-app:3"]}' >"${fx}/ecs_list-task-definitions--status=INACTIVE.json"
run_case "INACTIVE saiman-demo task definitions are listed on their own and do not fail" 0 "INACTIVE    ecs task definition: ${td_stack}" "${common[@]}" --strict
grep -qF "INACTIVE    ecs task definition: ${td_init}" "${work}/out.txt" && ok "every INACTIVE revision is listed" || bad "an INACTIVE revision is missing from the output"
grep -q "other-app" "${work}/out.txt" && bad "a foreign INACTIVE task definition was listed" || ok "foreign INACTIVE task definitions are not listed"
grep -q -- "--family-prefix" "${fx}/calls.log" && bad "the listing must not use --family-prefix (exact family name)" || ok "no --family-prefix in the listing"
reset
echo '{"taskDefinitionArns":["'"${td_init}"'"]}' >"${fx}/ecs_list-task-definitions--status=DELETE_IN_PROGRESS.json"
run_case "DELETE_IN_PROGRESS task definitions get their own row and do not fail" 0 "DELETE_IN_PROGRESS ecs task definition: ${td_init}" "${common[@]}" --strict
grep -q "INACTIVE    ecs task definition" "${work}/out.txt" && bad "a DELETE_IN_PROGRESS revision was also shown as INACTIVE" || ok "DELETE_IN_PROGRESS is not shown as INACTIVE"
reset
echo '{"taskDefinitionArns":["'"${td_init}"'"]}' >"${fx}/ecs_list-task-definitions--status=INACTIVE.json"
echo '{"taskDefinitionArns":["'"${td_stack}"'"]}' >"${fx}/ecs_list-task-definitions--status=DELETE_IN_PROGRESS.json"
run_case "INACTIVE and DELETE_IN_PROGRESS revisions are listed on separate rows" 0 "1 INACTIVE and 1 DELETE_IN_PROGRESS task definition(s)" "${common[@]}" --strict
grep -qF "INACTIVE    ecs task definition: ${td_init}" "${work}/out.txt" && grep -qF "DELETE_IN_PROGRESS ecs task definition: ${td_stack}" "${work}/out.txt" && ok "each status keeps its own revision" || bad "statuses were mixed up"
touch "${fx}/ecs_list-task-definitions--status=INACTIVE.fail"
run_case "failing to list INACTIVE task definitions is a NOTE, not a failure" 0 "NOTE        INACTIVE task definitions could not be listed" "${common[@]}" --strict

reset
tagged "$(arn_of ecs cluster/saiman-demo)" >"${fx}/resourcegroupstaggingapi_get-resources.json"
echo '{"clusters":[{"clusterName":"saiman-demo","status":"ACTIVE"}],"failures":[]}' >"${fx}/ecs_describe-clusters.json"
run_case "a tagged ECS cluster that exists is a LEFTOVER" 1 "LEFTOVER    tagged resources (saiman:stack=demo-lite): arn:aws:ecs:eu-central-1:123456789012:cluster/saiman-demo" "${common[@]}" --strict
echo '{"clusters":[{"clusterName":"saiman-demo","status":"INACTIVE"}],"failures":[]}' >"${fx}/ecs_describe-clusters.json"
run_case "an INACTIVE (deleted) ECS cluster the index still lists is STALE" 0 "STALE       (tagging index)" "${common[@]}" --strict
echo '{"clusters":[],"failures":[{"reason":"MISSING"}]}' >"${fx}/ecs_describe-clusters.json"
run_case "a MISSING ECS cluster is STALE" 0 "STALE       (tagging index)" "${common[@]}" --strict

reset
tagged "$(arn_of ecs service/saiman-demo/web)" >"${fx}/resourcegroupstaggingapi_get-resources.json"
echo '{"services":[{"serviceName":"web","status":"DRAINING"}],"failures":[]}' >"${fx}/ecs_describe-services.json"
run_case "a DRAINING ECS service is a LEFTOVER" 1 "LEFTOVER    tagged resources" "${common[@]}" --strict
echo '{"services":[{"serviceName":"web","status":"INACTIVE"}],"failures":[]}' >"${fx}/ecs_describe-services.json"
run_case "an INACTIVE ECS service is STALE" 0 "STALE       (tagging index)" "${common[@]}" --strict

reset
tagged "$(arn_of rds db:saiman-demo-db)" >"${fx}/resourcegroupstaggingapi_get-resources.json"
echo '{"DBInstances":[{"DBInstanceIdentifier":"saiman-demo-db"}]}' >"${fx}/rds_describe-db-instances--db-instance-identifier.json"
run_case "a tagged RDS instance that exists is a LEFTOVER" 1 "LEFTOVER    tagged resources" "${common[@]}" --strict
rm "${fx}/rds_describe-db-instances--db-instance-identifier.json"
echo 'An error occurred (DBInstanceNotFound) when calling the DescribeDBInstances operation: DBInstance saiman-demo-db not found.' >"${fx}/rds_describe-db-instances--db-instance-identifier.err"
run_case "a deleted RDS instance is STALE" 0 "STALE       (tagging index)" "${common[@]}" --strict

reset
tagged "$(arn_of logs log-group:/saiman-demo-lite:*)" >"${fx}/resourcegroupstaggingapi_get-resources.json"
echo '{"logGroups":[{"logGroupName":"/saiman-demo-lite-other"}]}' >"${fx}/logs_describe-log-groups--log-group-name-prefix=_saiman-demo-lite.json"
run_case "a log group is matched by its exact name, not by prefix (another group does not keep it alive)" 0 "STALE       (tagging index)" "${common[@]}" --strict
echo '{"logGroups":[{"logGroupName":"/saiman-demo-lite"}]}' >"${fx}/logs_describe-log-groups--log-group-name-prefix=_saiman-demo-lite.json"
run_case "a tagged log group that exists is a LEFTOVER" 1 "LEFTOVER    tagged resources" "${common[@]}" --strict

reset
tagged "$(arn_of scheduler schedule/default/saiman-demo-stop)" >"${fx}/resourcegroupstaggingapi_get-resources.json"
echo '{"Name":"saiman-demo-stop"}' >"${fx}/scheduler_get-schedule--group-name.json"
run_case "a tagged schedule that exists is a LEFTOVER" 1 "LEFTOVER    tagged resources" "${common[@]}" --strict
rm "${fx}/scheduler_get-schedule--group-name.json"
echo 'An error occurred (ResourceNotFoundException) when calling the GetSchedule operation: Schedule saiman-demo-stop does not exist.' >"${fx}/scheduler_get-schedule--group-name.err"
run_case "a deleted schedule is STALE" 0 "STALE       (tagging index)" "${common[@]}" --strict

reset
tagged "arn:aws:sqs:eu-central-1:123456789012:saiman-demo-queue" >"${fx}/resourcegroupstaggingapi_get-resources.json"
run_case "a tagged type without a direct check stays a LEFTOVER, marked unverified" 1 "no direct check for this resource type" "${common[@]}" --strict

reset
tagged "${sg}" >"${fx}/resourcegroupstaggingapi_get-resources.json"
touch "${fx}/ec2_describe-security-groups--group-ids.deny"
run_case "a tagged ARN this role may not describe stays a LEFTOVER (fail closed)" 1 "this role may not describe it" "${common[@]}" --strict
rm "${fx}/ec2_describe-security-groups--group-ids.deny"
touch "${fx}/ec2_describe-security-groups--group-ids.fail"
run_case "a confirmation that fails for another reason is an error (exit 2), never STALE" 2 "could not confirm ${sg}" "${common[@]}" --strict
rm "${fx}/ec2_describe-security-groups--group-ids.fail"
echo 'this is not json' >"${fx}/ec2_describe-security-groups--group-ids.json"
run_case "an unparseable confirmation is an error (exit 2), never STALE" 2 "could not confirm" "${common[@]}" --strict

reset
echo 'this is not json' >"${fx}/resourcegroupstaggingapi_get-resources.json"
run_case "an unparseable tagging response is exit 2" 2 "unparseable response" "${common[@]}" --strict

# --- UNVERIFIED handling: strict vs allow-unverified ----------------------------------------------------
reset
touch "${fx}/resourcegroupstaggingapi_get-resources.deny" "${fx}/iam_list-roles.deny"
run_case "strict mode fails on UNVERIFIED checks" 1 "UNVERIFIED  tagged resources" "${common[@]}" --strict
run_case "default mode is strict" 1 "could not run" "${common[@]}"
run_case "allow-unverified passes and says what it could not check" 0 "unverified" "${common[@]}" --allow-unverified
printf '%s\n' '{"Vpcs":[{"VpcId":"vpc-9"}]}' >"${fx}/ec2_describe-vpcs.json"
run_case "a leftover still fails with allow-unverified" 1 "vpc-9" "${common[@]}" --allow-unverified

# --- allowlist stays in sync with the bootstrap stack ----------------------------------------------------
for role in saiman-gha-plan saiman-gha-apply saiman-gha-destroy; do
  if grep -q "\"${role}\"" "${here}/../bootstrap/iam.tf" && grep -qF "|${role}\"" "${script}"; then ok "allowlist has ${role} (bootstrap/iam.tf)"; else bad "allowlist and bootstrap/iam.tf disagree on ${role}"; fi
done
for role in saiman-demo-task-execution saiman-demo-scheduler; do
  if grep -q "\"\${local.demo_name_prefix}-${role#saiman-demo-}\"" "${here}/../bootstrap/demo_roles.tf" && grep -qF "|${role}\"" "${script}"; then ok "allowlist has ${role} (bootstrap/demo_roles.tf)"; else bad "allowlist and bootstrap/demo_roles.tf disagree on ${role}"; fi
done
if grep -q 'name *= "${local.demo_name_prefix}-task"' "${here}/../bootstrap/demo_roles.tf" && grep -qF '|saiman-demo-task"' "${script}"; then ok "allowlist has saiman-demo-task"; else bad "allowlist and bootstrap/demo_roles.tf disagree on saiman-demo-task"; fi
if grep -q 'boundary_name = "saiman-demo-boundary"' "${here}/../bootstrap/main.tf" && grep -qF '|saiman-demo-boundary"' "${script}"; then ok "allowlist has the boundary policy"; else bad "allowlist and bootstrap/main.tf disagree on the boundary policy"; fi

if [[ "${fails}" -gt 0 ]]; then
  echo "test-check-demo-down: ${fails} failure(s)" >&2
  exit 1
fi
echo "test-check-demo-down: all checks passed"
