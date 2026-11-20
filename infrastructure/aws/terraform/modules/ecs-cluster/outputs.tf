output "cluster_name" {
  description = "Cluster name (grocery-<env>-cluster)."
  value       = aws_ecs_cluster.this.name
}

output "cluster_arn" {
  description = "Cluster ARN."
  value       = aws_ecs_cluster.this.arn
}

output "service_connect_namespace_arn" {
  description = "Service Connect (Cloud Map HTTP) namespace ARN."
  value       = aws_service_discovery_http_namespace.this.arn
}

output "service_connect_namespace_name" {
  description = "Service Connect namespace name."
  value       = aws_service_discovery_http_namespace.this.name
}

output "tasks_security_group_id" {
  description = "Security group of every service and one-off task."
  value       = aws_security_group.tasks.id
}

output "task_failure_metric" {
  description = "Namespace and name of the per-service task failure metric."
  value = {
    namespace = var.task_failure_metric_namespace
    name      = var.task_failure_metric_name
  }
}

output "exec_log_group_name" {
  description = "ECS Exec session log group."
  value       = aws_cloudwatch_log_group.exec.name
}
