variable "name" {
  description = "Name prefix, grocery-<env>."
  type        = string
}

variable "environment" {
  description = "dev | staging | prod."
  type        = string
}

variable "aliases" {
  description = "Public host names of the web app (the ACM certificate must cover them)."
  type        = list(string)
}

variable "certificate_arn" {
  description = "ACM certificate in us-east-1 for the aliases."
  type        = string
}

variable "route53_zone_id" {
  description = "Public hosted zone for the alias records (null = manage DNS elsewhere)."
  type        = string
  default     = null
}

variable "api_alb_arn" {
  description = "Internal ALB (CloudFront VPC origin)."
  type        = string
}

variable "api_origin_domain_name" {
  description = "Host name CloudFront uses for the API origin (TLS/SNI); the ALB certificate must cover it."
  type        = string
}

variable "logs_bucket" {
  description = "Bucket for CloudFront standard logs and S3 access logs (ACLs enabled, SSE-S3)."
  type        = string
}

variable "logs_bucket_domain_name" {
  description = "Domain name of the logs bucket (<bucket>.s3.amazonaws.com) for CloudFront standard logs."
  type        = string
}

variable "price_class" {
  description = "CloudFront price class."
  type        = string
  default     = "PriceClass_200"
}

variable "geo_allow_countries" {
  description = "ISO country codes allowed (empty = everywhere)."
  type        = list(string)
  default     = []
}

variable "content_security_policy" {
  description = "Content-Security-Policy: the production nginx policy (frontend/grocery-web/nginx, Razorpay Checkout allowed) plus upgrade-insecure-requests, which only the HTTPS edge may add."
  type        = string
  default     = "default-src 'self'; script-src 'self' https://checkout.razorpay.com; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self' https://api.razorpay.com https://lumberjack.razorpay.com; frame-src https://api.razorpay.com https://checkout.razorpay.com; object-src 'none'; base-uri 'self'; form-action 'self' https://api.razorpay.com; frame-ancestors 'none'; upgrade-insecure-requests"
}

variable "waf_enabled" {
  description = "Attach an AWS WAF web ACL (managed rule groups + rate limit)."
  type        = bool
}

variable "waf_rate_limit_per_5_minutes" {
  description = "Requests per IP per 5 minutes before WAF blocks."
  type        = number
  default     = 2000
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
}
