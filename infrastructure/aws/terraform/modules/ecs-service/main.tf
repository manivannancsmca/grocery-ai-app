# One deployable unit on ECS Fargate:
#   task definition  = app container "app" + ADOT collector sidecar "adot-collector" (AMP remote write, X-Ray traces);
#                      logs of both through the awslogs driver into /grocery/<env>/<service>
#   service          = native ECS blue/green (deployment_controller ECS, strategy BLUE_GREEN or CANARY): two target
#                      groups + production/test listener rules when load balanced, Service Connect traffic shifting
#                      otherwise; bake time; rollback on the deployment circuit breaker and on CloudWatch alarms
#   autoscaling      = target tracking on CPU, plus requests per task for load-balanced services
#   IAM              = execution role (pull this repository, read exactly the secrets/parameters it injects, write its
#                      log group) and task role (only what the sidecar needs: AMP remote write to one workspace, X-Ray)
#
# Deployments are owned by CI: Terraform registers revisions (with the image tag CI last recorded in SSM) but ignores
# the service's task_definition, so `aws ecs update-service` from Jenkins is never undone by an apply.

data "aws_region" "current" {}
data "aws_partition" "current" {}

locals {
  region         = data.aws_region.current.region
  family         = var.service_name
  log_group_name = "/grocery/${var.environment}/${var.name}"
  adot_enabled   = var.adot.enabled
  lb_enabled     = var.load_balancer != null

  # Memory: the ADOT sidecar and the Service Connect agent (added by ECS, not in the task definition) share the task.
  adot_memory = local.adot_enabled ? var.adot.memory : 0
  app_memory  = var.memory - local.adot_memory - var.service_connect_memory_reserve

  environment = [for k in sort(keys(var.environment_variables)) : { name = k, value = var.environment_variables[k] }]
  secrets     = [for k in sort(keys(var.secrets)) : { name = k, valueFrom = var.secrets[k] }]

  app_container = merge(
    {
      name                   = "app"
      image                  = var.image
      essential              = true
      memory                 = local.app_memory
      readonlyRootFilesystem = var.read_only_root_filesystem
      stopTimeout            = 30
      portMappings = [{
        name          = "http"
        containerPort = var.container_port
        protocol      = "tcp"
        appProtocol   = "http"
      }]
      environment = local.environment
      secrets     = local.secrets
      healthCheck = {
        command     = ["CMD-SHELL", var.container_health_check]
        interval    = 15
        timeout     = 5
        retries     = 4
        startPeriod = var.health_check_start_period
      }
      linuxParameters = { initProcessEnabled = true }
      mountPoints     = var.read_only_root_filesystem ? [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }] : []
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = local.log_group_name
          awslogs-region        = local.region
          awslogs-stream-prefix = "app"
          mode                  = "non-blocking"
          max-buffer-size       = "25m"
        }
      }
      dependsOn = local.adot_enabled ? [{ containerName = "adot-collector", condition = "START" }] : []
    },
    var.command == null ? {} : { command = var.command },
  )

  adot_container = {
    name                   = "adot-collector"
    image                  = var.adot.image
    essential              = true
    memory                 = var.adot.memory
    readonlyRootFilesystem = true
    environment = [
      { name = "AMP_REMOTE_WRITE_ENDPOINT", value = var.adot.amp_remote_write_endpoint },
      { name = "APP_PORT", value = tostring(coalesce(var.adot.metrics_port, var.container_port)) },
      { name = "GROCERY_ENV", value = var.environment },
      { name = "METRICS_PATH", value = coalesce(var.adot.metrics_path, "/actuator/prometheus") },
      { name = "SERVICE_NAME", value = var.name },
    ]
    secrets = [{ name = "AOT_CONFIG_CONTENT", valueFrom = var.adot.config_parameter_arn }]
    healthCheck = {
      command     = ["/healthcheck"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 10
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = local.log_group_name
        awslogs-region        = local.region
        awslogs-stream-prefix = "adot"
        mode                  = "non-blocking"
      }
    }
  }

  container_definitions = [
    for c in [local.app_container, local.adot_container] : c if c.name == "app" || local.adot_enabled
  ]

  ssm_parameter_arns = concat(var.ssm_parameter_arns, local.adot_enabled ? [var.adot.config_parameter_arn] : [])
}

# ---------------------------------------------------------------------------------------------------------- logs
resource "aws_cloudwatch_log_group" "this" {
  name              = local.log_group_name
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_kms_key_arn
  tags              = var.tags
}

# ---------------------------------------------------------------------------------------------------------- IAM
data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:ecs:${local.region}:${var.account_id}:*"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.service_name}-exec"
  description        = "ECS execution role of ${var.service_name}: image pull, logs, injected secrets"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "execution" {
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # GetAuthorizationToken has no resource-level permissions
  }

  dynamic "statement" {
    for_each = var.ecr_repository_arn == null ? [] : [1]
    content {
      sid       = "PullOwnImage"
      actions   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
      resources = [var.ecr_repository_arn]
    }
  }

  dynamic "statement" {
    for_each = var.ecr_kms_key_arn == null ? [] : [1]
    content {
      sid       = "DecryptImageLayers"
      actions   = ["kms:Decrypt"]
      resources = [var.ecr_kms_key_arn]
    }
  }

  statement {
    sid       = "WriteOwnLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.this.arn}:*"]
  }

  dynamic "statement" {
    for_each = length(var.secret_arns) > 0 ? [1] : []
    content {
      sid       = "ReadInjectedSecrets"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = var.secret_arns
    }
  }

  dynamic "statement" {
    for_each = length(local.ssm_parameter_arns) > 0 ? [1] : []
    content {
      sid       = "ReadInjectedParameters"
      actions   = ["ssm:GetParameters"]
      resources = local.ssm_parameter_arns
    }
  }

  dynamic "statement" {
    for_each = length(var.secret_kms_key_arns) > 0 ? [1] : []
    content {
      sid       = "DecryptInjectedValues"
      actions   = ["kms:Decrypt"]
      resources = var.secret_kms_key_arns
      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["secretsmanager.${local.region}.amazonaws.com", "ssm.${local.region}.amazonaws.com"]
      }
    }
  }
}

resource "aws_iam_role_policy" "execution" {
  name   = "execution"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

resource "aws_iam_role" "task" {
  name               = "${var.service_name}-task"
  description        = "Task role of ${var.service_name} (the application and its ADOT sidecar)"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "task" {
  # The Spring services call no AWS API themselves (secrets arrive as env vars); only the sidecar does. This guardrail
  # also keeps the document non-empty for units without a sidecar (schema-registry).
  statement {
    sid       = "NoIamFromTasks"
    effect    = "Deny"
    actions   = ["iam:*", "organizations:*", "sts:AssumeRole"]
    resources = ["*"]
  }

  dynamic "statement" {
    for_each = local.adot_enabled ? [1] : []
    content {
      sid       = "RemoteWriteMetrics"
      actions   = ["aps:RemoteWrite"]
      resources = [var.adot.amp_workspace_arn]
    }
  }

  dynamic "statement" {
    for_each = local.adot_enabled ? [1] : []
    content {
      sid = "SendTraces"
      actions = [
        "xray:PutTraceSegments",
        "xray:PutTelemetryRecords",
        "xray:GetSamplingRules",
        "xray:GetSamplingTargets",
        "xray:GetSamplingStatisticSummaries",
      ]
      resources = ["*"] # X-Ray has no resource-level permissions for these actions
    }
  }

  dynamic "statement" {
    for_each = var.enable_execute_command ? [1] : []
    content {
      sid = "EcsExec"
      actions = [
        "ssmmessages:CreateControlChannel",
        "ssmmessages:CreateDataChannel",
        "ssmmessages:OpenControlChannel",
        "ssmmessages:OpenDataChannel",
      ]
      resources = ["*"] # Session Manager channels have no resource-level permissions
    }
  }

  dynamic "statement" {
    for_each = var.enable_execute_command ? [1] : []
    content {
      sid       = "EcsExecLogs"
      actions   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogGroups", "logs:DescribeLogStreams"]
      resources = ["*"]
    }
  }

  dynamic "statement" {
    for_each = var.enable_execute_command && var.exec_kms_key_arn != null ? [1] : []
    content {
      sid       = "EcsExecDecrypt"
      actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
      resources = [var.exec_kms_key_arn]
    }
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "task"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

# ------------------------------------------------------------------------------------------------ task definition
resource "aws_ecs_task_definition" "this" {
  family                   = local.family
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.cpu)
  memory                   = tostring(var.memory)
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  container_definitions    = jsonencode(local.container_definitions)
  skip_destroy             = true # keep old revisions: rollback.sh redeploys them

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  dynamic "volume" {
    for_each = var.read_only_root_filesystem ? [1] : []
    content {
      name = "tmp"
    }
  }

  tags = var.tags
}

# -------------------------------------------------------------------------------------------- rollback alarms
locals {
  task_failures_alarm = "${var.service_name}-task-failures"
  errors_alarm        = "${var.service_name}-5xx-rate"
  unhealthy_alarm     = "${var.service_name}-unhealthy-targets"
  rollback_alarms     = concat([local.task_failures_alarm, local.errors_alarm], local.lb_enabled ? [local.unhealthy_alarm] : [])
}

resource "aws_cloudwatch_metric_alarm" "task_failures" {
  alarm_name          = local.task_failures_alarm
  alarm_description   = "Tasks of ${var.service_name} stopped because they failed to start or the app exited (blue/green rollback alarm)."
  namespace           = var.task_failure_metric.namespace
  metric_name         = var.task_failure_metric.name
  dimensions          = { Group = "service:${var.service_name}" }
  statistic           = "Sum"
  period              = 60
  evaluation_periods  = 3
  datapoints_to_alarm = 1
  threshold           = var.alarm_thresholds.task_failures
  comparison_operator = "GreaterThanOrEqualToThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]
  tags                = var.tags
}

# 5xx rate. Load balanced: ALB target 5xx over both target groups (the live one alternates between deployments).
# Internal: the Service Connect proxy metrics of this service.
resource "aws_cloudwatch_metric_alarm" "errors_lb" {
  count = local.lb_enabled ? 1 : 0

  alarm_name          = local.errors_alarm
  alarm_description   = "${var.service_name}: more than ${var.alarm_thresholds.error_rate_percent}% 5xx (blue/green rollback alarm)."
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.alarm_thresholds.error_rate_percent
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]

  metric_query {
    id          = "rate"
    expression  = "IF((req_blue + req_green) >= ${var.alarm_thresholds.min_requests}, 100 * (err_blue + err_green) / (req_blue + req_green), 0)"
    label       = "5xx percent"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = {
      err_blue  = { metric = "HTTPCode_Target_5XX_Count", tg = var.load_balancer.blue_target_group_arn_suffix }
      err_green = { metric = "HTTPCode_Target_5XX_Count", tg = var.load_balancer.green_target_group_arn_suffix }
      req_blue  = { metric = "RequestCount", tg = var.load_balancer.blue_target_group_arn_suffix }
      req_green = { metric = "RequestCount", tg = var.load_balancer.green_target_group_arn_suffix }
    }
    content {
      id = metric_query.key
      metric {
        namespace   = "AWS/ApplicationELB"
        metric_name = metric_query.value.metric
        period      = 60
        stat        = "Sum"
        dimensions = {
          LoadBalancer = var.load_balancer.alb_arn_suffix
          TargetGroup  = metric_query.value.tg
        }
      }
    }
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "errors_service_connect" {
  count = local.lb_enabled ? 0 : 1

  alarm_name          = local.errors_alarm
  alarm_description   = "${var.service_name}: more than ${var.alarm_thresholds.error_rate_percent}% 5xx through Service Connect (blue/green rollback alarm)."
  comparison_operator = "GreaterThanThreshold"
  threshold           = var.alarm_thresholds.error_rate_percent
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]

  metric_query {
    id          = "rate"
    expression  = "IF(req >= ${var.alarm_thresholds.min_requests}, 100 * err / req, 0)"
    label       = "5xx percent"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = { err = "HTTPCode_Target_5XX_Count", req = "RequestCount" }
    content {
      id = metric_query.key
      metric {
        namespace   = "AWS/ECS"
        metric_name = metric_query.value
        period      = 60
        stat        = "Sum"
        dimensions = {
          ClusterName   = var.cluster_name
          ServiceName   = var.service_name
          DiscoveryName = var.name
        }
      }
    }
  }

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "unhealthy_targets" {
  count = local.lb_enabled ? 1 : 0

  alarm_name          = local.unhealthy_alarm
  alarm_description   = "${var.service_name}: unhealthy ALB targets (blue/green rollback alarm)."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = 2
  datapoints_to_alarm = 2
  treat_missing_data  = "notBreaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]

  metric_query {
    id          = "unhealthy"
    expression  = "MAX([blue, green])"
    label       = "Unhealthy targets (both target groups)"
    return_data = true
  }

  dynamic "metric_query" {
    for_each = {
      blue  = var.load_balancer.blue_target_group_arn_suffix
      green = var.load_balancer.green_target_group_arn_suffix
    }
    content {
      id = metric_query.key
      metric {
        namespace   = "AWS/ApplicationELB"
        metric_name = "UnHealthyHostCount"
        period      = 60
        stat        = "Maximum"
        dimensions = {
          LoadBalancer = var.load_balancer.alb_arn_suffix
          TargetGroup  = metric_query.value
        }
      }
    }
  }

  tags = var.tags
}

# Not a rollback alarm: the service has no running task at all (Phase 13 ServiceDown, CloudWatch side).
resource "aws_cloudwatch_metric_alarm" "down" {
  alarm_name          = "${var.service_name}-down"
  alarm_description   = "${var.service_name} has no running task (ServiceDown)."
  namespace           = "ECS/ContainerInsights"
  metric_name         = "RunningTaskCount"
  dimensions          = { ClusterName = var.cluster_name, ServiceName = var.service_name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 2
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [var.alarm_topic_arn]
  ok_actions          = [var.alarm_topic_arn]
  tags                = var.tags
}

# -------------------------------------------------------------------------------------------------------- service
resource "aws_ecs_service" "this" {
  name                              = var.service_name
  cluster                           = var.cluster_arn
  task_definition                   = aws_ecs_task_definition.this.arn
  desired_count                     = var.desired_count
  platform_version                  = "LATEST"
  enable_execute_command            = var.enable_execute_command
  enable_ecs_managed_tags           = true
  propagate_tags                    = "SERVICE"
  availability_zone_rebalancing     = "ENABLED"
  health_check_grace_period_seconds = local.lb_enabled ? var.health_check_start_period : null
  wait_for_steady_state             = false

  dynamic "capacity_provider_strategy" {
    for_each = var.capacity_provider_strategy
    content {
      capacity_provider = capacity_provider_strategy.value.capacity_provider
      weight            = capacity_provider_strategy.value.weight
      base              = capacity_provider_strategy.value.base
    }
  }

  network_configuration {
    subnets          = var.subnet_ids
    security_groups  = var.security_group_ids
    assign_public_ip = false
  }

  deployment_controller {
    type = "ECS"
  }

  deployment_configuration {
    strategy             = var.deployment.canary_percent == null ? "BLUE_GREEN" : "CANARY"
    bake_time_in_minutes = tostring(var.deployment.bake_time_minutes)

    dynamic "canary_configuration" {
      for_each = var.deployment.canary_percent == null ? [] : [1]
      content {
        canary_percent              = var.deployment.canary_percent
        canary_bake_time_in_minutes = tostring(var.deployment.canary_bake_time_minutes)
      }
    }
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  alarms {
    alarm_names = local.rollback_alarms
    enable      = true
    rollback    = true
  }

  dynamic "load_balancer" {
    for_each = local.lb_enabled ? [var.load_balancer] : []
    content {
      target_group_arn = load_balancer.value.blue_target_group_arn
      container_name   = "app"
      container_port   = var.container_port

      advanced_configuration {
        alternate_target_group_arn = load_balancer.value.green_target_group_arn
        production_listener_rule   = load_balancer.value.production_listener_rule_arn
        test_listener_rule         = load_balancer.value.test_listener_rule_arn
        role_arn                   = load_balancer.value.infrastructure_role_arn
      }
    }
  }

  service_connect_configuration {
    enabled   = true
    namespace = var.service_connect_namespace_arn

    log_configuration {
      log_driver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.this.name
        awslogs-region        = local.region
        awslogs-stream-prefix = "service-connect"
      }
    }

    service {
      port_name      = "http"
      discovery_name = var.name

      client_alias {
        port     = var.container_port
        dns_name = var.name

        dynamic "test_traffic_rules" {
          for_each = var.deployment.test_traffic_header == null ? [] : [1]
          content {
            header {
              name = var.deployment.test_traffic_header
              value {
                exact = "true"
              }
            }
          }
        }
      }

      dynamic "timeout" {
        for_each = var.service_connect_timeouts == null ? [] : [var.service_connect_timeouts]
        content {
          idle_timeout_seconds        = timeout.value.idle_seconds
          per_request_timeout_seconds = timeout.value.per_request_seconds
        }
      }
    }
  }

  tags = merge(var.tags, { Service = var.name })

  lifecycle {
    # desired_count: Application Auto Scaling owns it. task_definition: CI deploys new revisions (update-service).
    # load_balancer: after a blue/green deployment ECS swaps the primary and alternate target groups.
    ignore_changes = [desired_count, task_definition, load_balancer]
  }

  # The rollback alarms must exist before the service references them.
  depends_on = [
    aws_iam_role_policy.execution,
    aws_iam_role_policy.task,
    aws_cloudwatch_metric_alarm.task_failures,
    aws_cloudwatch_metric_alarm.errors_lb,
    aws_cloudwatch_metric_alarm.errors_service_connect,
    aws_cloudwatch_metric_alarm.unhealthy_targets,
  ]
}

# ---------------------------------------------------------------------------------------------------- autoscaling
resource "aws_appautoscaling_target" "this" {
  service_namespace  = "ecs"
  scalable_dimension = "ecs:service:DesiredCount"
  resource_id        = "service/${var.cluster_name}/${aws_ecs_service.this.name}"
  min_capacity       = var.min_count
  max_capacity       = var.max_count
  tags               = var.tags
}

resource "aws_appautoscaling_policy" "cpu" {
  name               = "${var.service_name}-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension
  resource_id        = aws_appautoscaling_target.this.resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.autoscaling.cpu_target_percent
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# Requests per running task, over both target groups (ALBRequestCountPerTarget would follow only one of them).
resource "aws_appautoscaling_policy" "requests" {
  count = local.lb_enabled ? 1 : 0

  name               = "${var.service_name}-requests"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.this.service_namespace
  scalable_dimension = aws_appautoscaling_target.this.scalable_dimension
  resource_id        = aws_appautoscaling_target.this.resource_id

  target_tracking_scaling_policy_configuration {
    target_value       = var.autoscaling.requests_per_task_per_minute
    scale_in_cooldown  = 300
    scale_out_cooldown = 60

    customized_metric_specification {
      metrics {
        id          = "per_task"
        expression  = "(req_blue + req_green) / MAX([tasks, 1])"
        label       = "Requests per task per minute"
        return_data = true
      }

      dynamic "metrics" {
        for_each = {
          req_blue  = var.load_balancer.blue_target_group_arn_suffix
          req_green = var.load_balancer.green_target_group_arn_suffix
        }
        content {
          id          = metrics.key
          return_data = false
          metric_stat {
            stat = "Sum"
            metric {
              namespace   = "AWS/ApplicationELB"
              metric_name = "RequestCount"
              dimensions {
                name  = "LoadBalancer"
                value = var.load_balancer.alb_arn_suffix
              }
              dimensions {
                name  = "TargetGroup"
                value = metrics.value
              }
            }
          }
        }
      }

      metrics {
        id          = "tasks"
        return_data = false
        metric_stat {
          stat = "Average"
          metric {
            namespace   = "ECS/ContainerInsights"
            metric_name = "RunningTaskCount"
            dimensions {
              name  = "ClusterName"
              value = var.cluster_name
            }
            dimensions {
              name  = "ServiceName"
              value = var.service_name
            }
          }
        }
      }
    }
  }
}
