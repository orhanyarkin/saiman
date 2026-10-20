#!/usr/bin/env python3
"""Mutation check for assets/readiness.sh (tested by assets/test-readiness.sh) and ops/demo-tunnel.sh + the Makefile targets
(tested by ops/test-ops-scripts.sh). Each mutation breaks one rule in a temporary copy of the repo's deploy tree and requires the
test to print the FAIL line that rule owns. A surviving mutation means the test misses that rule. Offline, no AWS.

Usage: python3 deploy/terraform/aws/ops/mutation-check-tunnel-readiness.py [rule-id ...]   (exit 0 all caught, 1 a mutation survived)
"""
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
AWS = "deploy/terraform/aws"
UP_CHECK = 'if wget -q -T 3 -O /dev/null "http://${host}:${port}/actuator/health" 2>/dev/null; then'

# (id, file, old, new, test script, text of the FAIL line the rule owns)
READINESS = f"{AWS}/assets/test-readiness.sh"
OPS = f"{AWS}/ops/test-ops-scripts.sh"
MUTATIONS = [
    ("ready-needs-status-as-first-key", f"{AWS}/assets/readiness.sh", UP_CHECK,
     "if wget -q -T 3 -O - \"http://${host}:${port}/actuator/health\" 2>/dev/null | grep -q '^{\"status\":\"UP\"'; then", READINESS, "readiness.sh does not accept the real UP bodies"),
    ("ready-503-counts-as-up", f"{AWS}/assets/readiness.sh", UP_CHECK, UP_CHECK.replace("; then", " || true; then"), READINESS, "readiness.sh exited 0 although a service answers 503"),
    ("ready-explain-misses-the-components", f"{AWS}/assets/readiness.sh", "else if (depth == 3 && key[1] == \"components\")", "else if (depth == 2 && key[1] == \"components\")", READINESS, "the failure does not name the DOWN component"),
    ("ready-explain-misses-the-overall-status", f"{AWS}/assets/readiness.sh", "if (depth == 1) overall = val", "if (depth == 0) overall = val", READINESS, "the failure does not name the DOWN component"),
    ("ready-explain-prints-the-body", f"{AWS}/assets/readiness.sh", "  code=\"$(printf '%s\\n' \"${reply}\" | sed -n", "  echo \"${reply}\"\n  code=\"$(printf '%s\\n' \"${reply}\" | sed -n", READINESS, "the failure output leaks"),
    ("ready-explain-prints-component-details", f"{AWS}/assets/readiness.sh", "    echo \"readiness: $1 (port $2): HTTP ${code:-?}, status ${overall:-?}, components not UP: ${down% }\"", "    echo \"readiness: $1 (port $2): HTTP ${code:-?}, status ${overall:-?}, components not UP: ${down% } ${reply}\"", READINESS, "details leaked"),
    ("ready-explain-trusts-component-names", f"{AWS}/assets/readiness.sh", "cname[m] ~ /^[A-Za-z0-9_-]+$/ && ", "", READINESS, "a component name that is not a plain token was printed"),
    ("ready-no-progress-reports", f"{AWS}/assets/readiness.sh", "if [ $((now - last_report)) -ge \"${report}\" ]; then", "if false; then", READINESS, "no progress report before the timeout"),
    ("ready-no-hint-without-components", f"{AWS}/assets/readiness.sh", "no component listed as not UP (is MANAGEMENT_ENDPOINT_HEALTH_SHOW_COMPONENTS=always set?)", "no component", READINESS, "no hint for a body without components"),
    ("ready-explain-needs-status-first-in-a-component", f"{AWS}/assets/readiness.sh", "    key[depth] = str\n", "    key[depth] = str\n    if (str == \"details\") { depth = 99 }\n", READINESS, "details leaked, or the DOWN component was missed|||the failure does not name the DOWN component"),
    ("tunnel-does-not-check-the-port", f"{AWS}/ops/demo-tunnel.sh", 'if [[ "${dry_run}" -eq 0 ]] && port_in_use "${local_port}"; then', "if false; then", OPS, "tunnel: a busy local port stops the tunnel"),
    ("tunnel-busy-port-is-not-an-error", f"{AWS}/ops/demo-tunnel.sh", "  } >&2\n  exit 1\nfi\n\nexport AWS_PAGER", "  } >&2\n  exit 0\nfi\n\nexport AWS_PAGER", OPS, "tunnel: a busy local port stops the tunnel"),
    ("tunnel-does-not-name-the-holder", f"{AWS}/ops/demo-tunnel.sh", "  if command -v docker >/dev/null 2>&1; then", "  if command -v docker-is-not-asked >/dev/null 2>&1; then", OPS, "tunnel: a busy local port stops the tunnel and names"),
    ("tunnel-dry-run-checks-the-port", f"{AWS}/ops/demo-tunnel.sh", 'if [[ "${dry_run}" -eq 0 ]] && port_in_use', "if port_in_use", OPS, "tunnel: --dry-run does not care about the local port"),
    ("tunnel-checks-after-the-aws-calls", f"{AWS}/ops/demo-tunnel.sh", 'if [[ "${dry_run}" -eq 0 ]] && port_in_use "${local_port}"; then', 'if [[ "${dry_run}" -eq 0 ]] && aws --region "${region}" ecs list-tasks --cluster "${cluster}" >/dev/null && port_in_use "${local_port}"; then', OPS, "tunnel: a busy port still reached AWS"),
    ("tunnel-without-a-suggestion", f"{AWS}/ops/demo-tunnel.sh", "    echo \"    make demo-tunnel TUNNEL_PORT=", "    echo \"    make demo-tunnel", OPS, "tunnel: no TUNNEL_PORT suggestion"),
    ("make-tunnel-ignores-tunnel-port", "Makefile", "--local-port $(TUNNEL_PORT)", "--local-port 8088", OPS, "Makefile: demo-tunnel does not pass"),
    ("make-capture-hard-codes-8088", "Makefile", "ORCH_URL=http://localhost:$(TUNNEL_PORT) LEDGER_URL=http://localhost:$(TUNNEL_PORT)", "ORCH_URL=http://localhost:8088 LEDGER_URL=http://localhost:8088", OPS, "Makefile: demo-capture still hard-codes"),
]


def tree(dest):
    for name in ("deploy", "scripts"):
        shutil.copytree(os.path.join(REPO, name), os.path.join(dest, name), ignore=shutil.ignore_patterns(".terraform", "*.tfstate*", "*.tfplan", "__pycache__", "build", "node_modules"))
    for name in ("Makefile", ".gitignore"):
        shutil.copy(os.path.join(REPO, name), os.path.join(dest, name))


def run(root, script):
    proc = subprocess.run(["bash", os.path.join(root, script)], capture_output=True, text=True, timeout=900)
    return proc.stdout + proc.stderr


def main(argv):
    wanted = set(argv)
    unknown = wanted - {m[0] for m in MUTATIONS}
    if unknown:
        print("unknown rule id(s): " + ", ".join(sorted(unknown)), file=sys.stderr)
        return 2
    root = tempfile.mkdtemp(prefix="saiman-mutation.")
    survived = 0
    try:
        tree(root)
        # Report every stale anchor together, before the first test run; a stale anchor must fail the run, never shorten it.
        stale = [(m[0], m[1]) for m in MUTATIONS if (not wanted or m[0] in wanted) and m[2] not in open(os.path.join(root, m[1]), encoding="utf-8").read()]
        if stale:
            for rule, rel in stale:
                print(f"SETUP {rule}: the mutation anchor is not in {rel} (update the mutation table)", file=sys.stderr)
            print(f"mutation-check: {len(stale)} stale anchor(s), nothing was run. Update the anchors to the current text of the file.", file=sys.stderr)
            return 2
        baseline = {}
        for script in (READINESS, OPS):
            baseline[script] = run(root, script)
        for rule, rel, old, new, script, expect in MUTATIONS:
            if wanted and rule not in wanted:
                continue
            path = os.path.join(root, rel)
            original = open(path, encoding="utf-8").read()
            if old not in original:
                print(f"SETUP {rule}: the mutation anchor is not in {rel} (update the mutation table)", file=sys.stderr)
                return 2
            if any(("FAIL: " + alt) in baseline[script] for alt in expect.split("|||")):
                print(f"SETUP {rule}: the baseline already prints that FAIL line; fix the tests first", file=sys.stderr)
                return 2
            open(path, "w", encoding="utf-8").write(original.replace(old, new, 1))
            try:
                out = run(root, script)
            finally:
                open(path, "w", encoding="utf-8").write(original)
            if any(("FAIL: " + alt) in out for alt in expect.split("|||")):
                print(f"caught   {rule}")
            else:
                survived += 1
                print(f"SURVIVED {rule}: the test did not print a FAIL line containing '{expect}'", file=sys.stderr)
        print("mutation-check: all mutations caught" if survived == 0 else f"mutation-check: {survived} mutation(s) survived")
        return 0 if survived == 0 else 1
    finally:
        shutil.rmtree(root, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
