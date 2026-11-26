# The ECS services (17 Spring Boot services + Schema Registry) and their image-tag parameters.

# The image tag each service runs. CI writes it after every successful deployment, so a Terraform apply that registers
# a new task definition revision (e.g. after an environment variable change) keeps the deployed image.
resource "aws_ssm_parameter" "image_tag" {
  for_each = toset(concat(
    [for k in local.ecs_names : k if local.svc[k].image_repository != null],
    ["schema-init"],
  ))

  name           = "/grocery/${var.environment}/${each.key}/image-tag"
  description    = "Image tag of ${each.key} currently deployed in ${var.environment} (written by CI)"
  type           = "String"
  insecure_value = var.initial_image_tag
  tags           = merge(local.tags, { Service = each.key })

  lifecycle {
    ignore_changes = [insecure_value]
  }
}

module "service" {
  source   = "../ecs-service"
  for_each = toset(local.ecs_names)

  name         = each.key
  service_name = "${local.name}-${each.key}"
  environment  = var.environment
  account_id   = local.account
  cluster_arn  = module.ecs_cluster.cluster_arn
  cluster_name = module.ecs_cluster.cluster_name

  image = (
    local.svc[each.key].image_repository != null
    ? "${local.ecr_registry}/${local.svc[each.key].image_repository}:${aws_ssm_parameter.image_tag[each.key].insecure_value}"
    : local.svc[each.key].external_image
  )
  ecr_repository_arn = (
    local.svc[each.key].image_repository != null
    ? "arn:${local.partition}:ecr:${local.region}:${local.account}:repository/${local.svc[each.key].image_repository}"
    : null
  )
  ecr_kms_key_arn = local.svc[each.key].image_repository != null ? var.ecr_kms_key_arn : null
  command         = local.svc[each.key].command

  container_port            = local.svc[each.key].port
  cpu                       = local.svc[each.key].cpu
  memory                    = local.svc[each.key].memory
  cpu_architecture          = var.cpu_architecture
  read_only_root_filesystem = local.svc[each.key].read_only_root
  container_health_check    = local.svc[each.key].container_health

  desired_count = local.svc[each.key].desired_count
  min_count     = local.svc[each.key].min_count
  max_count     = local.svc[each.key].max_count

  environment_variables = local.service_env[each.key]
  secrets               = local.service_secrets[each.key]
  secret_arns           = local.service_secret_arns[each.key]
  secret_kms_key_arns   = [module.secrets.kms_key_arn]
  # Foundation models named in the catalog's "bedrock_models" (ai-orchestrator-service: the knowledge base's embeddings)
  bedrock_model_arns = [
    for model in try(local.raw[each.key].bedrock_models, []) :
    "arn:${local.partition}:bedrock:${local.region}::foundation-model/${model}"
  ]

  subnet_ids                    = module.network.private_subnet_ids
  security_group_ids            = [module.ecs_cluster.tasks_security_group_id]
  capacity_provider_strategy    = local.capacity_provider_strategy
  service_connect_namespace_arn = module.ecs_cluster.service_connect_namespace_arn
  service_connect_timeouts      = local.svc[each.key].sc_timeouts

  load_balancer = local.svc[each.key].load_balancer == null ? null : {
    blue_target_group_arn         = module.alb.blue_green[each.key].blue_target_group_arn
    green_target_group_arn        = module.alb.blue_green[each.key].green_target_group_arn
    blue_target_group_arn_suffix  = module.alb.blue_green[each.key].blue_target_group_arn_suffix
    green_target_group_arn_suffix = module.alb.blue_green[each.key].green_target_group_arn_suffix
    production_listener_rule_arn  = module.alb.blue_green[each.key].production_listener_rule_arn
    test_listener_rule_arn        = module.alb.blue_green[each.key].test_listener_rule_arn
    alb_arn_suffix                = module.alb.blue_green[each.key].alb_arn_suffix
    infrastructure_role_arn       = module.alb.ecs_infrastructure_role_arn
  }

  deployment = {
    bake_time_minutes        = var.blue_green_bake_minutes
    canary_percent           = local.svc[each.key].canary_percent
    canary_bake_time_minutes = var.canary_bake_minutes
  }

  task_failure_metric = module.ecs_cluster.task_failure_metric
  alarm_topic_arn     = module.observability.alerts_topic_arn

  adot = {
    enabled                   = local.svc[each.key].adot
    image                     = var.adot_image
    memory                    = var.adot_memory
    config_parameter_arn      = module.observability.adot_config_parameter_arn
    amp_workspace_arn         = module.observability.amp_workspace_arn
    amp_remote_write_endpoint = module.observability.amp_remote_write_endpoint
    metrics_path              = local.svc[each.key].metrics_path
    metrics_port              = local.svc[each.key].metrics_port
  }

  enable_execute_command = var.enable_execute_command
  exec_kms_key_arn       = aws_kms_key.logs.arn
  log_retention_days     = var.log_retention_days
  log_kms_key_arn        = aws_kms_key.logs.arn
  tags                   = merge(local.tags, { Service = each.key })
}
