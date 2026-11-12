# Stage 5: Continuous Delivery with ArgoCD

The final stage replaces the push-based deployment (`kubectl apply` from Jenkins) with a GitOps workflow. Jenkins (Stage 4) builds and publishes the image and records the new image tag in the Kubernetes manifest stored in Git. ArgoCD watches the repository and automatically synchronizes the cluster with the state declared in Git.

Git becomes the single source of truth for the cluster, and Jenkins no longer needs direct access to the Kubernetes API.

## Objectives

- Install ArgoCD in the Kubernetes cluster
- Access the ArgoCD UI and log in
- Connect ArgoCD to the GitHub repository
- Create an ArgoCD Application named `ivolve` that points at the manifest directory
- Enable automatic synchronization
- Trigger the Jenkins pipeline and confirm that ArgoCD synchronizes the application

## Tools and Technologies

| Tool | Role |
|---|---|
| Kubernetes | Cluster from Stage 2 |
| ArgoCD | GitOps continuous delivery controller |
| Jenkins | Continuous integration server (Stage 4) |
| GitHub | Git repository and source of truth for the manifest |
| Docker Hub | Container image registry |
| kubectl | Cluster administration and verification |

## Architecture

```
Developer / Jenkins
        |
        |  1. build, scan, push image
        v
  Docker Hub  (rewan2233/ivolve-app:lab23-N)
        |
        |  2. update image tag in deployment.yaml
        |     and git push
        v
  GitHub  (Rewan0/iVolve-tasks, path ArgoCD/Lab23)
        |
        |  3. ArgoCD polls the repository
        v
     ArgoCD  (namespace: argocd)
        |
        |  4. automatic sync
        v
  Kubernetes  (namespace: ivolve, Deployment ivolveapp)
```

## Repository Contents

| Path | Description |
|---|---|
| `application.yaml` | Declarative form of the ArgoCD Application created in Step 4 |
| `README.md` | Procedure (this file) |

The manifest that ArgoCD watches is `ArgoCD/Lab23/deployment.yaml` in the `iVolve-tasks` repository. It is the application Deployment from [Stage 3](../03-k8s-manifests/07-app-deployment.yaml). ArgoCD only processes Kubernetes manifest files in the watched path, so the Jenkinsfile stored in the same directory is ignored. Screenshots are stored in `../docs/images/05-argocd/`.

## Environment

| Component | Details |
|---|---|
| Kubernetes cluster | master-1 and worker-1 (Calico CNI) |
| ArgoCD | Installed in the `argocd` namespace, exposed through NodePort |
| Application repository | `Rewan0/iVolve-tasks` |
| Container image | `rewan2233/ivolve-app` |
| Target namespace | `ivolve` |

## Implementation

### Step 1: Install ArgoCD

```bash
kubectl create namespace argocd
kubectl apply -n argocd --server-side -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
kubectl get pods -n argocd
```

The `--server-side` flag is used because the ArgoCD custom resource definitions are too large for a client-side apply. All pods in the `argocd` namespace must reach the `Running` state before continuing.

<img width="1294" height="300" alt="image" src="https://github.com/user-attachments/assets/d015f92f-2e67-4107-9d03-333663b3f60d" />

### Step 2: Access the ArgoCD UI

The `argocd-server` service is switched to NodePort so it is reachable from outside the cluster.

```bash
kubectl patch svc argocd-server -n argocd -p '{"spec":{"type":"NodePort"}}'
kubectl get svc argocd-server -n argocd
```

The initial administrator password is read from the generated secret:

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" | base64 -d; echo
```

Open `https://<master-1-IP>:<NodePort>` (the HTTPS port mapped to 443) and log in with the user `admin`.

<img width="1278" height="686" alt="Screenshot 2026-11-08 145511" src="https://github.com/user-attachments/assets/2d4bf4fe-bb76-4aca-a71f-67f5f86225f1" />

### Step 3: Connect ArgoCD to the GitHub repository

In the UI, under `Settings` -> `Repositories` -> `Connect Repo`:

| Field | Value |
|---|---|
| Connection method | HTTPS |
| Type | git |
| Name | ivolve |
| Project | default |
| Repository URL | `https://github.com/Rewan0/iVolve-tasks.git` |

The username and password fields are left empty for a public repository. For a private repository, use the GitHub username and a personal access token. The connection status must show `Successful`.

<img width="1273" height="688" alt="Screenshot 2026-11-08 152540" src="https://github.com/user-attachments/assets/29f3e636-6526-47ad-96c9-cfd5249f5f88" />

### Step 4: Create the ArgoCD Application

The Application `ivolve` is created from the UI under `Applications` -> `New App`:

| Setting | Value | Purpose |
|---|---|---|
| `path` | `ArgoCD/Lab23` | Directory containing the manifest to monitor |
| `targetRevision` | `main` | Branch tracked by ArgoCD |
| `destination.namespace` | `ivolve` | Namespace where resources are applied |
| `automated` | enabled | Applies Git changes without manual intervention |
| `prune` | true | Removes cluster resources that were deleted from Git |
| `selfHeal` | true | Reverts manual changes made directly in the cluster |

The same settings are captured declaratively in `application.yaml`:

```yaml
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: ivolve
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/Rewan0/iVolve-tasks.git
    targetRevision: main
    path: ArgoCD/Lab23
  destination:
    server: https://kubernetes.default.svc
    namespace: ivolve
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
```

The existing `ivolveapp` Deployment in the `ivolve` namespace is adopted by ArgoCD. Once synchronized, the application reports `Synced` and `Healthy`.

<img width="1278" height="686" alt="Screenshot 2026-11-08 153505" src="https://github.com/user-attachments/assets/9e4e5847-d078-483d-aad6-6c2d897494f7" />

### Step 5: Trigger the pipeline

Start the `Lab23` job in Jenkins with `Build Now`. The pipeline (see [Stage 4](../04-ci-jenkins/README.md)) builds and scans the image, pushes it to Docker Hub, and commits the new image tag to `ArgoCD/Lab23/deployment.yaml` on GitHub.

### Step 6: Verify the synchronization

ArgoCD detects the new commit during its next repository poll (about every three minutes) and applies it automatically. A refresh can be forced from the UI.

```bash
kubectl get app ivolve -n argocd
kubectl get deploy ivolveapp -n ivolve \
  -o jsonpath='{.spec.template.spec.containers[0].image}'; echo
kubectl get pods -n ivolve
```

Result:

```
NAME     SYNC STATUS   HEALTH STATUS
ivolve   Synced        Healthy

rewan2233/ivolve-app:lab23-4
```

The Deployment image changed from `rewan2233/ivolve-app:v1` to `rewan2233/ivolve-app:lab23-4` without any `kubectl` command being run by Jenkins, which confirms that ArgoCD detected the Git change and synchronized the cluster.

<img width="1696" height="207" alt="image" src="https://github.com/user-attachments/assets/5a897288-e43f-4da9-8b34-97f44813a337" />

## Conclusion

The push-based deployment was replaced with a GitOps workflow. Jenkins now stops at publishing the image and committing the new tag to the manifest, while ArgoCD pulls the desired state from Git and reconciles the cluster automatically. This gives a full audit trail of every deployment through Git history, removes the need for cluster credentials in Jenkins, and keeps the cluster state continuously corrected back to what is declared in the repository through the prune and self-heal options.

This completes the full flow of the project: Docker Compose, kubeadm cluster, Kubernetes manifests, Jenkins CI with a shared library and an agent, and ArgoCD CD.
