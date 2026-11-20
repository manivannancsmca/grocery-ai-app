terraform {
  required_version = ">= 1.10.0, < 2.0.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.73"
    }
    ec = {
      source  = "elastic/ec"
      version = "~> 0.14.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "grocery"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# CloudFront certificates and WAF web ACLs live in us-east-1.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "grocery"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

# Elastic Cloud: the API key comes from the EC_API_KEY environment variable at plan/apply time.
provider "ec" {}
