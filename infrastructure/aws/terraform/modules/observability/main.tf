# AWS-managed observability (Phase 13 signals in AWS):
#   metrics  ADOT sidecars -> Amazon Managed Prometheus (this workspace); Phase 13 alert rules as AMP rules
#   traces   ADOT sidecars -> X-Ray
#   logs     awslogs driver -> CloudWatch Logs (log groups live with each service)
#   alerts   AMP alertmanager + CloudWatch alarms -> SNS topic grocery-<env>-alerts (email)
#   Grafana  Amazon Managed Grafana workspace with AMP, CloudWatch and X-Ray data sources (optional: needs IAM Identity
#            Center); import the Phase 13 dashboards (infrastructure/local/observability/grafana/dashboards) into it.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  account   = data.aws_caller_identity.current.account_id
  partition = data.aws_partition.current.partition
  region    = data.aws_region.current.region
}

# -------------------------------------------------------------------------------------------------- SNS (alerts)
data "aws_iam_policy_document" "kms" {
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
    sid       = "AlarmPublishers"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey*"]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com", "aps.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account]
    }
  }
}

resource "aws_kms_key" "alerts" {
  description         = "${var.name} alert topic encryption"
  enable_key_rotation = true
  policy              = data.aws_iam_policy_document.kms.json
  tags                = var.tags
}

resource "aws_kms_alias" "alerts" {
  name          = "alias/${var.name}-alerts"
  target_key_id = aws_kms_key.alerts.key_id
}

resource "aws_sns_topic" "alerts" {
  name              = "${var.name}-alerts"
  display_name      = "grocery ${var.environment} alerts"
  kms_master_key_id = aws_kms_key.alerts.arn
  tags              = var.tags
}

data "aws_iam_policy_document" "alerts_topic" {
  statement {
    sid       = "AccountOwner"
    actions   = ["sns:GetTopicAttributes", "sns:SetTopicAttributes", "sns:Subscribe", "sns:ListSubscriptionsByTopic", "sns:Publish"]
    resources = [aws_sns_topic.alerts.arn]
    principals {
      type        = "AWS"
      identifiers = ["arn:${local.partition}:iam::${local.account}:root"]
    }
  }

  statement {
    sid       = "AlarmPublishers"
    actions   = ["sns:Publish", "sns:GetTopicAttributes"]
    resources = [aws_sns_topic.alerts.arn]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com", "aps.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account]
    }
  }
}

resource "aws_sns_topic_policy" "alerts" {
  arn    = aws_sns_topic.alerts.arn
  policy = data.aws_iam_policy_document.alerts_topic.json
}

resource "aws_sns_topic_subscription" "email" {
  for_each = toset(var.alert_emails)

  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = each.value
}

# ---------------------------------------------------------------------------------------------------------- AMP
resource "aws_cloudwatch_log_group" "amp" {
  name              = "/grocery/${var.environment}/amp"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_kms_key_arn
  tags              = var.tags
}

resource "aws_prometheus_workspace" "this" {
  alias = var.name

  logging_configuration {
    log_group_arn = "${aws_cloudwatch_log_group.amp.arn}:*"
  }

  tags = var.tags
}

resource "aws_prometheus_rule_group_namespace" "alerts" {
  name         = "grocery-alerts"
  workspace_id = aws_prometheus_workspace.this.id
  data         = var.alert_rules_file == null ? file("${path.module}/grocery-alerts.yml") : file(var.alert_rules_file)
}

resource "aws_prometheus_alert_manager_definition" "this" {
  workspace_id = aws_prometheus_workspace.this.id
  definition   = <<-YAML
    alertmanager_config: |
      route:
        receiver: sns
        group_by: [alertname, service]
        group_wait: 30s
        group_interval: 5m
        repeat_interval: 4h
        routes:
          - receiver: sns
            matchers: ['severity="critical"']
            repeat_interval: 1h
      receivers:
        - name: sns
          sns_configs:
            - topic_arn: ${aws_sns_topic.alerts.arn}
              sigv4:
                region: ${local.region}
              subject: 'grocery ${var.environment}: {{ .CommonLabels.alertname }}'
              attributes:
                severity: '{{ .CommonLabels.severity }}'
  YAML
}

# ------------------------------------------------------------------------------------- ADOT collector config
resource "aws_ssm_parameter" "adot_config" {
  name        = "/grocery/${var.environment}/adot/collector-config"
  description = "ADOT collector configuration of every service sidecar (AOT_CONFIG_CONTENT)"
  type        = "String"
  tier        = "Intelligent-Tiering"
  value       = file("${path.module}/adot-collector.yaml")
  tags        = var.tags
}

# ------------------------------------------------------------------------------------------------- Grafana (AMG)
data "aws_iam_policy_document" "grafana_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["grafana.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account]
    }
  }
}

resource "aws_iam_role" "grafana" {
  count = var.grafana_enabled ? 1 : 0

  name               = "${var.name}-grafana"
  description        = "Amazon Managed Grafana: read AMP, CloudWatch, X-Ray; notify SNS"
  assume_role_policy = data.aws_iam_policy_document.grafana_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "grafana" {
  statement {
    sid       = "QueryPrometheus"
    actions   = ["aps:QueryMetrics", "aps:GetLabels", "aps:GetSeries", "aps:GetMetricMetadata", "aps:DescribeWorkspace"]
    resources = [aws_prometheus_workspace.this.arn]
  }

  statement {
    sid       = "ListPrometheus"
    actions   = ["aps:ListWorkspaces"]
    resources = ["*"] # list action, no resource-level permissions
  }

  statement {
    sid = "ReadCloudWatchAndXRay"
    actions = [
      "cloudwatch:DescribeAlarms",
      "cloudwatch:DescribeAlarmsForMetric",
      "cloudwatch:DescribeAlarmHistory",
      "cloudwatch:GetMetricData",
      "cloudwatch:ListMetrics",
      "logs:DescribeLogGroups",
      "logs:GetLogGroupFields",
      "logs:StartQuery",
      "logs:StopQuery",
      "logs:GetQueryResults",
      "logs:GetLogEvents",
      "tag:GetResources",
      "xray:BatchGetTraces",
      "xray:GetTraceSummaries",
      "xray:GetTraceGraph",
      "xray:GetGroups",
      "xray:GetServiceGraph",
      "xray:GetTimeSeriesServiceStatistics",
      "xray:GetInsightSummaries",
    ]
    resources = ["*"] # read-only APIs without resource-level permissions
  }

  statement {
    sid       = "NotifyAlerts"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alerts.arn]
  }
}

resource "aws_iam_role_policy" "grafana" {
  count = var.grafana_enabled ? 1 : 0

  name   = "grafana-data-sources"
  role   = aws_iam_role.grafana[0].id
  policy = data.aws_iam_policy_document.grafana.json
}

resource "aws_grafana_workspace" "this" {
  count = var.grafana_enabled ? 1 : 0

  name                      = var.name
  description               = "grocery ${var.environment}: Phase 13 dashboards on AMP, CloudWatch and X-Ray"
  account_access_type       = "CURRENT_ACCOUNT"
  authentication_providers  = ["AWS_SSO"]
  permission_type           = "CUSTOMER_MANAGED"
  role_arn                  = aws_iam_role.grafana[0].arn
  data_sources              = ["PROMETHEUS", "CLOUDWATCH", "XRAY"]
  notification_destinations = ["SNS"]
  tags                      = var.tags
}

# -------------------------------------------------------------------------- CloudWatch alarms (infrastructure)
resource "aws_cloudwatch_metric_alarm" "database_down" {
  alarm_name          = "${var.name}-mysql-down"
  alarm_description   = "DatabaseDown: RDS ${var.rds_identifier} reports no metrics (critical)."
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = var.rds_identifier }
  statistic           = "SampleCount"
  period              = 60
  evaluation_periods  = 3
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
  tags                = var.tags
}

resource "aws_cloudwatch_metric_alarm" "database_cpu" {
  alarm_name          = "${var.name}-mysql-cpu-high"
  alarm_description   = "RDS ${var.rds_identifier} CPU above 80 % for 15 minutes (warning)."
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  dimensions          = { DBInstanceIdentifier = var.rds_identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "missing"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
  tags                = var.tags
}

resource "aws_cloudwatch_metric_alarm" "database_storage" {
  alarm_name          = "${var.name}-mysql-storage-low"
  alarm_description   = "RDS ${var.rds_identifier} has less than 2 GiB free (binlogs are kept 72 h) (critical)."
  namespace           = "AWS/RDS"
  metric_name         = "FreeStorageSpace"
  dimensions          = { DBInstanceIdentifier = var.rds_identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 2147483648
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "missing"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
  tags                = var.tags
}

resource "aws_cloudwatch_metric_alarm" "redis_down" {
  alarm_name          = "${var.name}-redis-down"
  alarm_description   = "RedisDown: no ElastiCache node of ${var.redis_replication_group_id} reports metrics (critical)."
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  evaluation_periods  = 3
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  metric_query {
    id          = "nodes"
    expression  = "SUM([${join(", ", [for i in range(var.redis_node_count) : "n${i}"])}])"
    label       = "Nodes reporting"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = range(var.redis_node_count)
    content {
      id = "n${metric_query.value}"
      metric {
        namespace   = "AWS/ElastiCache"
        metric_name = "EngineCPUUtilization"
        period      = 60
        stat        = "SampleCount"
        dimensions  = { CacheClusterId = format("%s-%03d", var.redis_replication_group_id, metric_query.value + 1) }
      }
    }
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "redis_memory" {
  alarm_name          = "${var.name}-redis-memory-high"
  alarm_description   = "ElastiCache ${var.redis_replication_group_id} memory above 90 % (warning)."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 90
  evaluation_periods  = 3
  treat_missing_data  = "missing"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  metric_query {
    id          = "worst"
    expression  = "MAX([${join(", ", [for i in range(var.redis_node_count) : "m${i}"])}])"
    label       = "Memory used (worst node)"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = range(var.redis_node_count)
    content {
      id = "m${metric_query.value}"
      metric {
        namespace   = "AWS/ElastiCache"
        metric_name = "DatabaseMemoryUsagePercentage"
        period      = 300
        stat        = "Maximum"
        dimensions  = { CacheClusterId = format("%s-%03d", var.redis_replication_group_id, metric_query.value + 1) }
      }
    }
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "kafka_lag" {
  alarm_name          = "${var.name}-kafka-consumer-lag-high"
  alarm_description   = "KafkaConsumerLagHigh: a consumer group on ${var.msk_cluster_name} lags more than ${var.kafka_lag_threshold} messages for 5 minutes (warning)."
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.kafka_lag_threshold
  evaluation_periods  = 5
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  metric_query {
    id          = "q"
    expression  = "SELECT MAX(SumOffsetLag) FROM SCHEMA(\"AWS/Kafka\", \"Cluster Name\", \"Consumer Group\", Topic) WHERE \"Cluster Name\" = '${var.msk_cluster_name}'"
    period      = 60
    label       = "Max offset lag over consumer groups"
    return_data = true
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "kafka_disk" {
  alarm_name          = "${var.name}-kafka-disk-high"
  alarm_description   = "A broker of ${var.msk_cluster_name} has used more than 80 % of its data volume (warning)."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 80
  evaluation_periods  = 2
  treat_missing_data  = "missing"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  metric_query {
    id          = "q"
    expression  = "SELECT MAX(KafkaDataLogsDiskUsed) FROM SCHEMA(\"AWS/Kafka\", \"Cluster Name\", \"Broker ID\") WHERE \"Cluster Name\" = '${var.msk_cluster_name}'"
    period      = 300
    label       = "Data volume used (worst broker)"
    return_data = true
  }

  tags = var.tags
}
