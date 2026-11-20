# S3 backend (bucket, region, KMS key and lock table come from backend.hcl):
#   terraform init -backend-config=backend.hcl
terraform {
  backend "s3" {
    key          = "grocery/prod/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}
