# Same variables in every environment; values in <env>.tfvars. See modules/platform/variables.tf for details.

variable "environment" {
  description = "dev | staging | prod."
  type        = string
}

variable "aws_region" {
  description = "AWS region of the environment."
  type        = string
}

variable "tags" {
  description = "Extra tags."
  type        = map(string)
  default     = {}
}

variable "vpc_cidr" {
  description = "VPC CIDR (/16)."
  type        = string
}

variable "az_count" {
  description = "Availability zones."
  type        = number
}

variable "single_nat_gateway" {
  description = "One NAT gateway (dev)."
  type        = bool
}

variable "interface_endpoints" {
  description = "Interface VPC endpoints."
  type        = list(string)
  default     = ["ecr.api", "ecr.dkr", "secretsmanager", "ssm", "logs", "sts"]
}

variable "operator_cidrs" {
  description = "CI / VPN networks for the ALB test listeners."
  type        = list(string)
  default     = []
}

variable "domain_name" {
  description = "Public host name of the web app."
  type        = string
}

variable "api_origin_domain_name" {
  description = "Host name of the internal ALB."
  type        = string
}

variable "route53_zone_id" {
  description = "Public hosted zone (null = DNS elsewhere)."
  type        = string
  default     = null
}

variable "alb_certificate_arn" {
  description = "Regional ACM certificate for api_origin_domain_name."
  type        = string
}

variable "cloudfront_certificate_arn" {
  description = "us-east-1 ACM certificate for domain_name."
  type        = string
}

variable "ecr_registry" {
  description = "Registry host (null = this account and region)."
  type        = string
  default     = null
}

variable "ecr_kms_key_arn" {
  description = "KMS key of the ECR repositories (envs/shared output ecr_kms_key_arn)."
  type        = string
  default     = null
}

variable "initial_image_tag" {
  description = "Image tag before CI's first deployment."
  type        = string
  default     = "dev"
}

variable "cpu_architecture" {
  description = "X86_64 or ARM64."
  type        = string
  default     = "X86_64"
}

variable "fargate_spot_weight" {
  description = "FARGATE_SPOT weight (0 = on-demand)."
  type        = number
  default     = 0
}

variable "sizing" {
  description = "Environment-wide size overrides."
  type = object({
    cpu           = optional(number)
    memory        = optional(number)
    desired_count = optional(number)
    min_count     = optional(number)
    max_count     = optional(number)
  })
  default = {}
}

variable "service_overrides" {
  description = "Per-unit overrides (canary_percent enables CANARY)."
  type = map(object({
    cpu            = optional(number)
    memory         = optional(number)
    desired_count  = optional(number)
    min_count      = optional(number)
    max_count      = optional(number)
    canary_percent = optional(number)
  }))
  default = {}
}

variable "java_tool_options" {
  description = "JAVA_TOOL_OPTIONS."
  type        = string
  default     = "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
}

variable "adot_image" {
  description = "ADOT collector image."
  type        = string
  default     = "public.ecr.aws/aws-observability/aws-otel-collector:v0.51.0"
}

variable "blue_green_bake_minutes" {
  description = "Blue/green bake time."
  type        = number
}

variable "canary_bake_minutes" {
  description = "Canary phase duration."
  type        = number
  default     = 5
}

variable "enable_execute_command" {
  description = "ECS Exec."
  type        = bool
  default     = false
}

variable "ci_principal_arns" {
  description = "Principals allowed to assume grocery-<env>-deployer."
  type        = list(string)
  default     = []
}

variable "rds" {
  description = "RDS sizing."
  type = object({
    instance_class               = string
    allocated_storage            = number
    max_allocated_storage        = number
    multi_az                     = bool
    backup_retention_days        = number
    deletion_protection          = bool
    performance_insights_enabled = bool
  })
}

variable "msk" {
  description = "MSK sizing."
  type = object({
    kafka_version        = string
    broker_instance_type = string
    broker_count         = number
    broker_volume_size   = number
    replication_factor   = number
    min_insync_replicas  = number
    enhanced_monitoring  = string
    topic_partitions     = number
  })
}

variable "debezium_connectors_enabled" {
  description = "Create the MSK Connect connectors."
  type        = bool
  default     = false
}

variable "msk_connect" {
  description = "MSK Connect settings."
  type = object({
    plugin_object_key    = string
    mcu_count            = number
    kafkaconnect_version = string
  })
  default = {
    plugin_object_key    = "debezium-mysql/debezium-connector-mysql-3.7.1.Final-with-msk-config-providers.zip"
    mcu_count            = 1
    kafkaconnect_version = "3.7.x"
  }
}

variable "redis" {
  description = "ElastiCache sizing."
  type = object({
    node_type               = string
    num_cache_clusters      = number
    snapshot_retention_days = number
  })
}

variable "elastic" {
  description = "Elastic Cloud deployment."
  type = object({
    enabled                  = bool
    region                   = string
    version                  = string
    privatelink_service_name = string
    hot_size                 = string
    hot_max_size             = string
    zone_count               = number
    url_override             = optional(string)
  })
}

variable "waf_enabled" {
  description = "AWS WAF on CloudFront."
  type        = bool
}

variable "cloudfront_price_class" {
  description = "CloudFront price class."
  type        = string
  default     = "PriceClass_200"
}

variable "log_retention_days" {
  description = "CloudWatch Logs retention."
  type        = number
}

variable "access_log_retention_days" {
  description = "Access log retention in S3."
  type        = number
  default     = 90
}

variable "alert_emails" {
  description = "Alert recipients."
  type        = list(string)
  default     = []
}

variable "grafana_enabled" {
  description = "Amazon Managed Grafana."
  type        = bool
  default     = false
}

variable "tracing_sampling_probability" {
  description = "Trace sampling."
  type        = number
  default     = 0.1
}

variable "secret_recovery_window_days" {
  description = "Secrets Manager recovery window."
  type        = number
  default     = 7
}

variable "jwt_issuer" {
  description = "JWT_ISSUER (null = https://<domain_name>)."
  type        = string
  default     = null
}

variable "bootstrap_admin_email" {
  description = "First SUPER_ADMIN e-mail."
  type        = string
}

variable "ai_provider" {
  description = "AI_PROVIDER: anthropic (Claude) or ollama."
  type        = string
  default     = "anthropic"
}

variable "anthropic_model" {
  description = "ANTHROPIC_MODEL."
  type        = string
  default     = "claude-opus-5-5"
}

variable "ollama_base_url" {
  description = "OLLAMA_BASE_URL (null = no model endpoint)."
  type        = string
  default     = null
}

variable "ollama_model" {
  description = "OLLAMA_MODEL."
  type        = string
  default     = "qwen3:4b-instruct"
}

variable "mail_enabled" {
  description = "GROCERY_MAIL_ENABLED."
  type        = bool
  default     = false
}

variable "smtp_host" {
  description = "SMTP host (null = SES in the region)."
  type        = string
  default     = null
}
