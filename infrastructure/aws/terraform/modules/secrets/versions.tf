terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 6.73.0, < 7.0.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.9.0, < 4.0.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = ">= 4.4.0, < 5.0.0"
    }
  }
}
