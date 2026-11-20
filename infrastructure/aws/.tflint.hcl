# tflint configuration for infrastructure/aws/terraform (run by validate.sh, in Docker).
# Every disabled rule is listed here with its reason; nothing else is suppressed.

config {
  format           = "compact"
  call_module_type = "local" # follow ../../modules/* so the AWS rules see the tfvars values (instance types ...)
  force            = false
}

plugin "terraform" {
  enabled = true
  preset  = "all"
}

plugin "aws" {
  enabled = true
  version = "0.50.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
}

# Reason: modules and roots keep the standard main/variables/outputs/versions layout, but the platform composition is
# split by concern (services.tf, one-off-tasks.tf, ci.tf, ssm.tf); the rule only accepts the three standard files.
rule "terraform_standard_module_structure" {
  enabled = false
}
