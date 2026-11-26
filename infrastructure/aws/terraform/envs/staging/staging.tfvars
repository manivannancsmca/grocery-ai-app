# staging: like prod (3 AZs, NAT per AZ, Multi-AZ data stores, 2 tasks per service, 5 min bake, WAF), smaller sizes.
# Values marked REPLACE must be set by the owner before the first apply.

environment = "staging"
aws_region  = "ap-south-1"

vpc_cidr            = "10.20.0.0/16"
az_count            = 3
single_nat_gateway  = false
interface_endpoints = ["ecr.api", "ecr.dkr", "secretsmanager", "ssm", "logs", "sts"]
operator_cidrs      = [] # REPLACE

domain_name                = "staging.grocery.example.com"                                                          # REPLACE
api_origin_domain_name     = "api-origin.staging.grocery.example.com"                                               # REPLACE
route53_zone_id            = null                                                                                   # REPLACE
alb_certificate_arn        = "arn:aws:acm:ap-south-1:111111111111:certificate/00000000-0000-0000-0000-000000000000" # REPLACE
cloudfront_certificate_arn = "arn:aws:acm:us-east-1:111111111111:certificate/00000000-0000-0000-0000-000000000000"  # REPLACE

ecr_kms_key_arn     = null # REPLACE (envs/shared output)
initial_image_tag   = "staging"
cpu_architecture    = "X86_64"
fargate_spot_weight = 0

sizing = {
  desired_count = 2
  min_count     = 2
  max_count     = 4
}
service_overrides = {}

blue_green_bake_minutes = 5
enable_execute_command  = false
ci_principal_arns       = [] # REPLACE

rds = {
  instance_class               = "db.t4g.medium"
  allocated_storage            = 50
  max_allocated_storage        = 200
  multi_az                     = true
  backup_retention_days        = 7
  deletion_protection          = true
  performance_insights_enabled = true
}

msk = {
  kafka_version        = "3.9.x.kraft"
  broker_instance_type = "kafka.t3.small"
  broker_count         = 3
  broker_volume_size   = 50
  replication_factor   = 3
  min_insync_replicas  = 2
  enhanced_monitoring  = "PER_BROKER"
  topic_partitions     = 3
}

debezium_connectors_enabled = false

redis = {
  node_type               = "cache.t4g.small"
  num_cache_clusters      = 2
  snapshot_retention_days = 3
}

elastic = {
  enabled                  = true
  region                   = "aws-ap-south-1"
  version                  = "9.4.6"
  privatelink_service_name = "com.amazonaws.vpce.ap-south-1.vpce-svc-REPLACE_ME" # REPLACE
  hot_size                 = "2g"
  hot_max_size             = "8g"
  zone_count               = 2
}

waf_enabled            = true
cloudfront_price_class = "PriceClass_200"

log_retention_days           = 30
access_log_retention_days    = 90
alert_emails                 = [] # REPLACE
grafana_enabled              = false
tracing_sampling_probability = 0.25
secret_recovery_window_days  = 7

bootstrap_admin_email = "admin@grocery.example.com" # REPLACE
ai_provider           = "anthropic"                 # Claude; set the key in grocery/staging/ai-orchestrator-service/anthropic (api_key)
ollama_base_url       = null
ai_knowledge_enabled  = false # help center (RAG): true after the Elasticsearch API key and Bedrock Titan V2 access exist (docs/rag/RAG-LOCAL-SETUP.md)
mail_enabled          = false
