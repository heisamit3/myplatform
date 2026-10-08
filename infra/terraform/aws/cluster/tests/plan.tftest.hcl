# terraform test: plans the whole stack against mocked providers, so it needs no AWS account or credentials
# (CI runs it). It catches wrong module inputs and checks the cost guardrails from ADR 0020.

mock_provider "aws" {
  mock_data "aws_availability_zones" {
    defaults = {
      names = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
    }
  }

  # The provider still validates ARNs, so identity data needs realistic values instead of random strings.
  mock_data "aws_partition" {
    defaults = {
      partition  = "aws"
      dns_suffix = "amazonaws.com"
    }
  }

  mock_data "aws_caller_identity" {
    defaults = {
      account_id = "123456789012"
      arn        = "arn:aws:iam::123456789012:user/terraform"
    }
  }

  mock_data "aws_iam_session_context" {
    defaults = {
      issuer_arn = "arn:aws:iam::123456789012:user/terraform"
    }
  }

  # The EKS module renders IAM policy JSON; random strings wouldn't parse.
  mock_data "aws_iam_policy_document" {
    defaults = {
      json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}"
    }
  }
}

mock_provider "tls" {}

variables {
  api_allowed_cidrs = ["203.0.113.7/32"]
}

run "plan_has_cost_guardrails" {
  command = plan

  assert {
    condition     = length(module.vpc.natgw_ids) == 0
    error_message = "No NAT gateway (ADR 0020)."
  }

  assert {
    condition     = length(module.vpc.public_subnets) == 2 && length(module.vpc.private_subnets) == 0
    error_message = "Two public subnets, no private ones."
  }

  assert {
    condition     = module.eks.kms_key_arn == null && module.eks.cloudwatch_log_group_name == null
    error_message = "No customer-managed KMS key and no CloudWatch log group: both bill or linger after destroy."
  }

  assert {
    condition     = endswith(aws_iam_role_policy_attachment.ebs_csi.policy_arn, ":policy/service-role/AmazonEBSCSIDriverPolicy")
    error_message = "EBS CSI role must use the AWS managed driver policy."
  }
}

run "api_cidrs_are_required" {
  command = plan

  variables {
    api_allowed_cidrs = []
  }

  expect_failures = [var.api_allowed_cidrs]
}
