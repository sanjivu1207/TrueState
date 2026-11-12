terraform {
  # 1.11+ for write-only arguments: the database password is generated as an
  # ephemeral value and never written to Terraform state.
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }

  # Bucket comes from infra/bootstrap, passed at init time:
  #   terraform init -backend-config="bucket=truestate-tfstate-<account id>"
  backend "s3" {
    key          = "truestate/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true # S3-native state locking, no DynamoDB table needed
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project   = "truestate"
      ManagedBy = "terraform"
    }
  }
}
