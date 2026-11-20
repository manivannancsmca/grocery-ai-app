variable "environment" {
  description = "dev | staging | prod."
  type        = string
}

variable "services" {
  description = "Per unit: its MySQL user (null = no database), whether it is a Kafka (SCRAM) user, and its own secrets (type placeholder | random_password | rsa_pkcs8 | kafka_jaas)."
  type = map(object({
    database_user = string
    kafka         = bool
    app_secrets = map(object({
      type = string
      keys = list(string)
    }))
  }))

  validation {
    condition = alltrue(flatten([
      for s in values(var.services) : [
        for d in values(s.app_secrets) : contains(["placeholder", "random_password", "rsa_pkcs8", "kafka_jaas"], d.type)
      ]
    ]))
    error_message = "app_secrets type must be placeholder, random_password, rsa_pkcs8 or kafka_jaas."
  }
}

variable "extra_kafka_users" {
  description = "SCRAM users that are not services (kafka-admin for the kafka-init task)."
  type        = list(string)
  default     = ["kafka-admin"]
}

variable "debezium_enabled" {
  description = "Create the Debezium database credentials."
  type        = bool
  default     = true
}

variable "recovery_window_days" {
  description = "Days a deleted secret can be restored (0 = immediate deletion)."
  type        = number
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
}
