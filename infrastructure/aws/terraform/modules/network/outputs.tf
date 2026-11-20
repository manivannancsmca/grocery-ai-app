output "vpc_id" {
  description = "VPC id."
  value       = aws_vpc.this.id
}

output "vpc_cidr_block" {
  description = "VPC CIDR."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "Public subnets (NAT gateways)."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "Private subnets (tasks, data stores, internal ALB)."
  value       = aws_subnet.private[*].id
}

output "availability_zones" {
  description = "AZs in use."
  value       = local.azs
}

output "nat_public_ips" {
  description = "Egress IPs (allow-list them at Razorpay / an external LLM endpoint if needed)."
  value       = aws_eip.nat[*].public_ip
}
