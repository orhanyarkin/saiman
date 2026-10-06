# Created here (not by the awslogs driver) so retention is always 1 day and nothing survives destroy.
resource "aws_cloudwatch_log_group" "main" {
  name              = local.log_group_name
  retention_in_days = 1
}
