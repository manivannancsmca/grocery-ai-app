output "plugin_bucket" {
  description = "Upload the plugin ZIP here."
  value       = aws_s3_bucket.plugins.id
}

output "security_group_id" {
  description = "Worker security group (needs MySQL and MSK IAM access)."
  value       = aws_security_group.this.id
}

output "connector_names" {
  description = "Service => connector name (also when not yet enabled)."
  value       = { for k, c in local.connectors : k => c.name }
}

output "rendered_connector_configs" {
  description = "Service => connector configuration as MSK Connect receives it (no secret values: credentials are config-provider references)."
  value       = { for k, c in local.connectors : k => c.configuration }
}
