variable "name" {
  description = "Deployment name, grocery-<env>."
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
  description = "Private subnets of the PrivateLink endpoint."
  type        = list(string)
}

variable "client_security_group_id" {
  description = "Security group of the ECS tasks."
  type        = string
}

variable "privatelink_service_name" {
  description = "Elastic's PrivateLink endpoint service in this region (Elastic docs 'AWS PrivateLink regions')."
  type        = string
}

variable "elastic_region" {
  description = "Elastic Cloud region id of the AWS region (e.g. aws-ap-south-1)."
  type        = string
}

variable "elastic_version" {
  description = "Elastic Stack version (match the local 9.x image)."
  type        = string
}

variable "deployment_template_id" {
  description = "Elastic Cloud deployment template."
  type        = string
  default     = "aws-general-purpose"
}

variable "hot_size" {
  description = "Memory per hot node, e.g. 1g, 4g."
  type        = string
}

variable "hot_max_size" {
  description = "Autoscaling ceiling of the hot tier."
  type        = string
}

variable "zone_count" {
  description = "Availability zones of the hot tier (1 dev, 2-3 prod)."
  type        = number
}

variable "secrets_kms_key_arn" {
  description = "KMS key for the superuser secret."
  type        = string
}

variable "recovery_window_days" {
  description = "Secret recovery window."
  type        = number
}

variable "tags" {
  description = "Tags (also applied to the Elastic deployment)."
  type        = map(string)
}
