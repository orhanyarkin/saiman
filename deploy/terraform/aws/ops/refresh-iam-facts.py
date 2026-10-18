#!/usr/bin/env python3
"""Regenerates bootstrap/tests/iam-action-facts.json from AWS's machine-readable Service Reference
(https://servicereference.us-east-1.amazonaws.com, the data behind the Service Authorization Reference).

The file is what keeps the bootstrap IAM tests offline and still authoritative:
  known      every action name of the services the CI roles use (a typo or a made-up action such as budgets:DeleteBudget is caught)
  star_only  actions with NO resource types: they only work with "Resource": "*" (for example ecs:DeregisterTaskDefinition).
             A scoped statement that lists one never grants it, and the role fails at apply/destroy time.

Usage: python3 deploy/terraform/aws/ops/refresh-iam-facts.py        (needs network; commit the result)
"""
import datetime
import json
import os
import sys
import urllib.request

SERVICES = ["ec2", "ecs", "logs", "rds", "scheduler", "ssm", "budgets"]
BASE = "https://servicereference.us-east-1.amazonaws.com/v1"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "bootstrap", "tests", "iam-action-facts.json")


def fetch(service):
    with urllib.request.urlopen(f"{BASE}/{service}/{service}.json", timeout=60) as r:
        return json.load(r)


def main():
    known, star_only = [], []
    for s in SERVICES:
        data = fetch(s)
        for a in data["Actions"]:
            name = f"{s}:{a['Name']}"
            known.append(name)
            if not a.get("Resources"):
                star_only.append(name)
    facts = {
        "source": BASE,
        "generated": datetime.date.today().isoformat(),
        "services": SERVICES,
        "star_only": sorted(star_only),
        "known": sorted(known),
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(facts, f, indent=0, sort_keys=False)
        f.write("\n")
    print(f"wrote {os.path.normpath(OUT)}: {len(known)} actions, {len(star_only)} star-only")
    return 0


if __name__ == "__main__":
    sys.exit(main())
