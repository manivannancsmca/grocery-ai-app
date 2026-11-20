variable "name" {
  description = "Name prefix for the KMS key (grocery)."
  type        = string
}

variable "repositories" {
  description = "Repository names, e.g. grocery/order-service."
  type        = list(string)
}

variable "mutable_tags" {
  description = "Tags excluded from immutability (the environment tags a promotion moves)."
  type        = list(string)
  default     = ["dev", "staging", "prod"]
}

variable "keep_tagged_images" {
  description = "Tagged images kept per repository."
  type        = number
  default     = 30
}

variable "untagged_expiry_days" {
  description = "Untagged images expire after this many days."
  type        = number
  default     = 7
}

variable "pull_account_ids" {
  description = "Other AWS accounts allowed to pull (environments in separate accounts). Empty = same account only."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
}
