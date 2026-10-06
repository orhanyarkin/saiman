output "state_bucket" {
  description = "State bucket name (put it into backend.hcl)."
  value       = aws_s3_bucket.state.id
}

output "plan_role_arn" {
  description = "Role for terraform plan in GitHub Actions (pull_request, main)."
  value       = aws_iam_role.plan.arn
}

output "apply_role_arn" {
  description = "Role for demo-up (environment demo-apply)."
  value       = aws_iam_role.apply.arn
}

output "destroy_role_arn" {
  description = "Role for demo-down and the reaper (environment demo-destroy)."
  value       = aws_iam_role.destroy.arn
}

output "demo_boundary_arn" {
  description = "Permissions boundary every role created by demo-lite must carry."
  value       = aws_iam_policy.demo_boundary.arn
}
