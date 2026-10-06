#!/usr/bin/env bash
# Static checks of GitHub Actions `run:` blocks (no actionlint here):
#   1. every workflow file parses (python3 yaml.safe_load);
#   2. no `${{ ... }}` expression inside a `run:` block: untrusted input must travel through `env:`;
#   3. no `set -x` (it would echo secrets);
#   4. every run block passes shellcheck (`-x -S warning`, the digest-pinned image of the root Makefile).
#
# Usage: check-workflow-run-blocks.sh [<workflow.yml>...]     (default: the demo-* workflows and terraform.yml)
# Needs python3 with PyYAML and either `shellcheck` or docker. Exit 0 clean, 1 findings, 2 usage/tooling.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "${here}/../../../.." && pwd)"
image="${SHELLCHECK_IMAGE:-koalaman/shellcheck@sha256:2097951f02e735b613f4a34de20c40f937a6c8f18ecb170612c88c34517221fb}"

files=("$@")
if [[ ${#files[@]} -eq 0 ]]; then
  for f in demo-up demo-down demo-destroy demo-reaper terraform; do
    files+=("${repo}/.github/workflows/${f}.yml")
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
for path in files:
    with open(path, encoding="utf-8") as fh:
        doc = yaml.safe_load(fh)
    base = os.path.basename(path)
    for job_id, job in (doc.get("jobs") or {}).items():
        perms = job.get("permissions", doc.get("permissions"))
        privileged = isinstance(perms, dict) and perms.get("id-token") == "write"
        for i, step in enumerate(job.get("steps") or []):
            if privileged:
                uses = str(step.get("uses", ""))
                wth = step.get("with") or {}
                run_txt = str(step.get("run", ""))
                label = f"{base}:{job_id}:step{i + 1}"
                if any(t in uses for t in ("pnpm/action-setup", "actions/setup-node", "actions/setup-java", "gradle/actions")):
                    print(f"FAIL {label}: toolchain setup ({uses.split('@')[0]}) in a job with id-token: write (M1)")
                    bad += 1
                if "cache" in wth or "cache-dependency-path" in wth:
                    print(f"FAIL {label}: cache: in a job with id-token: write (cache poisoning, M1)")
                    bad += 1
                if re.search(r"(^|[\s;&|(])(pnpm|npm|npx|yarn|\./gradlew|gradle)\s", run_txt):
                    print(f"FAIL {label}: package-manager/build command in a job with id-token: write (M1)")
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
