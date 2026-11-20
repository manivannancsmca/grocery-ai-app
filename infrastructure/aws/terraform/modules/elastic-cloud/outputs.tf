output "elasticsearch_url" {
  description = "ELASTICSEARCH_URIS for catalog-query-service (PrivateLink name)."
  value       = local.private_es_url
}

output "kibana_url" {
  description = "Kibana through PrivateLink (from inside the VPC / VPN)."
  value       = local.private_kibana
}

output "deployment_id" {
  description = "Elastic Cloud deployment id."
  value       = ec_deployment.this.id
}

output "superuser_secret_arn" {
  description = "Secret with the deployment's elastic superuser."
  value       = aws_secretsmanager_secret.superuser.arn
}
