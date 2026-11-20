variable "aws_region" {
  description = "Region of the registry and CodeArtifact (same as the environments)."
  type        = string
}

variable "ci_principal_arns" {
  description = "IAM principals (the Jenkins role) allowed to assume grocery-ci. Empty = no role."
  type        = list(string)
  default     = []
}

variable "pull_account_ids" {
  description = "Other accounts whose environments pull images (empty when all environments share this account)."
  type        = list(string)
  default     = []
}
