# Elastic Cloud deployment (catalog search) reachable only through AWS PrivateLink:
#   VPC interface endpoint to Elastic's regional endpoint service + private hosted zone vpce.<region>.aws.elastic-cloud.com
#   -> traffic filter (type vpce) that admits only this endpoint -> the deployment.
# The application authenticates with an API key the owner creates in Kibana and stores in
# grocery/<env>/catalog-query-service/elasticsearch (placeholder secret, JSON key api_key).
# The ec provider reads EC_API_KEY from the environment at plan/apply time.

data "aws_region" "current" {}

locals {
  region           = data.aws_region.current.region
  private_zone     = "vpce.${local.region}.aws.elastic-cloud.com"
  private_es_url   = "https://${ec_deployment.this.alias}.es.${local.private_zone}:443"
  private_kibana   = "https://${ec_deployment.this.alias}.kb.${local.private_zone}:443"
  traffic_rule_ids = [ec_deployment_traffic_filter.privatelink.id]
}

resource "aws_security_group" "endpoint" {
  name        = "${var.name}-elastic-privatelink"
  description = "PrivateLink endpoint to Elastic Cloud"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-elastic-privatelink" })
}

resource "aws_vpc_security_group_ingress_rule" "endpoint" {
  for_each = toset(["443", "9243"])

  security_group_id            = aws_security_group.endpoint.id
  description                  = "Elasticsearch HTTPS (${each.value}) from the ECS tasks"
  ip_protocol                  = "tcp"
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  referenced_security_group_id = var.client_security_group_id
}

resource "aws_vpc_endpoint" "elastic" {
  vpc_id              = var.vpc_id
  service_name        = var.privatelink_service_name
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = false
  subnet_ids          = var.subnet_ids
  security_group_ids  = [aws_security_group.endpoint.id]
  tags                = merge(var.tags, { Name = "${var.name}-elastic-cloud" })
}

resource "aws_route53_zone" "elastic" {
  name    = local.private_zone
  comment = "Elastic Cloud PrivateLink names of ${var.name}"

  vpc {
    vpc_id = var.vpc_id
  }

  tags = var.tags
}

resource "aws_route53_record" "wildcard" {
  zone_id = aws_route53_zone.elastic.zone_id
  name    = "*.${local.private_zone}"
  type    = "CNAME"
  ttl     = 60
  records = [aws_vpc_endpoint.elastic.dns_entry[0].dns_name]
}

resource "ec_deployment_traffic_filter" "privatelink" {
  name               = "${var.name}-privatelink"
  description        = "Only the ${var.name} VPC endpoint"
  region             = var.elastic_region
  type               = "vpce"
  include_by_default = false

  rule {
    source      = aws_vpc_endpoint.elastic.id
    description = "VPC endpoint of ${var.name}"
  }
}

resource "ec_deployment" "this" {
  name                   = var.name
  region                 = var.elastic_region
  version                = var.elastic_version
  deployment_template_id = var.deployment_template_id
  traffic_filter         = local.traffic_rule_ids

  elasticsearch = {
    hot = {
      size       = var.hot_size
      zone_count = var.zone_count
      autoscaling = {
        max_size = var.hot_max_size
      }
    }
  }

  kibana = {
    size       = "1g"
    zone_count = 1
  }

  tags = var.tags
}

# The deployment's "elastic" superuser: only for the owner to create the application API key; no service reads it.
resource "aws_secretsmanager_secret" "superuser" {
  name                    = "grocery/${var.environment}/elasticsearch/elastic-superuser"
  description             = "Elastic Cloud superuser of ${var.name} (create the catalog API key with it)"
  kms_key_id              = var.secrets_kms_key_arn
  recovery_window_in_days = var.recovery_window_days
  tags                    = var.tags
}

resource "aws_secretsmanager_secret_version" "superuser" {
  secret_id = aws_secretsmanager_secret.superuser.id
  secret_string = jsonencode({
    username = ec_deployment.this.elasticsearch_username
    password = ec_deployment.this.elasticsearch_password
    url      = local.private_es_url
  })
}
