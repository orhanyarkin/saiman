#!/usr/bin/env python3
"""Mutation check for tests/task.tftest.hcl (offline, no AWS).

For each rule of the ECS RegisterTaskDefinition test it breaks the module in exactly the way the rule is meant to catch,
runs `terraform test -filter=tests/task.tftest.hcl` in a temporary copy and requires BOTH a failing run AND the rule's own
error message in the output. A rule that stays green under its mutation is dead weight. The repository is never modified.

Usage: python3 deploy/terraform/aws/demo-lite/tests/mutation-check.py [rule-id ...]   (default: all; ~10 s each)
Exit 0 every mutation was caught, 1 a mutation survived or the baseline is red, 2 setup problem.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(HERE)
LONG = "x" * 300

# (id, file, old, new, expected text from the rule's error_message; whitespace-insensitive)
MUTATIONS = [
    ("retries-above-10", "containers.tf", "        retries     = 10\n        startPeriod = 120", "        retries     = 11\n        startPeriod = 120", "healthCheck.retries must be 1-10"),
    ("retries-zero", "containers.tf", "        retries     = 10\n        startPeriod = 10", "        retries     = 0\n        startPeriod = 10", "healthCheck.retries must be 1-10"),
    ("interval-below-5", "containers.tf", "        interval    = 20\n        timeout     = 20", "        interval    = 4\n        timeout     = 20", "healthCheck.interval must be 5-300"),
    ("interval-above-300", "containers.tf", "        interval    = 20\n        timeout     = 20", "        interval    = 301\n        timeout     = 20", "healthCheck.interval must be 5-300"),
    ("timeout-above-60", "containers.tf", "        interval    = 20\n        timeout     = 20", "        interval    = 20\n        timeout     = 61", "healthCheck.timeout must be 2-60"),
    ("timeout-below-2", "containers.tf", "        interval    = 20\n        timeout     = 20", "        interval    = 20\n        timeout     = 1", "healthCheck.timeout must be 2-60"),
    ("startperiod-above-300", "containers.tf", "        retries     = 10\n        startPeriod = 120", "        retries     = 10\n        startPeriod = 301", "healthCheck.startPeriod must be 0-300"),
    ("health-command-prefix", "containers.tf", '["CMD-SHELL", "REDISCLI_AUTH', '["SH", "REDISCLI_AUTH', "healthCheck.command must start with CMD or CMD-SHELL"),
    ("start-timeout-above-120", "containers.tf", "    startTimeout = 120\n  }\n\n  migrate_specs", "    startTimeout = 121\n  }\n\n  migrate_specs", "startTimeout and stopTimeout must be 2-120"),
    ("stop-timeout-above-120", "containers.tf", "      image         = local.images.nginx\n", "      image         = local.images.nginx\n      stopTimeout   = 130\n", "startTimeout and stopTimeout must be 2-120"),
    ("memory-below-6", "containers.tf", "      memory        = 64\n", "      memory        = 5\n", "container memory must be at least 6 MiB"),
    ("memory-reservation-not-below-memory", "containers.tf", "      memory        = 64\n", "      memory        = 64\n      memoryReservation = 64\n", "memoryReservation (if any) below memory"),
    ("restart-period-below-60", "containers.tf", "restartAttemptPeriod = 60", "restartAttemptPeriod = 30", "restartPolicy.restartAttemptPeriod must be 60-1800"),
    ("host-port-differs", "containers.tf", "portMappings  = [{ containerPort = 80, protocol = \"tcp\" }]", "portMappings  = [{ containerPort = 80, hostPort = 8080, protocol = \"tcp\" }]", "awsvpc port mappings"),
    ("invalid-dependency-condition", "containers.tf", '      redis = "HEALTHY"\n    }', '      redis = "READY"\n    }', "dependency condition must be START, COMPLETE, SUCCESS or HEALTHY"),
    ("dependency-on-missing-container", "containers.tf", '      redis = "HEALTHY"\n    }', '      redis = "HEALTHY"\n      nonexistent = "SUCCESS"\n    }', "dependency must name another container of the same task definition"),
    ("self-dependency", "containers.tf", "      image         = local.images.kafka\n", '      image         = local.images.kafka\n      depends       = { kafka = "START" }\n', "dependency must name another container of the same task definition"),
    ("healthy-without-health-check", "containers.tf", '      depends = { assets = "SUCCESS" }\n    }\n\n    # Runs assets/scripts/readiness.sh', '      depends = { assets = "SUCCESS", "otel-collector" = "HEALTHY" }\n    }\n\n    # Runs assets/scripts/readiness.sh', "HEALTHY may only target a container that has a health check"),
    ("dependency-cycle", "containers.tf", "      image         = local.images.kafka\n", '      image         = local.images.kafka\n      depends       = { orchestrator = "START" }\n', "circular container dependency"),
    ("mount-of-undeclared-volume", "containers.tf", 'volume_names_stack = ["assets", "web-html", "web-conf"]', 'volume_names_stack = ["assets", "web-html"]', "mount point must name a volume of its own task definition"),
    ("duplicate-container-names", "containers.tf", "      name        = name\n", '      name        = "assets"\n', "container names must be unique within a task definition|||Duplicate object key"),
    ("invalid-container-name", "containers.tf", "      name        = name\n", '      name        = "${name}.x"\n', "container name: up to 255 letters, numbers, hyphens and underscores"),
    ("env-and-secret-share-a-name", "containers.tf", '      X402_CLIENT_PRIVATE_KEY      = "x402_buyer_private_key" # x402.client.private-key', '      X402_CLIENT_PRIVATE_KEY      = "x402_buyer_private_key" # x402.client.private-key\n      SPRING_DATA_REDIS_URL        = "redis_password"', "environment and secret names must be unique within a container"),
    ("awslogs-option-missing", "containers.tf", '          "awslogs-stream-prefix" = name\n', "", "the awslogs driver needs awslogs-group, awslogs-region and awslogs-stream-prefix"),
    ("invalid-fargate-size", "ecs.tf", "init_task_memory = 4096", "init_task_memory = 3000", "task cpu/memory must be a valid Fargate combination"),
    ("invalid-family", "ecs.tf", 'family                   = "${local.name_prefix}-init"', 'family                   = "${local.name_prefix} init"', "family: up to 255 letters, numbers, hyphens and underscores|||invalid value for family"),
    ("no-essential-container", "containers.tf", "      image      = local.images.postgres\n      essential  = true\n      memory     = 256", "      image      = local.images.postgres\n      essential  = false\n      memory     = 256", "each task definition needs an essential container"),
    ("last-init-container-skips-a-migrator", "containers.tf", 'concat(["assets", "db-init"], [for a in keys(local.apps) : "${a}-migrate"])', 'concat(["assets", "db-init"], [for a in ["orchestrator"] : "${a}-migrate"])', "must wait for every other init container"),
    ("more-than-ten-containers", "containers.tf", "  stack_specs = merge(local.stack_fixed, local.app_specs)", "  stack_specs = merge(local.stack_fixed, local.app_specs, { extra1 = local.stack_fixed.web, extra2 = local.stack_fixed.web })", "at most 10 containers"),
    ("task-definition-over-64-kib", "containers.tf", '        KAFKA_HEAP_OPTS                                = "-Xms256m -Xmx512m"\n', '        KAFKA_HEAP_OPTS                                = "-Xms256m -Xmx512m"\n        PAD                                            = "' + "y" * 60000 + '"\n', "its container definitions must stay under 56000 bytes"),
    ("image-reference-too-long", "containers.tf", 'nginx    = "public.ecr.aws/docker/library/nginx:1.31.0-alpine"', 'nginx    = "public.ecr.aws/docker/library/nginx:1.31.0-alpine-' + LONG + '"', "at most 255 characters"),
]


def norm(text):
    text = re.sub(r"\x1b\[[0-9;]*m", "", text)
    return re.sub(r"[\s│╷╵]+", " ", text)


def run_tests(workdir):
    proc = subprocess.run(["terraform", "test", "-no-color", "-filter=tests/task.tftest.hcl"], cwd=workdir, capture_output=True, text=True, timeout=600)
    return proc.returncode, norm(proc.stdout + proc.stderr)


def main():
    wanted = set(sys.argv[1:])
    unknown = wanted - {m[0] for m in MUTATIONS}
    if unknown:
        print("unknown rule id(s): " + ", ".join(sorted(unknown)), file=sys.stderr)
        return 2
    work = tempfile.mkdtemp(prefix="saiman-mutation.")
    try:
        mod = os.path.join(work, "demo-lite")
        shutil.copytree(MODULE, mod, ignore=shutil.ignore_patterns(".terraform", "terraform.tfstate*", "*.tfplan"))
        if os.path.isdir(os.path.join(MODULE, ".terraform")):
            os.symlink(os.path.join(MODULE, ".terraform"), os.path.join(mod, ".terraform"))
        else:
            subprocess.run(["terraform", "init", "-backend=false", "-input=false"], cwd=mod, check=True, capture_output=True)
        code, _ = run_tests(mod)
        if code != 0:
            print("baseline is red: fix the tests before mutating", file=sys.stderr)
            return 1
        survived = 0
        for rule, fname, old, new, expect in MUTATIONS:
            if wanted and rule not in wanted:
                continue
            path = os.path.join(mod, fname)
            original = open(path, encoding="utf-8").read()
            if original.count(old) < 1:
                print(f"SETUP {rule}: the mutation anchor is not in {fname} (update mutation-check.py)", file=sys.stderr)
                return 2
            open(path, "w", encoding="utf-8").write(original.replace(old, new))
            try:
                code, out = run_tests(mod)
            finally:
                open(path, "w", encoding="utf-8").write(original)
            if code != 0 and any(norm(alt) in out for alt in expect.split("|||")):
                print(f"caught   {rule}")
            else:
                survived += 1
                why = "stayed green" if code == 0 else f"failed, but without the message '{expect}'"
                print(f"SURVIVED {rule}: {why}", file=sys.stderr)
        print("mutation-check: all mutations caught" if survived == 0 else f"mutation-check: {survived} mutation(s) survived")
        return 0 if survived == 0 else 1
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
