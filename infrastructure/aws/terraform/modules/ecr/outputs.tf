output "repository_urls" {
  description = "Repository name => URL."
  value       = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}

output "repository_arns" {
  description = "Repository name => ARN."
  value       = { for k, r in aws_ecr_repository.this : k => r.arn }
}

output "kms_key_arn" {
  description = "KMS key encrypting the images."
  value       = aws_kms_key.ecr.arn
}
