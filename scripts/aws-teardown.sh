#!/usr/bin/env bash
# Tears down the ephemeral EKS environment without leaving anything that bills (runbook: docs/runbook-aws-teardown.md).
# Kubernetes creates some AWS resources itself (load balancers for LoadBalancer Services, EBS volumes for PVCs).
# They aren't in Terraform state, so `terraform destroy` alone would orphan them (and an LB's network interfaces
# in the subnets make the VPC deletion fail). Order:
#   1. delete Argo CD Applications (so GitOps doesn't recreate what we delete next)
#   2. delete LoadBalancer Services, wait until AWS has removed the load balancers
#   3. delete the workload namespaces (pods + PVCs), wait until every PersistentVolume (= EBS volume) is gone
#   4. terraform destroy
#   5. check AWS for leftovers
# Usage: ./scripts/aws-teardown.sh [--skip-k8s] [--yes]
#   --skip-k8s  cluster already unreachable/gone: only destroy + leftover check
#   --yes       terraform destroy -auto-approve (default: Terraform asks)
set -euo pipefail
cd "$(dirname "$0")/.."

skip_k8s=false auto_approve=()
for arg in "$@"; do
  case "$arg" in
    --skip-k8s) skip_k8s=true ;;
    --yes) auto_approve=(-auto-approve) ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

for tool in terraform aws kubectl; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }
done

tf_dir=infra/terraform/aws/cluster
cluster="$(terraform -chdir="$tf_dir" output -raw cluster_name)"
region="$(terraform -chdir="$tf_dir" output -raw region)"
vpc_id="$(terraform -chdir="$tf_dir" output -raw vpc_id)"
# Every kubectl call names the EKS context explicitly, so this script can never touch minikube.
kctx="eks-$cluster"
k() { kubectl --context "$kctx" "$@"; }

echo "cluster=$cluster region=$region vpc=$vpc_id context=$kctx"

# wait_until DESCRIPTION SECONDS COMMAND...: re-runs COMMAND every 10 s until it succeeds.
wait_until() {
  local what="$1" timeout="$2"; shift 2
  local start=$SECONDS
  until "$@"; do
    if (( SECONDS - start > timeout )); then
      echo "timed out after ${timeout}s waiting for: $what" >&2
      return 1
    fi
    sleep 10
  done
}

aws_lbs_in_vpc() {
  aws elbv2 describe-load-balancers --region "$region" \
    --query "LoadBalancers[?VpcId=='$vpc_id'].LoadBalancerName" --output text
  aws elb describe-load-balancers --region "$region" \
    --query "LoadBalancerDescriptions[?VPCId=='$vpc_id'].LoadBalancerName" --output text
}
no_aws_lbs() { [ -z "$(aws_lbs_in_vpc | tr -d '[:space:]')" ]; }
no_k8s_lbs() { [ -z "$(k get svc -A -o jsonpath='{range .items[?(@.spec.type=="LoadBalancer")]}{.metadata.name}{end}')" ]; }
no_pvs() { [ -z "$(k get pv -o name)" ]; }

if [ "$skip_k8s" = false ]; then
  k version >/dev/null || { echo "can't reach $kctx (aws eks update-kubeconfig ...? or use --skip-k8s)" >&2; exit 1; }

  echo "== 1. Argo CD Applications"
  if k get crd applications.argoproj.io >/dev/null 2>&1; then
    # The root app (app of apps) first: while it exists, selfHeal recreates every child we delete.
    # It has no finalizer, so deleting it leaves the children in place for the next step.
    for app in $(k -n argocd get applications.argoproj.io -o name | grep '/root-' || true); do
      k -n argocd delete "$app" --wait --timeout=2m
    done
    # The children's finalizer deletes what each app deployed (cascade) before the Application itself goes away.
    k -n argocd delete applications.argoproj.io --all --wait --timeout=5m
  else
    echo "Argo CD not installed"
  fi

  echo "== 2. LoadBalancer Services"
  k get svc -A -o jsonpath='{range .items[?(@.spec.type=="LoadBalancer")]}{.metadata.namespace}{" "}{.metadata.name}{"\n"}{end}' |
    while read -r ns name; do
      [ -n "$name" ] && k -n "$ns" delete svc "$name" --wait --timeout=5m
    done
  wait_until "Kubernetes LoadBalancer Services gone" 300 no_k8s_lbs
  # Deleting the Service starts the LB deletion; AWS takes a minute or two more.
  wait_until "AWS load balancers in $vpc_id gone" 600 no_aws_lbs
  echo "no load balancers left"

  echo "== 3. Workload namespaces (pods, PVCs)"
  for ns in $(k get ns -o jsonpath='{.items[*].metadata.name}'); do
    case "$ns" in default | kube-system | kube-public | kube-node-lease) continue ;; esac
    k delete ns "$ns" --wait=false
  done
  # PVCs left in default (none expected, but they'd keep their EBS volume).
  k -n default delete pvc --all --wait=false
  # A PV disappears only after the CSI driver has deleted its EBS volume (reclaimPolicy Delete).
  wait_until "PersistentVolumes (EBS volumes) deleted" 600 no_pvs
  echo "no persistent volumes left"
fi

echo "== 4. terraform destroy"
terraform -chdir="$tf_dir" destroy "${auto_approve[@]}"

echo "== 5. Leftover check ($region)"
leftovers=0
report() {
  local what="$1" found="$2"
  if [ -n "$(printf '%s' "$found" | tr -d '[:space:]')" ]; then
    echo "LEFTOVER $what: $found"
    leftovers=1
  else
    echo "ok: no $what"
  fi
}
report "EKS cluster" "$(aws eks list-clusters --region "$region" --query "clusters[?@=='$cluster']" --output text)"
report "EC2 instances" "$(aws ec2 describe-instances --region "$region" \
  --filters "Name=tag:eks:cluster-name,Values=$cluster" "Name=instance-state-name,Values=pending,running,stopping,stopped" \
  --query 'Reservations[].Instances[].InstanceId' --output text)"
report "load balancers" "$(aws_lbs_in_vpc 2>/dev/null || true)"
report "PVC EBS volumes" "$(aws ec2 describe-volumes --region "$region" \
  --filters Name=tag-key,Values=kubernetes.io/created-for/pvc/name --query 'Volumes[].VolumeId' --output text)"
report "VPC" "$(aws ec2 describe-vpcs --region "$region" --filters "Name=tag:Name,Values=$cluster" --query 'Vpcs[].VpcId' --output text)"
report "Elastic IPs" "$(aws ec2 describe-addresses --region "$region" --query 'Addresses[].PublicIp' --output text)"

if [ "$leftovers" -ne 0 ]; then
  echo "Something is still there and may bill. Delete it in the console, then check Billing." >&2
  exit 1
fi
echo "Clean. Last step by hand: Billing console → Bills (today's charges), a day later Cost Explorer."
