# argo-infra

ApplicationSet-based GitOps setup for local Kubernetes learning with Argo CD.

## Structure

```text
.
├── root/
│   ├── root-app.yaml
│   ├── bootstrap/
│   │   └── kustomization.yaml
│   └── applicationsets/
│       ├── helm-apps.yaml      # Git file generator: discovers infrastructure/**/config.yaml and platform/**/config.yaml
│       └── git-apps.yaml       # Git file generator: discovers applications/**/config.yaml
├── infrastructure/
│   ├── ingress/
│   │   ├── config.yaml         # App identity: name, namespace, chart details
│   │   └── values.yaml         # Helm values
│   └── monitoring/
│       ├── config.yaml
│       └── values.yaml
├── platform/
│   └── jenkins/
│       ├── config.yaml
│       └── values.yaml
└── applications/
    ├── service-a/
    │   ├── config.yaml         # App identity: name, namespace, path
    │   └── chart/
    │       ├── Chart.yaml
    │       ├── values.yaml
    │       └── templates/
    │           ├── deployment.yaml
    │           └── service.yaml
    └── service-b/
        ├── config.yaml
        └── manifests/
            ├── deployment.yaml
            ├── service.yaml
            └── configmap.yaml
```

## Model

- `root/root-app.yaml` is the bootstrap application.
- Root app syncs `root/bootstrap`, which applies ApplicationSets.
- ApplicationSets use Git file generators to auto-discover apps.
- Each app folder has a `config.yaml` defining its identity (name, namespace, source).
- Helm values stay in `values.yaml` alongside the config.
- To add a new app, just create a folder with `config.yaml` — ApplicationSet discovers it.

## Quick On/Off

- To disable an app: delete or rename its `config.yaml` file.
- To re-enable: restore the `config.yaml`.
- No changes needed in ApplicationSet templates.

## Prerequisites

- Local Kubernetes cluster running (kind, minikube, k3d, Docker Desktop K8s)
- Argo CD installed in namespace argocd
- This repository pushed to a git remote reachable by Argo CD

## Bootstrap

1. Update repo URL placeholders in:
   - `root/root-app.yaml`
   - `root/applicationsets/helm-apps.yaml`
   - `root/applicationsets/git-apps.yaml`
2. Apply root app:

```bash
kubectl apply -f root/root-app.yaml
```

## Verify

```bash
kubectl -n argocd get applicationsets
kubectl -n argocd get applications
kubectl get ns ingress-nginx monitoring jenkins service-a service-b
```
