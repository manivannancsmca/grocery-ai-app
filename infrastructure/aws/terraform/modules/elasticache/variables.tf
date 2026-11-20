variable "name" {
  description = "Name prefix, grocery-<env>."
  type        = string
}

variable "environment" {
  description = "dev | staging | prod."
  type        = string
}

variable "vpc_id" {
  description = "VPC id."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets."
  type        = list(string)
}

variable "client_security_group_ids" {
  description = "Label => security group allowed to connect."
  type        = map(string)
}

variable "engine_version" {
  description = "Redis OSS engine version."
  type        = string
  default     = "7.1"
}

variable "parameter_group_family" {
  description = "Parameter group family matching engine_version."
  type        = string
  default     = "redis7"
}

variable "node_type" {
  description = "Node type."
  type        = string
}

variable "num_cache_clusters" {
  description = "Nodes (1 = no replica; >1 enables Multi-AZ and automatic failover)."
  type        = number
}

variable "snapshot_retention_days" {
  description = "Daily snapshots kept."
  type        = number
}

variable "auth_token" {
  description = "Redis AUTH token (16-128 alphanumeric characters)."
  type        = string
  sensitive   = true
}

variable "log_retention_days" {
  description = "Slow log retention."
  type        = number
}

variable "log_kms_key_arn" {
  description = "KMS key for CloudWatch log groups."
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
}
