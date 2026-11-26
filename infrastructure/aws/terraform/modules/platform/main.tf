# One environment of the grocery platform: composes the modules. Identical for dev, staging and prod; only
# envs/<env>/<env>.tfvars differ. The deployable units come from services.auto.tfvars.json (var.catalog).

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}
data "aws_partition" "current" {}
data "aws_elb_service_account" "this" {}

locals {
  name      = "grocery-${var.environment}"
  account   = data.aws_caller_identity.current.account_id
  region    = data.aws_region.current.region
  partition = data.aws_partition.current.partition
  tags = merge(var.tags, {
    Project     = "grocery"
    Environment = var.environment
    ManagedBy   = "terraform"
  })

  app_url      = "https://${var.domain_name}"
  jwt_issuer   = var.jwt_issuer == null ? local.app_url : var.jwt_issuer
  ecr_registry = var.ecr_registry == null ? "${local.account}.dkr.ecr.${local.region}.amazonaws.com" : var.ecr_registry
  smtp_host    = var.smtp_host == null ? "email-smtp.${local.region}.amazonaws.com" : var.smtp_host

  # ------------------------------------------------------------------------------------------- the catalog
  raw       = var.catalog.services
  ecs_names = sort([for k, s in local.raw : k if s.kind == "ecs"])

  # Normalised view of every ECS unit (uniform types), sizes resolved: per-unit override > env sizing > catalog.
  svc = {
    for k in local.ecs_names : k => {
      runtime           = local.raw[k].runtime
      port              = local.raw[k].port
      health_check_path = local.raw[k].health_check_path
      container_health  = local.raw[k].container_health_check
      metrics_path      = try(local.raw[k].metrics_path, null)
      metrics_port      = try(local.raw[k].metrics_port, null)
      adot              = local.raw[k].adot
      read_only_root    = local.raw[k].read_only_root_filesystem
      command           = try(local.raw[k].command, null)
      image_repository  = try(local.raw[k].image_repository, null)
      external_image    = try(local.raw[k].external_image, null)
      load_balancer     = local.raw[k].load_balancer
      sc_timeouts       = local.raw[k].service_connect_timeouts
      needs             = local.raw[k].needs
      database          = local.raw[k].database
      connector_file    = local.raw[k].debezium_connector

      cpu           = coalesce(try(var.service_overrides[k].cpu, null), var.sizing.cpu, local.raw[k].cpu)
      memory        = coalesce(try(var.service_overrides[k].memory, null), var.sizing.memory, local.raw[k].memory)
      desired_count = coalesce(try(var.service_overrides[k].desired_count, null), var.sizing.desired_count, local.raw[k].desired_count)
      min_count     = coalesce(try(var.service_overrides[k].min_count, null), var.sizing.min_count, local.raw[k].desired_count)
      max_count = max(
        coalesce(try(var.service_overrides[k].min_count, null), var.sizing.min_count, local.raw[k].desired_count),
        coalesce(try(var.service_overrides[k].max_count, null), var.sizing.max_count, local.raw[k].max_count),
      )
      canary_percent = try(var.service_overrides[k].canary_percent, null)
    }
  }

  # ------------------------------------------------------------------------------------------- environment
  # {{tokens}} of the catalog's environment values.
  token_values = {
    app_url                  = local.app_url
    bootstrap_admin_email    = var.bootstrap_admin_email
    ai_provider              = var.ai_provider
    ollama_base_url          = var.ollama_base_url == null ? "" : var.ollama_base_url
    ollama_model             = var.ollama_model
    anthropic_model          = var.anthropic_model
    ai_knowledge_enabled     = tostring(var.ai_knowledge_enabled)
    mail_enabled             = tostring(var.mail_enabled)
    smtp_host                = local.smtp_host
    kafka_bootstrap_scram    = module.msk.bootstrap_brokers_scram
    kafka_replication_factor = tostring(var.msk.replication_factor)
  }

  catalog_env = {
    for k in local.ecs_names : k => {
      for ek, ev in local.raw[k].environment : ek => replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(
        ev,
        "{{app_url}}", local.token_values.app_url),
        "{{bootstrap_admin_email}}", local.token_values.bootstrap_admin_email),
        "{{ai_provider}}", local.token_values.ai_provider),
        "{{ollama_base_url}}", local.token_values.ollama_base_url),
        "{{ollama_model}}", local.token_values.ollama_model),
        "{{anthropic_model}}", local.token_values.anthropic_model),
        "{{ai_knowledge_enabled}}", local.token_values.ai_knowledge_enabled),
        "{{mail_enabled}}", local.token_values.mail_enabled),
        "{{smtp_host}}", local.token_values.smtp_host),
        "{{kafka_bootstrap_scram}}", local.token_values.kafka_bootstrap_scram),
      "{{kafka_replication_factor}}", local.token_values.kafka_replication_factor)
    }
  }

  elasticsearch_url = var.elastic.enabled ? module.elastic[0].elasticsearch_url : (var.elastic.url_override == null ? "" : var.elastic.url_override)

  # Contract §7: what application-aws.yml reads.
  spring_common_env = {
    SPRING_PROFILES_ACTIVE       = "aws"
    GROCERY_ENV                  = var.environment
    TZ                           = "UTC"
    JAVA_TOOL_OPTIONS            = var.java_tool_options
    OTEL_EXPORTER_OTLP_ENDPOINT  = "http://localhost:4318"
    TRACING_SAMPLING_PROBABILITY = tostring(var.tracing_sampling_probability)
    JWT_ISSUER                   = local.jwt_issuer
    IDENTITY_JWKS_URI            = "http://identity-service:8101/.well-known/jwks.json"
  }

  service_env = {
    for k in local.ecs_names : k => {
      for ek, ev in merge(
        local.svc[k].runtime == "spring-boot" ? local.spring_common_env : {},
        local.svc[k].needs.database ? { DB_HOST = module.rds.address, DB_PORT = tostring(module.rds.port) } : {},
        local.svc[k].needs.kafka && local.svc[k].runtime == "spring-boot" ? { KAFKA_BOOTSTRAP_SERVERS = module.msk.bootstrap_brokers_scram } : {},
        local.svc[k].needs.redis ? { REDIS_HOST = module.redis.primary_endpoint, REDIS_PORT = tostring(module.redis.port), REDIS_TLS = "true" } : {},
        local.svc[k].needs.elasticsearch ? { ELASTICSEARCH_URIS = local.elasticsearch_url } : {},
        local.svc[k].needs.schema_registry ? {
          SCHEMA_REGISTRY_URL                     = "http://schema-registry:8081"
          GROCERY_EVENTS_AVRO_SCHEMA_REGISTRY_URL = "http://schema-registry:8081"
        } : {},
        local.svc[k].needs.approval_jwks ? { APPROVAL_JWKS_URI = "http://ai-orchestrator-service:8115/.well-known/approval-jwks.json" } : {},
        local.catalog_env[k],
      ) : ek => ev if ev != ""
    }
  }

  # ------------------------------------------------------------------------------------------- secrets
  # "db:password" -> <service db secret ARN>:password::   "approval-signing-key" -> <secret ARN> (whole value)
  secret_arn_by_name = {
    for k in local.ecs_names : k => merge(
      { redis = module.secrets.redis_secret_arn },
      local.svc[k].database != null ? { db = module.secrets.db_secret_arns[k] } : {},
      local.svc[k].needs.kafka ? { kafka = module.secrets.kafka_secret_arns[k] } : {},
      { for n, d in local.raw[k].app_secrets : n => module.secrets.app_secret_arns["${k}/${n}"] },
    )
  }

  service_secrets = {
    for k in local.ecs_names : k => {
      for ek, ref in local.raw[k].secrets : ek => (
        length(split(":", ref)) > 1
        ? "${local.secret_arn_by_name[k][split(":", ref)[0]]}:${split(":", ref)[1]}::"
        : local.secret_arn_by_name[k][ref]
      )
    }
  }

  # De-duplicated on the (known) secret names, so the list length is known at plan time.
  service_secret_arns = {
    for k in local.ecs_names : k => [
      for n in distinct([for ek, ref in local.raw[k].secrets : split(":", ref)[0]]) : local.secret_arn_by_name[k][n]
    ]
  }

  # ------------------------------------------------------------------------------------------- Debezium
  connector_configs = {
    for k in local.ecs_names : k => jsondecode(file("${var.repo_root}/${local.svc[k].connector_file}"))
    if local.svc[k].connector_file != null
  }

  capacity_provider_strategy = var.fargate_spot_weight > 0 ? [
    { capacity_provider = "FARGATE", weight = 0, base = 0 },
    { capacity_provider = "FARGATE_SPOT", weight = var.fargate_spot_weight, base = 0 },
  ] : [{ capacity_provider = "FARGATE", weight = 1, base = 0 }]
}

# ------------------------------------------------------------------------------------------------ KMS (platform)
data "aws_iam_policy_document" "logs_kms" {
  statement {
    sid       = "AccountAdministration"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${local.partition}:iam::${local.account}:root"]
    }
  }

  statement {
    sid       = "CloudWatchLogs"
    actions   = ["kms:Encrypt*", "kms:Decrypt*", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:Describe*"]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["logs.${local.region}.amazonaws.com"]
    }
    condition {
      test     = "ArnLike"
      variable = "kms:EncryptionContext:aws:logs:arn"
      values   = ["arn:${local.partition}:logs:${local.region}:${local.account}:log-group:*"]
    }
  }
}

resource "aws_kms_key" "logs" {
  description         = "${local.name} CloudWatch Logs and ECS Exec encryption"
  enable_key_rotation = true
  policy              = data.aws_iam_policy_document.logs_kms.json
  tags                = local.tags
}

resource "aws_kms_alias" "logs" {
  name          = "alias/${local.name}-logs"
  target_key_id = aws_kms_key.logs.key_id
}

data "aws_iam_policy_document" "s3_kms" {
  statement {
    sid       = "AccountAdministration"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${local.partition}:iam::${local.account}:root"]
    }
  }
}

resource "aws_kms_key" "s3" {
  description         = "${local.name} S3 data buckets"
  enable_key_rotation = true
  policy              = data.aws_iam_policy_document.s3_kms.json
  tags                = local.tags
}

resource "aws_kms_alias" "s3" {
  name          = "alias/${local.name}-s3"
  target_key_id = aws_kms_key.s3.key_id
}

# ------------------------------------------------------------------------------------------------ logs bucket
# ALB access logs, CloudFront standard logs and S3 server access logs. SSE-S3 because ALB access logs cannot be
# written to an SSE-KMS bucket; ACLs enabled (BucketOwnerPreferred) because CloudFront standard logging needs them.
resource "aws_s3_bucket" "logs" {
  bucket = "${local.name}-logs-${local.account}"
  tags   = local.tags
}

resource "aws_s3_bucket_public_access_block" "logs" {
  bucket                  = aws_s3_bucket.logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "logs" {
  bucket = aws_s3_bucket.logs.id
  rule {
    object_ownership = "BucketOwnerPreferred"
  }
}

resource "aws_s3_bucket_versioning" "logs" {
  bucket = aws_s3_bucket.logs.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "logs" {
  bucket = aws_s3_bucket.logs.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "logs" {
  bucket = aws_s3_bucket.logs.id

  rule {
    id     = "expire-access-logs"
    status = "Enabled"
    filter {}
    expiration {
      days = var.access_log_retention_days
    }
    noncurrent_version_expiration {
      noncurrent_days = 7
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

data "aws_iam_policy_document" "logs_bucket" {
  statement {
    sid       = "AlbAccessLogs"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.logs.arn}/alb/AWSLogs/${local.account}/*"]
    principals {
      type        = "AWS"
      identifiers = [data.aws_elb_service_account.this.arn]
    }
  }

  statement {
    sid       = "S3ServerAccessLogs"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.logs.arn}/s3/*"]
    principals {
      type        = "Service"
      identifiers = ["logging.s3.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.logs.arn, "${aws_s3_bucket.logs.arn}/*"]
    principals {
      type        = "*"
      identifiers = ["*"]
    }
    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "logs" {
  bucket = aws_s3_bucket.logs.id
  policy = data.aws_iam_policy_document.logs_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.logs]
}

# --------------------------------------------------------------------------------------------------- modules
module "network" {
  source = "../network"

  name                = local.name
  environment         = var.environment
  cidr_block          = var.vpc_cidr
  az_count            = var.az_count
  single_nat_gateway  = var.single_nat_gateway
  interface_endpoints = var.interface_endpoints
  log_retention_days  = var.log_retention_days
  log_kms_key_arn     = aws_kms_key.logs.arn
  tags                = local.tags
}

module "secrets" {
  source = "../secrets"

  environment          = var.environment
  recovery_window_days = var.secret_recovery_window_days
  debezium_enabled     = length(local.connector_configs) > 0
  services = {
    for k in local.ecs_names : k => {
      database_user = local.svc[k].database == null ? null : local.svc[k].database.user
      kafka         = local.svc[k].needs.kafka
      app_secrets = {
        for n, d in local.raw[k].app_secrets : n => {
          type = d.type
          keys = [for key in d.keys : tostring(key)]
        }
      }
    }
  }
  tags = local.tags
}

module "observability" {
  source = "../observability"

  name                       = local.name
  environment                = var.environment
  alert_emails               = var.alert_emails
  alert_rules_file           = var.alert_rules_file
  grafana_enabled            = var.grafana_enabled
  rds_identifier             = module.rds.identifier
  redis_replication_group_id = module.redis.replication_group_id
  redis_node_count           = var.redis.num_cache_clusters
  msk_cluster_name           = module.msk.cluster_name
  log_retention_days         = var.log_retention_days
  log_kms_key_arn            = aws_kms_key.logs.arn
  tags                       = local.tags
}

module "ecs_cluster" {
  source = "../ecs-cluster"

  name                = local.name
  environment         = var.environment
  vpc_id              = module.network.vpc_id
  vpc_cidr_block      = module.network.vpc_cidr_block
  fargate_spot_weight = var.fargate_spot_weight
  exec_kms_key_arn    = aws_kms_key.logs.arn
  log_retention_days  = var.log_retention_days
  log_kms_key_arn     = aws_kms_key.logs.arn
  tags                = local.tags
}

module "rds" {
  source = "../rds-mysql"

  name       = local.name
  vpc_id     = module.network.vpc_id
  subnet_ids = module.network.private_subnet_ids
  client_security_group_ids = {
    ecs-tasks   = module.ecs_cluster.tasks_security_group_id
    msk-connect = module.msk_connect.security_group_id
  }
  instance_class               = var.rds.instance_class
  allocated_storage            = var.rds.allocated_storage
  max_allocated_storage        = var.rds.max_allocated_storage
  multi_az                     = var.rds.multi_az
  backup_retention_days        = var.rds.backup_retention_days
  deletion_protection          = var.rds.deletion_protection
  performance_insights_enabled = var.rds.performance_insights_enabled
  tags                         = local.tags
}

module "msk" {
  source = "../msk"

  name                 = local.name
  environment          = var.environment
  vpc_id               = module.network.vpc_id
  subnet_ids           = module.network.private_subnet_ids
  kafka_version        = var.msk.kafka_version
  broker_instance_type = var.msk.broker_instance_type
  broker_count         = var.msk.broker_count
  broker_volume_size   = var.msk.broker_volume_size
  replication_factor   = var.msk.replication_factor
  min_insync_replicas  = var.msk.min_insync_replicas
  enhanced_monitoring  = var.msk.enhanced_monitoring
  scram_client_security_group_ids = {
    ecs-tasks = module.ecs_cluster.tasks_security_group_id
  }
  iam_client_security_group_ids = {
    msk-connect = module.msk_connect.security_group_id
  }
  scram_secret_arns  = module.secrets.kafka_secret_arns
  log_retention_days = var.log_retention_days
  log_kms_key_arn    = aws_kms_key.logs.arn
  tags               = local.tags
}

module "msk_connect" {
  source = "../msk-connect"

  name                  = local.name
  environment           = var.environment
  connectors_enabled    = var.debezium_connectors_enabled
  connector_configs     = local.connector_configs
  kafkaconnect_version  = var.msk_connect.kafkaconnect_version
  mcu_count             = var.msk_connect.mcu_count
  plugin_object_key     = var.msk_connect.plugin_object_key
  vpc_id                = module.network.vpc_id
  vpc_cidr_block        = module.network.vpc_cidr_block
  subnet_ids            = module.network.private_subnet_ids
  msk_cluster_arn       = module.msk.cluster_arn
  msk_cluster_name      = module.msk.cluster_name
  msk_cluster_uuid      = module.msk.cluster_uuid
  bootstrap_servers_iam = module.msk.bootstrap_brokers_iam
  replication_factor    = var.msk.replication_factor
  database_hostname     = module.rds.address
  database_port         = module.rds.port
  database_secret_name  = module.secrets.debezium_secret_name
  database_secret_arn   = module.secrets.debezium_secret_arn
  secrets_kms_key_arn   = module.secrets.kms_key_arn
  s3_kms_key_arn        = aws_kms_key.s3.arn
  access_logs_bucket    = aws_s3_bucket.logs.id
  alarm_topic_arn       = module.observability.alerts_topic_arn
  log_retention_days    = var.log_retention_days
  log_kms_key_arn       = aws_kms_key.logs.arn
  tags                  = local.tags
}

module "redis" {
  source = "../elasticache"

  name        = local.name
  environment = var.environment
  vpc_id      = module.network.vpc_id
  subnet_ids  = module.network.private_subnet_ids
  client_security_group_ids = {
    ecs-tasks = module.ecs_cluster.tasks_security_group_id
  }
  node_type               = var.redis.node_type
  num_cache_clusters      = var.redis.num_cache_clusters
  snapshot_retention_days = var.redis.snapshot_retention_days
  auth_token              = module.secrets.redis_auth_token
  log_retention_days      = var.log_retention_days
  log_kms_key_arn         = aws_kms_key.logs.arn
  tags                    = local.tags
}

module "elastic" {
  source = "../elastic-cloud"
  count  = var.elastic.enabled ? 1 : 0

  name                     = local.name
  environment              = var.environment
  vpc_id                   = module.network.vpc_id
  subnet_ids               = module.network.private_subnet_ids
  client_security_group_id = module.ecs_cluster.tasks_security_group_id
  privatelink_service_name = var.elastic.privatelink_service_name
  elastic_region           = var.elastic.region
  elastic_version          = var.elastic.version
  hot_size                 = var.elastic.hot_size
  hot_max_size             = var.elastic.hot_max_size
  zone_count               = var.elastic.zone_count
  secrets_kms_key_arn      = module.secrets.kms_key_arn
  recovery_window_days     = var.secret_recovery_window_days
  tags                     = local.tags
}

module "alb" {
  source = "../alb"

  name                    = local.name
  vpc_id                  = module.network.vpc_id
  subnet_ids              = module.network.private_subnet_ids
  tasks_security_group_id = module.ecs_cluster.tasks_security_group_id
  operator_cidrs          = var.operator_cidrs
  certificate_arn         = var.alb_certificate_arn
  deletion_protection     = var.environment == "prod"
  access_logs_bucket      = aws_s3_bucket.logs.id
  apps = {
    for k in local.ecs_names : k => {
      short_name         = join("", [for part in split("-", k) : substr(part, 0, 1)])
      target_port        = local.svc[k].port
      health_check_path  = local.svc[k].health_check_path
      listener_port      = local.svc[k].load_balancer.listener_port
      test_listener_port = local.svc[k].load_balancer.test_listener_port
      from_cloudfront    = local.svc[k].load_balancer.from_cloudfront
    } if local.svc[k].load_balancer != null
  }
  tags = local.tags

  depends_on = [aws_s3_bucket_policy.logs]
}

resource "aws_route53_record" "api_origin" {
  count = var.route53_zone_id == null ? 0 : 1

  zone_id = var.route53_zone_id
  name    = var.api_origin_domain_name
  type    = "A"

  alias {
    name                   = module.alb.dns_name
    zone_id                = module.alb.zone_id
    evaluate_target_health = false
  }
}

module "spa" {
  source = "../spa-hosting"
  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  name                    = local.name
  environment             = var.environment
  aliases                 = [var.domain_name]
  certificate_arn         = var.cloudfront_certificate_arn
  route53_zone_id         = var.route53_zone_id
  api_alb_arn             = module.alb.arn
  api_origin_domain_name  = var.api_origin_domain_name
  logs_bucket             = aws_s3_bucket.logs.id
  logs_bucket_domain_name = aws_s3_bucket.logs.bucket_domain_name
  price_class             = var.cloudfront_price_class
  waf_enabled             = var.waf_enabled
  tags                    = local.tags

  depends_on = [aws_s3_bucket_ownership_controls.logs]
}
