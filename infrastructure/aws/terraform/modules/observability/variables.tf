variable "name" {
  description = "Name prefix, grocery-<env>."
  type        = string
}

variable "environment" {
  description = "dev | staging | prod."
  type        = string
}

variable "alert_emails" {
  description = "Email addresses subscribed to the alert topic (each must confirm the subscription)."
  type        = list(string)
}

variable "alert_rules_file" {
  description = "Prometheus rules file for AMP (null = the module's grocery-alerts.yml)."
  type        = string
  default     = null
}

variable "grafana_enabled" {
  description = "Create the Amazon Managed Grafana workspace (needs IAM Identity Center in the account/organization)."
  type        = bool
}

variable "rds_identifier" {
  description = "RDS instance identifier."
  type        = string
}

variable "redis_replication_group_id" {
  description = "ElastiCache replication group id (nodes are <id>-001, -002 ...)."
  type        = string
}

variable "redis_node_count" {
  description = "Nodes in the replication group."
  type        = number
}

variable "msk_cluster_name" {
  description = "MSK cluster name."
  type        = string
}

variable "kafka_lag_threshold" {
  description = "KafkaConsumerLagHigh threshold (messages)."
  type        = number
  default     = 100
}

variable "log_retention_days" {
  description = "Retention of the AMP log group."
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
