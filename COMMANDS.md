minikube start
sudo minikube tunnel
ngrok http 80

## /etc/hosts

```
127.0.0.1  argocd.local argo-workflows.local argo-rollouts.local jenkins.local chat-app.local jarvis.local vault.local
```

## Argo CD

```bash
# UI: http://argocd.local   (admin / admin)
kubectl -n argocd get applications
kubectl -n argocd get applicationsets

# force a refresh when a sync looks stale
kubectl -n argocd patch app <name> --type merge \
  -p '{"metadata":{"annotations":{"argocd.argoproj.io/refresh":"hard"}}}'
```

## Vault

Run `init` once, ever. Save the unseal key and root token somewhere safe.

```bash
kubectl -n vault exec -it vault-0 -- vault operator init -key-shares=1 -key-threshold=1
```

Unseal after every pod restart; Vault starts sealed and cannot read its data until then.

```bash
kubectl -n vault exec -it vault-0 -- vault operator unseal <UNSEAL_KEY>
kubectl -n vault exec -it vault-0 -- vault status
```

Give External Secrets the root token (once, after init).

```bash
kubectl -n external-secrets create secret generic vault-token --from-literal=token=<ROOT_TOKEN>
```

Enable the KV v2 engine and add a secret. UI: http://vault.local

```bash
kubectl -n vault exec -it vault-0 -- sh -c 'export VAULT_TOKEN=<ROOT_TOKEN>; \
  vault secrets enable -path=secret -version=2 kv; \
  vault kv put secret/demo/app username=admin password=local'
```

## External Secrets

```bash
kubectl get clustersecretstore
kubectl describe clustersecretstore vault-backend
kubectl get externalsecret -A
```

## Ingress

```bash
kubectl get ingress -A
kubectl -n ingress-nginx get svc ingress-nginx-controller

# alternative to `minikube tunnel`
kubectl -n ingress-nginx port-forward svc/ingress-nginx-controller 8080:80
```