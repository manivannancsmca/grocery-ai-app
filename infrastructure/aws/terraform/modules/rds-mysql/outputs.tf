output "address" {
  description = "DB_HOST."
  value       = aws_db_instance.this.address
}

output "port" {
  description = "DB_PORT."
  value       = aws_db_instance.this.port
}

output "identifier" {
  description = "Instance identifier (CloudWatch dimension DBInstanceIdentifier)."
  value       = aws_db_instance.this.identifier
}

output "master_secret_arn" {
  description = "RDS-managed master credentials secret (username/password JSON), read only by db-init."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}

output "kms_key_arn" {
  description = "KMS key of the instance and of the master secret."
  value       = aws_kms_key.this.arn
}

output "security_group_id" {
  description = "Security group of the instance."
  value       = aws_security_group.this.id
}
