variable "name" {
  description = "Name prefix, grocery-<env>."
  type        = string
}

variable "environment" {
  description = "dev | staging | prod."
  type        = string
}

variable "cidr_block" {
  description = "VPC CIDR (a /16 is expected: public subnets are /24, private subnets /20)."
  type        = string
}

variable "az_count" {
  description = "Number of availability zones (2 in dev, 3 in staging and prod)."
  type        = number

  validation {
    condition     = var.az_count >= 2 && var.az_count <= 3
    error_message = "az_count must be 2 or 3 (RDS, MSK and ElastiCache subnet groups need at least two AZs)."
  }
}

variable "single_nat_gateway" {
  description = "One shared NAT gateway (dev, cheaper, not AZ-resilient) instead of one per AZ."
  type        = bool
}

variable "interface_endpoints" {
  description = "Interface VPC endpoint service suffixes (each costs per AZ-hour)."
  type        = list(string)
}

variable "log_retention_days" {
  description = "Retention of the VPC flow log group."
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
