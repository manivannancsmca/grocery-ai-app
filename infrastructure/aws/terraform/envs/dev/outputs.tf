output "deploy_contract" {
  description = "Cluster, services, task families, containers and repositories CI deploys with."
  value       = module.platform.deploy_contract
}

output "app_url" {
  description = "Public URL."
  value       = module.platform.app_url
}

output "cloudfront_domain_name" {
  description = "CloudFront domain (DNS target of domain_name)."
  value       = module.platform.cloudfront_domain_name
}

output "alb_dns_name" {
  description = "Internal ALB (DNS target of api_origin_domain_name)."
  value       = module.platform.alb_dns_name
}

output "nat_public_ips" {
  description = "Outbound IPs."
  value       = module.platform.nat_public_ips
}

output "msk_connect_plugin_bucket" {
  description = "Bucket for the Debezium plugin ZIP."
  value       = module.platform.msk_connect_plugin_bucket
}

output "debezium_connector_configs" {
  description = "Rendered connector configurations."
  value       = module.platform.debezium_connector_configs
}

output "placeholder_secrets" {
  description = "Secrets to fill in."
  value       = module.platform.placeholder_secrets
}

output "alerts_topic_arn" {
  description = "SNS alert topic."
  value       = module.platform.alerts_topic_arn
}

output "grafana_url" {
  description = "Amazon Managed Grafana URL."
  value       = module.platform.grafana_url
}

output "run_task_network" {
  description = "--network-configuration for the one-off tasks."
  value       = module.platform.run_task_network
}
