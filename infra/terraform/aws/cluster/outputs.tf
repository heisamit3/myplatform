output "cluster_name" {
  value = module.eks.cluster_name
}

output "region" {
  value = var.region
}

output "kubeconfig_command" {
  description = "Adds the cluster to ~/.kube/config as context eks-myplatform (minikube stays the other context)."
  value       = "aws eks update-kubeconfig --region ${var.region} --name ${module.eks.cluster_name} --alias eks-${module.eks.cluster_name}"
}

output "vpc_id" {
  value = module.vpc.vpc_id
}
