output "family" {
  description = "Task definition family (aws ecs run-task --task-definition <family>)."
  value       = aws_ecs_task_definition.this.family
}

output "task_definition_arn" {
  description = "Current revision."
  value       = aws_ecs_task_definition.this.arn
}

output "log_group_name" {
  description = "Where the run's output goes."
  value       = aws_cloudwatch_log_group.this.name
}

output "execution_role_arn" {
  description = "Execution role (CI's iam:PassRole)."
  value       = aws_iam_role.execution.arn
}

output "task_role_arn" {
  description = "Task role (CI's iam:PassRole)."
  value       = aws_iam_role.task.arn
}
