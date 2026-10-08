# Runbook: tear down the AWS environment

The EKS cluster costs ~$0.16–0.20 per hour while it exists, ~$4.50 per forgotten day (ADR 0020).
Tear it down at the end of every AWS session. The S3 state bucket (`bootstrap/`) stays; it costs cents.

## Why not just `terraform destroy`

Kubernetes creates some AWS resources on its own, and Terraform doesn't know about them:

| Created by | AWS resource | If left behind |
|---|---|---|
| `Service` of `type: LoadBalancer` (e.g. Traefik) | NLB/ELB + network interfaces in our subnets | keeps billing; its interfaces make the VPC deletion fail |
| `PersistentVolumeClaim` (Postgres, Kafka, MongoDB) | EBS volume, via the EBS CSI driver | keeps billing per GB-month, silently |

They have to go **while the cluster still runs**, because their controllers are the ones that delete them.
And Argo CD has to go first, or it recreates them from Git.

## Steps (at the PC, AWS credentials in the shell)

```bash
./scripts/aws-teardown.sh          # Terraform still asks before destroying; --yes skips that
```

The script:
1. deletes all Argo CD Applications (their finalizer removes what they deployed),
2. deletes every `LoadBalancer` Service, then waits until AWS shows no load balancer in the VPC,
3. deletes the workload namespaces and waits until every PersistentVolume is gone (= EBS volume deleted),
4. runs `terraform destroy` (~10 min),
5. checks the region for leftovers: cluster, instances, load balancers, PVC volumes, VPC, Elastic IPs.
   It exits non-zero if anything is found.

Every `kubectl` call uses the `eks-myplatform` context explicitly, so it can't touch minikube.

Then, by hand: **Billing console → Bills** for today's charges. Cost Explorer lags by about a day; look again tomorrow.

## When something goes wrong

- **Cluster already gone or unreachable** (e.g. a half-finished destroy): `./scripts/aws-teardown.sh --skip-k8s`
  runs only the destroy and the leftover check.
- **A leftover is reported:** delete it in the console (EC2 → Load Balancers / Volumes), then re-run with `--skip-k8s`.
  If a load balancer was orphaned, the VPC deletion fails with `DependencyViolation`; delete the LB first.
- **`destroy` times out on the VPC or subnets:** something still holds a network interface. EC2 → Network Interfaces,
  filter by the VPC, see the "Description" (usually an ELB), delete its owner, re-run.
- **Forgot to tear down:** the $5 budget alert email is the safety net. Run the script as soon as you see it.
