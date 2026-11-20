output "service_name" {
  description = "ECS service name (grocery-<env>-<name>)."
  value       = aws_ecs_service.this.name
}

output "task_family" {
  description = "Task definition family (same as the service name)."
  value       = aws_ecs_task_definition.this.family
}

output "task_definition_arn" {
  description = "Revision registered by Terraform (CI registers later ones)."
  value       = aws_ecs_task_definition.this.arn
}

output "app_container_name" {
  description = "Container whose image CI replaces."
  value       = "app"
}

output "task_role_arn" {
  description = "Task role."
  value       = aws_iam_role.task.arn
}

output "execution_role_arn" {
  description = "Execution role."
  value       = aws_iam_role.execution.arn
}

output "log_group_name" {
  description = "Log group of the app, the sidecar and Service Connect."
  value       = aws_cloudwatch_log_group.this.name
}

output "rollback_alarm_names" {
  description = "Alarms that roll a deployment back."
  value       = local.rollback_alarms
}
