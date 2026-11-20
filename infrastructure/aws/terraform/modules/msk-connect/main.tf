# MSK Connect = the outbox relay. One Debezium MySQL connector per producing service, rendered from the same
# services/<service>/deploy/debezium-connector.json the local stack registers, with the environment-specific values
# replaced (RDS host, credentials through the Secrets Manager config provider, MSK IAM bootstrap servers, replication).
#
# The custom plugin is a ZIP the owner (or CI) uploads to s3://<plugin bucket>/<plugin_object_key>:
#   debezium-connector-mysql-<version>.Final-plugin (its jars, including debezium-core with BinaryDataConverter)
#   + msk-config-providers (com.amazonaws.kafka.config.providers.SecretsManagerConfigProvider)
# Connectors are created only when var.connectors_enabled (after the ZIP exists and db-init/kafka-init have run).

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  account   = data.aws_caller_identity.current.account_id
  region    = data.aws_region.current.region
  partition = data.aws_partition.current.partition

  # Values that differ from the local stack. The local JSON keeps ${env:DEBEZIUM_DB_PASSWORD}; here the worker's
  # Secrets Manager config provider resolves the credentials at runtime (they never appear in the connector config).
  iam_client = {
    "security.protocol"                  = "SASL_SSL"
    "sasl.mechanism"                     = "AWS_MSK_IAM"
    "sasl.jaas.config"                   = "software.amazon.msk.auth.iam.IAMLoginModule required;"
    "sasl.client.callback.handler.class" = "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
  }
  overrides = merge(
    {
      "database.hostname"                               = var.database_hostname
      "database.port"                                   = tostring(var.database_port)
      "database.user"                                   = "$${secretsmanager:${var.database_secret_name}:username}"
      "database.password"                               = "$${secretsmanager:${var.database_secret_name}:password}"
      "database.ssl.mode"                               = "required"
      "schema.history.internal.kafka.bootstrap.servers" = var.bootstrap_servers_iam
      "topic.creation.default.replication.factor"       = tostring(var.replication_factor)
    },
    { for k, v in local.iam_client : "schema.history.internal.producer.${k}" => v },
    { for k, v in local.iam_client : "schema.history.internal.consumer.${k}" => v },
  )

  connectors = {
    for svc, cfg in var.connector_configs : svc => {
      name = "${var.name}-${cfg["name"]}"
      configuration = merge(
        { for k, v in cfg : k => tostring(v) if k != "name" },
        local.overrides,
      )
    }
  }

  topic_arn_prefix = "arn:${local.partition}:kafka:${local.region}:${local.account}:topic/${var.msk_cluster_name}/${var.msk_cluster_uuid}"
  group_arn_prefix = "arn:${local.partition}:kafka:${local.region}:${local.account}:group/${var.msk_cluster_name}/${var.msk_cluster_uuid}"
}

# ------------------------------------------------------------------------------------------------- plugin bucket
resource "aws_s3_bucket" "plugins" {
  bucket = "${var.name}-msk-connect-plugins-${local.account}"
  tags   = var.tags
}

resource "aws_s3_bucket_public_access_block" "plugins" {
  bucket                  = aws_s3_bucket.plugins.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "plugins" {
  bucket = aws_s3_bucket.plugins.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "plugins" {
  bucket = aws_s3_bucket.plugins.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "plugins" {
  bucket = aws_s3_bucket.plugins.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = var.s3_kms_key_arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_logging" "plugins" {
  bucket        = aws_s3_bucket.plugins.id
  target_bucket = var.access_logs_bucket
  target_prefix = "s3/${aws_s3_bucket.plugins.id}/"
}

data "aws_iam_policy_document" "plugins_bucket" {
  statement {
    sid     = "DenyInsecureTransport"
    effect  = "Deny"
    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.plugins.arn,
      "${aws_s3_bucket.plugins.arn}/*",
    ]
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

resource "aws_s3_bucket_policy" "plugins" {
  bucket = aws_s3_bucket.plugins.id
  policy = data.aws_iam_policy_document.plugins_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.plugins]
}

resource "aws_mskconnect_custom_plugin" "debezium" {
  count = var.connectors_enabled ? 1 : 0

  name         = "${var.name}-debezium-mysql"
  description  = "Debezium MySQL connector + MSK config providers"
  content_type = "ZIP"

  location {
    s3 {
      bucket_arn = aws_s3_bucket.plugins.arn
      file_key   = var.plugin_object_key
    }
  }
}

resource "aws_mskconnect_worker_configuration" "this" {
  name        = "${var.name}-debezium-worker"
  description = "Converters of the outbox relay and the Secrets Manager config provider"

  properties_file_content = <<-PROPERTIES
    key.converter=org.apache.kafka.connect.storage.StringConverter
    value.converter=io.debezium.converters.BinaryDataConverter
    value.converter.delegate.converter.type=org.apache.kafka.connect.json.JsonConverter
    value.converter.delegate.converter.type.schemas.enable=false
    config.providers=secretsmanager
    config.providers.secretsmanager.class=com.amazonaws.kafka.config.providers.SecretsManagerConfigProvider
    config.providers.secretsmanager.param.region=${local.region}
  PROPERTIES
}

# ---------------------------------------------------------------------------------------------- network and IAM
resource "aws_security_group" "this" {
  name        = "${var.name}-msk-connect"
  description = "MSK Connect workers of ${var.name}"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-msk-connect" })
}

resource "aws_vpc_security_group_egress_rule" "vpc" {
  security_group_id = aws_security_group.this.id
  description       = "MySQL, MSK (IAM) and VPC endpoints inside the VPC"
  ip_protocol       = "tcp"
  from_port         = 0
  to_port           = 65535
  cidr_ipv4         = var.vpc_cidr_block
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["kafkaconnect.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account]
    }
  }
}

resource "aws_iam_role" "connector" {
  name               = "${var.name}-msk-connect"
  description        = "Service execution role of the Debezium connectors of ${var.name}"
  assume_role_policy = data.aws_iam_policy_document.assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "connector" {
  statement {
    sid       = "Cluster"
    actions   = ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster"]
    resources = [var.msk_cluster_arn]
  }

  statement {
    sid = "Topics"
    actions = [
      "kafka-cluster:CreateTopic",
      "kafka-cluster:DescribeTopic",
      "kafka-cluster:DescribeTopicDynamicConfiguration",
      "kafka-cluster:ReadData",
      "kafka-cluster:WriteData",
    ]
    resources = ["${local.topic_arn_prefix}/*"]
  }

  statement {
    sid       = "Groups"
    actions   = ["kafka-cluster:AlterGroup", "kafka-cluster:DescribeGroup"]
    resources = ["${local.group_arn_prefix}/*"]
  }

  statement {
    sid       = "DebeziumCredentials"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.database_secret_arn]
  }

  statement {
    sid       = "DecryptCredentials"
    actions   = ["kms:Decrypt"]
    resources = [var.secrets_kms_key_arn]
    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${local.region}.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy" "connector" {
  name   = "debezium"
  role   = aws_iam_role.connector.id
  policy = data.aws_iam_policy_document.connector.json
}

resource "aws_cloudwatch_log_group" "this" {
  name              = "/grocery/${var.environment}/msk-connect"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_kms_key_arn
  tags              = var.tags
}

# ---------------------------------------------------------------------------------------------------- connectors
resource "aws_mskconnect_connector" "this" {
  for_each = var.connectors_enabled ? local.connectors : {}

  name                       = each.value.name
  description                = "Outbox relay of ${each.key}"
  kafkaconnect_version       = var.kafkaconnect_version
  service_execution_role_arn = aws_iam_role.connector.arn
  connector_configuration    = each.value.configuration

  capacity {
    provisioned_capacity {
      mcu_count    = var.mcu_count
      worker_count = 1 # Debezium MySQL runs one task (tasks.max = 1)
    }
  }

  kafka_cluster {
    apache_kafka_cluster {
      bootstrap_servers = var.bootstrap_servers_iam
      vpc {
        security_groups = [aws_security_group.this.id]
        subnets         = var.subnet_ids
      }
    }
  }

  kafka_cluster_client_authentication {
    authentication_type = "IAM"
  }

  kafka_cluster_encryption_in_transit {
    encryption_type = "TLS"
  }

  plugin {
    custom_plugin {
      arn      = aws_mskconnect_custom_plugin.debezium[0].arn
      revision = aws_mskconnect_custom_plugin.debezium[0].latest_revision
    }
  }

  worker_configuration {
    arn      = aws_mskconnect_worker_configuration.this.arn
    revision = aws_mskconnect_worker_configuration.this.latest_revision
  }

  log_delivery {
    worker_log_delivery {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.this.name
      }
    }
  }

  tags = merge(var.tags, { Service = each.key })

  depends_on = [aws_iam_role_policy.connector]
}

# Phase 13 ConnectorTaskFailed: a task errored, or the connector runs no task.
resource "aws_cloudwatch_metric_alarm" "connector" {
  for_each = var.connectors_enabled ? local.connectors : {}

  alarm_name          = "${each.value.name}-task-failed"
  alarm_description   = "ConnectorTaskFailed: the outbox relay of ${each.key} is not running (critical)."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  treat_missing_data  = "breaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]

  metric_query {
    id          = "failed"
    expression  = "IF(errored > 0 OR running < 1, 1, 0)"
    label       = "Connector not healthy"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = { errored = "ErroredTaskCount", running = "RunningTaskCount" }
    content {
      id = metric_query.key
      metric {
        namespace   = "AWS/KafkaConnect"
        metric_name = metric_query.value
        period      = 60
        stat        = "Maximum"
        dimensions  = { ConnectorName = each.value.name }
      }
    }
  }

  tags = var.tags
}
