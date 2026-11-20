variable "name" {
  description = "Name prefix, grocery-<env>."
  type        = string
}

variable "vpc_id" {
  description = "VPC id."
  type        = string
}

variable "subnet_ids" {
  description = "Private subnets (at least two AZs)."
  type        = list(string)
}

variable "client_security_group_ids" {
  description = "Label => security group allowed to connect (ECS tasks, MSK Connect)."
  type        = map(string)
}

variable "engine_version" {
  description = "MySQL version (8.4 = latest 8.4 minor)."
  type        = string
  default     = "8.4"
}

variable "instance_class" {
  description = "Instance class."
  type        = string
}

variable "allocated_storage" {
  description = "Initial storage (GiB)."
  type        = number
}

variable "max_allocated_storage" {
  description = "Storage autoscaling ceiling (GiB)."
  type        = number
}

variable "multi_az" {
  description = "Multi-AZ standby (prod)."
  type        = bool
}

variable "backup_retention_days" {
  description = "Automated backups (>= 1: binary logging on RDS requires backups)."
  type        = number

  validation {
    condition     = var.backup_retention_days >= 1
    error_message = "Debezium needs the binlog, and RDS only enables it with automated backups (>= 1 day)."
  }
}

variable "deletion_protection" {
  description = "Deletion protection (and a final snapshot)."
  type        = bool
}

variable "performance_insights_enabled" {
  description = "Performance Insights (not available on the smallest classes)."
  type        = bool
}

variable "extra_parameters" {
  description = "Additional parameter group settings: name => { value, apply = immediate | pending-reboot }."
  type = map(object({
    value = string
    apply = string
  }))
  default = {}
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
}
