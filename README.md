# homelab

k3s cluster on 3 Raspberry Pis: PostgreSQL, Redis, web apps, queue workers, batch jobs.

| Node | IP | Role |
|---|---|---|
| pi4-8gb | 192.168.40.250 | k3s server, Postgres, Redis |
| pi4-4gb | 192.168.40.251 | k3s agent, apps / workers / batch |
| pi3 | 192.168.40.252 | k3s agent (light), not joined yet |

- Full setup guide and checklist: [K3S-GUIDE.md](K3S-GUIDE.md)
- Manifests: `k8s/<namespace>/`

```bash
export KUBECONFIG=~/.kube/homelab.yaml
kubectl apply -f k8s/data/ -f k8s/apps/
```

Secrets (DB/Redis passwords, tunnel credentials) are created in-cluster with
`kubectl create secret`. They are never stored in this repo.
