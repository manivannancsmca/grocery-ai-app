# grocery-<env>-deployer: the role Jenkins assumes to deploy to this environment (contract §6). It can:
#   * register task definition revisions and update the services of this cluster (blue/green), watch and stop
#     (roll back) service deployments, run the one-off tasks, pass this environment's task/execution roles to ECS;
#   * record the deployed image tag in /grocery/<env>/<service>/image-tag;
#   * publish the web app: upload releases/<sha>/ and meta/ to the web bucket, switch the CloudFront origin path,
#     invalidate;
#   * read what it needs to report: target health, alarms, the services' and one-off tasks' logs.
# Pushing images (ECR) and libraries (CodeArtifact) is the job of grocery-ci in envs/shared.

locals {
  deploy_role_enabled = length(var.ci_principal_arns) > 0
  cluster_arn         = module.ecs_cluster.cluster_arn
  ecs_prefix          = "arn:${local.partition}:ecs:${local.region}:${local.account}"
}

data "aws_iam_policy_document" "deployer_assume" {
  count = local.deploy_role_enabled ? 1 : 0

  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "AWS"
      identifiers = var.ci_principal_arns
    }
  }
}

resource "aws_iam_role" "deployer" {
  count = local.deploy_role_enabled ? 1 : 0

  name                 = "${local.name}-deployer"
  description          = "Jenkins deploys to ${var.environment}: ECS blue/green, one-off tasks, web app"
  assume_role_policy   = data.aws_iam_policy_document.deployer_assume[0].json
  max_session_duration = 3600
  tags                 = local.tags
}

data "aws_iam_policy_document" "deployer" {
  count = local.deploy_role_enabled ? 1 : 0

  statement {
    sid = "TaskDefinitions"
    actions = [
      "ecs:RegisterTaskDefinition",
      "ecs:DescribeTaskDefinition",
      "ecs:ListTaskDefinitions",
      "ecs:ListTaskDefinitionFamilies",
    ]
    resources = ["*"] # these ECS actions do not support resource-level permissions
  }

  statement {
    sid       = "TagNewRevisions"
    actions   = ["ecs:TagResource"]
    resources = ["${local.ecs_prefix}:task-definition/${local.name}-*:*"]
    condition {
      test     = "StringEquals"
      variable = "ecs:CreateAction"
      values   = ["RegisterTaskDefinition"]
    }
  }

  statement {
    sid = "Services"
    actions = [
      "ecs:DescribeServices",
      "ecs:UpdateService",
    ]
    resources = ["${local.ecs_prefix}:service/${module.ecs_cluster.cluster_name}/*"]
  }

  statement {
    sid = "ServiceDeployments"
    actions = [
      "ecs:ListServiceDeployments",
      "ecs:DescribeServiceDeployments",
      "ecs:DescribeServiceRevisions",
      "ecs:StopServiceDeployment",
    ]
    resources = [
      "${local.ecs_prefix}:service/${module.ecs_cluster.cluster_name}/*",
      "${local.ecs_prefix}:service-deployment/${module.ecs_cluster.cluster_name}/*",
      "${local.ecs_prefix}:service-revision/${module.ecs_cluster.cluster_name}/*",
    ]
  }

  statement {
    sid       = "RunOneOffTasks"
    actions   = ["ecs:RunTask"]
    resources = ["${local.ecs_prefix}:task-definition/${local.name}-*:*"]
    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [local.cluster_arn]
    }
  }

  statement {
    sid       = "WatchTasks"
    actions   = ["ecs:DescribeTasks", "ecs:ListTasks", "ecs:StopTask"]
    resources = ["*"]
    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [local.cluster_arn]
    }
  }

  statement {
    sid       = "PassTaskRoles"
    actions   = ["iam:PassRole"]
    resources = ["arn:${local.partition}:iam::${local.account}:role/${local.name}-*"]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }

  statement {
    sid       = "ImageTagParameters"
    actions   = ["ssm:GetParameter", "ssm:GetParameters", "ssm:PutParameter"]
    resources = ["arn:${local.partition}:ssm:${local.region}:${local.account}:parameter/grocery/${var.environment}/*"]
  }

  statement {
    sid       = "WebBucketList"
    actions   = ["s3:ListBucket"]
    resources = [module.spa.bucket_arn]
  }

  statement {
    sid       = "WebBucketObjects"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
    resources = ["${module.spa.bucket_arn}/releases/*", "${module.spa.bucket_arn}/meta/*"]
  }

  statement {
    sid       = "WebBucketKey"
    actions   = ["kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.spa.bucket_kms_key_arn]
  }

  # The debezium-plugin pipeline uploads the MSK Connect plugin ZIP (variable msk_connect.plugin_object_key) here.
  statement {
    sid       = "PluginBucketList"
    actions   = ["s3:ListBucket"]
    resources = ["arn:${local.partition}:s3:::${module.msk_connect.plugin_bucket}"]
    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["debezium-mysql/*"]
    }
  }

  statement {
    sid       = "PluginObjects"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["arn:${local.partition}:s3:::${module.msk_connect.plugin_bucket}/debezium-mysql/*"]
  }

  statement {
    sid       = "PluginBucketKey"
    actions   = ["kms:Encrypt", "kms:Decrypt", "kms:GenerateDataKey"]
    resources = [aws_kms_key.s3.arn]
  }

  statement {
    sid = "SwitchCloudFrontRelease"
    actions = [
      "cloudfront:GetDistribution",
      "cloudfront:GetDistributionConfig",
      "cloudfront:UpdateDistribution",
      "cloudfront:CreateInvalidation",
      "cloudfront:GetInvalidation",
    ]
    resources = [module.spa.distribution_arn]
  }

  statement {
    sid = "ReadDeploymentSignals"
    actions = [
      "elasticloadbalancing:DescribeTargetHealth",
      "elasticloadbalancing:DescribeTargetGroups",
      "elasticloadbalancing:DescribeListeners",
      "elasticloadbalancing:DescribeRules",
      "cloudwatch:DescribeAlarms",
    ]
    resources = ["*"] # Describe* actions without resource-level permissions
  }

  statement {
    sid       = "ReadLogs"
    actions   = ["logs:GetLogEvents", "logs:FilterLogEvents", "logs:DescribeLogStreams"]
    resources = ["arn:${local.partition}:logs:${local.region}:${local.account}:log-group:/grocery/${var.environment}/*"]
  }
}

resource "aws_iam_role_policy" "deployer" {
  count = local.deploy_role_enabled ? 1 : 0

  name   = "deploy"
  role   = aws_iam_role.deployer[0].id
  policy = data.aws_iam_policy_document.deployer[0].json
}
