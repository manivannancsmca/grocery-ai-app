# ElastiCache for Redis OSS: replication group, TLS in transit (required), AUTH token (from Secrets Manager, generated
# by the secrets module), encryption at rest with a customer-managed key, Multi-AZ with automatic failover when there is
# more than one node (staging/prod).

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

data "aws_iam_policy_document" "kms" {
  statement {
    sid       = "AccountAdministration"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }
}

resource "aws_kms_key" "this" {
  description         = "${var.name} ElastiCache encryption at rest"
  enable_key_rotation = true
  policy              = data.aws_iam_policy_document.kms.json
  tags                = var.tags
}

resource "aws_kms_alias" "this" {
  name          = "alias/${var.name}-redis"
  target_key_id = aws_kms_key.this.key_id
}

resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name}-redis"
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "this" {
  name        = "${var.name}-redis"
  description = "ElastiCache Redis of ${var.name}"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-redis" })
}

resource "aws_vpc_security_group_ingress_rule" "clients" {
  for_each = var.client_security_group_ids

  security_group_id            = aws_security_group.this.id
  description                  = "Redis TLS from ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  referenced_security_group_id = each.value
}

# Same eviction policy as the local container (redis-server --maxmemory-policy volatile-lru): only keys with a TTL
# (quotes, offers, rate-limit windows) are evicted, never carts or SSE state without one.
resource "aws_elasticache_parameter_group" "this" {
  name        = "${var.name}-redis7"
  family      = var.parameter_group_family
  description = "grocery ${var.environment}: volatile-lru eviction"

  parameter {
    name  = "maxmemory-policy"
    value = "volatile-lru"
  }

  tags = var.tags
}

resource "aws_cloudwatch_log_group" "slow_log" {
  name              = "/grocery/${var.environment}/redis-slow-log"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_kms_key_arn
  tags              = var.tags
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${var.name}-redis"
  description          = "Redis of ${var.name}: rate limits, carts, quotes, SSE fan-out, partner locations"
  engine               = "redis"
  engine_version       = var.engine_version
  node_type            = var.node_type
  num_cache_clusters   = var.num_cache_clusters
  port                 = 6379
  parameter_group_name = aws_elasticache_parameter_group.this.name

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.this.id]

  automatic_failover_enabled = var.num_cache_clusters > 1
  multi_az_enabled           = var.num_cache_clusters > 1

  transit_encryption_enabled = true
  transit_encryption_mode    = "required"
  auth_token                 = var.auth_token
  auth_token_update_strategy = "ROTATE"
  at_rest_encryption_enabled = true
  kms_key_id                 = aws_kms_key.this.arn

  snapshot_retention_limit   = var.snapshot_retention_days
  snapshot_window            = "17:00-18:00"
  maintenance_window         = "sun:20:30-sun:21:30"
  auto_minor_version_upgrade = true
  apply_immediately          = false

  log_delivery_configuration {
    destination      = aws_cloudwatch_log_group.slow_log.name
    destination_type = "cloudwatch-logs"
    log_format       = "json"
    log_type         = "slow-log"
  }

  tags = var.tags
}
