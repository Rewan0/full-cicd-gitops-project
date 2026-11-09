# Stage 3: Kubernetes Manifests

The Kubernetes resources that run the application and its database on the kubeadm cluster from Stage 2. These manifests are also the source of truth that ArgoCD watches in Stage 5, so every file in this folder describes the desired state of the `ivolve` namespace.

## Architecture

```
                        Namespace: ivolve   (ResourceQuota: 3 pods)
+------------------------------------------------------------------+
|                                                                  |
|   ClusterIP Service: ivolveappsvc (port 3000)                    |
|                  |                                               |
|          +-------+--------+                                      |
|          v                v                                      |
|   +--------------+  +--------------+       Node: worker-1        |
|   | ivolveapp    |  | ivolveapp    |       label: project=ivolve  |
|   | pod 1        |  | pod 2        |       taint: workload=       |
|   |  init-db     |  |  init-db     |              ivolveApp:      |
|   |  Node.js app |  |  Node.js app |              NoSchedule      |
|   +------+-------+  +------+-------+                              |
|          |  /app/logs      |                                     |
|          +--------+--------+                                      |
|                   v                                              |
|     PVC ivolvepvc -> PV ivolvepv (hostPath /mnt/app-logs)        |
|                                                                  |
|          |  DB_HOST (ConfigMap) + credentials (Secret)           |
|          v                                                       |
|   Headless Service: ivolvedbsvc (port 3306)                      |
|          |                                                       |
|   StatefulSet pod: ivolvedb-0 (MySQL 8.0)                        |
|     PVC data-ivolvedb-0 -> PV mysql-pv (hostPath /mnt/mysql-data)|
+------------------------------------------------------------------+
```

## Contents

| File | Resource | Origin |
|---|---|---|
| `00-namespace.yaml` | Namespace `ivolve` | Lab 11 |
| `01-resourcequota.yaml` | ResourceQuota `ivolve-quota` (3 pods) | Lab 11 |
| `02-configmap.yaml` | ConfigMap `ivolve-cm` (`DB_HOST`, `DB_USER`) | Lab 12 |
| `secret.yaml.example` | Secret `ivolve-secret` template (placeholders only) | Lab 12 |
| `03-pv.yaml` | PersistentVolumes `ivolvepv` (logs) and `mysql-pv` (database) | Labs 13, 14 |
| `04-pvc.yaml` | PersistentVolumeClaim `ivolvepvc` in `ivolve` | Labs 13, 15 |
| `05-db-service.yaml` | Headless Service `ivolvedbsvc` | Lab 14 |
| `06-db-statefulset.yaml` | StatefulSet `ivolvedb` (MySQL 8.0) | Lab 14 |
| `07-app-deployment.yaml` | Deployment `ivolveapp` (2 replicas, init container) | Lab 15 |
| `08-app-service.yaml` | ClusterIP Service `ivolveappsvc` | Lab 15 |

## Design Decisions

| Topic | Decision |
|---|---|
| Image | The Deployment uses `rewan2233/ivolve-app:v1`, the image built and pushed in Stage 1. In Stage 4 the Jenkins pipeline publishes new tags and updates this line. |
| Node placement | Both workloads use `nodeSelector: project=ivolve` and a toleration for `workload=ivolveApp:NoSchedule`, so they run only on the dedicated worker node configured in Stage 2. |
| Configuration | Non-sensitive values live in the ConfigMap `ivolve-cm`. Credentials live in the Secret `ivolve-secret`. Both are injected with `envFrom`. |
| Secrets in Git | The Secret is not part of the synced manifests. `secret.yaml.example` documents its shape, and the real Secret is created on the cluster with `kubectl create secret`. |
| Database bootstrap | The `init-db` init container waits until MySQL accepts connections, then creates the `ivolve` database and the application user. All statements use `IF NOT EXISTS`, so restarts are safe. |
| Storage | hostPath volumes are used for the lab environment. Static provisioning with `storageClassName: ""` for logs and `manual` for MySQL. |
| Rollout strategy | The ResourceQuota allows 3 pods (1 database and 2 application pods), so the Deployment uses `maxSurge: 0` and `maxUnavailable: 1`. A default rolling update would need a fourth pod and stall. |

## Prerequisites

1. The cluster from Stage 2 is running, and `worker-1` carries the label and taint.
2. The hostPath directories exist on `worker-1`:

```bash
sudo mkdir -p /mnt/app-logs && sudo chmod 777 /mnt/app-logs
sudo mkdir -p /mnt/mysql-data
```

3. The image `rewan2233/ivolve-app:v1` is available on Docker Hub.

## Deployment

Create the namespace first, then the Secret, then apply the remaining manifests:

```bash
kubectl apply -f 00-namespace.yaml

kubectl create secret generic ivolve-secret -n ivolve \
  --from-literal=MYSQL_ROOT_PASSWORD='<root-password>' \
  --from-literal=DB_PASSWORD='<app-user-password>'

kubectl apply -f .
```

`kubectl apply -f .` skips `secret.yaml.example` because of its extension.

## Verification

```bash
kubectl get all,pvc,cm,secret -n ivolve
kubectl get pv
kubectl get pods -n ivolve -o wide
kubectl get endpoints ivolveappsvc -n ivolve
```

Expected result:

| Check | Expected |
|---|---|
| `ivolvedb-0` | `Running 1/1` on `worker-1` |
| `ivolveapp` pods | 2 pods, `Running 1/1` on `worker-1` |
| `ivolvepvc`, `data-ivolvedb-0` | `Bound` |
| `ivolveappsvc` endpoints | Two pod IPs on port 3000 |

Database and privileges:

```bash
kubectl exec -it ivolvedb-0 -n ivolve -- mysql -uroot -p -e "SHOW DATABASES; SHOW GRANTS FOR 'ivolve_user'@'%';"
```

The `ivolve` database exists, and `ivolve_user` holds `ALL PRIVILEGES` on `ivolve.*`.

Application logs:

```bash
kubectl logs deployment/ivolveapp -n ivolve
```

```
Attempting to connect to MySQL...
Connected to MySQL and 'ivolve' DB found.
Server started on http://0.0.0.0:3000
```

Service test from the control plane node:

```bash
kubectl get svc ivolveappsvc -n ivolve
curl http://<CLUSTER-IP>:3000
```

Log persistence on `worker-1`:

```bash
ls -la /mnt/app-logs
```

`app.log` and `access.log` are present on the node filesystem.

<img width="2070" height="300" alt="image" src="https://github.com/user-attachments/assets/bbadbffb-9768-4b26-b8c9-18b1623174be" />
<img width="1399" height="202" alt="image" src="https://github.com/user-attachments/assets/84aa1234-19df-479f-a065-a5192a93cbba" />
<img width="2394" height="589" alt="image" src="https://github.com/user-attachments/assets/48d217b0-5554-47cd-a4cb-721488b04ebf" />
<img width="2560" height="1528" alt="image" src="https://github.com/user-attachments/assets/1a985362-0fbc-4a1e-9f1f-7454aba3397f" />
<img width="1213" height="255" alt="image" src="https://github.com/user-attachments/assets/9b88b3a1-191c-4ccc-b29d-f2163980063d" />

## Next Stage

Continue to [Stage 4: CI with Jenkins](../04-ci-jenkins/README.md), where a pipeline builds the image and updates the Deployment manifest in this folder.
