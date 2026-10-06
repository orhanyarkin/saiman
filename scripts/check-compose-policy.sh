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

# 2. Every service in the "apps" profile (and "evals") must set pull_policy: never, so compose can
#    never silently fall back to pulling an image with this tag from a registry
#    instead of using the one just built locally by `./gradlew bootBuildImage`. The
#    one-shot `evals` service (profile "evals", M6) is covered too.
bad_pull_policy=$(jq -r '
  .services
  | to_entries[]
  | select(((.value.profiles // []) | index("apps")) or ((.value.profiles // []) | index("evals")))
  # `web` is the one apps-profile service that runs a registry image (pinned nginx, rule 5).
  | select(.key != "web")
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
#    may only be set on the orchestrator and only to exactly `seller-api`. M4b (ADR-0021):
#    SAIMAN_LEDGER_SELLER_BASE_URL / _ALLOWED_HOSTS only on ledger, exactly
#    http://seller-api:8081 / seller-api; SELLER_INTERNAL_ALLOWED_HOSTS only on seller-api,
#    exactly seller-api,seller-api:8081.
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
  . as $orig
  # Env keys are normalised (upper case, "." and "-" -> "_") before every name rule: Spring relaxed
  # binding would otherwise let a dotted or lower-case key slip past an upper-case rule.
  | def norm: ascii_upcase | gsub("[.-]"; "_");
  (.services |= map_values(if .environment then .environment |= with_entries(.key |= norm) else . end)) as $root
  | def allowed: {
        "postgres": ["pg_superuser_password"],
        "ingest": ["mkk_credentials", "openai_api_key", "pg_ingest_owner_password", "pg_ingest_app_password"],
        "seller-api": ["openai_api_key", "pg_seller_api_owner_password", "pg_seller_api_app_password"],
        "orchestrator": ["x402_buyer_private_key", "openai_api_key", "pg_orchestrator_owner_password", "pg_orchestrator_app_password"],
        "ledger": ["pg_ledger_owner_password", "pg_ledger_app_password", "seller_service_token_ledger"],
        "evals": ["seller_service_token_evals"],
        "db-init": ["pg_superuser_password", "pg_orchestrator_owner_password", "pg_orchestrator_app_password", "pg_ledger_owner_password", "pg_ledger_app_password", "pg_seller_api_owner_password", "pg_seller_api_app_password", "pg_ingest_owner_password", "pg_ingest_app_password"]
      };
    # Property each mounted secret must be exposed as (configtree file name, ADR-0023/0024); db-init has no target.
    def target_of: {"seller_service_token_ledger": "saiman.ledger.seller.service-token", "seller_service_token_evals": "saiman.evals.seller.service-token"};
    def pg_target($src): if ($src | test("^pg_.*_owner_password$")) then "spring.flyway.password" elif ($src | test("^pg_.*_app_password$")) then "spring.datasource.password" else null end;
    # Own database users per app service (ADR-0024).
    def db_schema: {"orchestrator": "orchestrator", "seller-api": "seller_api", "ledger": "ledger", "ingest": "ingest"};
    def bind_sources: {"otel-collector": ["/otel-collector.yaml"], "web": ["/web/dist", "/nginx.conf"], "db-init": ["/postgres"], "evals": ["/build/evals"]};
    def inscope($p): ((($p // []) | index("apps")) != null) or ((($p // []) | index("evals")) != null);
    def forbidden_env: ["OPENAI_API_KEY", "OPENAI_BASE_URL", "AZURE_OPENAI_BASE_URL", "OPENAI_LOG", "SPRING_APPLICATION_JSON", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "SPRING_CONFIG_IMPORT", "SPRING_CONFIG_LOCATION", "SPRING_CONFIG_ADDITIONAL_LOCATION"];
    def keyish: test("(?i)(PRIVATE_?KEY|/secrets(/|$)|\\.key$|\\.pem$)|0x[0-9a-fA-F]{64}");
    $root.services | to_entries[] | .key as $svc | .value as $s
  | ( if ($s.env_file // []) | length > 0
      then "\($svc): env_file is not allowed (per-service env vars only, ADR-0009)"
      else empty end ),
    ( ($s.environment // {}) | to_entries[] | select(.key != "POSTGRES_PASSWORD_FILE") | "\(.key)=\(.value // "")" | select(keyish)
      | "\($svc): environment entry looks like a private key (ADR-0009: the buyer key reaches only the orchestrator, via secrets:, never as an environment entry)" ),
    ( ($s.secrets // [])[]? | .source as $src
      | select(((allowed[$svc] // []) | index($src)) == null)
      | "\($svc): mounts secret \"\($src)\" (allowed: \((allowed[$svc] // []) | join(", ") | if . == "" then "none" else . end); ADR-0009)" ),
    ( ($s.secrets // [])[]? | .source as $src
      | ($root.secrets // {})[$src] as $def
      | select(($def.file // "") != ($secrets_dir + "/" + $src))
      | "\($svc): secret \"\($src)\" must be file-sourced from the repo secrets/\($src) (not environment or another path)" ),
    ( ($s.secrets // [])[]? | select($svc != "db-init") | .source as $src
      | (target_of[$src] // pg_target($src)) as $want
      | select($want != null and (.target // "") != $want)
      | "\($svc): secret \"\($src)\" must be mounted with target \"\($want)\" (configtree property name, ADR-0023/0024)" ),
    ( ($orig.services[$svc].environment // {}) | keys[] | select(test("^[A-Z][A-Z0-9_]*$") | not)
      | "\($svc): environment key \"\(.)\" is not UPPER_SNAKE_CASE (dotted or lower-case keys would bypass the name rules)" ),
    ( ($s.environment // {}) | to_entries[] | select(.key | test("PASSWORD"))
      | select(($svc == "postgres" and .key == "POSTGRES_PASSWORD_FILE" and (.value // "") == "/run/secrets/pg_superuser_password") | not)
      | "\($svc): environment \(.key) is not allowed (passwords arrive as file secrets; only postgres may set POSTGRES_PASSWORD_FILE=/run/secrets/pg_superuser_password, ADR-0024)" ),
    ( ($s.environment // {}) | to_entries[] | select(.key | test("TOKEN$"))
      | "\($svc): environment \(.key) is not allowed (raw tokens arrive as file secrets or stay with humans; only SAIMAN_AUTH_*_SHA256 digests may be env, ADR-0023)" ),
    ( ($s.volumes // [])[]? | select(.type == "bind") | .source as $src
      | select(((bind_sources[$svc] // []) | map(. as $suffix | $src | endswith($suffix)) | any) | not)
      | "\($svc): bind source \($src) is not on the allowlist for this service (\((bind_sources[$svc] // []) | join(", ") | if . == "" then "none" else . end))" ),
    ( if inscope($s.profiles) then
        ( select($s.command != null or $s.entrypoint != null)
          | "\($svc): command/entrypoint overrides are not allowed on app services (they could pass --saiman.* flags)" ),
        ( ($s.environment // {}) | to_entries[] | select(.key == "SPRING_DATASOURCE_URL")
          | select((.value // "") != "jdbc:postgresql://postgres:5432/saiman")
          | "\($svc): SPRING_DATASOURCE_URL must be exactly jdbc:postgresql://postgres:5432/saiman (no URL parameters, no credentials)" ),
        ( ($s.environment // {}) | to_entries[] | select(.key | test("^SPRING_(DATASOURCE|FLYWAY)_(USERNAME|USER)$"))
          | . as $e | (db_schema[$svc]) as $schema
          | select($schema == null
              or ($e.key == "SPRING_DATASOURCE_USERNAME" and ($e.value // "") != ($schema + "_app"))
              or ($e.key == "SPRING_FLYWAY_USER" and ($e.value // "") != ($schema + "_owner")))
          | "\($svc): \($e.key)=\($e.value // "") is not allowed (runtime user is <schema>_app, migration user <schema>_owner, never the saiman superuser, ADR-0024)" ),
        ( select(db_schema[$svc] != null and ($s.profiles // []) == ["apps"])
          | ( select((($s.environment // {}).SPRING_DATASOURCE_USERNAME // "") != (db_schema[$svc] + "_app"))
              | "\($svc): SPRING_DATASOURCE_USERNAME must be \(db_schema[$svc])_app (ADR-0024)" ),
            ( select((($s.environment // {}).SPRING_FLYWAY_USER // "") != (db_schema[$svc] + "_owner"))
              | "\($svc): SPRING_FLYWAY_USER must be \(db_schema[$svc])_owner (ADR-0024)" ) )
      else empty end ),
    ( ($s.environment // {}) | to_entries[] | select(.key | test("^SAIMAN_AUTH_"))
      | . as $e
      | ( select(($e.key | test("^SAIMAN_AUTH_(READER_TOKEN|OPERATOR_TOKEN|SERVICE_TOKENS_(LEDGER|EVALS))_SHA256$")) | not)
          | "\($svc): \($e.key) is not one of the known SAIMAN_AUTH_*_SHA256 digest variables (ADR-0023)" ),
        ( select(($e.key | test("^SAIMAN_AUTH_(READER|OPERATOR)_TOKEN_SHA256$")) and ($svc != "orchestrator" and $svc != "ledger"))
          | "\($svc): \($e.key) is only allowed on orchestrator and ledger (human role digests, ADR-0023)" ),
        ( select(($e.key | test("^SAIMAN_AUTH_SERVICE_TOKENS_")) and $svc != "seller-api")
          | "\($svc): \($e.key) is only allowed on seller-api (the verifier of the service tokens, ADR-0023)" ),
        ( select((($e.value // "") | test("^(\\$\\{\($e.key):-\\}|[0-9a-f]{64})$")) | not)
          | "\($svc): \($e.key) must be the interpolation \"${\($e.key):-}\" or a 64-hex digest" ) ),
    ( ($s.volumes // [])[]? | select(.type == "bind" and ((.read_only // false) | not))
      | select(($svc == "evals" and (.source | endswith("/build/evals")) and .target == "/out") | not)
      | "\($svc): bind mount \(.source) is writable (only evals may write, and only to build/evals mounted at /out; everything else must be :ro)" ),
    ( select($svc == "evals")
      | select((($s.volumes // []) | length) != 1)
      | "evals: must have exactly one volume (../../build/evals:/out)" ),
    ( ($s.environment // {}) | keys[] | select(. as $k | (forbidden_env | index($k)) != null or ($k | test("^(SPRING_AI_OPENAI_|SAIMAN_INGEST_MKK_|SPRINGDOC_)")))
      | "\($svc): environment defines \(.) (the OpenAI key must arrive only as a secret file; OPENAI_BASE_URL / SPRING_AI_OPENAI_* would redirect the key, OPENAI_LOG=debug dumps prompts, SPRING_APPLICATION_JSON / SPRING_CONFIG_* / *JAVA_OPTIONS can inject any property or load arbitrary config; SAIMAN_INGEST_MKK_* would redirect the MKK Basic credential; SPRINGDOC_* could turn the OpenAPI endpoint back on, ADR-0022)" ),
    ( ($s.environment // {}) | to_entries[] | select(.key == "X402_CLIENT_ALLOWED_PLAINTEXT_HOSTS")
      | select($svc != "orchestrator" or (.value // "") != "seller-api")
      | "\($svc): X402_CLIENT_ALLOWED_PLAINTEXT_HOSTS must be exactly \"seller-api\" and only on orchestrator (plaintext x402 payments are allowed to the compose-internal seller alone; no wildcard)" ),
    ( ($s.environment // {}) | to_entries[] | select(.key | test("^SAIMAN_CHAIN_"))
      | select(($svc == "ledger" or $svc == "orchestrator") | not)
      | "\($svc): \(.key) is only allowed on ledger and orchestrator (M4, ADR-0018: only they read the chain)" ),
    ( ($s.environment // {}) | to_entries[] | select(.key == "SAIMAN_CHAIN_RPC_URL")
      | select((.value // "") | test("^https://sepolia\\.base\\.org(/[^@[:space:]]*)?$") | not)
      | "\($svc): SAIMAN_CHAIN_RPC_URL must be an https URL whose host is exactly sepolia.base.org (no other host, no http, no userinfo; testnet only, ADR-0018)" ),
    ( ($s.environment // {}) | to_entries[] | select(.key == "SAIMAN_CHAIN_ALLOWED_HOSTS")
      | select((.value // "") != "sepolia.base.org")
      | "\($svc): SAIMAN_CHAIN_ALLOWED_HOSTS must be exactly sepolia.base.org (testnet only, ADR-0018)" ),
    ( ($s.environment // {}) | to_entries[] | select(.key | test("^SAIMAN_LEDGER_SELLER_"))
      | select($svc != "ledger")
      | "\($svc): \(.key) is only allowed on ledger (M4b, ADR-0021: only the ledger corroborates credit notes)" ),
    ( ($s.environment // {}) | to_entries[] | select(.key == "SAIMAN_LEDGER_SELLER_BASE_URL")
      | select((.value // "") != "http://seller-api:8081")
      | "\($svc): SAIMAN_LEDGER_SELLER_BASE_URL must be exactly http://seller-api:8081 (the compose-internal seller; ADR-0021)" ),
    ( ($s.environment // {}) | to_entries[] | select(.key == "SAIMAN_LEDGER_SELLER_ALLOWED_HOSTS")
      | select((.value // "") != "seller-api")
      | "\($svc): SAIMAN_LEDGER_SELLER_ALLOWED_HOSTS must be exactly seller-api (ADR-0021)" ),
    ( ($s.environment // {}) | to_entries[] | select(.key | test("^SELLER_INTERNAL_"))
      | select($svc != "seller-api" or .key != "SELLER_INTERNAL_ALLOWED_HOSTS" or (.value // "") != "seller-api,seller-api:8081")
      | "\($svc): \(.key) must be SELLER_INTERNAL_ALLOWED_HOSTS=seller-api,seller-api:8081 on seller-api only (the published localhost port must not reach /internal/**; ADR-0021)" ),
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

# Kafka is unauthenticated until M6 (docs/THREAT_MODEL.md, ADR-0020): every topic is declared by the services, so
# a peer must not be able to create new ones; the KRaft controller listener stays inside the container; no JMX port.
bad_kafka=$(jq -r '
  .services | to_entries[] | .key as $svc | .value as $s |
    ( select(($s.image // "") | startswith("apache/kafka"))
      | ( select((($s.environment // {}).KAFKA_AUTO_CREATE_TOPICS_ENABLE // "") != "false")
          | "\($svc): KAFKA_AUTO_CREATE_TOPICS_ENABLE must be \"false\" (all topics are declared by the services)" ),
        ( select((($s.environment // {}).KAFKA_LISTENERS // "") | test("CONTROLLER://(localhost|127\\.0\\.0\\.1):") | not)
          | "\($svc): KAFKA_LISTENERS must bind CONTROLLER to localhost or 127.0.0.1 only" ) ),
    ( ($s.environment // {}) | keys[] | select(test("JMX_PORT$"))
      | "\($svc): environment defines \(.) (no remote JMX)" )
' <<<"${config_json}")
if [[ -n "${bad_kafka}" ]]; then
  fail_check "Kafka settings that widen the unauthenticated broker:"
  echo "${bad_kafka}" >&2
  violations=1
fi

# 5. The dashboard `web` service (M5, ADR-0022) is a plain nginx serving static files and
#    proxying same-origin paths. It must stay inert: a pinned registry image, one loopback port
#    mapping to container port 80, no environment/env_file/secrets/configs, and exactly two
#    read-only bind mounts (web/dist and nginx.conf). The nginx.conf itself is checked by
#    scripts/check-nginx-conf.sh.
bad_web=$(jq -r '
  (.services.web // empty) as $w
  | ( select(($w.image // "") | test("^nginx:[0-9]+\\.[0-9]+\\.[0-9]+-alpine$") | not)
      | "web: image \"\($w.image // "<unset>")\" must be a pinned nginx:<major.minor.patch>-alpine tag" ),
    ( select(($w.environment // {}) | length > 0)
      | "web: must not set environment (no env on the dashboard container)" ),
    ( select(($w.env_file // []) | length > 0)
      | "web: must not use env_file" ),
    ( select(($w.secrets // []) | length > 0)
      | "web: must not mount secrets" ),
    ( select(($w.configs // []) | length > 0)
      | "web: must not use configs" ),
    ( ($w.ports // [])[] | select((type == "string") or .host_ip != "127.0.0.1" or .target != 80)
      | "web: ports must be 127.0.0.1:<port>:80 only" ),
    ( [($w.volumes // [])[] | select(.type == "bind" and (.read_only // false) and
          ((.target == "/usr/share/nginx/html" and (.source | endswith("/web/dist")))
           or (.target == "/etc/nginx/conf.d/default.conf" and (.source | endswith("/nginx.conf")))))]
      as $ok
      | select((($w.volumes // []) | length) != 2 or ($ok | length) != 2)
      | "web: volumes must be exactly ../../web/dist -> /usr/share/nginx/html:ro and ./nginx.conf -> /etc/nginx/conf.d/default.conf:ro" )
' <<<"${config_json}")
if [[ -n "${bad_web}" ]]; then
  fail_check "web (dashboard) service outside its constraints:"
  echo "${bad_web}" >&2
  violations=1
fi

# The conf is only checked for the real compose file (fixtures have no nginx.conf next to them);
# NGINX_CONF may point elsewhere, see scripts/check-nginx-conf.sh.
if [[ "${COMPOSE_FILE}" == "deploy/compose/docker-compose.yml" ]]; then
  "$(dirname "${BASH_SOURCE[0]}")/check-nginx-conf.sh" || violations=1
fi

if [[ ${violations} -ne 0 ]]; then
  exit 1
fi

echo "check-compose-policy: PASS (ports bound to 127.0.0.1, app pull_policy: never, no saiman/ images, secrets: allowlist enforced, no OPENAI_* env, plaintext-hosts pinned to seller-api, chain RPC pinned to sepolia.base.org on ledger/orchestrator only, credit-note corroboration pinned to seller-api, Kafka auto-create off and controller local, dashboard web service constrained)"
