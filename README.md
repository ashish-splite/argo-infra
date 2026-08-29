# argo-infra

ApplicationSet-based GitOps setup for local Kubernetes learning with Argo CD.

## Structure

```text
.
├── root/
│   ├── root-app.yaml                    # Bootstrap application
│   └── applicationsets/
│       ├── applications.yaml                # Local Helm charts (applications/*)
│       ├── datastores.yaml              # External Helm charts (redis, postgres)
│       ├── observability.yaml           # Monitoring stack (prometheus)
│       ├── ci-cd.yaml                   # CI/CD tools (jenkins)
│       ├── platform-helm.yaml           # Platform Helm charts (ingress-nginx)
│       └── platform-manifests.yaml      # Platform manifests (argocd-ingress)
│
├── applications/                            # Application applications (local Helm charts)
│   ├── chat-app/
│   │   ├── app.yaml                     # App config: name, namespace, path, enabled
│   │   └── chart/                       # Helm chart
│   └── jarvis/
│       ├── app.yaml
│       └── chart/
│
├── datastores/                          # Databases (external Helm charts)
│   ├── redis/
│   │   ├── app.yaml                     # Chart reference + valuesPath
│   │   └── values.yaml                  # Helm values
│   └── postgres/
│       ├── app.yaml
│       └── values.yaml
│
├── observability/                       # Monitoring & logging
│   └── prometheus/
│       ├── app.yaml
│       └── values.yaml
│
├── ci-cd/                               # CI/CD tools
│   └── jenkins/
│       ├── app.yaml
│       └── values.yaml
│
├── platform-helm/                       # Platform components (external Helm)
│   └── ingress-nginx/
│       ├── app.yaml
│       └── values.yaml
│
└── platform-manifests/                  # Platform components (local manifests)
    └── argocd-ingress/
        ├── app.yaml
        └── chart/
```

## Model

- `root/root-app.yaml` is the bootstrap application
- Root app syncs `root/applicationsets/`, which applies all ApplicationSets
- Each category has its own ApplicationSet with Git file generator
- Apps are discovered by `app.yaml` files in each folder
- External Helm charts use `sources` with separate `values.yaml`
- Local charts reference `path` to the chart directory

## App Configuration

Each `app.yaml` contains:
```yaml
enabled: true                    # Toggle app on/off
name: my-app                     # Argo CD application name
namespace: my-namespace          # Target namespace

# For local charts:
path: applications/my-app/chart      # Path to Helm chart

# For external charts:
valuesPath: datastores/redis/values.yaml
chart:
  repoURL: https://charts.bitnami.com/bitnami
  name: redis
  version: "19.6.4"
```

## Quick On/Off

- To disable an app: set `enabled: false` in its `app.yaml`
- To re-enable: set `enabled: true`
- No changes needed in ApplicationSet templates

## Prerequisites

- Local Kubernetes cluster running (kind, minikube, k3d, Docker Desktop K8s)
- Argo CD installed in namespace argocd
- This repository pushed to a git remote reachable by Argo CD

## Bootstrap

1. Update repo URL in `root/root-app.yaml` and all ApplicationSets
2. Apply root app:

```bash
kubectl apply -f root/root-app.yaml
```

## Verify

```bash
kubectl -n argocd get applicationsets
kubectl -n argocd get applications
kubectl get ns apps datastores observability ci-cd ingress-nginx
```

## Categories

| Category | Folder | Description |
|----------|--------|-------------|
| applications | `applications/` | Application microapplications (local Helm charts) |
| datastores | `datastores/` | Databases - Redis, PostgreSQL |
| observability | `observability/` | Monitoring - Prometheus stack |
| ci-cd | `ci-cd/` | CI/CD tools - Jenkins |
| platform-helm | `platform-helm/` | Platform components (external Helm) |
| platform-manifests | `platform-manifests/` | Platform components (local manifests) |
