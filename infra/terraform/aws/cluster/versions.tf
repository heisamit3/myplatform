terraform {
  # 1.10+ for S3-native state locking (use_lockfile), so no DynamoDB table is needed.
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.68"
    }
  }

  # bucket and region come from backend.hcl (gitignored, it holds the account ID):
  #   terraform init -backend-config=backend.hcl
  backend "s3" {
    key          = "aws/cluster.tfstate"
    use_lockfile = true
    encrypt      = true
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "myplatform"
      ManagedBy = "terraform"
      Stack     = "cluster"
    }
  }
}
