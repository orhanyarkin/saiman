#!/usr/bin/env python3
"""Mutation check for the IAM coverage tests in tests/bootstrap.tftest.hcl (offline, no AWS).

Each mutation breaks iam.tf the way a real mistake would (an action in the wrong kind of statement, a missing or an invented
action) and requires a red `terraform test` that carries the rule's own message. The first mutation reproduces the demo-down
failure: ecs:DeregisterTaskDefinition in a statement that names ARNs. Engine: ops/tf_mutation.py. The repository is not modified.

Usage: python3 deploy/terraform/aws/bootstrap/tests/mutation-check.py [rule-id ...]
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MODULE = os.path.dirname(HERE)

MUTATIONS = [
    # The real bug: a star-only action behind a scoped Resource (both CI roles share the statement).
    ("deregister-in-a-scoped-statement", "iam.tf", '    Action   = ["ecs:DeregisterTaskDefinition"]\n    Resource = "*"', '    Action   = ["ecs:DeregisterTaskDefinition"]\n    Resource = "arn:aws:ecs:${var.region}:${local.account_id}:task-definition/saiman-demo*:*"', 'inside scoped statements: EcsDeregisterTaskDefinition: ecs:DeregisterTaskDefinition'),
    ("deregister-missing-for-the-task-definition-resource", "iam.tf", '    Action   = ["ecs:DeregisterTaskDefinition"]', '    Action   = ["ecs:DescribeTaskDefinition"]', "aws_ecs_task_definition: ecs:DeregisterTaskDefinition"),
    ("apply-lacks-replace-route", "iam.tf", '"ec2:ModifySecurityGroupRules", "ec2:ReplaceRoute",', '"ec2:ModifySecurityGroupRules",', "the apply role lacks: aws_route: ec2:ReplaceRoute"),
    ("apply-lacks-modify-security-group-rules", "iam.tf", '"ec2:ModifySecurityGroupRules", "ec2:ReplaceRoute",', '"ec2:ReplaceRoute",', "ec2:ModifySecurityGroupRules"),
    ("destroy-lacks-delete-cluster", "iam.tf", '"ecs:DeleteService", "ecs:DeleteCluster", "ecs:StopTask",', '"ecs:DeleteService", "ecs:StopTask",', "the destroy role lacks: aws_ecs_cluster: ecs:DeleteCluster"),
    ("apply-lacks-update-cluster-settings", "iam.tf", '"ecs:UpdateCluster", "ecs:UpdateClusterSettings",', '"ecs:UpdateCluster",', "aws_ecs_cluster: ecs:UpdateClusterSettings"),
    ("an-action-that-does-not-exist", "iam.tf", '"scheduler:CreateSchedule", "scheduler:UpdateSchedule", "scheduler:DeleteSchedule",\n        ]', '"scheduler:CreateSchedule", "scheduler:UpdateSchedule", "scheduler:DeleteSchedule", "budgets:DeleteBudget",\n        ]', "budgets:DeleteBudget"),
    ("a-wildcard-that-matches-nothing", "iam.tf", '"ecs:Describe*",', '"ecs:Descrbe*",', "ecs:Descrbe*"),
    ("workflows-lose-list-tasks", "iam.tf", '    "ecs:List*",', '    "ecs:ListClusters",', "the destroy role lacks what the workflows call:"),
    ("workflows-lose-run-task", "iam.tf", 'Action   = ["ecs:RunTask"]', 'Action   = ["ecs:StartTask"]', "the apply role lacks what the workflows call: demo-up: run-init-task.sh and the service start: ecs:RunTask"),
]


def main():
    sys.path.insert(0, os.path.join(HERE, "..", "..", "ops"))
    import tf_mutation

    return tf_mutation.run(MODULE, "tests/bootstrap.tftest.hcl", MUTATIONS, sys.argv[1:])


if __name__ == "__main__":
    sys.exit(main())
