#!/usr/bin/env python3
"""On-demand audit of the bootstrap IAM policies against AWS's Service Reference (needs network; not part of CI).

tests/bootstrap.tftest.hcl checks action names and the "only works with Resource *" rule offline. This script adds the part that
needs the full resource-type data: for every Allow statement that names ARNs it checks that each action supports a resource
type whose ARN format matches one of the statement's ARNs (for example scheduler:TagResource is schedule-group scoped, so a
statement naming schedule/... ARNs never grants it).

Usage:
  cd deploy/terraform/aws/bootstrap && terraform plan -out=/tmp/b.tfplan && terraform show -json /tmp/b.tfplan > /tmp/b.json
  python3 deploy/terraform/aws/ops/audit-iam-policies.py /tmp/b.json
Exit 0 no finding, 1 findings.
"""
import fnmatch
import json
import re
import sys
import urllib.request

BASE = "https://servicereference.us-east-1.amazonaws.com/v1"
SERVICES = {"ec2", "ecs", "logs", "rds", "scheduler", "ssm", "budgets"}  # the services ops/refresh-iam-facts.py covers


def load(service):
    with urllib.request.urlopen(f"{BASE}/{service}/{service}.json", timeout=60) as r:
        data = json.load(r)
    return {a["Name"]: a for a in data["Actions"]}, {r["Name"]: r.get("ARNFormats", []) for r in data["Resources"]}


def names_type(arn, fmt):
    """True if the policy ARN can name resources of this format: it starts with the format's literal prefix after the account."""
    literal = re.sub(r"^arn:\$\{Partition\}:[a-z0-9-]+:\$\{Region\}:\$\{Account\}:", "", fmt).split("${", 1)[0]
    tail = arn.split(":", 5)[-1] if arn.count(":") >= 5 else arn
    return tail.startswith(literal) or literal.startswith(tail.split("*", 1)[0])


def policies(plan):
    found = {}
    for rc in plan.get("resource_changes", []):
        if rc["type"] in ("aws_iam_role_policy", "aws_iam_policy"):
            after = rc["change"].get("after") or rc["change"].get("before") or {}
            if after.get("policy"):
                found[rc["address"]] = json.loads(after["policy"])
    return found


def main(path):
    pols = policies(json.load(open(path, encoding="utf-8")))
    cache, findings = {}, 0
    for name, pol in sorted(pols.items()):
        for st in pol["Statement"]:
            if st["Effect"] != "Allow" or "Action" not in st:
                continue
            acts = st["Action"] if isinstance(st["Action"], list) else [st["Action"]]
            res = st["Resource"] if isinstance(st["Resource"], list) else [st["Resource"]]
            if "*" in res:
                continue
            for pat in acts:
                svc, _, act = pat.partition(":")
                if svc not in SERVICES:
                    continue
                actions, resources = cache.setdefault(svc, load(svc))
                names = [x for x in actions if fnmatch.fnmatchcase(x, act)]
                wildcard = "*" in act

                def problem(n):
                    types = [r["Name"].rstrip("*") for r in actions[n].get("Resources", [])]
                    if not types:
                        return "supports only Resource '*' but the statement names ARNs"
                    if not any(names_type(arn, f) for arn in res for t in types for f in resources.get(t, [])):
                        return f"resource types {types} match none of the statement's ARNs"
                    return None

                problems = {n: problem(n) for n in names}
                # An explicit action must work; a wildcard (ecs:Describe*, iam:Get*) only needs to grant something.
                flagged = [(n, why) for n, why in problems.items() if why] if not wildcard else ([(act, "no matching action works with these ARNs")] if names and all(problems.values()) else [])
                if not names:
                    flagged = [(act, "matches no known action")]
                for n, why in flagged:
                    print(f"{name} [{st.get('Sid', '?')}] {svc}:{n}: {why}")
                    findings += 1
    print(f"audit-iam-policies: {findings} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
