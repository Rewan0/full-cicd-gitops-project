#!/usr/bin/env bash
# Run on both nodes. Set the hostname and /etc/hosts entries first (see README, Step 1).
set -euo pipefail

sudo swapoff -a
sudo sed -i '/ swap / s/^\(.*\)$/#\1/g' /etc/fstab

sudo tee /etc/modules-load.d/k8s.conf >/dev/null <<'EOT'
overlay
br_netfilter
EOT
sudo modprobe overlay
sudo modprobe br_netfilter

sudo tee /etc/sysctl.d/k8s.conf >/dev/null <<'EOT'
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOT
sudo sysctl --system
