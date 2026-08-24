# Kubernetes

Floci can run without Docker when its container-backed executors use
Kubernetes. Set the executor for each supported service to `kubernetes`; the
default remains `docker`, so existing Docker deployments are unchanged.

The [Kubernetes deployment manifests](../../deploy/kubernetes/) provide a
non-privileged, one-replica Floci StatefulSet and namespace-scoped RBAC. They
are suitable for applying with `kubectl` and can be adapted for an Okteto
deployment.

## Shared settings

| Setting | Environment variable | Default | Description |
|---|---|---|---|
| `floci.kubernetes.namespace` | `FLOCI_KUBERNETES_NAMESPACE` | pod namespace, then `default` | Namespace for managed workloads |
| `floci.kubernetes.storage-class` | `FLOCI_KUBERNETES_STORAGE_CLASS` | cluster default | Storage class for managed PVCs |
| `floci.kubernetes.default-storage-size` | `FLOCI_KUBERNETES_DEFAULT_STORAGE_SIZE` | `5Gi` | PVC size when a resource does not specify one |
| `floci.kubernetes.labels` | `FLOCI_KUBERNETES_LABELS` | _(none)_ | Comma-separated `key=value` labels for managed objects |
| `floci.kubernetes.image-pull-policy` | `FLOCI_KUBERNETES_IMAGE_PULL_POLICY` | `IfNotPresent` | Pull policy for managed workload images |
| `floci.kubernetes.startup-timeout-seconds` | `FLOCI_KUBERNETES_STARTUP_TIMEOUT_SECONDS` | `300` | Readiness wait for RDS and ElastiCache workloads |

The namespace is resolved from the configured value first, then from
`/var/run/secrets/kubernetes.io/serviceaccount/namespace`, and finally from
`default`.

## Executors

The following settings accept `docker` (the default) or `kubernetes`:

| Setting | Environment variable |
|---|---|
| `floci.services.rds.executor` | `FLOCI_SERVICES_RDS_EXECUTOR` |
| `floci.services.elasticache.executor` | `FLOCI_SERVICES_ELASTICACHE_EXECUTOR` |
| `floci.services.opensearch.executor` | `FLOCI_SERVICES_OPENSEARCH_EXECUTOR` |

Lambda already has the same choice through
`FLOCI_SERVICES_LAMBDA_EXECUTOR`; see the [Lambda Kubernetes executor](../services/lambda.md#kubernetes-executor).

## Managed workloads and storage

For each Kubernetes-backed RDS instance or cluster, ElastiCache replication
group, or OpenSearch domain, Floci creates:

- one single-replica StatefulSet;
- one headless Service used as the stable workload DNS name; and
- one `data` PVC when the resource declares persistent storage.

Workload names and PVCs are deterministic and tied to the persisted AWS
resource storage identity. A restart of Floci or its pod leaves the workload
and PVC in place. On startup, Floci adopts the existing StatefulSet and
reattaches its existing PVC rather than provisioning replacement storage.

An explicit resource stop removes the StatefulSet and Service but retains the
PVC. Deleting the AWS resource removes the StatefulSet, Service, and its PVC.
Kubernetes `stopAll()` during Floci shutdown intentionally leaves all managed
workloads running, so a subsequent process can adopt them.

Valkey/Redis uses append-only mode and a PVC only with the Kubernetes
ElastiCache executor. The Docker executor remains unchanged and starts
Valkey/Redis without a persistent volume.

OpenSearch launch is asynchronous: creating or restoring the StatefulSet does
not wait for the pod to become Ready. The existing `/_cluster/health` poller
transitions the domain out of `processing` when the search service is ready.
RDS and ElastiCache launches retain their blocking engine/PING readiness
behavior.

## Security and RBAC

The Kubernetes path uses namespace-scoped API calls only. The Floci deployment
does not use privileged containers, hostPath mounts, Docker socket mounts, or
Docker-in-Docker. The included Role grants only the APIs used by the Lambda and
stateful workload executors:

- `apps/statefulsets`: `create`, `get`, `delete`
- `services`: `create`, `get`, `delete`
- `persistentvolumeclaims`: `get`, `delete`
- `pods`: `create`, `get`, `list`, `watch`, `delete`, `deletecollection`
- `pods/log`: `get`, `watch`
- `pods/exec`: `create`
- `configmaps`: `create`, `get`, `update`, `patch`

See [Floci on Kubernetes](../../deploy/kubernetes/README.md) for image,
namespace, storage, apply, and inspection instructions.
