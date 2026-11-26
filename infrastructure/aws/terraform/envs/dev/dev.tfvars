# dev: smallest sizes, 2 AZs, one NAT gateway, 1 task per service (Fargate Spot), short bake time.
# Values marked REPLACE must be set by the owner before the first apply (nothing is applied by this repository).

environment = "dev"
aws_region  = "ap-south-1"

vpc_cidr            = "10.10.0.0/16"
az_count            = 2
single_nat_gateway  = true
interface_endpoints = ["ecr.api", "ecr.dkr", "secretsmanager", "ssm", "logs", "sts"]
operator_cidrs      = [] # REPLACE: Jenkins agents / VPN CIDRs that may call the test listeners, e.g. ["10.0.10.0/24"]

domain_name                = "dev.grocery.example.com"                                                              # REPLACE
api_origin_domain_name     = "api-origin.dev.grocery.example.com"                                                   # REPLACE
route53_zone_id            = null                                                                                   # REPLACE with the public zone id to manage DNS here
alb_certificate_arn        = "arn:aws:acm:ap-south-1:111111111111:certificate/00000000-0000-0000-0000-000000000000" # REPLACE
cloudfront_certificate_arn = "arn:aws:acm:us-east-1:111111111111:certificate/00000000-0000-0000-0000-000000000000"  # REPLACE

ecr_kms_key_arn     = null # REPLACE with the envs/shared output ecr_kms_key_arn
initial_image_tag   = "dev"
cpu_architecture    = "X86_64"
fargate_spot_weight = 1

sizing = {
  cpu           = 512
  memory        = 1024
  desired_count = 1
  min_count     = 1
  max_count     = 2
}
service_overrides = {
  "ai-orchestrator-service" = { memory = 2048 } # Spring AI + reactor-netty need the headroom
}

blue_green_bake_minutes = 1
enable_execute_command  = true
ci_principal_arns       = [] # REPLACE: e.g. ["arn:aws:iam::111111111111:role/jenkins"]

rds = {
  instance_class               = "db.t4g.small" # 15 service pools (Hikari max 10) need more connections than a micro offers
  allocated_storage            = 20
  max_allocated_storage        = 50
  multi_az                     = false
  backup_retention_days        = 1
  deletion_protection          = false
  performance_insights_enabled = false
}

msk = {
  kafka_version        = "3.9.x.kraft"
  broker_instance_type = "kafka.t3.small"
  broker_count         = 2
  broker_volume_size   = 20
  replication_factor   = 2
  min_insync_replicas  = 1
  enhanced_monitoring  = "DEFAULT"
  topic_partitions     = 3
}

debezium_connectors_enabled = false # true after the plugin ZIP is uploaded and db-init / kafka-init have run

redis = {
  node_type               = "cache.t4g.micro"
  num_cache_clusters      = 1
  snapshot_retention_days = 1
}

elastic = {
  enabled                  = true
  region                   = "aws-ap-south-1" # Elastic Cloud region id
  version                  = "9.4.6"
  privatelink_service_name = "com.amazonaws.vpce.ap-south-1.vpce-svc-REPLACE_ME" # REPLACE (Elastic docs: PrivateLink regions)
  hot_size                 = "1g"
  hot_max_size             = "4g"
  zone_count               = 1
}

waf_enabled            = false
cloudfront_price_class = "PriceClass_200"

log_retention_days           = 14
access_log_retention_days    = 30
alert_emails                 = [] # REPLACE: e.g. ["ops@example.com"]
grafana_enabled              = false
tracing_sampling_probability = 1.0
secret_recovery_window_days  = 0

bootstrap_admin_email = "admin@grocery.example.com" # REPLACE
ai_provider           = "anthropic"                 # Claude; set the key in grocery/dev/ai-orchestrator-service/anthropic (api_key)
ollama_base_url       = null                        # no model endpoint yet: the assistant answers AI_UNAVAILABLE
ai_knowledge_enabled  = false                       # help center (RAG): true after the Elasticsearch API key and Bedrock Titan V2 access exist (docs/rag/RAG-LOCAL-SETUP.md)
mail_enabled          = false
