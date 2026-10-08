module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "21.29.0"

  name               = var.cluster_name
  kubernetes_version = var.kubernetes_version

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.public_subnets

  # Reachable from my IP only; every request still needs IAM credentials.
  endpoint_public_access       = true
  endpoint_public_access_cidrs = var.api_allowed_cidrs

  # Access entries (EKS API) instead of the old aws-auth ConfigMap. Whoever runs terraform apply is cluster admin.
  authentication_mode                      = "API"
  enable_cluster_creator_admin_permissions = true

  # Ephemeral cluster: things that bill per month or outlive a destroy stay off.
  # - Control plane logs go to CloudWatch (ingestion + storage cost).
  # - A customer-managed KMS key costs $1/month and waits 7-30 days to be deleted. EKS already encrypts
  #   Secrets with an AWS-owned key by default.
  enabled_log_types           = []
  create_cloudwatch_log_group = false
  create_kms_key              = false
  encryption_config           = null
  deletion_protection         = false

  addons = {
    # Pod networking and pod identity must be up before nodes join, or nodes stay NotReady.
    vpc-cni                = { before_compute = true }
    eks-pod-identity-agent = { before_compute = true }
    kube-proxy             = {}
    coredns                = {}
    # PersistentVolumes on EBS (Postgres, Kafka, MongoDB). Gets AWS permissions through EKS Pod Identity.
    aws-ebs-csi-driver = {
      pod_identity_association = [{
        role_arn        = aws_iam_role.ebs_csi.arn
        service_account = "ebs-csi-controller-sa"
      }]
    }
  }

  eks_managed_node_groups = {
    default = {
      ami_type       = "AL2023_x86_64_STANDARD"
      capacity_type  = "SPOT"
      instance_types = var.node_instance_types

      min_size     = 1
      max_size     = 3
      desired_size = var.node_desired_size

      block_device_mappings = {
        xvda = {
          device_name = "/dev/xvda"
          ebs = {
            volume_size           = 20
            volume_type           = "gp3"
            encrypted             = true
            delete_on_termination = true
          }
        }
      }
    }
  }
}
