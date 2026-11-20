variable "name" {
  description = "Name prefix, grocery-<env>."
  type        = string
}

variable "environment" {
  description = "dev | staging | prod."
  type        = string
}

variable "connectors_enabled" {
  description = "Create the custom plugin and the connectors (needs the plugin ZIP in S3, db-init and kafka-init done)."
  type        = bool
}

variable "connector_configs" {
  description = "Service => decoded services/<service>/deploy/debezium-connector.json."
  type        = map(map(string))
}

variable "kafkaconnect_version" {
  description = "MSK Connect Apache Kafka Connect version."
  type        = string
  default     = "3.7.x"
}

variable "mcu_count" {
  description = "MSK Connect units per worker (1 = 1 vCPU, 4 GiB)."
  type        = number
  default     = 1
}

variable "plugin_object_key" {
  description = "Key of the plugin ZIP in the plugin bucket."
  type        = string
}

variable "vpc_id" {
  description = "VPC id."
  type        = string
}

variable "vpc_cidr_block" {
  description = "VPC CIDR (worker egress)."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets of the workers."
  type        = list(string)
}

variable "msk_cluster_arn" {
  description = "MSK cluster ARN."
  type        = string
}

variable "msk_cluster_name" {
  description = "MSK cluster name (topic/group ARNs)."
  type        = string
}

variable "msk_cluster_uuid" {
  description = "MSK cluster UUID (topic/group ARNs)."
  type        = string
}

variable "bootstrap_servers_iam" {
  description = "MSK bootstrap servers for IAM authentication."
  type        = string
}

variable "replication_factor" {
  description = "Replication factor of topics the connector creates (schema history)."
  type        = number
}

variable "database_hostname" {
  description = "RDS address."
  type        = string
}

variable "database_port" {
  description = "RDS port."
  type        = number
}

variable "database_secret_name" {
  description = "Secrets Manager name of the Debezium credentials (JSON username/password)."
  type        = string
}

variable "database_secret_arn" {
  description = "ARN of the same secret."
  type        = string
}

variable "secrets_kms_key_arn" {
  description = "KMS key of the secret."
  type        = string
}

variable "s3_kms_key_arn" {
  description = "KMS key for the plugin bucket."
  type        = string
}

variable "access_logs_bucket" {
  description = "Bucket receiving S3 server access logs."
  type        = string
}

variable "alarm_topic_arn" {
  description = "SNS topic for alarms."
  type        = string
}

variable "log_retention_days" {
  description = "Worker log retention."
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
