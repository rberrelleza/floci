# Floci on Kubernetes

These plain manifests run Floci and its Kubernetes-backed service workloads in
the namespace where they are applied. They do not require Docker-in-Docker,
privileged containers, host mounts, or a Docker socket.

## Prerequisites

- A Kubernetes cluster and `kubectl` configured for the target namespace.
- Permission to create a ServiceAccount, Role, RoleBinding, Service, StatefulSet,
  and PVC in that namespace.
- A Floci image available to the cluster nodes.

Build and tag the image from the repository root, then push it to a registry
reachable by the cluster, or replace the image in `floci.yaml`:

```bash
docker build -f docker/Dockerfile -t floci/floci:latest .
```

For a remote cluster, use a registry-qualified tag instead:

```bash
docker build -f docker/Dockerfile -t registry.example.com/team/floci:latest .
docker push registry.example.com/team/floci:latest
```

## Apply

Select the target namespace before applying. The manifests intentionally omit
`metadata.namespace`, so the same files can be reused in any namespace:

```bash
kubectl config set-context --current --namespace=floci
kubectl apply -f deploy/kubernetes/
```

The StatefulSet resolves its namespace from its own pod using the downward API.
To use a non-default storage class for Floci's state PVC, add
`storageClassName` to the `volumeClaimTemplates` entry before applying. The
managed AWS-resource PVCs use `FLOCI_KUBERNETES_STORAGE_CLASS` when set, or the
cluster default otherwise. Their default size is configured by
`FLOCI_KUBERNETES_DEFAULT_STORAGE_SIZE`.

## Created resources

The directory creates:

- ServiceAccount `floci`
- namespace-scoped Role and RoleBinding named `floci`
- Service `floci`
- one-replica StatefulSet `floci` with a `data` PVC mounted at `/app/data`

Floci creates one single-replica StatefulSet and headless Service for each
Kubernetes-backed RDS, ElastiCache Valkey/Redis, and OpenSearch resource.
Persistent resources also receive a resource-specific `data` PVC. Those
workloads and PVCs survive a Floci process or pod restart; the next Floci
process adopts the existing StatefulSets and PVCs. Explicit resource stop
removes the workload but retains its PVC, while deleting the AWS resource
removes its workload and PVC.

The Service exposes:

- `4566` for the AWS API
- ElastiCache proxy ports `6379-6383`
- RDS proxy ports `7001-7005`
- OpenSearch ports `9400-9404`

The corresponding Floci allocator environment variables are set in the
StatefulSet and must remain aligned with these Service ports.

## Inspect

```bash
kubectl get statefulset,service,pod,pvc
kubectl rollout status statefulset/floci
kubectl logs statefulset/floci
kubectl describe pod floci-0
```

This handoff adds and validates the manifests but does not deploy them to a
cluster.
