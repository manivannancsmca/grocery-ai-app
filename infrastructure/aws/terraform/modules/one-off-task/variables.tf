variable "name" {
  description = "Task name (db-init, kafka-init, schema-init)."
  type        = string
}

variable "family" {
  description = "Task definition family: grocery-<env>-<name>."
  type        = string
}

variable "environment" {
  description = "dev | staging | prod."
  type        = string
}

variable "account_id" {
  description = "AWS account id."
  type        = string
}

variable "image" {
  description = "Image reference."
  type        = string
}

variable "ecr_repository_arn" {
  description = "ECR repository of the image (null for public images)."
  type        = string
  default     = null
}

variable "ecr_kms_key_arn" {
  description = "KMS key of the ECR repositories."
  type        = string
  default     = null
}

variable "entry_point" {
  description = "Entry point override."
  type        = list(string)
  default     = null
}

variable "command" {
  description = "Command override."
  type        = list(string)
  default     = null
}

variable "cpu" {
  description = "Task CPU units."
  type        = number
}

variable "memory" {
  description = "Task memory (MiB)."
  type        = number
}

variable "cpu_architecture" {
  description = "X86_64 or ARM64."
  type        = string
  default     = "X86_64"
}

variable "environment_variables" {
  description = "Plain environment variables."
  type        = map(string)
  default     = {}
}

variable "secrets" {
  description = "Environment variable => Secrets Manager valueFrom."
  type        = map(string)
  default     = {}
}

variable "secret_arns" {
  description = "Secret ARNs the execution role may read."
  type        = list(string)
  default     = []
}

variable "secret_kms_key_arns" {
  description = "KMS keys of those secrets."
  type        = list(string)
  default     = []
}

variable "log_retention_days" {
  description = "Log retention."
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
