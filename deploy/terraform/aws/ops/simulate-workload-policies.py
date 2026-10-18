#!/usr/bin/env python3
"""On-demand, read-only check of the demo workload roles with AWS's own policy simulator (needs credentials, not part of CI).

The offline tests (bootstrap/tests/bootstrap.tftest.hcl, check-workload-actions.sh) check action names and statement shapes.
This asks IAM itself what the task role and the task execution role may do, boundary included, for the requests the
containers really make and for the ones that must stay impossible (listing the Terraform state, writing, reading secrets).

  --plan FILE   policies of a bootstrap plan (`terraform show -json`), simulated with iam:SimulateCustomPolicy: what the roles
                WILL be able to do after the human applies bootstrap
  --live        the roles as they exist now, with iam:SimulatePrincipalPolicy
  --bucket B    the state bucket (never printed in full: the account id inside it is redacted)

Only iam:Simulate* and sts:GetCallerIdentity are called. Exit 0 when every planned decision matches, 1 otherwise (--live is
reported only: before the apply it is expected to differ).
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile

REGION = "eu-central-1"
ASSETS_PREFIX = "demo/assets/zz-session/"

# (role, action, resource template, context, expected: allow | deny)  {b}=bucket {a}=account
# Not simulated: the execution role's logs:CreateLogStream / PutLogEvents on the log group "/saiman-demo-lite". The simulator
# denies them even for a policy whose Resource equals the request ARN (checked: it cannot evaluate a log group name that starts
# with "/", while the same policy with a plain name is allowed), so that answer would be noise. The real awslogs driver writes
# there (the init task logs reached CloudWatch), and bootstrap.tftest.hcl checks the action and the boundary ceiling.
REQUESTS = [
    ("task", "s3:ListBucket", "arn:aws:s3:::{b}", {"s3:prefix": ASSETS_PREFIX}, "allow"),  # aws s3 sync (demo-up #6)
    ("task", "s3:GetObject", "arn:aws:s3:::{b}/" + ASSETS_PREFIX + "web/dist/index.html", {}, "allow"),
    ("task", "s3:GetObject", "arn:aws:s3:::{b}/artifacts/corpus/latest.dump", {}, "allow"),  # aws s3 cp: HeadObject + GetObject
    ("task", "s3:GetObject", "arn:aws:s3:::{b}/artifacts/corpus/latest.meta.json", {}, "allow"),
    ("task", "ssmmessages:CreateDataChannel", "*", {}, "allow"),  # ECS Exec
    ("task", "s3:ListBucket", "arn:aws:s3:::{b}", {"s3:prefix": "bootstrap/"}, "deny"),
    ("task", "s3:ListBucket", "arn:aws:s3:::{b}", {"s3:prefix": "demo-lite/"}, "deny"),
    ("task", "s3:ListBucket", "arn:aws:s3:::{b}", {"s3:prefix": "artifacts/corpus/"}, "deny"),
    ("task", "s3:ListBucket", "arn:aws:s3:::{b}", {}, "deny"),  # no prefix at all
    ("task", "s3:GetObject", "arn:aws:s3:::{b}/demo-lite/terraform.tfstate", {}, "deny"),
    ("task", "s3:GetObject", "arn:aws:s3:::{b}/bootstrap/terraform.tfstate", {}, "deny"),
    ("task", "s3:PutObject", "arn:aws:s3:::{b}/demo/assets/zz-session/x", {}, "deny"),
    ("task", "ssm:GetParameters", "arn:aws:ssm:" + REGION + ":{a}:parameter/saiman/demo/pg_master_password", {}, "deny"),
    ("execution", "ssm:GetParameters", "arn:aws:ssm:" + REGION + ":{a}:parameter/saiman/demo/pg_master_password", {}, "allow"),
    ("execution", "ssm:GetParameters", "arn:aws:ssm:" + REGION + ":{a}:parameter/saiman/demo/redis_password", {}, "allow"),  # SPRING_DATA_REDIS_PASSWORD, REDIS_PASSWORD
    ("execution", "kms:Decrypt", "arn:aws:kms:" + REGION + ":{a}:key/00000000-0000-0000-0000-000000000000", {"kms:ViaService": "ssm." + REGION + ".amazonaws.com"}, "allow"),
    ("execution", "ssm:GetParameters", "arn:aws:ssm:" + REGION + ":{a}:parameter/saiman/other/x", {}, "deny"),
    ("execution", "s3:GetObject", "arn:aws:s3:::{b}/demo/assets/zz-session/x", {}, "deny"),
]
ROLE_ADDR = {"task": "aws_iam_role_policy.demo_task", "execution": "aws_iam_role_policy.demo_task_execution"}
ROLE_NAME = {"task": "saiman-demo-task", "execution": "saiman-demo-task-execution"}


def aws(*args):
    proc = subprocess.run(["aws", "--region", REGION, "--output", "json", *args], capture_output=True, text=True)
    if proc.returncode != 0:
        sys.exit("aws " + " ".join(args[:2]) + " failed: " + re.sub(r"\d{12}", "<ACCOUNT>", proc.stderr.strip()))
    return json.loads(proc.stdout)


def redact(text, account):
    return text.replace(account, "<ACCOUNT>")


def planned_policies(path):
    plan = json.load(open(path, encoding="utf-8"))
    docs = {}
    for rc in plan["resource_changes"]:
        after = rc["change"].get("after") or {}
        if rc["address"] in ROLE_ADDR.values() or rc["address"] == "aws_iam_policy.demo_boundary":
            docs[rc["address"]] = after["policy"]
    return docs


def simulate(role, action, resource, context, mode, docs, account):
    entries = [{"ContextKeyName": "aws:RequestedRegion", "ContextKeyValues": [REGION], "ContextKeyType": "string"}]
    entries += [{"ContextKeyName": k, "ContextKeyValues": [v], "ContextKeyType": "string"} for k, v in context.items()]
    req = {"ActionNames": [action], "ResourceArns": [resource], "ContextEntries": entries}
    with tempfile.NamedTemporaryFile("w", suffix=".json") as f:
        if mode == "plan":
            req["PolicyInputList"] = [docs[ROLE_ADDR[role]]]
            req["PermissionsBoundaryPolicyInputList"] = [docs["aws_iam_policy.demo_boundary"]]
            json.dump(req, f)
            f.flush()
            out = aws("iam", "simulate-custom-policy", "--cli-input-json", "file://" + f.name)
        else:
            req["PolicySourceArn"] = f"arn:aws:iam::{account}:role/saiman/demo/{ROLE_NAME[role]}"
            json.dump(req, f)
            f.flush()
            out = aws("iam", "simulate-principal-policy", "--cli-input-json", "file://" + f.name)
    res = out["EvaluationResults"][0]
    boundary = res.get("PermissionsBoundaryDecisionDetail", {}).get("AllowedByPermissionsBoundary")
    return res["EvalDecision"], boundary, res.get("MissingContextValues", [])


def run(mode, docs, bucket, account):
    bad = 0
    print(f"--- {'planned policies (simulate-custom-policy)' if mode == 'plan' else 'live roles (simulate-principal-policy)'}")
    for role, action, resource, context, want in REQUESTS:
        resource = resource.format(b=bucket, a=account)
        got, boundary, missing = simulate(role, action, resource, context, mode, docs, account)
        fine = got == "allowed" if want == "allow" else got.endswith("Deny")
        mark = "ok  " if fine else "DIFF"
        bad += not fine
        what = redact(resource, account).replace(bucket, "<BUCKET>")
        ctx = ",".join(f"{k}={v}" for k, v in context.items()) or "no context"
        print(f"{mark} {role:9} {action:30} {what[:80]:80} [{ctx}] -> {got}" + (f" (boundary allows: {boundary})" if boundary is not None else "") + (" (no s3:prefix in the request)" if action == "s3:ListBucket" and "s3:prefix" in missing else "") + ("" if fine else f"   wanted {want}"))
    return bad


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--plan")
    ap.add_argument("--live", action="store_true")
    ap.add_argument("--bucket", required=True)
    args = ap.parse_args()
    if not args.plan and not args.live:
        ap.error("give --plan and/or --live")
    account = aws("sts", "get-caller-identity")["Account"]
    rc = 0
    if args.plan:
        rc |= run("plan", planned_policies(args.plan), args.bucket, account) > 0
    if args.live:
        run("live", None, args.bucket, account)
    print("simulate-workload-policies: " + ("planned decisions match" if not rc else "PLANNED DECISIONS DIFFER"))
    return rc


if __name__ == "__main__":
    sys.exit(main())
