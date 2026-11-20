# One ECR repository per deployable image (shared by every environment: a promotion re-tags, it never rebuilds).
# Tags are immutable except the moving environment tags (dev, staging, prod), which the promotion re-points.

data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "kms" {
  statement {
    sid       = "AccountAdministration"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }
}

resource "aws_kms_key" "ecr" {
  description         = "${var.name} ECR image encryption"
  enable_key_rotation = true
  policy              = data.aws_iam_policy_document.kms.json
  tags                = var.tags
}

resource "aws_kms_alias" "ecr" {
  name          = "alias/${var.name}-ecr"
  target_key_id = aws_kms_key.ecr.key_id
}

resource "aws_ecr_repository" "this" {
  for_each = toset(var.repositories)

  name                 = each.value
  image_tag_mutability = "IMMUTABLE_WITH_EXCLUSION"
  force_delete         = false

  dynamic "image_tag_mutability_exclusion_filter" {
    for_each = var.mutable_tags
    content {
      filter      = image_tag_mutability_exclusion_filter.value
      filter_type = "WILDCARD"
    }
  }

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = aws_kms_key.ecr.arn
  }

  tags = merge(var.tags, { Service = basename(each.value) })
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name
  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images after ${var.untagged_expiry_days} days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = var.untagged_expiry_days
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep the last ${var.keep_tagged_images} tagged images"
        selection = {
          tagStatus      = "tagged"
          tagPatternList = ["*"]
          countType      = "imageCountMoreThan"
          countNumber    = var.keep_tagged_images
        }
        action = { type = "expire" }
      }
    ]
  })
}

# Optional cross-account pulls (when staging/prod live in other accounts than the registry).
data "aws_iam_policy_document" "pull" {
  count = length(var.pull_account_ids) > 0 ? 1 : 0

  statement {
    sid = "CrossAccountPull"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]
    principals {
      type        = "AWS"
      identifiers = [for id in var.pull_account_ids : "arn:aws:iam::${id}:root"]
    }
  }
}

resource "aws_ecr_repository_policy" "pull" {
  for_each = length(var.pull_account_ids) > 0 ? aws_ecr_repository.this : {}

  repository = each.value.name
  policy     = data.aws_iam_policy_document.pull[0].json
}
