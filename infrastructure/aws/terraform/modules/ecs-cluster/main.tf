# ECS cluster grocery-<env>-cluster, the Service Connect namespace every service registers in (order-service:8110 ...),
# the security group shared by the service tasks, and the "task failures" metric used by the rollback alarms.

resource "aws_cloudwatch_log_group" "exec" {
  name              = "/grocery/${var.environment}/ecs-exec"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_kms_key_arn
  tags              = var.tags
}

resource "aws_ecs_cluster" "this" {
  name = "${var.name}-cluster"

  setting {
    name  = "containerInsights"
    value = var.container_insights
  }

  configuration {
    execute_command_configuration {
      kms_key_id = var.exec_kms_key_arn
      logging    = "OVERRIDE"
      log_configuration {
        cloud_watch_encryption_enabled = true
        cloud_watch_log_group_name     = aws_cloudwatch_log_group.exec.name
      }
    }
  }

  service_connect_defaults {
    namespace = aws_service_discovery_http_namespace.this.arn
  }

  tags = var.tags
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    base              = 0
    weight            = var.fargate_spot_weight == 0 ? 1 : 0
  }

  dynamic "default_capacity_provider_strategy" {
    for_each = var.fargate_spot_weight > 0 ? [1] : []
    content {
      capacity_provider = "FARGATE_SPOT"
      weight            = var.fargate_spot_weight
    }
  }
}

resource "aws_service_discovery_http_namespace" "this" {
  name        = var.name
  description = "Service Connect namespace of ${var.name}"
  tags        = var.tags
}

# ------------------------------------------------------------------------------------- shared task security group
resource "aws_security_group" "tasks" {
  name        = "${var.name}-ecs-tasks"
  description = "ECS service and one-off tasks of ${var.name}"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-ecs-tasks" })
}

resource "aws_vpc_security_group_ingress_rule" "tasks_service_connect" {
  security_group_id            = aws_security_group.tasks.id
  description                  = "Service Connect traffic between services (application ports 8080-8199)"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8199
  referenced_security_group_id = aws_security_group.tasks.id
}

resource "aws_vpc_security_group_egress_rule" "tasks_vpc" {
  security_group_id = aws_security_group.tasks.id
  description       = "Everything inside the VPC: other tasks, RDS, MSK, Redis, VPC endpoints, Elastic PrivateLink"
  ip_protocol       = "tcp"
  from_port         = 0
  to_port           = 65535
  cidr_ipv4         = var.vpc_cidr_block
}

resource "aws_vpc_security_group_egress_rule" "tasks_internet" {
  for_each = toset([for p in var.internet_egress_ports : tostring(p)])

  security_group_id = aws_security_group.tasks.id
  description       = "Internet through NAT on ${each.value} (Razorpay API, SMTP, public images, external model endpoint)"
  ip_protocol       = "tcp"
  from_port         = tonumber(each.value)
  to_port           = tonumber(each.value)
  cidr_ipv4         = "0.0.0.0/0"
}

# ---------------------------------------------------------------------------- task failures -> metric for alarms
# ECS publishes no "task failed" metric. EventBridge sends every STOPPED task of this cluster that failed to start or
# whose essential container exited to a log group; a metric filter counts them per group ("service:<name>"). The
# ecs-service module alarms on it, and the alarm is one of the blue/green rollback alarms.
resource "aws_cloudwatch_log_group" "task_failures" {
  name              = "/aws/events/${var.name}-task-failures"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_kms_key_arn
  tags              = var.tags
}

resource "aws_cloudwatch_event_rule" "task_failures" {
  name        = "${var.name}-task-failures"
  description = "Stopped ECS tasks of ${aws_ecs_cluster.this.name} that failed"
  event_pattern = jsonencode({
    source        = ["aws.ecs"]
    "detail-type" = ["ECS Task State Change"]
    detail = {
      clusterArn = [aws_ecs_cluster.this.arn]
      lastStatus = ["STOPPED"]
      stopCode   = ["TaskFailedToStart", "EssentialContainerExited"]
    }
  })
  tags = var.tags
}

resource "aws_cloudwatch_event_target" "task_failures" {
  rule = aws_cloudwatch_event_rule.task_failures.name
  arn  = aws_cloudwatch_log_group.task_failures.arn
}

data "aws_iam_policy_document" "events_to_logs" {
  statement {
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.task_failures.arn}:*"]
    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com", "delivery.logs.amazonaws.com"]
    }
  }
}

resource "aws_cloudwatch_log_resource_policy" "events_to_logs" {
  policy_name     = "${var.name}-task-failures"
  policy_document = data.aws_iam_policy_document.events_to_logs.json
}

resource "aws_cloudwatch_log_metric_filter" "task_failures" {
  name           = "${var.name}-task-failures"
  log_group_name = aws_cloudwatch_log_group.task_failures.name
  pattern        = "{ $.detail.lastStatus = \"STOPPED\" }"

  metric_transformation {
    name       = var.task_failure_metric_name
    namespace  = var.task_failure_metric_namespace
    value      = "1"
    unit       = "Count"
    dimensions = { Group = "$.detail.group" }
  }
}
