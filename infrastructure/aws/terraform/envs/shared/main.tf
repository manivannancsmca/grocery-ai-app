# Account-level resources shared by every environment, applied once before dev/staging/prod:
#   * ECR: one repository per image of services.auto.tfvars.json (+ the schema-init tool image), shared by all
#     environments (a promotion re-tags; it never rebuilds);
#   * CodeArtifact: the Maven repository of grocery-common and grocery-event-schemas;
#   * grocery-ci: the role Jenkins assumes to push images and publish libraries.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  catalog   = jsondecode(file("${path.module}/../../services.auto.tfvars.json"))
  account   = data.aws_caller_identity.current.account_id
  partition = data.aws_partition.current.partition
  region    = data.aws_region.current.region
  tags      = { Project = "grocery", Environment = "shared", ManagedBy = "terraform" }

  repositories = sort(distinct(concat(
    [for k, s in local.catalog.services : try(s.image_repository, "") if try(s.image_repository, null) != null],
    [for k, t in local.catalog.one_off_tasks : try(t.image_repository, "") if try(t.image_repository, null) != null],
  )))
}

module "ecr" {
  source = "../../modules/ecr"

  name                 = "grocery"
  repositories         = local.repositories
  keep_tagged_images   = 30
  untagged_expiry_days = 7
  pull_account_ids     = var.pull_account_ids
  tags                 = local.tags
}

module "codeartifact" {
  source = "../../modules/codeartifact"

  domain     = "grocery"
  repository = "grocery-maven"
  tags       = local.tags
}

# ------------------------------------------------------------------------------------------- CI build/push role
data "aws_iam_policy_document" "ci_assume" {
  count = length(var.ci_principal_arns) > 0 ? 1 : 0

  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "AWS"
      identifiers = var.ci_principal_arns
    }
  }
}

resource "aws_iam_role" "ci" {
  count = length(var.ci_principal_arns) > 0 ? 1 : 0

  name                 = "grocery-ci"
  description          = "Jenkins: push images to ECR, publish libraries to CodeArtifact"
  assume_role_policy   = data.aws_iam_policy_document.ci_assume[0].json
  max_session_duration = 3600
  tags                 = local.tags
}

data "aws_iam_policy_document" "ci" {
  count = length(var.ci_principal_arns) > 0 ? 1 : 0

  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # no resource-level permissions
  }

  statement {
    sid = "PushAndInspectImages"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImageScanFindings",
      "ecr:DescribeImages",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = values(module.ecr.repository_arns)
  }

  statement {
    sid       = "EcrKey"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey", "kms:Encrypt"]
    resources = [module.ecr.kms_key_arn]
  }

  statement {
    sid       = "CodeArtifactToken"
    actions   = ["codeartifact:GetAuthorizationToken"]
    resources = [module.codeartifact.domain_arn]
  }

  statement {
    sid       = "CodeArtifactBearer"
    actions   = ["sts:GetServiceBearerToken"]
    resources = ["*"] # scoped by the condition
    condition {
      test     = "StringEquals"
      variable = "sts:AWSServiceName"
      values   = ["codeartifact.amazonaws.com"]
    }
  }

  statement {
    sid = "CodeArtifactPublishAndRead"
    actions = [
      "codeartifact:GetRepositoryEndpoint",
      "codeartifact:ReadFromRepository",
      "codeartifact:DescribePackageVersion",
      "codeartifact:ListPackageVersions",
      "codeartifact:PublishPackageVersion",
      "codeartifact:PutPackageMetadata",
    ]
    resources = [
      module.codeartifact.repository_arn,
      "arn:${local.partition}:codeartifact:${local.region}:${local.account}:package/${module.codeartifact.domain}/${module.codeartifact.repository}/*",
    ]
  }
}

resource "aws_iam_role_policy" "ci" {
  count = length(var.ci_principal_arns) > 0 ? 1 : 0

  name   = "build-and-publish"
  role   = aws_iam_role.ci[0].id
  policy = data.aws_iam_policy_document.ci[0].json
}
