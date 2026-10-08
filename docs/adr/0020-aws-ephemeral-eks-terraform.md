# 0020: Ephemeral EKS with Terraform: public subnets, spot nodes, S3 state with native locking

Date: 2026-10-08 · Status: accepted

## Context

Phase 7 runs the full stack on AWS. CLAUDE.md fixes the frame: EKS created and destroyed with Terraform, about
$10/month, no NAT gateways, no MSK/RDS (Kafka and databases in-cluster), spot/small instances, state in S3.
The code is written and checked remotely; the first `apply` happens at the PC.

## Decision

- **Two stacks.** `infra/terraform/aws/bootstrap/` creates only the state bucket and keeps its own state locally
  (it can't live in a bucket it creates). Applied once, never destroyed (`prevent_destroy`). `cluster/` holds
  everything that comes and goes per session.
- **State:** S3 bucket `myplatform-tfstate-<account>-<region>`, versioned (old versions expire after 30 days),
  SSE-S3, all public access blocked, HTTPS-only policy. Locking with S3's native lock file (`use_lockfile`,
  Terraform 1.10+) instead of a DynamoDB table: one less resource, same protection against two applies at once.
  `bucket`/`region` come from a gitignored `backend.hcl`, because the bucket name contains the account ID.
- **Region `ap-south-1` (Mumbai):** lowest latency from Dhaka, cheap spot capacity.
- **Community modules, pinned exactly:** `terraform-aws-modules/vpc` 6.7.3 and `/eks` 21.29.0, AWS provider
  `~> 6.68`. They're what most teams use, and a hand-written EKS setup is ~40 resources (IAM, security groups,
  launch templates) with many ways to get it subtly wrong. The parts worth explaining (IAM for the CSI driver,
  cost switches) are written out in our code.
- **Network:** one VPC, 2 public subnets in 2 AZs, **no NAT and no private subnets**. Nodes get public IPs for egress;
  their security group still allows no inbound from the internet. Two AZs (the EKS minimum), not three: EBS volumes
  belong to one AZ, so fewer AZs means stateful pods find their volume more often.
- **EKS 1.36** (standard support until 2027-08). A version in extended support bills the control plane 6×.
  API endpoint public but limited to `api_allowed_cidrs` (my IP /32), with IAM auth on every call.
  Access entries (`authentication_mode = "API"`), creator is admin; no `aws-auth` ConfigMap.
- **Nodes:** one managed node group, **spot**, AL2023, 2 × 2 vCPU / 8 GiB (`m6a/m6i/m5a/m5.large`; several types
  = more spot pools). No burstable T types, since their CPU credits can bill extra under JVM startup load. 20 GiB gp3 root.
- **Add-ons:** vpc-cni, kube-proxy, coredns, eks-pod-identity-agent, aws-ebs-csi-driver. The CSI driver gets its
  AWS permissions via **EKS Pod Identity** (role trusted by `pods.eks.amazonaws.com`), not the node role, so
  only that pod can create volumes.
- **Off on purpose (cost / leftovers after destroy):** control plane logs to CloudWatch, the customer-managed
  KMS key ($1/month, 7–30 days pending deletion; EKS already encrypts Secrets with an AWS-owned key),
  deletion protection.
- **Checks without an AWS account:** `terraform fmt`, `validate`, and `terraform test` (a full `plan` against
  mocked providers that asserts the guardrails: no NAT, 2 public subnets, no KMS key/log group, API CIDRs required).
  CI runs them in `.github/workflows/terraform.yml`, with no cloud credentials.

## Cost (rough, per hour the cluster exists)

| Item | $/h |
|---|---|
| EKS control plane | 0.10 |
| 2 spot `*.large` nodes | ~0.04–0.08 |
| 2 public IPv4 addresses | 0.01 |
| 2 × 20 GiB gp3 | ~0.005 |
| **Total** | **~0.16–0.20** |

That's ~$0.75 for a 4-hour session, so ~50 hours/month fit in $10. A **forgotten** cluster costs ~$4.50/day.
The $5 budget alert catches that within about a day. A LoadBalancer (NLB ~$0.02/h + LCU) is decided with the edge
setup on EKS; the S3 state costs cents.

## Consequences

- `apply` → ~15 min until nodes are Ready; `destroy` → ~10 min. Everything in-cluster (DBs, Kafka) starts empty.
- Spot nodes can be reclaimed with 2 minutes' notice. Acceptable for a demo; on-demand is one variable away.
- Kubernetes objects that create AWS resources (LoadBalancer Services, PVC volumes) aren't in Terraform state.
  They must be deleted before `destroy` (teardown runbook, Phase 7).
- Local tools needed at the PC: Terraform, AWS CLI, and a kubectl within one minor version of 1.36 (local is 1.31;
  minikube's 1.31 is then out of kubectl's supported skew, to be checked when that happens).
