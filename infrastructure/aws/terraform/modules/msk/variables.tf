variable "name" {
  description = "Cluster name, grocery-<env>."
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
  description = "One private subnet per AZ (broker_count must be a multiple of their number)."
  type        = list(string)
}

variable "kafka_version" {
  description = "MSK Kafka version string (KRaft): pick the newest '*.kraft' version MSK offers in the region."
  type        = string
}

variable "broker_instance_type" {
  description = "Broker instance type."
  type        = string
}

variable "broker_count" {
  description = "Number of brokers (multiple of the subnet count)."
  type        = number
}

variable "broker_volume_size" {
  description = "EBS GiB per broker."
  type        = number
}

variable "replication_factor" {
  description = "Default replication factor (and kafka-init's). <= broker_count."
  type        = number
}

variable "min_insync_replicas" {
  description = "min.insync.replicas."
  type        = number
}

variable "enhanced_monitoring" {
  description = "DEFAULT | PER_BROKER | PER_TOPIC_PER_BROKER | PER_TOPIC_PER_PARTITION."
  type        = string
  default     = "PER_BROKER"
}

variable "scram_client_security_group_ids" {
  description = "Label => security group allowed on the SCRAM listener (9096)."
  type        = map(string)
}

variable "iam_client_security_group_ids" {
  description = "Label => security group allowed on the IAM listener (9098)."
  type        = map(string)
}

variable "scram_secret_arns" {
  description = "Kafka user => SCRAM secret ARN (name AmazonMSK_..., customer-managed KMS key)."
  type        = map(string)
}

variable "log_retention_days" {
  description = "Broker log retention."
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
