# Secrets Manager entries, all encrypted with one customer-managed key per environment. Names follow the contract
# grocery/<env>/<service>/<name>; the Kafka SCRAM ones carry the prefix MSK requires (AmazonMSK_grocery/<env>/...).
#
#   generated here (random / tls providers; values live in the encrypted Terraform state):
#     grocery/<env>/<service>/db                      {"username","password"}  one per service database
#     grocery/<env>/debezium/db                       {"username","password"}  MSK Connect (config provider)
#     AmazonMSK_grocery/<env>/<user>/kafka            {"username","password"}  SCRAM users (services, kafka-admin)
#     grocery/<env>/redis/auth-token                  {"auth_token"}
#     app secrets of type random_password / rsa_pkcs8 / kafka_jaas (bootstrap admin, approval signing key ...)
#   placeholders (value "REPLACE_ME"; set the real value in the console/CLI, Terraform never overwrites it):
#     grocery/<env>/payment-service/razorpay, grocery/<env>/catalog-query-service/elasticsearch,
#     grocery/<env>/notification-service/smtp, grocery/<env>/ai-orchestrator-service/anthropic

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  prefix = "grocery/${var.environment}"

  db_services    = { for k, s in var.services : k => s.database_user if s.database_user != null }
  kafka_users    = toset(concat([for k, s in var.services : k if s.kafka], var.extra_kafka_users))
  app_secret_all = merge([for svc, s in var.services : { for n, d in s.app_secrets : "${svc}/${n}" => merge(d, { service = svc, name = n }) }]...)

  app_placeholder = { for k, d in local.app_secret_all : k => d if d.type == "placeholder" }
  app_random      = { for k, d in local.app_secret_all : k => d if d.type == "random_password" }
  app_rsa         = { for k, d in local.app_secret_all : k => d if d.type == "rsa_pkcs8" }
  app_jaas        = { for k, d in local.app_secret_all : k => d if d.type == "kafka_jaas" }

  app_random_keys = merge([for k, d in local.app_random : { for key in d.keys : "${k}#${key}" => { secret = k, key = key } }]...)
}

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
  description         = "grocery ${var.environment} Secrets Manager and SSM SecureString encryption"
  enable_key_rotation = true
  policy              = data.aws_iam_policy_document.kms.json
  tags                = var.tags
}

resource "aws_kms_alias" "this" {
  name          = "alias/grocery-${var.environment}-secrets"
  target_key_id = aws_kms_key.this.key_id
}

# ------------------------------------------------------------------------------------------------ database users
resource "random_password" "db" {
  for_each = merge(local.db_services, var.debezium_enabled ? { debezium = "debezium" } : {})

  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "db" {
  for_each = local.db_services

  name                    = "${local.prefix}/${each.key}/db"
  description             = "MySQL credentials of ${each.key} (created in RDS by the db-init task)"
  kms_key_id              = aws_kms_key.this.arn
  recovery_window_in_days = var.recovery_window_days
  tags                    = merge(var.tags, { Service = each.key })
}

resource "aws_secretsmanager_secret_version" "db" {
  for_each = local.db_services

  secret_id     = aws_secretsmanager_secret.db[each.key].id
  secret_string = jsonencode({ username = each.value, password = random_password.db[each.key].result })
}

resource "aws_secretsmanager_secret" "debezium" {
  count = var.debezium_enabled ? 1 : 0

  name                    = "${local.prefix}/debezium/db"
  description             = "MySQL credentials of the Debezium connectors (MSK Connect config provider)"
  kms_key_id              = aws_kms_key.this.arn
  recovery_window_in_days = var.recovery_window_days
  tags                    = merge(var.tags, { Service = "debezium" })
}

resource "aws_secretsmanager_secret_version" "debezium" {
  count = var.debezium_enabled ? 1 : 0

  secret_id     = aws_secretsmanager_secret.debezium[0].id
  secret_string = jsonencode({ username = "debezium", password = random_password.db["debezium"].result })
}

# ---------------------------------------------------------------------------------------------- Kafka SCRAM users
resource "random_password" "kafka" {
  for_each = local.kafka_users

  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "kafka" {
  for_each = local.kafka_users

  name                    = "AmazonMSK_${local.prefix}/${each.key}/kafka"
  description             = "MSK SASL/SCRAM user ${each.key}"
  kms_key_id              = aws_kms_key.this.arn
  recovery_window_in_days = var.recovery_window_days
  tags                    = merge(var.tags, { Service = each.key })
}

resource "aws_secretsmanager_secret_version" "kafka" {
  for_each = local.kafka_users

  secret_id     = aws_secretsmanager_secret.kafka[each.key].id
  secret_string = jsonencode({ username = each.key, password = random_password.kafka[each.key].result })
}

# No aws_secretsmanager_secret_policy here: associating the secret with the cluster makes MSK attach its own
# resource policy (AWSKafkaResourcePolicy); a Terraform-managed one would fight it.

# ----------------------------------------------------------------------------------------------------- Redis AUTH
resource "random_password" "redis" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "redis" {
  name                    = "${local.prefix}/redis/auth-token"
  description             = "ElastiCache Redis AUTH token"
  kms_key_id              = aws_kms_key.this.arn
  recovery_window_in_days = var.recovery_window_days
  tags                    = merge(var.tags, { Service = "redis" })
}

resource "aws_secretsmanager_secret_version" "redis" {
  secret_id     = aws_secretsmanager_secret.redis.id
  secret_string = jsonencode({ auth_token = random_password.redis.result })
}

# ---------------------------------------------------------------------------------------------- application secrets
resource "aws_secretsmanager_secret" "app" {
  for_each = local.app_secret_all

  name                    = "${local.prefix}/${each.value.service}/${each.value.name}"
  description             = "${each.value.service}: ${each.value.name} (${each.value.type})"
  kms_key_id              = aws_kms_key.this.arn
  recovery_window_in_days = var.recovery_window_days
  tags                    = merge(var.tags, { Service = each.value.service })
}

# Placeholders: the owner sets the real values (Razorpay keys, Elastic API key, SMTP credentials) out of band.
resource "aws_secretsmanager_secret_version" "placeholder" {
  for_each = local.app_placeholder

  secret_id     = aws_secretsmanager_secret.app[each.key].id
  secret_string = jsonencode({ for key in each.value.keys : key => "REPLACE_ME" })

  lifecycle {
    ignore_changes = [secret_string]
  }
}

resource "random_password" "app" {
  for_each = local.app_random_keys

  length  = 32
  special = true
  # Characters that survive shells, JSON and properties files unescaped.
  override_special = "-_.+=~"
  min_lower        = 2
  min_upper        = 2
  min_numeric      = 2
  min_special      = 2
}

resource "aws_secretsmanager_secret_version" "random" {
  for_each = local.app_random

  secret_id     = aws_secretsmanager_secret.app[each.key].id
  secret_string = jsonencode({ for key in each.value.keys : key => random_password.app["${each.key}#${key}"].result })
}

# RSA key in unencrypted PKCS#8 PEM (-----BEGIN PRIVATE KEY-----), e.g. AI_APPROVAL_SIGNING_KEY: every replica must
# sign with the same key. Stored as a plain string (the whole secret is the PEM).
resource "tls_private_key" "app" {
  for_each = local.app_rsa

  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "aws_secretsmanager_secret_version" "rsa" {
  for_each = local.app_rsa

  secret_id     = aws_secretsmanager_secret.app[each.key].id
  secret_string = tls_private_key.app[each.key].private_key_pem_pkcs8
}

# Kafka JAAS line of the unit's own SCRAM user (the Schema Registry needs it as one property).
resource "aws_secretsmanager_secret_version" "jaas" {
  for_each = local.app_jaas

  secret_id = aws_secretsmanager_secret.app[each.key].id
  secret_string = jsonencode({
    jaas_config = "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"${each.value.service}\" password=\"${random_password.kafka[each.value.service].result}\";"
  })
}
