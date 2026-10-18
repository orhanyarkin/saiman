#!/usr/bin/env python3
"""Mutation check for ops/check-demo-down.sh: breaks the confirmation of tagged resources one way at a time (in a temp
copy) and requires ops/test-check-demo-down.sh to go red. A surviving mutation means the self-test misses that rule.

Usage: python3 deploy/terraform/aws/ops/mutation-check-demo-down.py     (exit 0 all caught, 1 a mutation survived)
"""
import os
import shutil
import subprocess
import sys
import tempfile
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # deploy/terraform/aws
muts = [
 ("stale-counts-as-leftover", 'gone) stale+=("${arn}") ;;', 'gone) leftovers+=("${tag_label}: ${arn}") ;;'),
 ("existing-resource-reported-stale", 'exists) leftovers+=("${tag_label}: ${arn}") ;;', 'exists) stale+=("${arn}") ;;'),
 ("denied-confirmation-reported-stale", 'denied) leftovers+=("${tag_label}: ${arn} (tagging index only: this role may not describe it)") ;;', 'denied) stale+=("${arn}") ;;'),
 ("failed-confirmation-reported-stale", '*) errors+=("${tag_label}: could not confirm ${arn}") ;;', '*) stale+=("${arn}") ;;'),
 ("unknown-type-reported-stale", 'unknown) leftovers+=("${tag_label}: ${arn} (unverified: no direct check for this resource type)") ;;', 'unknown) stale+=("${arn}") ;;'),
 ("inactive-task-definition-is-a-leftover", 'verdict=inactive', 'verdict=exists'),
 ("log-group-matched-by-prefix", "select(.logGroupName == $n)", "."),
 ("notfound-not-recognised", "grep -qE 'NotFound|Unable to describe task definition'", "grep -qE 'NoSuchThing'"),
 ("inactive-cluster-counts-as-live", 'select(.status != "INACTIVE")\' ecs describe-clusters', '.\' ecs describe-clusters'),
 ("jq-failure-reads-as-gone", '*) verdict=failed ;;', '*) verdict=gone ;;'),
 ("inactive-listing-failure-fails-the-run", 'notes+=("INACTIVE task definitions could not be listed")', 'errors+=("INACTIVE task definitions could not be listed")'),
]
bad = 0
for name, old, new in muts:
    d = tempfile.mkdtemp(dir=os.environ.get("TMPDIR", "/tmp"))
    shutil.copytree(os.path.join(root, "ops"), os.path.join(d, "ops"), ignore=shutil.ignore_patterns("__pycache__"))
    os.makedirs(os.path.join(d, "bootstrap"))
    for f in ("iam.tf", "demo_roles.tf", "main.tf"):
        shutil.copy(os.path.join(root, "bootstrap", f), os.path.join(d, "bootstrap", f))
    p = os.path.join(d, "ops", "check-demo-down.sh")
    s = open(p, encoding="utf-8").read()
    if s.count(old) != 1:
        print(f"BROKEN   {name}: edit target found {s.count(old)} times"); bad += 1; continue
    open(p, "w", encoding="utf-8").write(s.replace(old, new))
    r = subprocess.run(["bash", os.path.join(d, "ops", "test-check-demo-down.sh")], capture_output=True, text=True)
    shutil.rmtree(d)
    if r.returncode == 0:
        print(f"SURVIVED {name}"); bad += 1
    else:
        print(f"caught   {name}")
sys.exit(1 if bad else 0)
