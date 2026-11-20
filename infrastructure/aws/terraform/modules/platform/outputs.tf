output "deploy_contract" {
  description = "Names CI deploys with (also in SSM /grocery/<env>/deploy/contract)."
  value       = local.deploy_contract
}

output "app_url" {
  description = "Public URL of the web app (and /api)."
  value       = local.app_url
}

output "cloudfront_domain_name" {
  description = "Point domain_name here when DNS is managed elsewhere."
  value       = module.spa.distribution_domain_name
}

output "alb_dns_name" {
  description = "Internal ALB; point api_origin_domain_name here when DNS is managed elsewhere."
  value       = module.alb.dns_name
}

output "nat_public_ips" {
  description = "Outbound IPs (allow-lists at Razorpay or an external model endpoint)."
  value       = module.network.nat_public_ips
}

output "msk_connect_plugin_bucket" {
  description = "Upload the Debezium plugin ZIP here before enabling the connectors."
  value       = module.msk_connect.plugin_bucket
}

output "debezium_connector_configs" {
  description = "Connector configurations as rendered for MSK Connect (credentials are config-provider references)."
  value       = module.msk_connect.rendered_connector_configs
}

output "placeholder_secrets" {
  description = "Secrets the owner must fill in (Razorpay, Elastic API key, SMTP)."
  value       = module.secrets.placeholder_secret_names
}

output "alerts_topic_arn" {
  description = "SNS alert topic."
  value       = module.observability.alerts_topic_arn
}

output "grafana_url" {
  description = "Amazon Managed Grafana (null when disabled)."
  value       = module.observability.grafana_endpoint
}

output "run_task_network" {
  description = "--network-configuration for aws ecs run-task of the one-off tasks."
  value       = "awsvpcConfiguration={subnets=[${join(",", module.network.private_subnet_ids)}],securityGroups=[${module.ecs_cluster.tasks_security_group_id}],assignPublicIp=DISABLED}"
}
