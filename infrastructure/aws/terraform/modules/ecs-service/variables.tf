variable "name" {
  description = "Unit name from services.auto.tfvars.json (order-service). Also the Service Connect name."
  type        = string
}

variable "service_name" {
  description = "ECS service name and task definition family: grocery-<env>-<name>."
  type        = string
}

variable "environment" {
  description = "dev | staging | prod."
  type        = string
}

variable "account_id" {
  description = "AWS account id (trust policy conditions)."
  type        = string
}

variable "cluster_arn" {
  description = "ECS cluster ARN."
  type        = string
}

variable "cluster_name" {
  description = "ECS cluster name."
  type        = string
}

variable "image" {
  description = "Full image reference of the app container."
  type        = string
}

variable "ecr_repository_arn" {
  description = "ECR repository the execution role may pull from (null for public images)."
  type        = string
  default     = null
}

variable "ecr_kms_key_arn" {
  description = "KMS key of the ECR repositories (null when not KMS-encrypted)."
  type        = string
  default     = null
}

variable "command" {
  description = "Command override of the app container (null = image default)."
  type        = list(string)
  default     = null
}

variable "container_port" {
  description = "Application port."
  type        = number
}

variable "cpu" {
  description = "Task CPU units (256, 512, 1024 ...)."
  type        = number
}

variable "memory" {
  description = "Task memory (MiB); the app container gets what the sidecar and the Service Connect agent leave."
  type        = number
}

variable "cpu_architecture" {
  description = "X86_64 or ARM64 (the images must exist for it)."
  type        = string

  validation {
    condition     = contains(["X86_64", "ARM64"], var.cpu_architecture)
    error_message = "cpu_architecture must be X86_64 or ARM64."
  }
}

variable "service_connect_memory_reserve" {
  description = "MiB left for the Service Connect agent that ECS injects."
  type        = number
  default     = 128
}

variable "read_only_root_filesystem" {
  description = "Read-only root filesystem for the app container (a writable /tmp volume is mounted)."
  type        = bool
  default     = true
}

variable "container_health_check" {
  description = "CMD-SHELL health check of the app container."
  type        = string
}

variable "health_check_start_period" {
  description = "Seconds before failing health checks count (JVM start-up, Flyway)."
  type        = number
  default     = 180
}

variable "desired_count" {
  description = "Initial desired count (autoscaling owns it afterwards)."
  type        = number
}

variable "min_count" {
  description = "Autoscaling minimum."
  type        = number
}

variable "max_count" {
  description = "Autoscaling maximum."
  type        = number
}

variable "autoscaling" {
  description = "Target tracking targets."
  type = object({
    cpu_target_percent           = number
    requests_per_task_per_minute = number
  })
  default = {
    cpu_target_percent           = 60
    requests_per_task_per_minute = 3000
  }
}

variable "environment_variables" {
  description = "Plain environment variables of the app container."
  type        = map(string)
}

variable "secrets" {
  description = "Environment variable => valueFrom (Secrets Manager ARN[:json-key::] or SSM parameter ARN)."
  type        = map(string)
}

variable "secret_arns" {
  description = "Secrets Manager secret ARNs the execution role may read (exactly those referenced in secrets)."
  type        = list(string)
}

variable "ssm_parameter_arns" {
  description = "SSM parameter ARNs the execution role may read."
  type        = list(string)
  default     = []
}

variable "secret_kms_key_arns" {
  description = "KMS keys encrypting the injected secrets and parameters."
  type        = list(string)
}

variable "subnet_ids" {
  description = "Private subnets of the tasks."
  type        = list(string)
}

variable "security_group_ids" {
  description = "Security groups of the tasks."
  type        = list(string)
}

variable "capacity_provider_strategy" {
  description = "Fargate capacity providers."
  type = list(object({
    capacity_provider = string
    weight            = number
    base              = number
  }))
}

variable "service_connect_namespace_arn" {
  description = "Service Connect namespace."
  type        = string
}

variable "service_connect_timeouts" {
  description = "Service Connect timeouts of this service as a server (null = AWS defaults: 15 s per request). Long-lived streams (SSE, model calls) need per_request_seconds = 0."
  type = object({
    idle_seconds        = number
    per_request_seconds = number
  })
  default = null
}

variable "load_balancer" {
  description = "ALB wiring for blue/green (null = internal service, blue/green through Service Connect)."
  type = object({
    blue_target_group_arn         = string
    green_target_group_arn        = string
    blue_target_group_arn_suffix  = string
    green_target_group_arn_suffix = string
    production_listener_rule_arn  = string
    test_listener_rule_arn        = string
    alb_arn_suffix                = string
    infrastructure_role_arn       = string
  })
  default = null
}

variable "deployment" {
  description = "Blue/green settings. canary_percent != null switches the strategy to CANARY (shift that share, bake, then 100 %)."
  type = object({
    bake_time_minutes        = number
    canary_percent           = optional(number)
    canary_bake_time_minutes = optional(number, 5)
    test_traffic_header      = optional(string)
  })
}

variable "alarm_thresholds" {
  description = "Rollback alarm thresholds."
  type = object({
    error_rate_percent = number
    min_requests       = number
    task_failures      = number
  })
  default = {
    error_rate_percent = 5
    min_requests       = 20
    task_failures      = 1
  }
}

variable "task_failure_metric" {
  description = "Namespace and metric name published by the ecs-cluster module."
  type = object({
    namespace = string
    name      = string
  })
}

variable "alarm_topic_arn" {
  description = "SNS topic for alarm notifications."
  type        = string
}

variable "adot" {
  description = "ADOT collector sidecar."
  type = object({
    enabled                   = bool
    image                     = string
    memory                    = number
    config_parameter_arn      = string
    amp_workspace_arn         = string
    amp_remote_write_endpoint = string
    metrics_path              = optional(string)
    metrics_port              = optional(number) # a separate management port (api-gateway: 9080); default the container port
  })
}

variable "enable_execute_command" {
  description = "Allow ECS Exec (debugging shells)."
  type        = bool
  default     = false
}

variable "exec_kms_key_arn" {
  description = "KMS key of ECS Exec sessions."
  type        = string
  default     = null
}

variable "bedrock_model_arns" {
  description = "Amazon Bedrock foundation models the application may invoke (ai-orchestrator-service's knowledge base embeddings); none by default."
  type        = list(string)
  default     = []
}

variable "log_retention_days" {
  description = "Retention of /grocery/<env>/<name>."
  type        = number
}

variable "log_kms_key_arn" {
  description = "KMS key of the log group."
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
}
