# Inputs of one environment. envs/<env>/main.tf passes the catalog (services.auto.tfvars.json) and <env>.tfvars.

variable "environment" {
  description = "dev | staging | prod."
  type        = string

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging or prod."
  }
}

variable "catalog" {
  description = "Decoded services.auto.tfvars.json (services, one_off_tasks, kafka_topics)."
  type        = any
}

variable "repo_root" {
  description = "Path of the repository root (Debezium connector JSONs under services/)."
  type        = string
}

variable "tags" {
  description = "Extra tags (Project, Environment and ManagedBy are always set)."
  type        = map(string)
  default     = {}
}

# ------------------------------------------------------------------------------------------------------- network
variable "vpc_cidr" {
  description = "VPC CIDR (/16)."
  type        = string
}

variable "az_count" {
  description = "Availability zones (2 dev, 3 staging/prod)."
  type        = number
}

variable "single_nat_gateway" {
  description = "One NAT gateway for the whole VPC (dev only)."
  type        = bool
}

variable "interface_endpoints" {
  description = "Interface VPC endpoints."
  type        = list(string)
  default     = ["ecr.api", "ecr.dkr", "secretsmanager", "ssm", "logs", "sts"]
}

variable "operator_cidrs" {
  description = "CI agents / VPN networks allowed on the ALB test listeners and the internal Schema Registry listener."
  type        = list(string)
  default     = []
}

# ------------------------------------------------------------------------------------------------- DNS and TLS
variable "domain_name" {
  description = "Public host name of the web app (CloudFront alias), e.g. dev.grocery.example.com."
  type        = string
}

variable "api_origin_domain_name" {
  description = "Host name of the internal ALB (CloudFront API origin SNI, Schema Registry URL for schema-init)."
  type        = string
}

variable "route53_zone_id" {
  description = "Public hosted zone for domain_name and api_origin_domain_name (null = DNS managed elsewhere)."
  type        = string
  default     = null
}

variable "alb_certificate_arn" {
  description = "ACM certificate in the environment's region covering api_origin_domain_name."
  type        = string
}

variable "cloudfront_certificate_arn" {
  description = "ACM certificate in us-east-1 covering domain_name."
  type        = string
}

# ------------------------------------------------------------------------------------------------ images, sizes
variable "ecr_registry" {
  description = "Registry host of the grocery/* repositories (null = this account's registry in this region)."
  type        = string
  default     = null
}

variable "ecr_kms_key_arn" {
  description = "KMS key of the ECR repositories (output of envs/shared); null when the registry is not KMS-encrypted."
  type        = string
  default     = null
}

variable "initial_image_tag" {
  description = "Image tag written to /grocery/<env>/<service>/image-tag on creation; CI overwrites it on every deployment."
  type        = string
  default     = "dev"
}

variable "cpu_architecture" {
  description = "X86_64 or ARM64 for the service tasks (the CI images must be built for it)."
  type        = string
  default     = "X86_64"
}

variable "fargate_spot_weight" {
  description = "Weight of FARGATE_SPOT for services (0 = on-demand only)."
  type        = number
  default     = 0
}

variable "sizing" {
  description = "Environment-wide overrides of the catalog sizes (null fields keep the catalog value)."
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
  description = "Per-unit overrides; canary_percent switches that unit to the CANARY strategy."
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
  description = "JAVA_TOOL_OPTIONS of the Spring services."
  type        = string
  default     = "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
}

variable "adot_image" {
  description = "ADOT collector image (pinned)."
  type        = string
  default     = "public.ecr.aws/aws-observability/aws-otel-collector:v0.51.0"
}

variable "adot_memory" {
  description = "Hard memory limit of the ADOT sidecar (MiB)."
  type        = number
  default     = 256
}

# ------------------------------------------------------------------------------------------------- deployments
variable "blue_green_bake_minutes" {
  description = "Bake time after traffic moved to green, before blue is retired (contract: 5 in staging/prod)."
  type        = number
}

variable "canary_bake_minutes" {
  description = "Canary phase duration when a unit uses CANARY."
  type        = number
  default     = 5
}

variable "enable_execute_command" {
  description = "ECS Exec on the services (dev only)."
  type        = bool
  default     = false
}

variable "ci_principal_arns" {
  description = "IAM principals (Jenkins role) allowed to assume grocery-<env>-deployer. Empty = no deploy role."
  type        = list(string)
  default     = []
}

# -------------------------------------------------------------------------------------------------- data stores
variable "rds" {
  description = "RDS MySQL sizing."
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
  description = "Create the MSK Connect plugin and connectors (after the plugin ZIP is in S3 and db-init/kafka-init ran)."
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
  description = "Elastic Cloud deployment. enabled = false leaves ELASTICSEARCH_URIS to url_override."
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

# --------------------------------------------------------------------------------------------- web / edge
variable "waf_enabled" {
  description = "AWS WAF on CloudFront."
  type        = bool
}

variable "cloudfront_price_class" {
  description = "CloudFront price class."
  type        = string
  default     = "PriceClass_200"
}

# ------------------------------------------------------------------------------------------------ observability
variable "log_retention_days" {
  description = "CloudWatch Logs retention."
  type        = number
}

variable "access_log_retention_days" {
  description = "Retention of ALB / CloudFront / S3 access logs in the logs bucket."
  type        = number
  default     = 90
}

variable "alert_emails" {
  description = "Alert recipients (SNS email subscriptions)."
  type        = list(string)
  default     = []
}

variable "grafana_enabled" {
  description = "Amazon Managed Grafana workspace (needs IAM Identity Center)."
  type        = bool
  default     = false
}

variable "alert_rules_file" {
  description = "AMP rules file (null = modules/observability/grocery-alerts.yml)."
  type        = string
  default     = null
}

variable "tracing_sampling_probability" {
  description = "TRACING_SAMPLING_PROBABILITY of the services (contract: 0.1 in AWS)."
  type        = number
  default     = 0.1
}

variable "secret_recovery_window_days" {
  description = "Secrets Manager recovery window."
  type        = number
  default     = 7
}

# ------------------------------------------------------------------------------------------- application settings
variable "jwt_issuer" {
  description = "JWT_ISSUER of every service (null = https://<domain_name>)."
  type        = string
  default     = null
}

variable "bootstrap_admin_email" {
  description = "First SUPER_ADMIN created by identity-service (its password is generated in Secrets Manager)."
  type        = string
}

variable "ai_provider" {
  description = "AI_PROVIDER of ai-orchestrator-service: anthropic (Claude, the production model; key in Secrets Manager) or ollama."
  type        = string
  default     = "anthropic"
}

variable "anthropic_model" {
  description = "ANTHROPIC_MODEL: the Claude model (claude-sonnet-5-5 or claude-haiku-5-5 answer faster and cost less)."
  type        = string
  default     = "claude-opus-5-5"
}

variable "ollama_base_url" {
  description = "OLLAMA_BASE_URL (null = no model endpoint: the assistant answers AI_UNAVAILABLE)."
  type        = string
  default     = null
}

variable "ollama_model" {
  description = "OLLAMA_MODEL."
  type        = string
  default     = "qwen3:4b-instruct"
}

variable "mail_enabled" {
  description = "GROCERY_MAIL_ENABLED (enable once the SMTP secret holds real credentials)."
  type        = bool
  default     = false
}

variable "smtp_host" {
  description = "SMTP host (null = Amazon SES SMTP endpoint of the region)."
  type        = string
  default     = null
}
