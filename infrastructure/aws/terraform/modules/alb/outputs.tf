output "arn" {
  description = "ALB ARN (CloudFront VPC origin)."
  value       = aws_lb.this.arn
}

output "arn_suffix" {
  description = "ALB ARN suffix (CloudWatch dimension)."
  value       = aws_lb.this.arn_suffix
}

output "dns_name" {
  description = "Internal DNS name."
  value       = aws_lb.this.dns_name
}

output "zone_id" {
  description = "Hosted zone of the ALB (alias records)."
  value       = aws_lb.this.zone_id
}

output "security_group_id" {
  description = "ALB security group."
  value       = aws_security_group.alb.id
}

output "ecs_infrastructure_role_arn" {
  description = "Role ECS uses to shift traffic during blue/green."
  value       = aws_iam_role.ecs_infrastructure.arn
}

output "blue_green" {
  description = "Per app: target groups and listener rules for the ecs-service module."
  value = {
    for k, a in var.apps : k => {
      blue_target_group_arn         = aws_lb_target_group.this["${k}-blue"].arn
      green_target_group_arn        = aws_lb_target_group.this["${k}-green"].arn
      blue_target_group_arn_suffix  = aws_lb_target_group.this["${k}-blue"].arn_suffix
      green_target_group_arn_suffix = aws_lb_target_group.this["${k}-green"].arn_suffix
      production_listener_rule_arn  = aws_lb_listener_rule.this["${k}-prod"].arn
      test_listener_rule_arn        = aws_lb_listener_rule.this["${k}-test"].arn
      alb_arn_suffix                = aws_lb.this.arn_suffix
      infrastructure_role_arn       = aws_iam_role.ecs_infrastructure.arn
      listener_port                 = a.listener_port
      test_listener_port            = a.test_listener_port
    }
  }
}
