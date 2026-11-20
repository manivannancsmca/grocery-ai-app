output "kms_key_arn" {
  description = "Key encrypting every secret (and SSM SecureStrings)."
  value       = aws_kms_key.this.arn
}

output "db_secret_arns" {
  description = "Service => database credentials secret ARN."
  value       = { for k, s in aws_secretsmanager_secret.db : k => s.arn }
}

output "kafka_secret_arns" {
  description = "Kafka user => SCRAM secret ARN."
  value       = { for k, s in aws_secretsmanager_secret.kafka : k => s.arn }
}

output "app_secret_arns" {
  description = "<service>/<name> => secret ARN."
  value       = { for k, s in aws_secretsmanager_secret.app : k => s.arn }
}

output "redis_secret_arn" {
  description = "Redis AUTH token secret ARN (JSON key auth_token)."
  value       = aws_secretsmanager_secret.redis.arn
}

output "redis_auth_token" {
  description = "Redis AUTH token (for the replication group)."
  value       = random_password.redis.result
  sensitive   = true
}

output "debezium_secret_arn" {
  description = "Debezium credentials secret ARN."
  value       = try(aws_secretsmanager_secret.debezium[0].arn, null)
}

output "debezium_secret_name" {
  description = "Debezium credentials secret name (config provider reference)."
  value       = try(aws_secretsmanager_secret.debezium[0].name, null)
}

output "placeholder_secret_names" {
  description = "Secrets whose real value the owner must set."
  value       = [for k, d in local.app_placeholder : aws_secretsmanager_secret.app[k].name]
}
