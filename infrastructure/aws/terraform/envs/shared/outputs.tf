output "ecr_repository_urls" {
  description = "Repository name => URL (CI pushes <url>:<semver>-<shortSha> and sha-<fullSha>)."
  value       = module.ecr.repository_urls
}

output "ecr_kms_key_arn" {
  description = "Pass as ecr_kms_key_arn in every <env>.tfvars (execution roles decrypt image layers)."
  value       = module.ecr.kms_key_arn
}

output "codeartifact" {
  description = "Domain, owner and repository for Maven (settings.xml) and the library pipelines."
  value = {
    domain       = module.codeartifact.domain
    domain_owner = module.codeartifact.domain_owner
    repository   = module.codeartifact.repository
  }
}

output "ci_role_arn" {
  description = "Role Jenkins assumes to push images and publish libraries."
  value       = try(aws_iam_role.ci[0].arn, null)
}
