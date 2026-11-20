# A task definition that is not a service: run on demand with `aws ecs run-task` (db-init, kafka-init, schema-init).
# Same IAM pattern as ecs-service: an execution role limited to its image, its log group and the secrets it injects;
# a task role with no permissions beyond a guardrail.

data "aws_region" "current" {}

locals {
  region         = data.aws_region.current.region
  log_group_name = "/grocery/${var.environment}/${var.name}"
  environment    = [for k in sort(keys(var.environment_variables)) : { name = k, value = var.environment_variables[k] }]
  secrets        = [for k in sort(keys(var.secrets)) : { name = k, valueFrom = var.secrets[k] }]

  container = merge(
    {
      name                   = "app"
      image                  = var.image
      essential              = true
      readonlyRootFilesystem = false
      environment            = local.environment
      secrets                = local.secrets
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = local.log_group_name
          awslogs-region        = local.region
          awslogs-stream-prefix = "run"
        }
      }
    },
    var.entry_point == null ? {} : { entryPoint = var.entry_point },
    var.command == null ? {} : { command = var.command },
  )
}

resource "aws_cloudwatch_log_group" "this" {
  name              = local.log_group_name
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_kms_key_arn
  tags              = var.tags
}

data "aws_iam_policy_document" "assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [var.account_id]
    }
  }
}

resource "aws_iam_role" "execution" {
  name               = "${var.family}-exec"
  description        = "Execution role of the one-off task ${var.family}"
  assume_role_policy = data.aws_iam_policy_document.assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "execution" {
  statement {
    sid       = "WriteOwnLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.this.arn}:*"]
  }

  dynamic "statement" {
    for_each = var.ecr_repository_arn == null ? [] : [1]
    content {
      sid       = "EcrAuth"
      actions   = ["ecr:GetAuthorizationToken"]
      resources = ["*"] # no resource-level permissions
    }
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

  dynamic "statement" {
    for_each = length(var.secret_arns) > 0 ? [1] : []
    content {
      sid       = "ReadInjectedSecrets"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = var.secret_arns
    }
  }

  dynamic "statement" {
    for_each = length(var.secret_kms_key_arns) > 0 ? [1] : []
    content {
      sid       = "DecryptInjectedSecrets"
      actions   = ["kms:Decrypt"]
      resources = var.secret_kms_key_arns
      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["secretsmanager.${local.region}.amazonaws.com"]
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
  name               = "${var.family}-task"
  description        = "Task role of the one-off task ${var.family} (no AWS API access)"
  assume_role_policy = data.aws_iam_policy_document.assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "task" {
  statement {
    sid       = "NoAwsApiAccess"
    effect    = "Deny"
    actions   = ["*"]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "task" {
  name   = "deny-all"
  role   = aws_iam_role.task.id
  policy = data.aws_iam_policy_document.task.json
}

resource "aws_ecs_task_definition" "this" {
  family                   = var.family
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = tostring(var.cpu)
  memory                   = tostring(var.memory)
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  container_definitions    = jsonencode([local.container])
  skip_destroy             = true

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  tags = var.tags
}
