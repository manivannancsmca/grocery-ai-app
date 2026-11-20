variable "name" {
  description = "Name prefix, grocery-<env> (ALB and target group names must stay within 32 characters)."
  type        = string
}

variable "vpc_id" {
  description = "VPC id."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets (internal ALB)."
  type        = list(string)
}

variable "tasks_security_group_id" {
  description = "Security group of the ECS tasks (targets, and clients of internal listeners)."
  type        = string
}

variable "apps" {
  description = "Load-balanced units. short_name keeps target group names <= 32 characters."
  type = map(object({
    short_name         = string
    target_port        = number
    health_check_path  = string
    listener_port      = number
    test_listener_port = number
    from_cloudfront    = bool
  }))
}

variable "operator_cidrs" {
  description = "Networks of CI agents / VPN allowed on the test listeners and the internal production listeners."
  type        = list(string)
  default     = []
}

variable "certificate_arn" {
  description = "ACM certificate (same region) for the HTTPS listeners; must cover the API origin name."
  type        = string
}

variable "ssl_policy" {
  description = "TLS policy of the listeners."
  type        = string
  default     = "ELBSecurityPolicy-TLS13-1-2-Res-2021-06"
}

variable "idle_timeout_seconds" {
  description = "Idle timeout (SSE streams send heartbeats more often than this)."
  type        = number
  default     = 120
}

variable "deletion_protection" {
  description = "Deletion protection."
  type        = bool
}

variable "access_logs_bucket" {
  description = "S3 bucket for access logs (SSE-S3; ALB cannot write to SSE-KMS buckets)."
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
}
