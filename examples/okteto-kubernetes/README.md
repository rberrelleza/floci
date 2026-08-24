# Floci Kubernetes demo

This demo runs Floci and a small FastAPI/nginx application in one Kubernetes
namespace. The backend asks Floci to create one PostgreSQL RDS instance, one
Valkey replication group, and one OpenSearch domain. It uses only the
connection endpoints returned by the corresponding `Describe` APIs.

The frontend has separate PostgreSQL, Valkey, and OpenSearch cards. Add an item,
list items to observe the Valkey cache-hit flag, and search the OpenSearch
index.

## Deploy with Okteto

From this directory, select the target namespace and deploy:

```bash
okteto namespace use floci-k8s
okteto validate
okteto deploy --wait
okteto endpoints
```

The manifest builds the Floci image from the repository root using
`docker/Dockerfile`, then builds the two demo images. The deploy steps apply
Floci's manifests first, apply the app manifests second, and wait for all
rollouts. The built image references come from Okteto's
`OKTETO_BUILD_*_IMAGE` variables.

The deployment is intentionally left running so the StatefulSets and PVCs can
be inspected and restarted. Do not use `okteto up` for this demo.

## API

The backend exposes:

- `GET /api/health`
- `POST /api/items` with `{"value":"..."}`
- `GET /api/items`
- `GET /api/search?q=...`

The app creates its SQL table and OpenSearch index if they are absent. Repeated
backend starts describe existing AWS resources before creating anything, so a
restart adopts the same resources instead of duplicating them.
