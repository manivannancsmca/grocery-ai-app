# Internal ALB in the private subnets. CloudFront reaches it through a VPC origin (no public load balancer at all).
# Every load-balanced unit gets two target groups (blue/green), a production listener and a test listener, each with a
# forwarding rule that ECS re-weights during a native blue/green deployment:
#   api-gateway      production 443  (from CloudFront)            test 8443  (CI / VPN only)
#   schema-registry  production 8081 (tasks, schema-init, CI)     test 18081 (CI / VPN only)

data "aws_ec2_managed_prefix_list" "cloudfront" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

data "aws_partition" "current" {}

locals {
  # Listener ports: production and test of every app.
  listeners = merge(
    { for k, a in var.apps : "${k}-prod" => { app = k, port = a.listener_port, kind = "prod" } },
    { for k, a in var.apps : "${k}-test" => { app = k, port = a.test_listener_port, kind = "test" } },
  )
  short = { for k, a in var.apps : k => a.short_name }
}

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Internal ALB of ${var.name}"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-alb" })
}

resource "aws_vpc_security_group_ingress_rule" "cloudfront" {
  for_each = { for k, a in var.apps : k => a if a.from_cloudfront }

  security_group_id = aws_security_group.alb.id
  description       = "${each.key}: HTTPS from CloudFront (VPC origin)"
  ip_protocol       = "tcp"
  from_port         = each.value.listener_port
  to_port           = each.value.listener_port
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront.id
}

resource "aws_vpc_security_group_ingress_rule" "tasks" {
  for_each = { for k, a in var.apps : k => a if !a.from_cloudfront }

  security_group_id            = aws_security_group.alb.id
  description                  = "${each.key}: production listener from the ECS tasks (one-off tasks)"
  ip_protocol                  = "tcp"
  from_port                    = each.value.listener_port
  to_port                      = each.value.listener_port
  referenced_security_group_id = var.tasks_security_group_id
}

# Production (internal apps) and test listeners from CI / VPN networks.
resource "aws_vpc_security_group_ingress_rule" "operators" {
  for_each = {
    for pair in setproduct(keys(local.listeners), var.operator_cidrs) : "${pair[0]}|${pair[1]}" => {
      listener = local.listeners[pair[0]]
      cidr     = pair[1]
    }
    if local.listeners[pair[0]].kind == "test" || !var.apps[local.listeners[pair[0]].app].from_cloudfront
  }

  security_group_id = aws_security_group.alb.id
  description       = "${each.value.listener.app} ${each.value.listener.kind} listener from CI/VPN"
  ip_protocol       = "tcp"
  from_port         = each.value.listener.port
  to_port           = each.value.listener.port
  cidr_ipv4         = each.value.cidr
}

resource "aws_vpc_security_group_egress_rule" "targets" {
  for_each = var.apps

  security_group_id            = aws_security_group.alb.id
  description                  = "${each.key}: to the targets"
  ip_protocol                  = "tcp"
  from_port                    = each.value.target_port
  to_port                      = each.value.target_port
  referenced_security_group_id = var.tasks_security_group_id
}

resource "aws_vpc_security_group_ingress_rule" "tasks_from_alb" {
  for_each = var.apps

  security_group_id            = var.tasks_security_group_id
  description                  = "${each.key}: from the internal ALB"
  ip_protocol                  = "tcp"
  from_port                    = each.value.target_port
  to_port                      = each.value.target_port
  referenced_security_group_id = aws_security_group.alb.id
}

resource "aws_lb" "this" {
  name                       = "${var.name}-alb"
  internal                   = true
  load_balancer_type         = "application"
  security_groups            = [aws_security_group.alb.id]
  subnets                    = var.subnet_ids
  idle_timeout               = var.idle_timeout_seconds
  drop_invalid_header_fields = true
  desync_mitigation_mode     = "defensive"
  enable_deletion_protection = var.deletion_protection
  enable_http2               = true

  access_logs {
    enabled = true
    bucket  = var.access_logs_bucket
    prefix  = "alb"
  }

  tags = var.tags
}

resource "aws_lb_target_group" "this" {
  for_each = {
    for pair in setproduct(keys(var.apps), ["blue", "green"]) : "${pair[0]}-${pair[1]}" => {
      app   = pair[0]
      color = pair[1]
    }
  }

  name                 = "${var.name}-${local.short[each.value.app]}-${each.value.color}"
  port                 = var.apps[each.value.app].target_port
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = var.vpc_id
  deregistration_delay = 30

  health_check {
    path                = var.apps[each.value.app].health_check_path
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }

  tags = merge(var.tags, { Service = each.value.app, Color = each.value.color })
}

resource "aws_lb_listener" "this" {
  for_each = local.listeners

  load_balancer_arn = aws_lb.this.arn
  port              = each.value.port
  protocol          = "HTTPS"
  ssl_policy        = var.ssl_policy
  certificate_arn   = var.certificate_arn

  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "application/json"
      message_body = "{\"code\":\"NOT_FOUND\"}"
      status_code  = "404"
    }
  }

  tags = var.tags
}

# The rules ECS re-weights. Initially production -> blue, test -> green.
resource "aws_lb_listener_rule" "this" {
  for_each = local.listeners

  listener_arn = aws_lb_listener.this[each.key].arn
  priority     = 100

  action {
    type = "forward"
    forward {
      target_group {
        arn    = aws_lb_target_group.this["${each.value.app}-blue"].arn
        weight = each.value.kind == "prod" ? 100 : 0
      }
      target_group {
        arn    = aws_lb_target_group.this["${each.value.app}-green"].arn
        weight = each.value.kind == "prod" ? 0 : 100
      }
    }
  }

  condition {
    path_pattern {
      values = ["/*"]
    }
  }

  tags = var.tags

  lifecycle {
    ignore_changes = [action] # ECS moves the weights during blue/green deployments
  }
}

# Role ECS assumes to re-weight the listener rules (native blue/green).
data "aws_iam_policy_document" "infrastructure_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_infrastructure" {
  name               = "${var.name}-ecs-infra-lb"
  description        = "ECS blue/green: lets ECS shift traffic between the target groups of ${aws_lb.this.name}"
  assume_role_policy = data.aws_iam_policy_document.infrastructure_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "ecs_infrastructure" {
  role       = aws_iam_role.ecs_infrastructure.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/AmazonECSInfrastructureRolePolicyForLoadBalancers"
}
