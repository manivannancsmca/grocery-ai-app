variable "name" {
  description = "Name prefix, grocery-<env>. The cluster is <name>-cluster."
  type        = string
}

variable "environment" {
  description = "dev | staging | prod."
  type        = string
}

variable "vpc_id" {
  description = "VPC of the tasks."
  type        = string
}

variable "vpc_cidr_block" {
  description = "VPC CIDR (task egress inside the VPC)."
  type        = string
}

variable "internet_egress_ports" {
  description = "TCP ports the tasks may reach on the internet through NAT."
  type        = list(number)
  default     = [443, 587]
}

variable "container_insights" {
  description = "enhanced | enabled | disabled. RunningTaskCount (ServiceDown alarm, request autoscaling) needs it on."
  type        = string
  default     = "enhanced"
}

variable "fargate_spot_weight" {
  description = "Default strategy weight of FARGATE_SPOT (0 = on-demand only). Dev uses Spot."
  type        = number
  default     = 0
}

variable "exec_kms_key_arn" {
  description = "KMS key encrypting ECS Exec sessions."
  type        = string
}

variable "log_retention_days" {
  description = "Retention of the cluster log groups."
  type        = number
}

variable "log_kms_key_arn" {
  description = "KMS key for CloudWatch log groups."
  type        = string
}

variable "task_failure_metric_namespace" {
  description = "Namespace of the task failure metric."
  type        = string
  default     = "Grocery/ECS"
}

variable "task_failure_metric_name" {
  description = "Name of the task failure metric (dimension Group = service:<ecs service name>)."
  type        = string
  default     = "TaskFailures"
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
}
