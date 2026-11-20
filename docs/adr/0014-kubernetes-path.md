# ADR-0014: Start on ECS Fargate; move to Amazon EKS only when the triggers below are met

- **Status:** Accepted. A plan for later, not a migration now.
- **Date:** 2026-11-13

## Context

ADR-0012 puts the platform on ECS Fargate: about 19 ECS services, one team, no cluster to operate. Kubernetes keeps
coming up as "the standard", so this records **when** moving to Amazon EKS becomes worth its cost, and **how** to
do it without rewriting services or pipelines. Everything the platform runs is already a plain OCI image with
configuration from environment variables (`aws` profile), health endpoints and OTLP telemetry. None of it is
ECS-specific.

## Decision

**Stay on ECS Fargate** while none of these holds. **Plan a move to EKS** when two or more hold for a quarter:

| Trigger | Signal |
|---|---|
| Scale of the system | well beyond ~30 services, or several teams that each want their own namespaces, quotas and release cadence |
| Service-mesh needs | mTLS everywhere with per-route policies, retries/timeouts/circuit breaking outside the code, traffic mirroring: more than Service Connect offers |
| Custom autoscaling | scaling on Kafka consumer lag or queue depth per consumer group (KEDA), scheduled scaling, pod-level vertical scaling |
| Cost at scale | steady load where bin-packing on EC2/Karpenter (Spot, Graviton) clearly beats Fargate per-task pricing, after Savings Plans |
| Ecosystem | operators the team wants to run in-cluster (e.g. Strimzi, an Elasticsearch operator), Argo CD/Rollouts, policy engines (OPA/Kyverno) |
| Portability | a requirement to run the same deployment on another cloud or on-premises |

**How, when it happens** (incremental, service by service, no code changes):
1. **Same images.** The CI images (`Dockerfile.service`, `frontend/grocery-web/Dockerfile.prod`) and the same tags
   are deployed. Pipelines keep their build, test and scan stages; only the deploy stage changes.
2. **Helm charts generated from `infrastructure/aws/terraform/services.auto.tfvars.json`**, the single list of
   deployable units: one generic chart (Deployment, Service, HPA, PodDisruptionBudget, ServiceAccount) and one values
   file per service (port, CPU and memory requests/limits, health path, replicas, env, secrets) generated from it.
3. **Service Connect → Kubernetes Services** with the same names and ports (`order-service:8110`), so the `aws`
   profile's URLs do not change. The ALB is managed by the AWS Load Balancer Controller (Ingress).
4. **Secrets Manager → External Secrets Operator**, reading the same `grocery/<env>/<service>/<name>` secrets; IAM
   per service through EKS Pod Identity (instead of ECS task roles).
5. **ADOT → the EKS ADOT add-on** (collector as a DaemonSet or sidecar), exporting to the same AMP, X-Ray and
   CloudWatch Logs, so dashboards and alerts do not change (ADR-0013).
6. **Blue/green → Argo Rollouts** (or Flagger) with the same analysis: 5xx rate, readiness and bake time, using the
   AMP metrics; canary 10 % → 100 %.
7. **Data services stay as they are** (RDS, MSK, ElastiCache, Elastic Cloud); only the compute layer moves.
8. **Migration order:** stateless edge first (api-gateway behind a weighted ALB/Route 53 split), then consumers, with
   ECS and EKS running side by side. Kafka consumer groups make the switch safe: tasks on both sides share the group.

## Consequences

- ✅ Nothing is spent on Kubernetes operations until there is a measurable reason.
- ✅ Choices made now keep the move cheap: OCI images, env-only configuration, OTLP, health endpoints, one service
  list as the source of truth, stateless services, expand/contract migrations.
- ⚠️ EKS brings a control plane (and add-ons) to upgrade about three times a year, node groups or Karpenter to run,
  and Kubernetes skills the team must hold.
- ⚠️ Until then, the platform lives with ECS limits: Service Connect instead of a full mesh, autoscaling on CPU and
  request count (consumer-lag scaling needs custom CloudWatch metrics), and Fargate pricing.

## Alternatives considered

- **EKS from day one:** maximum flexibility, but the cluster, its upgrades and its add-ons would cost more than
  the platform's whole compute layer is worth at this size.
- **Self-managed Kubernetes (kOps, kubeadm) on EC2:** all of EKS's cost plus running the control plane. Rejected.
- **ECS forever:** viable for a long time. This ADR does not assume a move; it only defines the triggers and keeps
  the path open.
