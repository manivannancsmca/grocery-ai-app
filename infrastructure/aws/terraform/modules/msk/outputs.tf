output "cluster_arn" {
  description = "Cluster ARN."
  value       = aws_msk_cluster.this.arn
}

output "cluster_name" {
  description = "Cluster name (CloudWatch dimension 'Cluster Name')."
  value       = aws_msk_cluster.this.cluster_name
}

output "cluster_uuid" {
  description = "Cluster UUID (topic/group ARNs for IAM policies)."
  value       = aws_msk_cluster.this.cluster_uuid
}

output "bootstrap_brokers_scram" {
  description = "KAFKA_BOOTSTRAP_SERVERS for the services (SASL/SCRAM, TLS, 9096)."
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_scram
}

output "bootstrap_brokers_iam" {
  description = "Bootstrap servers for MSK Connect (IAM, TLS, 9098)."
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_iam
}

output "security_group_id" {
  description = "Broker security group."
  value       = aws_security_group.this.id
}
