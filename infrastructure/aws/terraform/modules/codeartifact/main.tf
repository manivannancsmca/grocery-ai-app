# CodeArtifact Maven repository for the shared libraries (grocery-common, grocery-event-schemas). Maven Central is an
# upstream, so builds resolve everything through one authenticated endpoint.

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

resource "aws_kms_key" "this" {
  description         = "${var.domain} CodeArtifact domain encryption"
  enable_key_rotation = true
  policy              = data.aws_iam_policy_document.kms.json
  tags                = var.tags
}

resource "aws_kms_alias" "this" {
  name          = "alias/${var.domain}-codeartifact"
  target_key_id = aws_kms_key.this.key_id
}

resource "aws_codeartifact_domain" "this" {
  domain         = var.domain
  encryption_key = aws_kms_key.this.arn
  tags           = var.tags
}

resource "aws_codeartifact_repository" "maven_central" {
  repository  = "maven-central-store"
  description = "Proxy of Maven Central"
  domain      = aws_codeartifact_domain.this.domain

  external_connections {
    external_connection_name = "public:maven-central"
  }

  tags = var.tags
}

resource "aws_codeartifact_repository" "maven" {
  repository  = var.repository
  description = "grocery-common, grocery-event-schemas (published by the library pipelines)"
  domain      = aws_codeartifact_domain.this.domain

  upstream {
    repository_name = aws_codeartifact_repository.maven_central.repository
  }

  tags = var.tags
}
