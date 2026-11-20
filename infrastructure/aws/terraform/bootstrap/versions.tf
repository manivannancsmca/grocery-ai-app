terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
  }

  # Local state on purpose: this creates the bucket every other state lives in.
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "grocery"
      Environment = "shared"
      ManagedBy   = "terraform"
    }
  }
}
