# One-time setup: the S3 bucket that holds the main stack's Terraform state.
#
# This tiny config keeps its own state locally (it is one bucket, and you can
# re-import it if that file is lost). Run it once, before `infra/`:
#
#   cd infra/bootstrap && terraform init && terraform apply
#
# Cost: S3 storage for a few KB of state, effectively $0.

terraform {
  required_version = ">= 1.11"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

variable "aws_region" {
  type    = string
  default = "us-east-1"
}

provider "aws" {
  region = var.aws_region
}

data "aws_caller_identity" "current" {}

resource "aws_s3_bucket" "tfstate" {
  # Account ID in the name keeps it globally unique without guessing.
  bucket = "truestate-tfstate-${data.aws_caller_identity.current.account_id}"

  # `terraform destroy` here should only succeed once the main stack is gone;
  # force_destroy lets it remove the old state versions at that point.
  force_destroy = true
}

resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  versioning_configuration {
    status = "Enabled" # recover from a bad apply by restoring an older state
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket                  = aws_s3_bucket.tfstate.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

output "state_bucket" {
  value = aws_s3_bucket.tfstate.bucket
}
