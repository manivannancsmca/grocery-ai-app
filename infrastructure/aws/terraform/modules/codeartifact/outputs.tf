output "domain" {
  description = "Domain name."
  value       = aws_codeartifact_domain.this.domain
}

output "domain_arn" {
  description = "Domain ARN."
  value       = aws_codeartifact_domain.this.arn
}

output "domain_owner" {
  description = "Account that owns the domain (for aws codeartifact get-authorization-token --domain-owner)."
  value       = aws_codeartifact_domain.this.owner
}

output "repository" {
  description = "Maven repository name."
  value       = aws_codeartifact_repository.maven.repository
}

output "repository_arn" {
  description = "Maven repository ARN."
  value       = aws_codeartifact_repository.maven.arn
}
