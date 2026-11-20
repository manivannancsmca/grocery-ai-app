variable "domain" {
  description = "CodeArtifact domain name."
  type        = string
}

variable "repository" {
  description = "Maven repository for the shared libraries."
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
}
