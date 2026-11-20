# The web app on S3 + CloudFront, and the single public entry point of the platform:
#   default behaviour  -> S3 (private bucket, Origin Access Control), origin path /releases/<sha> chosen by
#                         infrastructure/ci/scripts/deploy-spa.sh; a CloudFront Function serves index.html for client
#                         routes (SPA fallback without custom error responses, so API 403/404 stay untouched)
#   /api/*             -> the internal ALB through a CloudFront VPC origin (api-gateway); no caching
#   every response     -> security headers policy (HSTS, CSP, frame/content-type/referrer/permissions policies)
#   optional AWS WAF (managed rule groups + per-IP rate limit)

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_origin_request_policy" "all_viewer_except_host" {
  name = "Managed-AllViewerExceptHostHeader"
}

locals {
  account      = data.aws_caller_identity.current.account_id
  partition    = data.aws_partition.current.partition
  s3_origin_id = "web-s3"
  api_origin   = "api-alb"
}

# ------------------------------------------------------------------------------------------------------- bucket
resource "aws_kms_key" "web" {
  description         = "${var.name} web bucket encryption"
  enable_key_rotation = true
  tags                = var.tags
}

resource "aws_kms_alias" "web" {
  name          = "alias/${var.name}-web"
  target_key_id = aws_kms_key.web.key_id
}

data "aws_iam_policy_document" "web_kms" {
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
    sid       = "CloudFrontReadsObjects"
    actions   = ["kms:Decrypt"]
    resources = ["*"]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceArn"
      values   = [aws_cloudfront_distribution.this.arn]
    }
  }
}

resource "aws_kms_key_policy" "web" {
  key_id = aws_kms_key.web.id
  policy = data.aws_iam_policy_document.web_kms.json
}

resource "aws_s3_bucket" "web" {
  bucket = "${var.name}-web-${local.account}"
  tags   = var.tags
}

resource "aws_s3_bucket_public_access_block" "web" {
  bucket                  = aws_s3_bucket.web.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "web" {
  bucket = aws_s3_bucket.web.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "web" {
  bucket = aws_s3_bucket.web.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "web" {
  bucket = aws_s3_bucket.web.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.web.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_logging" "web" {
  bucket        = aws_s3_bucket.web.id
  target_bucket = var.logs_bucket
  target_prefix = "s3/${aws_s3_bucket.web.id}/"
}

resource "aws_s3_bucket_lifecycle_configuration" "web" {
  bucket = aws_s3_bucket.web.id

  rule {
    id     = "expire-noncurrent"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

data "aws_iam_policy_document" "web_bucket" {
  statement {
    sid       = "CloudFrontReadsReleases"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.web.arn}/releases/*"]
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.this.arn]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.web.arn, "${aws_s3_bucket.web.arn}/*"]
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

resource "aws_s3_bucket_policy" "web" {
  bucket = aws_s3_bucket.web.id
  policy = data.aws_iam_policy_document.web_bucket.json

  depends_on = [aws_s3_bucket_public_access_block.web]
}

# --------------------------------------------------------------------------------------------- CloudFront pieces
resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "${var.name}-web"
  description                       = "CloudFront -> ${aws_s3_bucket.web.id}"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_vpc_origin" "api" {
  vpc_origin_endpoint_config {
    name                   = "${var.name}-api"
    arn                    = var.api_alb_arn
    http_port              = 80
    https_port             = 443
    origin_protocol_policy = "https-only"

    origin_ssl_protocols {
      items    = ["TLSv1.2"]
      quantity = 1
    }
  }

  tags = var.tags
}

resource "aws_cloudfront_function" "spa_router" {
  name    = "${var.name}-spa-router"
  runtime = "cloudfront-js-2.0"
  comment = "SPA fallback: client-side routes (no file extension) are served index.html"
  publish = true
  code    = <<-JS
    function handler(event) {
      var request = event.request;
      if (!/\.[A-Za-z0-9]+$/.test(request.uri)) {
        request.uri = '/index.html';
      }
      return request;
    }
  JS
}

resource "aws_cloudfront_response_headers_policy" "security" {
  name    = "${var.name}-security-headers"
  comment = "Security headers of every response"

  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = 63072000
      include_subdomains         = true
      preload                    = true
      override                   = true
    }
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }
    content_security_policy {
      content_security_policy = var.content_security_policy
      override                = false # a stricter policy set by the origin wins
    }
  }

  custom_headers_config {
    items {
      header   = "Permissions-Policy"
      value    = "camera=(), microphone=(), geolocation=(self), payment=(self \"https://checkout.razorpay.com\")"
      override = true
    }
    items {
      header   = "Cross-Origin-Opener-Policy"
      value    = "same-origin-allow-popups"
      override = true
    }
  }
}

# ---------------------------------------------------------------------------------------------------------- WAF
resource "aws_wafv2_web_acl" "this" {
  count    = var.waf_enabled ? 1 : 0
  provider = aws.us_east_1

  name        = "${var.name}-web"
  description = "grocery ${var.name}: AWS managed rules and a per-IP rate limit"
  scope       = "CLOUDFRONT"

  default_action {
    allow {}
  }

  dynamic "rule" {
    for_each = {
      AWSManagedRulesAmazonIpReputationList = 10
      AWSManagedRulesCommonRuleSet          = 20
      AWSManagedRulesKnownBadInputsRuleSet  = 30
    }
    content {
      name     = rule.key
      priority = rule.value

      override_action {
        none {}
      }

      statement {
        managed_rule_group_statement {
          vendor_name = "AWS"
          name        = rule.key
        }
      }

      visibility_config {
        cloudwatch_metrics_enabled = true
        metric_name                = rule.key
        sampled_requests_enabled   = true
      }
    }
  }

  rule {
    name     = "rate-limit-per-ip"
    priority = 40

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.waf_rate_limit_per_5_minutes
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "rate-limit-per-ip"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.name}-web"
    sampled_requests_enabled   = true
  }

  tags = var.tags
}

# --------------------------------------------------------------------------------------------------- distribution
resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  comment             = "grocery ${var.name}: web app + /api"
  aliases             = var.aliases
  default_root_object = "index.html"
  http_version        = "http2and3"
  is_ipv6_enabled     = true
  price_class         = var.price_class
  web_acl_id          = var.waf_enabled ? aws_wafv2_web_acl.this[0].arn : null

  origin {
    origin_id                = local.s3_origin_id
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
    origin_path              = ""
  }

  origin {
    origin_id   = local.api_origin
    domain_name = var.api_origin_domain_name

    vpc_origin_config {
      vpc_origin_id            = aws_cloudfront_vpc_origin.api.id
      origin_read_timeout      = 60
      origin_keepalive_timeout = 60
    }
  }

  default_cache_behavior {
    target_origin_id           = local.s3_origin_id
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.optimized.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_router.arn
    }
  }

  ordered_cache_behavior {
    path_pattern               = "/api/*"
    target_origin_id           = local.api_origin
    viewer_protocol_policy     = "https-only"
    allowed_methods            = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = false # SSE streams (/api/notifications/stream, AI chat) must pass through unbuffered
    cache_policy_id            = data.aws_cloudfront_cache_policy.disabled.id
    origin_request_policy_id   = data.aws_cloudfront_origin_request_policy.all_viewer_except_host.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id
  }

  restrictions {
    geo_restriction {
      restriction_type = length(var.geo_allow_countries) == 0 ? "none" : "whitelist"
      locations        = var.geo_allow_countries
    }
  }

  viewer_certificate {
    acm_certificate_arn      = var.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  logging_config {
    bucket          = var.logs_bucket_domain_name
    prefix          = "cloudfront/"
    include_cookies = false
  }

  tags = var.tags

  lifecycle {
    # deploy-spa.sh switches the S3 origin path (/releases/<sha>) on every frontend deployment and rollback; an apply
    # must not undo it. Consequence: change origins by `terraform apply -replace` or a temporary removal of this line.
    ignore_changes = [origin]
  }
}

# ---------------------------------------------------------------------------------------------------------- DNS
resource "aws_route53_record" "alias" {
  for_each = var.route53_zone_id == null ? {} : { for pair in setproduct(var.aliases, ["A", "AAAA"]) : "${pair[0]}|${pair[1]}" => pair }

  zone_id = var.route53_zone_id
  name    = each.value[0]
  type    = each.value[1]

  alias {
    name                   = aws_cloudfront_distribution.this.domain_name
    zone_id                = aws_cloudfront_distribution.this.hosted_zone_id
    evaluate_target_health = false
  }
}

# ------------------------------------------------------------------------------- names read by deploy-spa.sh
resource "aws_ssm_parameter" "bucket" {
  name        = "/grocery/${var.environment}/frontend/bucket-name"
  description = "Web bucket (deploy-spa.sh uploads releases/<sha>/ here)"
  type        = "String"
  value       = aws_s3_bucket.web.id
  tags        = var.tags
}

resource "aws_ssm_parameter" "distribution" {
  name        = "/grocery/${var.environment}/frontend/distribution-id"
  description = "CloudFront distribution of the web app (deploy-spa.sh switches its S3 origin path)"
  type        = "String"
  value       = aws_cloudfront_distribution.this.id
  tags        = var.tags
}
