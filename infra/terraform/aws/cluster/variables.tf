variable "region" {
  description = "AWS region. Mumbai: close to me, and cheap spot capacity."
  type        = string
  default     = "ap-south-1"
}

variable "cluster_name" {
  description = "EKS cluster name, also the prefix for the VPC and IAM roles."
  type        = string
  default     = "myplatform"
}

variable "kubernetes_version" {
  description = "EKS Kubernetes minor version. Must be in standard support: extended support bills the control plane 6x."
  type        = string
  default     = "1.36"
}

variable "api_allowed_cidrs" {
  description = "CIDRs allowed to reach the public Kubernetes API endpoint, e.g. my IP as /32 (curl -s https://checkip.amazonaws.com)."
  type        = list(string)

  validation {
    condition     = length(var.api_allowed_cidrs) > 0 && alltrue([for c in var.api_allowed_cidrs : can(cidrhost(c, 0))])
    error_message = "api_allowed_cidrs needs at least one valid CIDR, e.g. [\"203.0.113.7/32\"]."
  }
}

variable "node_instance_types" {
  description = "Spot candidates, all 2 vCPU / 8 GiB. Several types = more spot pools = fewer interruptions. No burstable T types: their CPU credits can bill extra."
  type        = list(string)
  default     = ["m6a.large", "m6i.large", "m5a.large", "m5.large"]
}

variable "node_desired_size" {
  description = "Number of worker nodes. 2 x 8 GiB fits the full stack (services, Kafka, MongoDB, Postgres, Argo CD, monitoring)."
  type        = number
  default     = 2
}
