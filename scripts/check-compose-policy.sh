#!/usr/bin/env bash
# Static policy checks on deploy/compose/docker-compose.yml, run in CI so a change to
# the compose file can't silently reintroduce an exposed port, an accidental registry
# pull of a dev image, a reference to the third-party "saiman/" Docker Hub namespace
# (our images are ghcr.io/orhanyarkin/saiman-<svc>:dev), or a path for wallet key
# material to reach a service other than the orchestrator (ADR-0009: no service gets an
# env_file, only the orchestrator may hold a compose `secrets:` mount, and nothing
# anywhere — including the orchestrator, before its M3 secrets: mount lands — may set an
# environment entry, a config or a bind-mounted volume that looks like a private key).
#
# Resolves the config with `--no-interpolate --no-env-resolution` so it never reads a
# local .env (app services carry no env_file; see docker-compose.yml), and with
# `--profile '*'` so every service is scanned regardless of profile, not only "apps".
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-deploy/compose/docker-compose.yml}"

fail_check() {
  echo "check-compose-policy: FAIL: $*" >&2
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "check-compose-policy: required command '$1' not found on PATH" >&2
    exit 1
  }
}

require_command docker
require_command jq

config_json=$(docker compose -f "${COMPOSE_FILE}" --profile '*' config --no-interpolate --no-env-resolution --format json)

violations=0

# Where compose resolves `file: ../../secrets/<name>` for this compose file (symlinks are
# not resolved by compose, so neither are they here).
secrets_dir=$(realpath -ms "$(dirname "${COMPOSE_FILE}")/../../secrets")

# 1. Every published port on every service must bind to 127.0.0.1 only (never 0.0.0.0
#    or an unset host_ip, which Docker treats as "all interfaces").
bad_ports=$(jq -r '
  .services
  | to_entries[]
  | .key as $svc
  | (.value.ports // [])[]
  # Without interpolation a mapping that contains a variable stays a short-syntax string.
  | if type == "string" then
      select(startswith("127.0.0.1:") | not)
      | "\($svc): port \"\(.)\" is not bound to 127.0.0.1"
    else
      select(.host_ip != "127.0.0.1")
      | "\($svc): port \(.published // "?")->\(.target) has host_ip \"\(.host_ip // "<unset>")\" (must be 127.0.0.1)"
    end
' <<<"${config_json}")
if [[ -n "${bad_ports}" ]]; then
  fail_check "port(s) not bound to 127.0.0.1:"
  echo "${bad_ports}" >&2
  violations=1
fi

# 2. Every service in the "apps" profile must set pull_policy: never, so compose can
#    never silently fall back to pulling an image with this tag from a registry
#    instead of using the one just built locally by `./gradlew bootBuildImage`.
bad_pull_policy=$(jq -r '
  .services
  | to_entries[]
  | select((.value.profiles // []) | index("apps"))
  | select(.value.pull_policy != "never")
  | "\(.key): pull_policy is \"\(.value.pull_policy // "<unset>")\" (must be \"never\")"
' <<<"${config_json}")
if [[ -n "${bad_pull_policy}" ]]; then
  fail_check "app service(s) without pull_policy: never:"
  echo "${bad_pull_policy}" >&2
  violations=1
fi

# 3. No service image may reference the "saiman/" Docker Hub namespace: that Hub user
#    belongs to a third party, not this project.
bad_images=$(jq -r '
  .services
  | to_entries[]
  | select(.value.image != null)
  | select(.value.image | test("^(docker\\.io/)?saiman/"))
  | "\(.key): image \"\(.value.image)\" matches the forbidden saiman/ namespace"
' <<<"${config_json}")
if [[ -n "${bad_images}" ]]; then
  fail_check "service(s) referencing the forbidden saiman/ image namespace:"
  echo "${bad_images}" >&2
  violations=1
fi

# 4. Wallet key material must only ever reach the orchestrator, and only through
#    compose `secrets:` (ADR-0009 + M2 amendment: exactly ingest -> {mkk_credentials,
#    openai_api_key}, seller-api -> {openai_api_key}, orchestrator ->
#    {x402_buyer_private_key, openai_api_key}; all three must be file-sourced from secrets/<name>; no service may set OPENAI_API_KEY,
#    OPENAI_BASE_URL, AZURE_OPENAI_BASE_URL, OPENAI_LOG, SAIMAN_INGEST_MKK_* or SPRING_CONFIG_IMPORT|LOCATION|ADDITIONAL_LOCATION in its environment).
#    The buyer key reaches only the orchestrator, from M3,
#    via `secrets:` mounted at /run/secrets/, read with
#    `spring.config.import=optional:configtree:/run/secrets/`). X402_CLIENT_ALLOWED_PLAINTEXT_HOSTS
#    may only be set on the orchestrator and only to exactly `seller-api`.
#    This scans every service, regardless of profile, for five patterns:
#      - an env_file (per-service secrets are explicit env vars only, never a whole file)
#      - a `secrets:` mount outside the allowlist above
#      - a `configs:` entry sourced from a key- or path-looking value
#      - a bind-mounted volume whose source looks like a secrets path (/secrets/, *.key,
#        *.pem)
#      - any environment key or value that looks like a private key (name matching
#        PRIVATE_?KEY case-insensitively, a path under secrets/, a *.key/*.pem file, or a
#        bare 0x-prefixed 32-byte hex literal) -- including on orchestrator itself, since
#        before its M3 secrets: mount lands it has no business holding one either.
bad_key_material=$(jq -r --arg secrets_dir "${secrets_dir}" '
  . as $root
  | def allowed: {"ingest": ["mkk_credentials", "openai_api_key"], "seller-api": ["openai_api_key"], "orchestrator": ["x402_buyer_private_key", "openai_api_key"]};
    def forbidden_env: ["OPENAI_API_KEY", "OPENAI_BASE_URL", "AZURE_OPENAI_BASE_URL", "OPENAI_LOG", "SPRING_APPLICATION_JSON", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "SPRING_CONFIG_IMPORT", "SPRING_CONFIG_LOCATION", "SPRING_CONFIG_ADDITIONAL_LOCATION"];
    def keyish: test("(?i)(PRIVATE_?KEY|/secrets(/|$)|\\.key$|\\.pem$)|0x[0-9a-fA-F]{64}");
    $root.services | to_entries[] | .key as $svc | .value as $s
  | ( if ($s.env_file // []) | length > 0
      then "\($svc): env_file is not allowed (per-service env vars only, ADR-0009)"
      else empty end ),
    ( ($s.environment // {}) | to_entries[] | "\(.key)=\(.value // "")" | select(keyish)
      | "\($svc): environment entry looks like a private key (ADR-0009: the buyer key reaches only the orchestrator, via secrets:, never as an environment entry)" ),
    ( ($s.secrets // [])[]? | .source as $src
      | select(((allowed[$svc] // []) | index($src)) == null)
      | "\($svc): mounts secret \"\($src)\" (allowed: \((allowed[$svc] // []) | join(", ") | if . == "" then "none" else . end); ADR-0009)" ),
    ( ($s.secrets // [])[]? | .source as $src | select(($src == "mkk_credentials" or $src == "openai_api_key" or $src == "x402_buyer_private_key"))
      | ($root.secrets // {})[$src] as $def
      | select(($def.file // "") != ($secrets_dir + "/" + $src))
      | "\($svc): secret \"\($src)\" must be file-sourced from the repo secrets/\($src) (not environment or another path)" ),
    ( ($s.environment // {}) | keys[] | select(. as $k | (forbidden_env | index($k)) != null or ($k | test("^(SPRING_AI_OPENAI_|SAIMAN_INGEST_MKK_)")))
      | "\($svc): environment defines \(.) (the OpenAI key must arrive only as a secret file; OPENAI_BASE_URL / SPRING_AI_OPENAI_* would redirect the key, OPENAI_LOG=debug dumps prompts, SPRING_APPLICATION_JSON / SPRING_CONFIG_* / *JAVA_OPTIONS can inject any property or load arbitrary config; SAIMAN_INGEST_MKK_* would redirect the MKK Basic credential)" ),
    ( ($s.environment // {}) | to_entries[] | select(.key == "X402_CLIENT_ALLOWED_PLAINTEXT_HOSTS")
      | select($svc != "orchestrator" or (.value // "") != "seller-api")
      | "\($svc): X402_CLIENT_ALLOWED_PLAINTEXT_HOSTS must be exactly \"seller-api\" and only on orchestrator (plaintext x402 payments are allowed to the compose-internal seller alone; no wildcard)" ),
    ( ($s.environment // {}) | keys[] | select(contains("$"))
      | "\($svc): environment key \"\(.)\" contains $ (interpolated names would bypass this check)" ),
    ( ($s.configs // [])[]? | .source as $src | (($root.configs // {})[$src] // {})
      | select((.environment // "") + (.file // "") | keyish)
      | "\($svc): config \"\($src)\" is sourced from what looks like a key" ),
    ( ($s.volumes // [])[]? | select(.type == "bind") | select((.source // "") | keyish)
      | "\($svc): bind-mounts \(.source) (key material must come through secrets:, not a bind mount)" )
' <<<"${config_json}")
if [[ -n "${bad_key_material}" ]]; then
  fail_check "service(s) with a path for key material outside ADR-0009's orchestrator-only secrets mount:"
  echo "${bad_key_material}" >&2
  violations=1
fi

if [[ ${violations} -ne 0 ]]; then
  exit 1
fi

echo "check-compose-policy: PASS (ports bound to 127.0.0.1, app pull_policy: never, no saiman/ images, secrets: allowlist enforced, no OPENAI_* env, plaintext-hosts pinned to seller-api)"
