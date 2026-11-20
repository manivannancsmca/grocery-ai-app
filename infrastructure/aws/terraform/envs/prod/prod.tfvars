# prod: 3 AZs, NAT per AZ, Multi-AZ RDS and Redis, 3 MSK brokers, at least 2 tasks per service (catalog sizes),
# 5 min bake, WAF, Grafana. The gateway shows the optional canary strategy (10 % for 5 min, then 100 %).
# Values marked REPLACE must be set by the owner before the first apply.

environment = "prod"
aws_region  = "ap-south-1"

vpc_cidr            = "10.30.0.0/16"
az_count            = 3
single_nat_gateway  = false
interface_endpoints = ["ecr.api", "ecr.dkr", "secretsmanager", "ssm", "logs", "sts"]
operator_cidrs      = [] # REPLACE

domain_name                = "grocery.example.com"                                                                  # REPLACE
api_origin_domain_name     = "api-origin.grocery.example.com"                                                       # REPLACE
route53_zone_id            = null                                                                                   # REPLACE
alb_certificate_arn        = "arn:aws:acm:ap-south-1:111111111111:certificate/00000000-0000-0000-0000-000000000000" # REPLACE
cloudfront_certificate_arn = "arn:aws:acm:us-east-1:111111111111:certificate/00000000-0000-0000-0000-000000000000"  # REPLACE

ecr_kms_key_arn     = null # REPLACE (envs/shared output)
initial_image_tag   = "prod"
cpu_architecture    = "X86_64"
fargate_spot_weight = 0

sizing = {} # catalog sizes: 2 tasks minimum per service
service_overrides = {
  "api-gateway" = { canary_percent = 10 }
}

blue_green_bake_minutes = 5
canary_bake_minutes     = 5
enable_execute_command  = false
ci_principal_arns       = [] # REPLACE

rds = {
  instance_class               = "db.r7g.large"
  allocated_storage            = 100
  max_allocated_storage        = 1000
  multi_az                     = true
  backup_retention_days        = 14
  deletion_protection          = true
  performance_insights_enabled = true
}

msk = {
  kafka_version        = "3.9.x.kraft"
  broker_instance_type = "kafka.m7g.large"
  broker_count         = 3
  broker_volume_size   = 500
  replication_factor   = 3
  min_insync_replicas  = 2
  enhanced_monitoring  = "PER_TOPIC_PER_BROKER"
  topic_partitions     = 6
}

debezium_connectors_enabled = false

redis = {
  node_type               = "cache.r7g.large"
  num_cache_clusters      = 2
  snapshot_retention_days = 7
}

elastic = {
  enabled                  = true
  region                   = "aws-ap-south-1"
  version                  = "9.4.6"
  privatelink_service_name = "com.amazonaws.vpce.ap-south-1.vpce-svc-REPLACE_ME" # REPLACE
  hot_size                 = "4g"
  hot_max_size             = "32g"
  zone_count               = 2
}

waf_enabled            = true
cloudfront_price_class = "PriceClass_200"

log_retention_days           = 90
access_log_retention_days    = 365
alert_emails                 = [] # REPLACE
grafana_enabled              = true
tracing_sampling_probability = 0.1
secret_recovery_window_days  = 30

bootstrap_admin_email = "admin@grocery.example.com" # REPLACE
ai_provider           = "anthropic"                 # Claude; set the key in grocery/prod/ai-orchestrator-service/anthropic (api_key)
ollama_base_url       = null
mail_enabled          = false
