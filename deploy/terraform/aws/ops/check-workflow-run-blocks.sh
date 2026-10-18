#!/usr/bin/env bash
# Static checks of GitHub Actions `run:` blocks (no actionlint here):
#   1. every workflow file parses (python3 yaml.safe_load);
#   2. no `${{ ... }}` expression inside a `run:` block: untrusted input must travel through `env:`;
#   3. no `set -x` (it would echo secrets);
#   4. every run block passes shellcheck (`-x -S warning`, the digest-pinned image of the root Makefile).
#
#   5. no `pull_request_target` or `workflow_run` trigger anywhere;
#   6. a job with `id-token: write` OR `packages: write` must not set up a toolchain, use `cache:` or run a
#      package manager (build code must not run with publish/deploy credentials; audit M1). The only exception is
#      the explicit allowlist ALLOWED_BUILD_WITH_PUBLISH below;
#   7. meta-check: the workflows that own a REQUIRED status check (ci.yml, terraform.yml) have an unfiltered
#      pull_request trigger, because a workflow skipped by path filtering reports no status at all and the
#      protect-main ruleset then waits for that check forever; and terraform.yml's push path filter and the
#      GUARD_PATHS list of its `static` job are the same list, covering the workflows and the scripts the
#      Terraform guards depend on, so the guards cannot be bypassed by editing those files (audit M2).
#
# Usage: check-workflow-run-blocks.sh [<workflow.yml>...]     (default: EVERY file in .github/workflows)
# Needs python3 with PyYAML and either `shellcheck` or docker. Exit 0 clean, 1 findings, 2 usage/tooling.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "${here}/../../../.." && pwd)"
image="${SHELLCHECK_IMAGE:-koalaman/shellcheck@sha256:2097951f02e735b613f4a34de20c40f937a6c8f18ecb170612c88c34517221fb}"

files=("$@")
if [[ ${#files[@]} -eq 0 ]]; then
  for f in "${repo}"/.github/workflows/*.yml; do
    files+=("${f}")
  done
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/saiman-runblocks.XXXXXX")"
trap 'rm -rf "${work}"' EXIT
chmod 755 "${work}"

python3 -I - "${work}" "${files[@]}" <<'PY'
import os
import re
import sys

import yaml

out, files = sys.argv[1], sys.argv[2:]
n = 0
bad = 0
# The ONLY job that builds third-party code while holding packages: write: it publishes the images (ADR-0007).
# Its residual risk is documented in docs/THREAT_MODEL.md (Image integrity). Nothing else may be added here
# without an ADR.
ALLOWED_BUILD_WITH_PUBLISH = {("ci.yml", "images")}
MISSING = object()
# The workflows that own a required status check of the protect-main ruleset: "backend (./gradlew check)",
# "web (lint, typecheck, test, build)", "compose policy (deploy/compose)", "shellcheck (scripts, deploy, hooks)"
# (ci.yml) and "terraform (fmt, validate, test)" (terraform.yml). Renaming one of those jobs breaks the ruleset.
REQUIRED_CHECK_WORKFLOWS = {"ci.yml", "terraform.yml"}
REQUIRED_TF_PATHS = [
    ".github/workflows/**",
    "scripts/ensure-secret-files.sh",
    "scripts/with-auth-digests.sh",
    "deploy/compose/postgres/**",
    "deploy/compose/nginx.conf",
    "deploy/terraform/**",
]
for path in files:
    with open(path, encoding="utf-8") as fh:
        doc = yaml.safe_load(fh)
    base = os.path.basename(path)
    triggers = doc.get("on", doc.get(True)) or {}
    trigger_names = triggers if isinstance(triggers, (list, dict)) else [triggers]
    for forbidden in ("pull_request_target", "workflow_run"):
        if forbidden in trigger_names:
            print(f"FAIL {base}: trigger {forbidden} is forbidden (runs with secrets in the context of untrusted code)")
            bad += 1
    # A workflow that path filtering skips reports NO status, and a required check that never reports blocks
    # every pull request ("Expected - Waiting for status to be reported"), so the workflows that own one must
    # trigger on every pull request. terraform.yml decides inside the job instead (GUARD_PATHS of `static`,
    # kept equal to its push filter); ci.yml's four jobs are cheap enough to always run.
    if base in REQUIRED_CHECK_WORKFLOWS and isinstance(triggers, dict):
        pr = triggers.get("pull_request", MISSING)
        if pr is MISSING:
            print(f"FAIL {base}: no pull_request trigger, so its required status check would never report")
            bad += 1
        elif isinstance(pr, dict):
            for key in ("paths", "paths-ignore"):
                if pr.get(key):
                    print(f"FAIL {base}: pull_request has a {key} filter: a skipped workflow reports no status and the ruleset waits for the required check forever")
                    bad += 1
    if base == "terraform.yml" and isinstance(triggers, dict):
        push_paths = (triggers.get("push") or {}).get("paths") or []
        guard_env = ((doc.get("jobs") or {}).get("static") or {}).get("env") or {}
        guard_paths = str(guard_env.get("GUARD_PATHS", "")).split()
        for want in REQUIRED_TF_PATHS:
            if want not in push_paths:
                print(f"FAIL {base}: push path filter lacks {want} (a guard change would skip the guards)")
                bad += 1
            if want not in guard_paths:
                print(f"FAIL {base}: the static job's GUARD_PATHS lacks {want} (a guard change would not run the guards)")
                bad += 1
        if sorted(set(push_paths)) != sorted(set(guard_paths)):
            print(f"FAIL {base}: GUARD_PATHS {sorted(set(guard_paths))} and the push path filter {sorted(set(push_paths))} must be the same list")
            bad += 1
    for job_id, job in (doc.get("jobs") or {}).items():
        perms = job.get("permissions", doc.get("permissions"))
        privileged = isinstance(perms, dict) and (perms.get("id-token") == "write" or perms.get("packages") == "write")
        if (base, job_id) in ALLOWED_BUILD_WITH_PUBLISH:
            privileged = False
        for i, step in enumerate(job.get("steps") or []):
            if privileged:
                uses = str(step.get("uses", ""))
                wth = step.get("with") or {}
                run_txt = str(step.get("run", ""))
                label = f"{base}:{job_id}:step{i + 1}"
                if any(t in uses for t in ("pnpm/action-setup", "actions/setup-node", "actions/setup-java", "gradle/actions")):
                    print(f"FAIL {label}: toolchain setup ({uses.split('@')[0]}) in a job with id-token/packages: write (M1)")
                    bad += 1
                if "cache" in wth or "cache-dependency-path" in wth:
                    print(f"FAIL {label}: cache: in a job with id-token/packages: write (cache poisoning, M1)")
                    bad += 1
                if re.search(r"(^|[\s;&|(])(pnpm|npm|npx|yarn|\./gradlew|gradle)\s", run_txt):
                    print(f"FAIL {label}: package-manager/build command in a job with id-token/packages: write (M1)")
                    bad += 1
            run = step.get("run")
            if run is None:
                continue
            n += 1
            label = f"{base}:{job_id}:step{i + 1}:{step.get('name', '')}"
            if "${{" in run:
                print(f"FAIL {label}: ${{{{ }}}} expression inside run: (pass it through env:)")
                bad += 1
            if "set -x" in run or "xtrace" in run:
                print(f"FAIL {label}: set -x / xtrace in a run block")
                bad += 1
            with open(os.path.join(out, f"{n:03d}-{base}-{job_id}-{i + 1}.sh"), "w", encoding="utf-8") as fh:
                fh.write("#!/usr/bin/env bash\n# " + label.replace("\n", " ") + "\n" + run)
print(f"extracted {n} run block(s) from {len(files)} workflow(s)")
sys.exit(1 if bad else 0)
PY

shopt -s nullglob
blocks=("${work}"/*.sh)
[[ ${#blocks[@]} -gt 0 ]] || {
  echo "check-workflow-run-blocks: no run blocks found"
  exit 0
}
if command -v shellcheck >/dev/null 2>&1; then
  shellcheck -x -S warning "${blocks[@]}"
else
  command -v docker >/dev/null 2>&1 || {
    echo "check-workflow-run-blocks: neither shellcheck nor docker found" >&2
    exit 2
  }
  names=()
  for b in "${blocks[@]}"; do names+=("/mnt/${b##*/}"); done
  docker run --rm -v "${work}":/mnt:ro -w /mnt "${image}" -x -S warning "${names[@]}"
fi
echo "check-workflow-run-blocks: ${#blocks[@]} run block(s) clean"
