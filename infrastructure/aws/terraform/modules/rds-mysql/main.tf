# RDS for MySQL 8.4: one instance hosting one database + user per service (created by the db-init one-off task), with
# the binlog settings Debezium needs: ROW format, FULL row image, GTID. Binlog retention (>= 72 h) is not a parameter
# on RDS; db-init sets it with CALL mysql.rds_set_configuration('binlog retention hours', 72). Binary logging itself
# is on whenever automated backups are (backup_retention_period >= 1).
# The master password is managed by RDS in Secrets Manager (never in Terraform state).

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

data "aws_iam_policy_document" "kms" {
  statement {
    sid       = "AccountAdministration"
    actions   = ["kms:*"]
    resources = ["*"]
    principals {
      type        = "AWS"
      identifiers = ["arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }
}

resource "aws_kms_key" "this" {
  description         = "${var.name} RDS storage, master secret and Performance Insights"
  enable_key_rotation = true
  policy              = data.aws_iam_policy_document.kms.json
  tags                = var.tags
}

resource "aws_kms_alias" "this" {
  name          = "alias/${var.name}-rds"
  target_key_id = aws_kms_key.this.key_id
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-mysql"
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "this" {
  name        = "${var.name}-mysql"
  description = "RDS MySQL of ${var.name}"
  vpc_id      = var.vpc_id
  tags        = merge(var.tags, { Name = "${var.name}-mysql" })
}

resource "aws_vpc_security_group_ingress_rule" "clients" {
  for_each = var.client_security_group_ids

  security_group_id            = aws_security_group.this.id
  description                  = "MySQL from ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  referenced_security_group_id = each.value
}

resource "aws_db_parameter_group" "this" {
  name        = "${var.name}-mysql84"
  family      = "mysql8.4"
  description = "grocery: Debezium binlog (ROW, FULL, GTID), utf8mb4, UTC, TLS only"

  dynamic "parameter" {
    for_each = merge({
      binlog_format            = { value = "ROW", apply = "immediate" }
      binlog_row_image         = { value = "FULL", apply = "immediate" }
      gtid-mode                = { value = "ON", apply = "pending-reboot" }
      enforce_gtid_consistency = { value = "ON", apply = "pending-reboot" }
      character_set_server     = { value = "utf8mb4", apply = "immediate" }
      collation_server         = { value = "utf8mb4_0900_ai_ci", apply = "immediate" }
      time_zone                = { value = "UTC", apply = "immediate" }
      require_secure_transport = { value = "1", apply = "immediate" }
      slow_query_log           = { value = "1", apply = "immediate" }
      long_query_time          = { value = "1", apply = "immediate" }
      log_output               = { value = "FILE", apply = "immediate" }
    }, var.extra_parameters)
    content {
      name         = parameter.key
      value        = parameter.value.value
      apply_method = parameter.value.apply
    }
  }

  tags = var.tags

  lifecycle {
    create_before_destroy = true
  }
}

data "aws_iam_policy_document" "monitoring_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "monitoring" {
  name               = "${var.name}-rds-monitoring"
  assume_role_policy = data.aws_iam_policy_document.monitoring_assume.json
  tags               = var.tags
}

resource "aws_iam_role_policy_attachment" "monitoring" {
  role       = aws_iam_role.monitoring.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

resource "aws_db_instance" "this" {
  identifier     = "${var.name}-mysql"
  engine         = "mysql"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.this.arn

  username                      = "grocery_admin"
  manage_master_user_password   = true
  master_user_secret_kms_key_id = aws_kms_key.this.arn

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  parameter_group_name   = aws_db_parameter_group.this.name
  publicly_accessible    = false
  multi_az               = var.multi_az
  ca_cert_identifier     = "rds-ca-rsa2048-g1"
  port                   = 3306

  iam_database_authentication_enabled = true

  backup_retention_period   = var.backup_retention_days
  backup_window             = "18:00-19:00"
  maintenance_window        = "sun:19:30-sun:20:30"
  copy_tags_to_snapshot     = true
  delete_automated_backups  = false
  deletion_protection       = var.deletion_protection
  skip_final_snapshot       = !var.deletion_protection
  final_snapshot_identifier = var.deletion_protection ? "${var.name}-mysql-final" : null

  auto_minor_version_upgrade      = true
  allow_major_version_upgrade     = false
  apply_immediately               = false
  enabled_cloudwatch_logs_exports = ["error", "slowquery"]

  monitoring_interval = 60
  monitoring_role_arn = aws_iam_role.monitoring.arn

  performance_insights_enabled          = var.performance_insights_enabled
  performance_insights_kms_key_id       = var.performance_insights_enabled ? aws_kms_key.this.arn : null
  performance_insights_retention_period = var.performance_insights_enabled ? 7 : null

  tags = var.tags
}
