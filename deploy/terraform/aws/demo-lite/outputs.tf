# Nothing here is sensitive: names, ARNs and ids only.

output "cluster_name" {
  description = "ECS cluster name (created by ecs.tf)."
  value       = local.cluster_name
}

output "service_name" {
  description = "ECS service name (created by ecs.tf)."
  value       = local.service_name
}

output "log_group" {
  description = "CloudWatch log group of every container."
  value       = aws_cloudwatch_log_group.main.name
}

output "expires_at" {
  description = "Demo expiry (RFC 3339), as passed to demo-up."
  value       = var.expires_at
}

output "rds_endpoint" {
  description = "RDS address (host only) reachable from the task security group."
  value       = aws_db_instance.main.address
}

output "rds_instance_arn" {
  description = "Demo RDS instance ARN."
  value       = aws_db_instance.main.arn
}

output "task_security_group_id" {
  description = "Security group for the Fargate task (no ingress)."
  value       = aws_security_group.task.id
}

output "rds_security_group_id" {
  description = "Security group of the RDS instance."
  value       = aws_security_group.rds.id
}

output "subnet_ids" {
  description = "Public subnet ids (two AZs) for the ECS service."
  value       = aws_subnet.public[*].id
}

output "task_execution_role_arn" {
  description = "ECS task execution role (fixed, bootstrap)."
  value       = local.task_execution_role_arn
}

output "task_role_arn" {
  description = "ECS task role (fixed, bootstrap; no SSM permissions)."
  value       = local.task_role_arn
}

output "scheduler_role_arn" {
  description = "EventBridge Scheduler role (fixed, bootstrap)."
  value       = local.scheduler_role_arn
}

output "ssm_parameter_arn_prefix" {
  description = "ARN pattern of the SSM parameters the demo-up workflow creates (SecureStrings)."
  value       = local.ssm_prefix_arn
}

output "corpus_object_arn" {
  description = "ARN of the private corpus dump the restore step reads."
  value       = "${local.bucket_arn}/${var.corpus_object_key}"
}
