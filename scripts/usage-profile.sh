#!/usr/bin/env bash
# Switches the agent usage profile (CLAUDE.md "Usage profiles"): applies .claude/profiles/<name>.json to the
# model/effort/maxTurns lines of .claude/agents/*.md frontmatter and to CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS in
# .claude/settings.json, then records the active profile in .claude/profile. Shows the diff before applying.
# Idempotent: applying the active profile again changes nothing. Only the named lines are rewritten, so switching
# back and forth restores the files byte for byte.
#
# Usage: scripts/usage-profile.sh pro|max      apply a profile
#        scripts/usage-profile.sh --show       print the active profile
# SAIMAN_PROFILE_ROOT overrides the repository root (used by scripts/test-usage-profile.sh).
set -euo pipefail

root="${SAIMAN_PROFILE_ROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
claude_dir="${root}/.claude"

if [[ "${1:-}" == "--show" ]]; then
  if [[ -f "${claude_dir}/profile" ]]; then
    echo "active profile: $(cat "${claude_dir}/profile")"
  else
    echo "active profile: (none recorded)"
  fi
  exit 0
fi

profile="${1:-}"
if [[ -z "${profile}" || ! "${profile}" =~ ^[a-z]+$ || ! -f "${claude_dir}/profiles/${profile}.json" ]]; then
  echo "usage-profile: unknown profile '${profile}'; available: $(cd "${claude_dir}/profiles" && ls *.json | sed 's/\.json$//' | tr '\n' ' ')" >&2
  exit 2
fi

python3 - "${claude_dir}" "${profile}" <<'PY'
import difflib, json, os, re, sys

claude_dir, profile = sys.argv[1], sys.argv[2]
spec = json.load(open(os.path.join(claude_dir, "profiles", profile + ".json")))

def render_agent(text, values, path):
    if not text.startswith("---\n"):
        sys.exit(f"usage-profile: {path} has no frontmatter")
    end = text.index("\n---\n", 4)
    head, body = text[: end + 1], text[end + 1 :]
    for key in ("model", "effort", "maxTurns"):
        pattern = re.compile(rf"^{key}: .*$", re.M)
        if not pattern.search(head):
            sys.exit(f"usage-profile: {path} frontmatter has no '{key}:' line")
        head = pattern.sub(f"{key}: {values[key]}", head, count=1)
    return head + body

changes = []
agents_dir = os.path.join(claude_dir, "agents")
for name, values in sorted(spec["agents"].items()):
    path = os.path.join(agents_dir, name + ".md")
    if not os.path.isfile(path):
        sys.exit(f"usage-profile: profile names agent '{name}' but {path} does not exist")
    old = open(path, encoding="utf-8").read()
    changes.append((path, old, render_agent(old, values, path)))

settings = os.path.join(claude_dir, "settings.json")
old = open(settings, encoding="utf-8").read()
pattern = re.compile(r'("CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS"\s*:\s*")\d+(")')
if not pattern.search(old):
    sys.exit("usage-profile: settings.json has no CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS")
new = pattern.sub(lambda m: m.group(1) + str(int(spec["maxConcurrentSubagents"])) + m.group(2), old, count=1)
json.loads(new)  # still valid JSON
changes.append((settings, old, new))

marker = os.path.join(claude_dir, "profile")
old = open(marker, encoding="utf-8").read() if os.path.exists(marker) else ""
changes.append((marker, old, profile + "\n"))

pending = [(p, o, n) for p, o, n in changes if o != n]
if not pending:
    print(f"usage-profile: '{profile}' is already active; nothing to change")
    sys.exit(0)
for path, o, n in pending:
    rel = os.path.relpath(path, os.path.dirname(claude_dir))
    sys.stdout.writelines(difflib.unified_diff(o.splitlines(True), n.splitlines(True), "a/" + rel, "b/" + rel))
for path, _, n in pending:
    with open(path, "w", encoding="utf-8") as f:
        f.write(n)
print(f"usage-profile: applied '{profile}' ({len(pending)} file(s) changed)")
PY
