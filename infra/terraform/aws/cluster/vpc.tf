# Public subnets only, no NAT gateway (ADR 0020). A NAT gateway costs ~$0.06/h plus data, more than the nodes.
# Nodes get public IPs for egress (pulling images); inbound is still closed by their security group.

data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  # EKS needs subnets in at least two AZs. Two (not three): EBS volumes are bound to one AZ,
  # so fewer AZs means a rescheduled stateful pod is less likely to land away from its volume.
  azs = slice(data.aws_availability_zones.available.names, 0, 2)
}

module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "6.7.3"

  name = var.cluster_name
  cidr = "10.0.0.0/16"

  azs            = local.azs
  public_subnets = ["10.0.0.0/20", "10.0.16.0/20"]

  map_public_ip_on_launch = true
  enable_nat_gateway      = false
  enable_dns_hostnames    = true
  enable_dns_support      = true

  # Lets the AWS load balancer integration find these subnets for internet-facing LBs.
  public_subnet_tags = {
    "kubernetes.io/role/elb" = "1"
  }
}
