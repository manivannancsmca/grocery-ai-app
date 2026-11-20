output "primary_endpoint" {
  description = "REDIS_HOST."
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "port" {
  description = "REDIS_PORT."
  value       = aws_elasticache_replication_group.this.port
}

output "replication_group_id" {
  description = "Replication group id (CloudWatch dimension)."
  value       = aws_elasticache_replication_group.this.id
}

output "member_clusters" {
  description = "Node ids (CloudWatch dimension CacheClusterId)."
  value       = aws_elasticache_replication_group.this.member_clusters
}
