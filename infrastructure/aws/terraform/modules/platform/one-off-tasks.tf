# One-off ECS tasks (aws ecs run-task, see the README): db-init, kafka-init, schema-init.
# Connector registration is not a task in AWS: the MSK Connect connectors are Terraform resources (modules/msk-connect).

locals {
  one_off      = var.catalog.one_off_tasks
  kafka_topics = var.catalog.kafka_topics

  db_units = [for k in local.ecs_names : k if local.svc[k].database != null]
  # DB_PASSWORD_ORDER_SERVICE ... (one variable per service database)
  db_password_vars = { for k in local.db_units : k => "DB_PASSWORD_${upper(replace(k, "-", "_"))}" }

  db_init_script = templatefile("${path.module}/templates/db-init.sh.tftpl", {
    db_host                = module.rds.address
    db_port                = module.rds.port
    binlog_retention_hours = 72
    databases = [for k in local.db_units : {
      name     = local.svc[k].database.name
      user     = local.svc[k].database.user
      variable = local.db_password_vars[k]
    }]
    debezium_databases = [for k in local.ecs_names : local.svc[k].database.name if local.svc[k].connector_file != null]
  })

  kafka_init_script = templatefile("${path.module}/templates/kafka-init.sh.tftpl", {
    bootstrap           = module.msk.bootstrap_brokers_scram
    replication_factor  = var.msk.replication_factor
    partitions          = var.msk.topic_partitions
    domain_topics       = local.kafka_topics.domain_topics
    domain_retention_ms = local.kafka_topics.domain_retention_ms
    dlt_partitions      = local.kafka_topics.dlt_partitions
    dlt_retention_ms    = local.kafka_topics.dlt_retention_ms
    extra_topics        = local.kafka_topics.extra_topics
  })

  schema_registry_listener_port = local.svc["schema-registry"].load_balancer.listener_port
}

module "db_init" {
  source = "../one-off-task"

  name        = "db-init"
  family      = "${local.name}-db-init"
  environment = var.environment
  account_id  = local.account
  image       = local.one_off["db-init"].image
  cpu         = local.one_off["db-init"].cpu
  memory      = local.one_off["db-init"].memory
  entry_point = ["bash", "-c"]
  command     = [local.db_init_script]

  cpu_architecture = var.cpu_architecture
  secrets = merge(
    {
      MYSQL_MASTER_USERNAME = "${module.rds.master_secret_arn}:username::"
      MYSQL_MASTER_PASSWORD = "${module.rds.master_secret_arn}:password::"
    },
    { for k in local.db_units : local.db_password_vars[k] => "${module.secrets.db_secret_arns[k]}:password::" },
    length(local.connector_configs) > 0 ? { DEBEZIUM_DB_PASSWORD = "${module.secrets.debezium_secret_arn}:password::" } : {},
  )
  secret_arns = concat(
    [module.rds.master_secret_arn],
    [for k in local.db_units : module.secrets.db_secret_arns[k]],
    length(local.connector_configs) > 0 ? [module.secrets.debezium_secret_arn] : [],
  )
  secret_kms_key_arns = [module.secrets.kms_key_arn, module.rds.kms_key_arn]

  log_retention_days = var.log_retention_days
  log_kms_key_arn    = aws_kms_key.logs.arn
  tags               = merge(local.tags, { Service = "db-init" })
}

module "kafka_init" {
  source = "../one-off-task"

  name        = "kafka-init"
  family      = "${local.name}-kafka-init"
  environment = var.environment
  account_id  = local.account
  image       = local.one_off["kafka-init"].image
  cpu         = local.one_off["kafka-init"].cpu
  memory      = local.one_off["kafka-init"].memory
  entry_point = ["bash", "-c"]
  command     = [local.kafka_init_script]

  cpu_architecture = var.cpu_architecture
  secrets = {
    KAFKA_SASL_USERNAME = "${module.secrets.kafka_secret_arns["kafka-admin"]}:username::"
    KAFKA_SASL_PASSWORD = "${module.secrets.kafka_secret_arns["kafka-admin"]}:password::"
  }
  secret_arns         = [module.secrets.kafka_secret_arns["kafka-admin"]]
  secret_kms_key_arns = [module.secrets.kms_key_arn]

  log_retention_days = var.log_retention_days
  log_kms_key_arn    = aws_kms_key.logs.arn
  tags               = merge(local.tags, { Service = "kafka-init" })
}

module "schema_init" {
  source = "../one-off-task"

  name               = "schema-init"
  family             = "${local.name}-schema-init"
  environment        = var.environment
  account_id         = local.account
  image              = "${local.ecr_registry}/${local.one_off["schema-init"].image_repository}:${aws_ssm_parameter.image_tag["schema-init"].insecure_value}"
  ecr_repository_arn = "arn:${local.partition}:ecr:${local.region}:${local.account}:repository/${local.one_off["schema-init"].image_repository}"
  ecr_kms_key_arn    = var.ecr_kms_key_arn
  cpu                = local.one_off["schema-init"].cpu
  memory             = local.one_off["schema-init"].memory

  cpu_architecture = var.cpu_architecture
  environment_variables = {
    SCHEMA_REGISTRY_URL  = "https://${var.api_origin_domain_name}:${local.schema_registry_listener_port}"
    SCHEMAS_DIR          = "/schemas"
    SCHEMA_COMPATIBILITY = "BACKWARD_TRANSITIVE"
  }

  log_retention_days = var.log_retention_days
  log_kms_key_arn    = aws_kms_key.logs.arn
  tags               = merge(local.tags, { Service = "schema-init" })
}
