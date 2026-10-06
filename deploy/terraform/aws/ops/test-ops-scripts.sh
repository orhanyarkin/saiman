#!/usr/bin/env bash
# Offline self-test of the demo ops scripts (no AWS, no credentials): a fake `aws` on PATH serves canned JSON
# per "<service>_<operation>" and logs every call (full argv) so the test can assert what was and was NOT sent.
#   <key>.json   response (default {})      <key>.once.json  response for the first call only
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-ops-test2.XXXXXX")"
trap 'rm -rf "${work}"' EXIT
bin="${work}/bin"
fx="${work}/fx"
mkdir -p "${bin}" "${fx}"
cat >"${bin}/aws" <<'FAKE'
#!/usr/bin/env bash
args=("$@")
echo "$*" >>"${FAKE_AWS_DIR}/calls.log"
while [[ ${#args[@]} -gt 0 && "${args[0]}" == --region ]]; do args=("${args[@]:2}"); done
while [[ ${#args[@]} -gt 0 && "${args[0]}" == --output ]]; do args=("${args[@]:2}"); done
key="${args[0]}_${args[1]}"
if [[ -e "${FAKE_AWS_DIR}/${key}.once.json" ]]; then
  cat "${FAKE_AWS_DIR}/${key}.once.json"
  mv "${FAKE_AWS_DIR}/${key}.once.json" "${FAKE_AWS_DIR}/${key}.served"
elif [[ -e "${FAKE_AWS_DIR}/${key}.json" ]]; then
  cat "${FAKE_AWS_DIR}/${key}.json"
elif [[ "${key}" == ssm_get-parameter ]]; then
  echo "fake-token-value-for-${args[*]: -6:1}"
else
  echo '{}'
fi
FAKE
chmod +x "${bin}/aws"
export FAKE_AWS_DIR="${fx}"
export PATH="${bin}:${PATH}"

fails=0
ok() { echo "ok:   $*"; }
bad() {
  echo "FAIL: $*" >&2
  fails=$((fails + 1))
}
reset() {
  find "${fx}" -type f -delete
  : >"${fx}/calls.log"
}
# expect <description> <expected exit> <output substring or ''> <command...>
expect() {
  local desc="$1" want="$2" needle="$3" rc=0
  shift 3
  "$@" >"${work}/out.txt" 2>&1 || rc=$?
  if [[ "${rc}" -ne "${want}" ]]; then
    bad "${desc}: exit ${rc}, wanted ${want}"
    sed 's/^/      /' "${work}/out.txt" >&2
  elif [[ -n "${needle}" ]] && ! grep -qF -- "${needle}" "${work}/out.txt"; then
    bad "${desc}: output lacks: ${needle}"
    sed 's/^/      /' "${work}/out.txt" >&2
  else
    ok "${desc}"
  fi
}

ops="${here}"

# --- check-ssm-names -----------------------------------------------------------------------------------
cp "${here}/../demo-lite/containers.tf" "${work}/containers.tf"
if grep -q 'redis_password' "${work}/containers.tf"; then
  expect "ssm names: containers.tf and put-demo-parameters.sh agree" 0 "identical" "${ops}/check-ssm-names.sh"
else
  # Before the ECS task audit fix lands (redis_password), the only drift is that one name.
  expect "ssm names: only redis_password differs (containers.tf not yet updated)" 1 "+redis_password" "${ops}/check-ssm-names.sh"
  printf '  "x" = "redis_password"\n' >/dev/null
  sed -i 's/^\(    ledger = {\)$/\1\n      SAIMAN_REDIS_PASSWORD_X = "redis_password"/' "${work}/containers.tf"
  expect "ssm names: agree once redis_password is injected" 0 "identical" "${ops}/check-ssm-names.sh" "${work}/containers.tf"
fi
sed -i 's/seller_service_token_ledger"/seller_service_token_ledger_v2"/' "${work}/containers.tf"
expect "ssm names: a renamed valueFrom is reported as drift" 1 "DRIFT" "${ops}/check-ssm-names.sh" "${work}/containers.tf"

# --- prepare-demo-secrets + put-demo-parameters + scan ------------------------------------------------------
secrets="${work}/secrets"
buyer="0x$(printf 'ab%.0s' {1..32})"
reset
X402_BUYER_PRIVATE_KEY="${buyer}" OPENAI_API_KEY="sk-test-openai-key-0123456789abcdef" \
  "${ops}/prepare-demo-secrets.sh" "${secrets}" >"${work}/prep.txt" 2>&1 || {
  bad "prepare-demo-secrets failed"
  cat "${work}/prep.txt" >&2
}
if [[ "$(stat -c %a "${secrets}")" == 700 ]] && [[ -z "$(find "${secrets}" -type f ! -perm 600)" ]]; then ok "secrets dir 0700, files 0600"; else bad "secret modes"; fi
if grep -v "^::add-mask::" "${work}/prep.txt" | grep -qE "${buyer}|sk-test-openai"; then bad "prepare output leaks a human-supplied secret"; else ok "prepare output does not echo supplied secrets"; fi
if [[ "$(grep -c '^::add-mask::' "${work}/prep.txt")" -ge 14 ]]; then ok "every secret is masked with ::add-mask::"; else bad "too few ::add-mask:: lines"; fi
expect "prepare rejects a malformed buyer key" 2 "32-byte hex" env X402_BUYER_PRIVATE_KEY=nothex OPENAI_API_KEY=x "${ops}/prepare-demo-secrets.sh" "${work}/s2"
expect "prepare requires the buyer key" 2 "X402_BUYER_PRIVATE_KEY" env -u X402_BUYER_PRIVATE_KEY OPENAI_API_KEY=x "${ops}/prepare-demo-secrets.sh" "${work}/s3"

reset
expect "put parameters" 0 "expires-at = 2026-10-06T18:00:00Z" "${ops}/put-demo-parameters.sh" --region eu-central-1 --expires-at 2026-10-06T18:00:00Z "${secrets}"
if grep -c 'ssm put-parameter' "${fx}/calls.log" | grep -qx 16; then ok "15 SecureStrings + expires-at written (grafana absent)"; else bad "unexpected put-parameter count: $(grep -c 'ssm put-parameter' "${fx}/calls.log")"; fi
leak=0
for f in "${secrets}"/*; do
  if grep -qFf "${f}" "${fx}/calls.log"; then leak=1; fi
done
[[ "${leak}" -eq 0 ]] && ok "no secret value appears in any aws argv" || bad "a secret value was passed on the aws command line"
if grep 'put-parameter' "${fx}/calls.log" | grep -v 'expires-at' | grep -vq -- '--type SecureString --overwrite --value file://'; then bad "a secret parameter was not a SecureString written from file://"; else ok "secrets are SecureString + --overwrite + file://"; fi
if grep -q 'add-tags-to-resource.*Key=expires-at,Value=2026-10-06T18:00:00Z' "${fx}/calls.log"; then ok "expiry tag written"; else bad "expiry tag missing"; fi
expect "put parameters rejects a non-UTC expiry" 2 "must look like" "${ops}/put-demo-parameters.sh" --region eu-central-1 --expires-at 2026-10-06T18:00:00+02:00 "${secrets}"

# --- scan-tf-state ----------------------------------------------------------------------------------------------
reset
digest="$(printf '0%.0s' {1..64})"
printf '{"values":{"auth":"%s","payTo":"0x0000000000000000000000000000000000000001"}}\n' "${digest}" >"${work}/clean.json"
expect "scan: digests and addresses are not secrets" 0 "clean" "${ops}/scan-tf-state.sh" "${secrets}" "${work}/clean.json"
printf '{"x":"%s"}\n' "$(tr -d '\n' <"${secrets}/pg_superuser_password")" >"${work}/dirty.json"
expect "scan: the db master password is found and named, not printed" 1 "'pg_superuser_password'" "${ops}/scan-tf-state.sh" "${secrets}" "${work}/dirty.json"
if grep -qFf "${secrets}/pg_superuser_password" "${work}/out.txt"; then bad "scan printed the secret value"; else ok "scan output has no secret value"; fi
printf '{"x":"%s"}\n' "$(tr -d '\n' <"${secrets}/openai_api_key")" >"${work}/dirty2.json"
expect "scan: the OpenAI key is found" 1 "'openai_api_key'" "${ops}/scan-tf-state.sh" "${secrets}" "${work}/dirty2.json"
printf '{"x":"sk-proj-abcdefghijklmnopqrstuvwx"}\n' >"${work}/dirty3.json"
expect "scan: an OpenAI-shaped key is found by shape" 1 "OpenAI-style" "${ops}/scan-tf-state.sh" "${secrets}" "${work}/dirty3.json"
printf '{"instances":[{"attributes":{"password":"hunter2hunter2"}}]}\n' >"${work}/dirty4.json"
expect "scan: a non-empty password attribute is found" 1 "password" "${ops}/scan-tf-state.sh" "${secrets}" "${work}/dirty4.json"

# --- demo-expiry --------------------------------------------------------------------------------------------------
reset
expect "expiry: no parameter means nothing to do" 0 "exists=false" "${ops}/demo-expiry.sh" --region eu-central-1
echo '{"Parameters":[{"Name":"/saiman/demo/expires-at"}]}' >"${fx}/ssm_describe-parameters.json"
echo '{"TagList":[{"Key":"expires-at","Value":"2026-10-06T18:00:00Z"}]}' >"${fx}/ssm_list-tags-for-resource.json"
expect "expiry: future expiry is not expired" 0 "expired=false" "${ops}/demo-expiry.sh" --region eu-central-1 --now 2026-10-06T17:59:59Z
expect "expiry: past expiry is expired" 0 "expired=true" "${ops}/demo-expiry.sh" --region eu-central-1 --now 2026-10-06T18:00:01Z
echo '{"TagList":[]}' >"${fx}/ssm_list-tags-for-resource.json"
expect "expiry: a parameter without a tag counts as expired" 0 "expired=true" "${ops}/demo-expiry.sh" --region eu-central-1
if grep -q 'get-parameter' "${fx}/calls.log"; then bad "expiry used get-parameter (denied for the CI roles)"; else ok "expiry never calls get-parameter"; fi

# --- demo-cleanup ---------------------------------------------------------------------------------------------------
reset
echo '{"Parameters":[{"Name":"/saiman/demo/openai_api_key"},{"Name":"/saiman/demo/expires-at"},{"Name":"/saiman/demo/pg_master_password"}]}' >"${fx}/ssm_describe-parameters.json"
echo '{"Versions":[{"Key":"demo/a","VersionId":"v1"}],"DeleteMarkers":[{"Key":"demo/b","VersionId":"v2"}]}' >"${fx}/s3api_list-object-versions.once.json"
echo '{"taskDefinitionArns":["arn:aws:ecs:eu-central-1:1:task-definition/saiman-demo-lite:1"]}' >"${fx}/ecs_list-task-definitions.json"
expect "cleanup runs" 0 "deleted /saiman/demo/expires-at" "${ops}/demo-cleanup.sh" --region eu-central-1 --state-bucket saiman-1-tfstate
if grep -q 'delete-parameters --names /saiman/demo/openai_api_key /saiman/demo/pg_master_password' "${fx}/calls.log"; then ok "cleanup deletes the secrets in a batch"; else bad "secret batch delete missing"; fi
last_ssm="$(grep -E 'ssm delete-parameter' "${fx}/calls.log" | tail -n 1)"
if [[ "${last_ssm}" == *"delete-parameter --name /saiman/demo/expires-at"* ]]; then ok "expires-at is deleted last"; else bad "expires-at was not the last SSM delete: ${last_ssm}"; fi
if grep -q 's3api delete-objects --bucket saiman-1-tfstate' "${fx}/calls.log"; then ok "cleanup purges versions and delete markers"; else bad "no s3api delete-objects"; fi
if grep -q 'ecs deregister-task-definition' "${fx}/calls.log"; then ok "cleanup deregisters ACTIVE task definitions"; else bad "no deregister"; fi
if grep -qE 'terraform|ssm (put|get)|ecs (create|run|register|update)' "${fx}/calls.log"; then bad "cleanup made a create/read-secret call"; else ok "cleanup only deletes/lists"; fi

# --- wait-demo-ready ----------------------------------------------------------------------------------------------------
reset
echo '{"taskArns":["arn:aws:ecs:eu-central-1:1:task/saiman-demo/abc"]}' >"${fx}/ecs_list-tasks.json"
echo '{"tasks":[{"containers":[{"name":"assets","lastStatus":"STOPPED","exitCode":0},{"name":"readiness","lastStatus":"STOPPED","exitCode":0}]}]}' >"${fx}/ecs_describe-tasks.json"
expect "ready: readiness exit 0" 0 "all services are UP" "${ops}/wait-demo-ready.sh" --region eu-central-1 --interval-seconds 0
echo '{"tasks":[{"containers":[{"name":"readiness","lastStatus":"STOPPED","exitCode":1}]}]}' >"${fx}/ecs_describe-tasks.json"
expect "ready: readiness exit 1 fails" 1 "readiness exited 1" "${ops}/wait-demo-ready.sh" --region eu-central-1 --interval-seconds 0
echo '{"tasks":[{"containers":[{"name":"db-init","lastStatus":"STOPPED","exitCode":2},{"name":"readiness","lastStatus":"PENDING"}]}]}' >"${fx}/ecs_describe-tasks.json"
expect "ready: a failed one-shot fails fast" 1 "db-init (exit 2)" "${ops}/wait-demo-ready.sh" --region eu-central-1 --interval-seconds 0
echo '{"tasks":[{"containers":[{"name":"readiness","lastStatus":"RUNNING"}]}]}' >"${fx}/ecs_describe-tasks.json"
expect "ready: times out" 1 "not ready after 1s" "${ops}/wait-demo-ready.sh" --region eu-central-1 --interval-seconds 1 --timeout-seconds 1

# --- demo-tunnel ------------------------------------------------------------------------------------------------------------
reset
echo '{"taskArns":["arn:aws:ecs:eu-central-1:1:task/saiman-demo/abc123"]}' >"${fx}/ecs_list-tasks.json"
echo '{"tasks":[{"containers":[{"name":"web","lastStatus":"RUNNING","runtimeId":"abc123-0123456789"}]}]}' >"${fx}/ecs_describe-tasks.json"
expect "tunnel: dry run prints the port-forward command" 0 "--target ecs:saiman-demo_abc123_abc123-0123456789" "${ops}/demo-tunnel.sh" --dry-run
expect "tunnel: local 8088 -> 80" 0 '"localPortNumber":["8088"]' "${ops}/demo-tunnel.sh" --dry-run
echo '{"taskArns":[]}' >"${fx}/ecs_list-tasks.json"
expect "tunnel: no task is a clear error" 1 "no running task" "${ops}/demo-tunnel.sh" --dry-run

# --- demo-tokens ------------------------------------------------------------------------------------------------------------
reset
tokdir="$("${ops}/demo-tokens.sh" 2>"${work}/tok.err")" || bad "demo-tokens failed"
if [[ -d "${tokdir}" && "$(stat -c %a "${tokdir}")" == 700 && "$(stat -c %a "${tokdir}/api_reader_token")" == 600 && -s "${tokdir}/api_operator_token" ]]; then ok "tokens: 0700 dir, 0600 files"; else bad "tokens: wrong layout"; fi
if grep -qFf "${tokdir}/api_reader_token" "${work}/tok.err"; then bad "demo-tokens printed a token"; else ok "demo-tokens prints only paths"; fi
rm -rf "${tokdir}"

# --- tf-dummy-env -------------------------------------------------------------------------------------------------------------
expect "dummy env: valid placeholders, no password" 0 "TF_VAR_image_tag=sha-000000000000" "${ops}/tf-dummy-env.sh" saiman-1-tfstate
if "${ops}/tf-dummy-env.sh" saiman-1-tfstate | grep -qi 'db_master_password'; then bad "dummy env sets the master password"; else ok "dummy env never sets the master password"; fi

if [[ "${fails}" -gt 0 ]]; then
  echo "test-ops-scripts: ${fails} failure(s)" >&2
  exit 1
fi
echo "test-ops-scripts: all checks passed"
