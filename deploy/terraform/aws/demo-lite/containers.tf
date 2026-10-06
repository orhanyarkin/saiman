# Container definitions of the single demo task. docker-compose.yml is the source of truth for each
# service's image, port, environment and secret split; this file mirrors it with three differences:
#   - every hostname is 127.0.0.1 (one task, one network namespace),
#   - secrets are ECS `secrets[].valueFrom` SSM SecureString ARNs (env vars), because ECS cannot mount files,
#   - ordering is `dependsOn` (SUCCESS for one-shots, HEALTHY for kafka/redis) instead of depends_on.
#
# Secret split (ADR-0009, ADR-0024, ADR-0027), enforced by tests/task.tftest.hcl and tests/check-task.sh:
#   *-migrate     owner password only (SPRING_FLYWAY_PASSWORD), no app password, no provider or wallet key
#   apps          app password only; the buyer key reaches the orchestrator alone
#   db-init       master password + every PW_* (the only holder of all passwords, like compose db-init)
# No value below is a secret: the secrets maps hold SSM parameter NAMES, resolved to ARN strings.

locals {
  # arn:aws:ssm:<region>:<account>:parameter/saiman/demo/ (the demo-up workflow creates the parameters
  # named below, the execution role may read exactly this prefix). Strings only, no data source.
  ssm_param_prefix = trimsuffix(local.ssm_prefix_arn, "*")

  images = {
    # Third-party images follow compose (same pinned tags); Docker Hub "library" images come from the
    # public.ecr.aws mirror, which has no anonymous pull limit from AWS.
    awscli   = "public.ecr.aws/aws-cli/aws-cli:2.36.33"
    postgres = "public.ecr.aws/docker/library/postgres:17.10" # Debian: db-init needs bash
    kafka    = "apache/kafka:4.3.1"
    redis    = "public.ecr.aws/docker/library/redis:8.10.2-alpine"
    otel     = "otel/opentelemetry-collector:0.162.0"
    nginx    = "public.ecr.aws/docker/library/nginx:1.31.0-alpine"
    busybox  = "public.ecr.aws/docker/library/busybox:1.38.0"
  }

  # Shared ephemeral volumes. The `assets` container syncs s3://<state bucket>/demo/assets/<session>/ into
  # `assets` (layout from deploy/terraform/aws/assets/build-assets.sh: web/dist, nginx/default.conf,
  # otel/{config,grafana}.yaml, postgres/*, plus scripts/readiness.sh), then copies the nginx root and
  # config into `web-html` / `web-conf` (ECS cannot mount single files). `corpus` receives the private
  # dump (artifacts/, read by the task role).
  volume_names = ["assets", "web-html", "web-conf", "corpus"]

  jdbc_url = "jdbc:postgresql://${aws_db_instance.main.address}:5432/saiman?sslmode=require"

  assets_prefix = "s3://${var.state_bucket_name}/demo/assets/${var.session_id}"
  corpus_meta   = "${trimsuffix(var.corpus_object_key, ".dump")}.meta.json"

  # name -> schema, port. The four Spring Boot services (same order as ADR-0027).
  apps = {
    orchestrator = { schema = "orchestrator", port = 8080 }
    seller-api   = { schema = "seller_api", port = 8081 }
    ledger       = { schema = "ledger", port = 8082 }
    ingest       = { schema = "ingest", port = 8083 }
  }

  # Per-app extras on top of the common environment. Hostnames that compose spells as service names are
  # 127.0.0.1 here (listed in the A3a report).
  app_env = {
    orchestrator = {
      SAIMAN_AUTH_READER_TOKEN_SHA256                     = var.auth_digests["reader"]
      SAIMAN_AUTH_OPERATOR_TOKEN_SHA256                   = var.auth_digests["operator"]
      X402_CLIENT_ALLOWED_PAY_TO                          = var.x402_seller_payto_address
      X402_CLIENT_ALLOWED_PLAINTEXT_HOSTS                 = "127.0.0.1"
      SPRING_HTTP_CLIENTS_REDIRECTS                       = "dont-follow"
      SAIMAN_SELLER_BASE_URL                              = "http://127.0.0.1:8081"
      SAIMAN_ROUTER_REQUIRE_COST_SCOPE                    = "true"
      SAIMAN_ROUTER_MAX_SCOPE_BUDGET_USD_MICROS           = "150000"
      SAIMAN_ORCHESTRATOR_API_ALLOWED_HOSTS               = "localhost,127.0.0.1,[::1],localhost:8080,127.0.0.1:8080"
      SAIMAN_ORCHESTRATOR_SPEND_APPROVAL_THRESHOLD_ATOMIC = "10000"
      SAIMAN_CHAIN_RPC_URL                                = local.chain_rpc_url
      SAIMAN_CHAIN_ALLOWED_HOSTS                          = "sepolia.base.org"
    }
    seller-api = {
      SAIMAN_AUTH_SERVICE_TOKENS_LEDGER_SHA256 = var.auth_digests["service_ledger"]
      SELLER_INGEST_BASE_URL                   = "http://127.0.0.1:8083"
      SELLER_DISCLOSURES_SOURCE                = "rag"
      X402_SELLER_PAYTO_ADDRESS                = var.x402_seller_payto_address
      SELLER_INTERNAL_ALLOWED_HOSTS            = "127.0.0.1,127.0.0.1:8081"
    }
    ledger = {
      SAIMAN_AUTH_READER_TOKEN_SHA256    = var.auth_digests["reader"]
      SAIMAN_AUTH_OPERATOR_TOKEN_SHA256  = var.auth_digests["operator"]
      SAIMAN_CHAIN_RPC_URL               = local.chain_rpc_url
      SAIMAN_CHAIN_ALLOWED_HOSTS         = "sepolia.base.org"
      SAIMAN_LEDGER_API_ALLOWED_HOSTS    = "localhost,127.0.0.1,[::1],localhost:8082,127.0.0.1:8082"
      SAIMAN_LEDGER_SELLER_BASE_URL      = "http://127.0.0.1:8081"
      SAIMAN_LEDGER_SELLER_ALLOWED_HOSTS = "127.0.0.1"
    }
    ingest = {}
  }

  # Extra secrets per app: env var name -> SSM parameter name. Compose mounts these as configtree files;
  # ECS can only inject env vars, so each name is the Spring relaxed-binding spelling of the property.
  app_secrets = {
    orchestrator = {
      X402_CLIENT_PRIVATE_KEY      = "x402_buyer_private_key" # x402.client.private-key
      SAIMAN_ROUTER_OPENAI_API_KEY = "openai_api_key"         # saiman.router.openai.api-key
    }
    seller-api = {
      SAIMAN_ROUTER_OPENAI_API_KEY = "openai_api_key"
    }
    ledger = {
      SAIMAN_LEDGER_SELLER_SERVICE_TOKEN = "seller_service_token_ledger" # saiman.ledger.seller.service-token
    }
    ingest = {
      SAIMAN_ROUTER_OPENAI_API_KEY = "openai_api_key"
    }
  }

  # --- one-shots -----------------------------------------------------------------------------------

  oneshot = {
    essential    = false
    startTimeout = 300
  }

  migrate_specs = { for name, a in local.apps : "${name}-migrate" => merge(local.oneshot, {
    image  = "ghcr.io/orhanyarkin/saiman-${name}:${var.image_tag}"
    memory = 512
    env = {
      SAIMAN_RUN_MODE       = "migrate"
      SPRING_DATASOURCE_URL = local.jdbc_url
      SPRING_FLYWAY_USER    = "${a.schema}_owner"
      BPL_JVM_THREAD_COUNT  = "20"
    }
    secrets = {
      SPRING_FLYWAY_PASSWORD = "pg_${a.schema}_owner_password"
    }
    depends = { "db-init" = "SUCCESS" }
  }) }

  app_specs = { for name, a in local.apps : name => {
    image         = "ghcr.io/orhanyarkin/saiman-${name}:${var.image_tag}"
    essential     = true
    memory        = 768 # the Paketo memory calculator sizes the JVM from this limit
    restartPolicy = { enabled = true, restartAttemptPeriod = 60 }
    env = merge({
      SPRING_DATASOURCE_URL                   = local.jdbc_url
      SPRING_DATASOURCE_USERNAME              = "${a.schema}_app"
      SPRING_FLYWAY_ENABLED                   = "false" # migrations run in <svc>-migrate (ADR-0027)
      SPRING_KAFKA_BOOTSTRAP_SERVERS          = "127.0.0.1:9092"
      SPRING_DATA_REDIS_URL                   = "redis://127.0.0.1:6379"
      OTEL_EXPORTER_OTLP_ENDPOINT             = "http://127.0.0.1:4318"
      MANAGEMENT_TRACING_SAMPLING_PROBABILITY = "1.0"
      MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED  = "true"
      BPL_JVM_THREAD_COUNT                    = "50"
    }, local.app_env[name])
    secrets = merge({
      SPRING_DATASOURCE_PASSWORD = "pg_${a.schema}_app_password"
    }, local.app_secrets[name])
    depends = merge(
      {
        "${name}-migrate" = "SUCCESS"
        kafka             = "HEALTHY"
        redis             = "HEALTHY"
      },
      name == "ingest" ? { "corpus-restore" = "SUCCESS" } : {},
    )
  } }

  # --- fixed containers ----------------------------------------------------------------------------

  fixed_specs = {
    # Downloads the per-session assets and the corpus dump from the state bucket (task role: read-only
    # on demo/* and artifacts/*).
    assets = merge(local.oneshot, {
      image      = local.images.awscli
      memory     = 384
      entryPoint = ["sh", "-c"]
      command = [join(" ", [
        "set -eu;",
        "aws s3 sync --only-show-errors \"${local.assets_prefix}/\" /assets/;",
        "cp -R /assets/web/dist/. /web-html/;",
        "cp /assets/nginx/default.conf /web-conf/default.conf;",
        "aws s3 cp --only-show-errors \"s3://${var.state_bucket_name}/${var.corpus_object_key}\" /corpus/ingest-corpus.dump;",
        "aws s3 cp --only-show-errors \"s3://${var.state_bucket_name}/${local.corpus_meta}\" /corpus/ingest-corpus.meta.json",
      ])]
      env = {
        AWS_DEFAULT_REGION = local.region
        AWS_PAGER          = ""
      }
      mounts = [
        { volume = "assets", path = "/assets", ro = false },
        { volume = "web-html", path = "/web-html", ro = false },
        { volume = "web-conf", path = "/web-conf", ro = false },
        { volume = "corpus", path = "/corpus", ro = false },
      ]
    })

    # Role bootstrap as the RDS master (ADR-0024, ADR-0028). The master is not a superuser, so the
    # vector extension is created first (bootstrap-roles.sh requires it with BOOTSTRAP_RDS=1).
    # Every password arrives by `secrets`, never `environment`; psql reads PGPASSWORD from the env.
    db-init = merge(local.oneshot, {
      image      = local.images.postgres
      memory     = 128
      entryPoint = ["bash", "-c"]
      command = [join(" ", [
        "set -euo pipefail;",
        "psql -X -q -v ON_ERROR_STOP=1 -c 'CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public';",
        "exec bash /assets/postgres/bootstrap-roles.sh",
      ])]
      env = {
        BOOTSTRAP_SECRET_SOURCE = "env"
        BOOTSTRAP_RDS           = "1"
        PGHOST                  = aws_db_instance.main.address
        PGDATABASE              = "saiman"
        PGUSER                  = "saiman"
        PGSSLMODE               = "require"
      }
      secrets = merge(
        { PGPASSWORD = "pg_master_password" },
        { for pair in setproduct(keys(local.apps), ["owner", "app"]) :
          "PW_${upper(local.apps[pair[0]].schema)}_${upper(pair[1])}" => "pg_${local.apps[pair[0]].schema}_${pair[1]}_password"
        },
      )
      mounts  = [{ volume = "assets", path = "/assets", ro = true }]
      depends = { assets = "SUCCESS" }
    })

    # Restores the private corpus dump as ingest_owner (the schema owner) once ingest is migrated.
    # The assets container already fetched the dump from S3 into the shared `corpus` volume, so this
    # container needs no AWS access. Idempotent: corpus-restore.sh skips when rows exist.
    corpus-restore = merge(local.oneshot, {
      image      = local.images.postgres
      memory     = 256
      entryPoint = ["bash"]
      command    = ["/assets/postgres/corpus-restore.sh", "/corpus"]
      env = {
        PGHOST     = aws_db_instance.main.address
        PGDATABASE = "saiman"
        PGUSER     = "ingest_owner"
        PGSSLMODE  = "require"
      }
      secrets = { PGPASSWORD = "pg_ingest_owner_password" }
      mounts = [
        { volume = "assets", path = "/assets", ro = true },
        { volume = "corpus", path = "/corpus", ro = true },
      ]
      depends = { assets = "SUCCESS", "ingest-migrate" = "SUCCESS" }
    })

    # Single-node KRaft broker on loopback only (ADR-0020). One listener: clients are in the same task.
    kafka = {
      image         = local.images.kafka
      essential     = true
      memory        = 1024
      restartPolicy = { enabled = true, restartAttemptPeriod = 60 }
      env = {
        CLUSTER_ID                                     = "c2FpbWFuLWxvY2FsLWthZg"
        KAFKA_NODE_ID                                  = "1"
        KAFKA_PROCESS_ROLES                            = "broker,controller"
        KAFKA_CONTROLLER_QUORUM_VOTERS                 = "1@127.0.0.1:9093"
        KAFKA_CONTROLLER_LISTENER_NAMES                = "CONTROLLER"
        KAFKA_LISTENERS                                = "INTERNAL://127.0.0.1:9092,CONTROLLER://127.0.0.1:9093"
        KAFKA_ADVERTISED_LISTENERS                     = "INTERNAL://127.0.0.1:9092"
        KAFKA_LISTENER_SECURITY_PROTOCOL_MAP           = "INTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT"
        KAFKA_INTER_BROKER_LISTENER_NAME               = "INTERNAL"
        KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR         = "1"
        KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR = "1"
        KAFKA_TRANSACTION_STATE_LOG_MIN_ISR            = "1"
        KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS         = "0"
        KAFKA_AUTO_CREATE_TOPICS_ENABLE                = "false"
        KAFKA_LOG_DIRS                                 = "/var/lib/kafka/data"
        KAFKA_HEAP_OPTS                                = "-Xms256m -Xmx512m"
      }
      healthCheck = {
        command     = ["CMD-SHELL", "/opt/kafka/bin/kafka-topics.sh --bootstrap-server 127.0.0.1:9092 --list >/dev/null || exit 1"]
        interval    = 10
        timeout     = 15
        retries     = 20
        startPeriod = 20
      }
    }

    redis = {
      image         = local.images.redis
      essential     = true
      memory        = 128
      restartPolicy = { enabled = true, restartAttemptPeriod = 60 }
      command       = ["redis-server", "--bind", "127.0.0.1", "--save", "", "--appendonly", "no"]
      healthCheck = {
        command     = ["CMD", "redis-cli", "-h", "127.0.0.1", "ping"]
        interval    = 5
        timeout     = 5
        retries     = 20
        startPeriod = 5
      }
    }

    # Config comes from the assets container. With a Grafana Cloud endpoint a second --config overlay
    # (otel/grafana.yaml) adds the OTLP/HTTP exporter; base64(id:token) is injected as GRAFANA_OTLP_AUTH
    # from SSM and the endpoint as GRAFANA_OTLP_ENDPOINT. Without it only the debug exporter runs.
    # Not essential: telemetry must never take the demo down.
    otel-collector = {
      image         = local.images.otel
      essential     = false
      memory        = 256
      restartPolicy = { enabled = true, restartAttemptPeriod = 60 }
      command = concat(
        ["--config=/assets/otel/config.yaml"],
        var.grafana_otlp_endpoint == null ? [] : ["--config=/assets/otel/grafana.yaml"],
      )
      env = { for k, v in { GRAFANA_OTLP_ENDPOINT = var.grafana_otlp_endpoint } : k => v if v != null }
      secrets = {
        for k, v in { GRAFANA_OTLP_AUTH = "grafana_otlp_auth" } : k => v
        if var.grafana_otlp_endpoint != null
      }
      mounts  = [{ volume = "assets", path = "/assets", ro = true }]
      depends = { assets = "SUCCESS" }
    }

    # nginx serves the SPA and path-routes to the services (config from the assets container); it is
    # the target of the SSM port-forward. No environment, no secrets (same rule as compose).
    web = {
      image         = local.images.nginx
      essential     = true
      memory        = 64
      restartPolicy = { enabled = true, restartAttemptPeriod = 60 }
      portMappings  = [{ containerPort = 80, protocol = "tcp" }]
      mounts = [
        { volume = "web-html", path = "/usr/share/nginx/html", ro = true },
        { volume = "web-conf", path = "/etc/nginx/conf.d", ro = true },
      ]
      depends = { assets = "SUCCESS" }
    }

    # Runs assets/scripts/readiness.sh (POSIX sh + wget, from the synced prefix): exits 0 once all four
    # services report UP, 1 after its timeout. demo-up waits on this container.
    readiness = {
      image      = local.images.busybox
      essential  = false
      memory     = 32
      entryPoint = ["sh"]
      command    = ["/assets/scripts/readiness.sh"]
      mounts     = [{ volume = "assets", path = "/assets", ro = true }]
      depends    = { assets = "SUCCESS" }
    }
  }

  specs = merge(local.fixed_specs, local.migrate_specs, local.app_specs)

  # Spec -> ECS container definition. env/secrets/depends/mounts are the readable spellings.
  container_definitions = [for name, c in local.specs : merge(
    { for k, v in c : k => v if !contains(["env", "secrets", "depends", "mounts"], k) },
    {
      name        = name
      environment = [for k, v in try(c.env, {}) : { name = k, value = v }]
      secrets     = [for k, v in try(c.secrets, {}) : { name = k, valueFrom = "${local.ssm_param_prefix}${v}" }]
      dependsOn   = [for k, v in try(c.depends, {}) : { containerName = k, condition = v }]
      mountPoints = [for m in try(c.mounts, []) : { sourceVolume = m.volume, containerPath = m.path, readOnly = m.ro }]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.main.name
          "awslogs-region"        = local.region
          "awslogs-stream-prefix" = name
        }
      }
    },
  )]
}
