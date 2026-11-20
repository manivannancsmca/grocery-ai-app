# Amazon MSK (provisioned, KRaft), TLS everywhere, no unauthenticated access:
#   * SASL/SCRAM-SHA-512 (port 9096) for the services, the Schema Registry and kafka-init: one secret per user
#     (AmazonMSK_grocery/<env>/<service>/kafka), associated here;
#   * IAM (port 9098) for MSK Connect (Debezium).
# Topics are created explicitly by the kafka-init one-off task (auto.create.topics.enable=false).

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
  description         = "${var.name} MSK encryption at rest"
  enable_key_rotation = true
  policy              = data.aws_iam_policy_document.kms.json
  tags                = var.tags
}

resource "aws_kms_alias" "this" {
  name          = "alias/${var.name}-msk"
  target_key_id = aws_kms_key.this.key_id
}

resource "aws_security_group" "this" {
  name        = "${var.name}-msk"
  description = "MSK brokers of ${var.name}"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-msk" })
}

resource "aws_vpc_security_group_ingress_rule" "scram" {
  for_each = var.scram_client_security_group_ids

  security_group_id            = aws_security_group.this.id
  description                  = "SASL/SCRAM over TLS from ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = 9096
  to_port                      = 9096
  referenced_security_group_id = each.value
}

resource "aws_vpc_security_group_ingress_rule" "iam" {
  for_each = var.iam_client_security_group_ids

  security_group_id            = aws_security_group.this.id
  description                  = "IAM auth over TLS from ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = 9098
  to_port                      = 9098
  referenced_security_group_id = each.value
}

resource "aws_cloudwatch_log_group" "brokers" {
  name              = "/grocery/${var.environment}/msk-brokers"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_kms_key_arn
  tags              = var.tags
}

resource "aws_msk_configuration" "this" {
  name           = "${var.name}-kafka"
  kafka_versions = [var.kafka_version]
  description    = "grocery broker defaults"

  server_properties = <<-PROPERTIES
    auto.create.topics.enable=false
    default.replication.factor=${var.replication_factor}
    min.insync.replicas=${var.min_insync_replicas}
    num.partitions=3
    log.retention.hours=168
    unclean.leader.election.enable=false
    allow.everyone.if.no.acl.found=true
  PROPERTIES

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_msk_cluster" "this" {
  cluster_name           = var.name
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.broker_count
  enhanced_monitoring    = var.enhanced_monitoring

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.this.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.broker_volume_size
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  client_authentication {
    unauthenticated = false
    sasl {
      scram = true
      iam   = true
    }
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.this.arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.brokers.name
      }
    }
  }

  tags = var.tags
}

resource "aws_msk_single_scram_secret_association" "this" {
  for_each = var.scram_secret_arns

  cluster_arn = aws_msk_cluster.this.arn
  secret_arn  = each.value
}
