# Stage 2: Kubernetes Cluster with Kubeadm

A two-node Kubernetes cluster (one control plane and one worker) built from scratch with kubeadm, containerd and Calico on VMware Workstation. This cluster hosts the application and database deployed in the next stage, and it is the target that ArgoCD deploys to in the final stage.

## Architecture

```
            +-------------------------------------+
            |     Windows Host (VMware NAT)       |
            |     Network: 192.168.217.0/24       |
            +------------------+------------------+
                               |
          +--------------------+--------------------+
          |                                         |
+---------v----------+                   +----------v---------+
|     master-1       |                   |      worker-1      |
|  192.168.217.132   |<---- kubeadm ---->|  192.168.217.133   |
|   Control Plane    |        join       |    Worker Node     |
|                    |                   |                    |
| - kube-apiserver   |                   | - kubelet          |
| - etcd             |                   | - kube-proxy       |
| - kube-scheduler   |                   | - containerd       |
| - controller-mgr   |                   | - calico-node      |
| - kubelet          |                   |                    |
| - containerd       |                   | label: project=ivolve
+--------------------+                   | taint: workload=ivolveApp:NoSchedule
                                         +--------------------+

 Pod network (Calico): 10.244.0.0/16      Service network: 10.96.0.0/12
```

## Environment

| Node | Role | IP Address | CPU | RAM | Disk | OS |
|---|---|---|:---:|:---:|:---:|---|
| `master-1` | Control plane | 192.168.217.132 | 2 | 4 GB | 20 GB | Ubuntu 22.04 LTS |
| `worker-1` | Worker | 192.168.217.133 | 2 | 2 GB | 20 GB | Ubuntu 22.04 LTS |

| Component | Version |
|---|---|
| Kubernetes | v1.28.15 |
| Container runtime | containerd 2.x |
| CNI | Calico v3.27.0 |
| Hypervisor | VMware Workstation (NAT network) |

## Repository Contents

| Path | Description |
|---|---|
| `scripts/01-prepare-node.sh` | Node preparation: swap, kernel modules, sysctl |
| `scripts/02-install-containerd.sh` | containerd installation with the systemd cgroup driver |
| `scripts/03-install-kubernetes.sh` | kubeadm, kubelet and kubectl installation |
| `README.md` | Step-by-step procedure (this file) |


## Procedure

### Step 1: Node preparation (both nodes)

Set the hostname on each node and add the host entries:

```bash
sudo hostnamectl set-hostname master-1   # on master-1
sudo hostnamectl set-hostname worker-1   # on worker-1

sudo tee -a /etc/hosts <<EOF
192.168.217.132 master-1
192.168.217.133 worker-1
EOF
```

Disable swap, because the kubelet refuses to run with swap enabled:

```bash
sudo swapoff -a
sudo sed -i '/ swap / s/^\(.*\)$/#\1/g' /etc/fstab
```

Load the required kernel modules. `overlay` supports container image layers, and `br_netfilter` lets iptables see bridged traffic:

```bash
sudo tee /etc/modules-load.d/k8s.conf <<EOF
overlay
br_netfilter
EOF
sudo modprobe overlay
sudo modprobe br_netfilter
```

Apply the network sysctl settings:

```bash
sudo tee /etc/sysctl.d/k8s.conf <<EOF
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF
sudo sysctl --system
```

<img width="2560" height="1528" alt="image" src="https://github.com/user-attachments/assets/0489cafd-8862-4f69-bb9e-a75a1932e6ae" />
<img width="2560" height="1528" alt="image" src="https://github.com/user-attachments/assets/fdefbfe1-a976-446d-a64e-377e186c1f4a" />

### Step 2: Install containerd (both nodes)

```bash
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg lsb-release
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null
sudo apt-get update
sudo apt-get install -y containerd.io
```

Configure the systemd cgroup driver. containerd and the kubelet must use the same driver:

```bash
sudo mkdir -p /etc/containerd
containerd config default | sudo tee /etc/containerd/config.toml > /dev/null
sudo sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
sudo systemctl enable containerd
sudo systemctl restart containerd
systemctl is-active containerd
```

<img width="2560" height="1528" alt="image" src="https://github.com/user-attachments/assets/a69d344d-3f69-4bc3-834f-da89f9e1323e" />
<img width="2560" height="1528" alt="image" src="https://github.com/user-attachments/assets/6de4be14-f736-4acc-b07a-1815e63e698f" />

### Step 3: Install kubeadm, kubelet and kubectl (both nodes)

```bash
sudo apt-get install -y apt-transport-https ca-certificates curl gpg
curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.28/deb/Release.key | sudo gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.28/deb/ /" | sudo tee /etc/apt/sources.list.d/kubernetes.list
sudo apt-get update
sudo apt-get install -y kubelet kubeadm kubectl
sudo apt-mark hold kubelet kubeadm kubectl
sudo systemctl enable kubelet
```

The packages are held so that a routine `apt upgrade` does not change the cluster version.

<img width="1684" height="364" alt="image" src="https://github.com/user-attachments/assets/b83d650a-8585-4b99-b666-c353c0169f6a" />
<img width="1441" height="363" alt="image" src="https://github.com/user-attachments/assets/b3ad5df3-04fc-48a0-a220-94ea76c1a9c1" />

### Step 4: Initialize the control plane (master-1)

The pod CIDR is `10.244.0.0/16` so that it does not overlap with the VM network (`192.168.217.0/24`).

```bash
sudo kubeadm init \
  --apiserver-advertise-address=192.168.217.132 \
  --pod-network-cidr=10.244.0.0/16 \
  --kubernetes-version=v1.28.15
```

<img width="2560" height="1528" alt="image" src="https://github.com/user-attachments/assets/dd63c720-c49e-4a3a-a238-ccb5041187e0" />

### Step 5: Configure kubectl (master-1)

```bash
mkdir -p $HOME/.kube
sudo cp -i /etc/kubernetes/admin.conf $HOME/.kube/config
sudo chown $(id -u):$(id -g) $HOME/.kube/config
kubectl get nodes
```

The control plane node reports `NotReady` until a CNI plugin is installed.

### Step 6: Join the worker node (worker-1)

On `master-1`, generate the join command:

```bash
kubeadm token create --print-join-command
```

Run the printed command on `worker-1` with `sudo`:

```bash
sudo kubeadm join 192.168.217.132:6443 \
  --token <TOKEN> \
  --discovery-token-ca-cert-hash sha256:<HASH>
```

<img width="2559" height="498" alt="image" src="https://github.com/user-attachments/assets/af63d549-aeca-411e-adf6-9b3a0d93106e" />

### Step 7: Install the Calico CNI (master-1)

```bash
kubectl create -f https://raw.githubusercontent.com/projectcalico/calico/v3.27.0/manifests/tigera-operator.yaml
curl -O https://raw.githubusercontent.com/projectcalico/calico/v3.27.0/manifests/custom-resources.yaml

# Match the pod CIDR used in kubeadm init
sed -i 's#192.168.0.0/16#10.244.0.0/16#' custom-resources.yaml

kubectl create -f custom-resources.yaml
kubectl get pods -n calico-system
```

Calico's default CIDR (`192.168.0.0/16`) contains the VM network, so it must be changed to the pod CIDR used during `kubeadm init`.

<img width="2560" height="1528" alt="image" src="https://github.com/user-attachments/assets/eb1352ee-643a-4a81-94c1-aaa2a3063123" />

### Step 8: Isolate the worker node with a taint and a label

The worker node is dedicated to the application workloads. A taint repels any pod that does not tolerate it, and a label lets pods target the node with a `nodeSelector`.

```bash
kubectl taint nodes worker-1 workload=ivolveApp:NoSchedule
kubectl label nodes worker-1 project=ivolve
```

Verify:

```bash
kubectl describe node worker-1 | grep -i taints
kubectl get nodes --show-labels
```

A pod is scheduled on `worker-1` only if it defines both of the following in its spec. The manifests in the next stage include them:

```yaml
nodeSelector:
  project: ivolve
tolerations:
  - key: "workload"
    operator: "Equal"
    value: "ivolveApp"
    effect: "NoSchedule"
```

Isolation test:

| Pod | Toleration | nodeSelector | Result |
|---|:---:|:---:|---|
| `no-toleration` (plain nginx) | No | No | `Pending`, rejected by taints |
| `ivolve-pod` | Yes | Yes | `Running` on `worker-1` |

<img width="2538" height="238" alt="image" src="https://github.com/user-attachments/assets/84b958f9-00bb-4ad8-b350-feb5a165de0c" />

## Verification

```bash
kubectl get nodes -o wide
kubectl get pods -A
kubectl get --raw='/readyz?verbose' | tail -n 5
```

Expected result: both nodes are `Ready`, all system pods are `Running`, and the API server readiness check passes.

Workload and DNS test:

```bash
kubectl create deployment nginx-test --image=nginx --replicas=2
kubectl run dns-test --image=busybox:1.28 --restart=Never -- nslookup kubernetes.default
kubectl logs dns-test
```

`kubernetes.default` resolves to `10.96.0.1`. Remove the test resources afterwards:

```bash
kubectl delete deployment nginx-test
kubectl delete pod dns-test
```

<img width="1179" height="124" alt="image" src="https://github.com/user-attachments/assets/4dec6909-49a6-4b0f-aaed-5bd669ef602d" />

## Key Concepts

| Concept | Purpose |
|---|---|
| Swap disabled | The scheduler assumes real memory, and the kubelet refuses to start with swap enabled |
| `overlay` and `br_netfilter` | Container image layers, and iptables visibility on bridged traffic |
| `ip_forward` | Allows nodes to route pod traffic between each other |
| `SystemdCgroup = true` | containerd and the kubelet must use the same cgroup driver |
| CNI (Calico) | Assigns pod IPs and connects pods across nodes; nodes stay `NotReady` without it |
| Pod CIDR vs node network | They must not overlap, otherwise routing breaks |
| Taint and label | Dedicate `worker-1` to the application workloads |

## Next Stage

Continue to [Stage 3: Kubernetes Manifests](../03-k8s-manifests/README.md), which deploys the application and the database onto this cluster.
