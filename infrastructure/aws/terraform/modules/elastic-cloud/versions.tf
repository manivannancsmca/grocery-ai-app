terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.73.0, < 7.0.0"
    }
    ec = {
      source  = "elastic/ec"
      version = ">= 0.14.0, < 1.0.0"
    }
  }
}
