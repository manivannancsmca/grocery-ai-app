# Offline plan of the whole environment with MOCK providers: no credentials, no AWS / Elastic Cloud API call.
# It evaluates everything `terraform validate` cannot (the catalog, tfvars, templates, the Debezium connector JSONs,
# for_each keys, IAM documents) and checks the deploy contract.
#   terraform test -var-file=<env>.tfvars        (validate.sh runs it for dev, staging and prod)

mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = {
      names = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
    }
  }
  mock_data "aws_region" {
    defaults = {
      region = "ap-south-1"
    }
  }
  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "111111111111"
    }
  }
  mock_data "aws_partition" {
    defaults = {
      partition = "aws"
    }
  }
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

mock_provider "aws" {
  alias = "us_east_1"
}

mock_provider "ec" {}

mock_provider "random" {}

mock_provider "tls" {}

run "plan" {
  command = plan

  assert {
    condition     = length(output.deploy_contract.services) == 18
    error_message = "Expected 18 ECS services (17 Spring Boot services + schema-registry)."
  }

  assert {
    condition     = output.deploy_contract.cluster == "grocery-${var.environment}-cluster"
    error_message = "Cluster name must be grocery-<env>-cluster."
  }

  assert {
    condition = alltrue([
      for k, s in output.deploy_contract.services :
      s.service == "grocery-${var.environment}-${k}" && s.task_family == s.service && s.container == "app"
    ])
    error_message = "Every ECS service must be grocery-<env>-<unit>, with the same task family and an 'app' container."
  }

  assert {
    condition     = length(output.debezium_connector_configs) == 12
    error_message = "Expected one Debezium connector per producing service (12)."
  }

  assert {
    condition = alltrue([
      for k, c in output.debezium_connector_configs :
      c["database.password"] == "$${secretsmanager:grocery/${var.environment}/debezium/db:password}" && !contains(keys(c), "name")
    ])
    error_message = "Connector credentials must come from the Secrets Manager config provider."
  }

  assert {
    condition     = length(output.placeholder_secrets) == 5
    error_message = "Expected the Razorpay, SMTP, Anthropic and both Elasticsearch (catalog-query, knowledge base) placeholder secrets."
  }
}
