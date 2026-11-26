# Environment root (identical in dev, staging and prod; validate.sh checks it): every difference lives in
# <env>.tfvars. The deployable units are read from the shared catalog services.auto.tfvars.json.

locals {
  catalog = jsondecode(file("${path.module}/../../services.auto.tfvars.json"))
}

module "platform" {
  source = "../../modules/platform"
  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  environment = var.environment
  catalog     = local.catalog
  repo_root   = "${path.module}/../../../../.."
  tags        = var.tags

  vpc_cidr            = var.vpc_cidr
  az_count            = var.az_count
  single_nat_gateway  = var.single_nat_gateway
  interface_endpoints = var.interface_endpoints
  operator_cidrs      = var.operator_cidrs

  domain_name                = var.domain_name
  api_origin_domain_name     = var.api_origin_domain_name
  route53_zone_id            = var.route53_zone_id
  alb_certificate_arn        = var.alb_certificate_arn
  cloudfront_certificate_arn = var.cloudfront_certificate_arn

  ecr_registry        = var.ecr_registry
  ecr_kms_key_arn     = var.ecr_kms_key_arn
  initial_image_tag   = var.initial_image_tag
  cpu_architecture    = var.cpu_architecture
  fargate_spot_weight = var.fargate_spot_weight
  sizing              = var.sizing
  service_overrides   = var.service_overrides
  java_tool_options   = var.java_tool_options
  adot_image          = var.adot_image

  blue_green_bake_minutes = var.blue_green_bake_minutes
  canary_bake_minutes     = var.canary_bake_minutes
  enable_execute_command  = var.enable_execute_command
  ci_principal_arns       = var.ci_principal_arns

  rds                         = var.rds
  msk                         = var.msk
  debezium_connectors_enabled = var.debezium_connectors_enabled
  msk_connect                 = var.msk_connect
  redis                       = var.redis
  elastic                     = var.elastic

  waf_enabled            = var.waf_enabled
  cloudfront_price_class = var.cloudfront_price_class

  log_retention_days           = var.log_retention_days
  access_log_retention_days    = var.access_log_retention_days
  alert_emails                 = var.alert_emails
  grafana_enabled              = var.grafana_enabled
  tracing_sampling_probability = var.tracing_sampling_probability
  secret_recovery_window_days  = var.secret_recovery_window_days

  jwt_issuer            = var.jwt_issuer
  bootstrap_admin_email = var.bootstrap_admin_email
  ai_provider           = var.ai_provider
  ollama_base_url       = var.ollama_base_url
  ollama_model          = var.ollama_model
  anthropic_model       = var.anthropic_model
  ai_knowledge_enabled  = var.ai_knowledge_enabled
  mail_enabled          = var.mail_enabled
  smtp_host             = var.smtp_host
}
