output "alerts_topic_arn" {
  description = "SNS topic every alarm notifies."
  value       = aws_sns_topic.alerts.arn
}

output "amp_workspace_arn" {
  description = "AMP workspace ARN (aps:RemoteWrite resource)."
  value       = aws_prometheus_workspace.this.arn
}

output "amp_remote_write_endpoint" {
  description = "Remote write URL for the ADOT sidecars."
  value       = "${aws_prometheus_workspace.this.prometheus_endpoint}api/v1/remote_write"
}

output "amp_query_endpoint" {
  description = "Prometheus query endpoint (Grafana data source)."
  value       = aws_prometheus_workspace.this.prometheus_endpoint
}

output "adot_config_parameter_arn" {
  description = "SSM parameter holding the collector configuration."
  value       = aws_ssm_parameter.adot_config.arn
}

output "grafana_endpoint" {
  description = "Amazon Managed Grafana URL (null when disabled)."
  value       = try("https://${aws_grafana_workspace.this[0].endpoint}", null)
}
