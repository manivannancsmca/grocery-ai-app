# Non-secret settings in Parameter Store (/grocery/<env>/...): the shared endpoints, and the deploy contract CI reads
# instead of hard-coding names. Image tags: services.tf; web bucket/distribution: modules/spa-hosting.

locals {
  deploy_contract = {
    environment  = var.environment
    region       = local.region
    cluster      = module.ecs_cluster.cluster_name
    ecr_registry = local.ecr_registry
    deploy_role  = local.deploy_role_enabled ? aws_iam_role.deployer[0].arn : null
    network = {
      subnets         = module.network.private_subnet_ids
      security_groups = [module.ecs_cluster.tasks_security_group_id]
    }
    services = {
      for k in local.ecs_names : k => {
        service        = module.service[k].service_name
        task_family    = module.service[k].task_family
        container      = module.service[k].app_container_name
        repository     = local.svc[k].image_repository
        image_tag_ssm  = local.svc[k].image_repository != null ? aws_ssm_parameter.image_tag[k].name : null
        log_group      = module.service[k].log_group_name
        port           = local.svc[k].port
        load_balanced  = local.svc[k].load_balancer != null
        test_listener  = local.svc[k].load_balancer != null ? "https://${var.api_origin_domain_name}:${local.svc[k].load_balancer.test_listener_port}" : null
        has_connector  = local.svc[k].connector_file != null
        rollback_alarm = module.service[k].rollback_alarm_names
      }
    }
    one_off_tasks = {
      db-init     = { task_family = module.db_init.family, log_group = module.db_init.log_group_name }
      kafka-init  = { task_family = module.kafka_init.family, log_group = module.kafka_init.log_group_name }
      schema-init = { task_family = module.schema_init.family, log_group = module.schema_init.log_group_name, image_tag_ssm = aws_ssm_parameter.image_tag["schema-init"].name }
    }
  }

  shared_parameters = {
    "db-host"                   = module.rds.address
    "db-port"                   = tostring(module.rds.port)
    "kafka-bootstrap-servers"   = module.msk.bootstrap_brokers_scram
    "schema-registry-url"       = "https://${var.api_origin_domain_name}:${local.schema_registry_listener_port}"
    "redis-host"                = module.redis.primary_endpoint
    "elasticsearch-url"         = local.elasticsearch_url == "" ? "none" : local.elasticsearch_url
    "ecs-cluster"               = module.ecs_cluster.cluster_name
    "msk-connect-plugin-bucket" = module.msk_connect.plugin_bucket
    "app-url"                   = local.app_url
  }
}

resource "aws_ssm_parameter" "shared" {
  for_each = local.shared_parameters

  name        = "/grocery/${var.environment}/shared/${each.key}"
  description = "grocery ${var.environment}: ${each.key}"
  type        = "String"
  value       = each.value
  tags        = local.tags
}

resource "aws_ssm_parameter" "deploy_contract" {
  name        = "/grocery/${var.environment}/deploy/contract"
  description = "Names CI uses to deploy grocery ${var.environment} (cluster, services, task families, containers, repositories)"
  type        = "String"
  tier        = "Intelligent-Tiering"
  value       = jsonencode(local.deploy_contract)
  tags        = local.tags
}
