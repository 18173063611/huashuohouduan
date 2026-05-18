#!/usr/bin/env bash
set -euo pipefail

# Close middleware ports on the server host. Keep SSH and loopback access open.
# Cloud security groups should also restrict these ports; this script is a host-level backstop.

INTERNAL_TCP_PORTS=(3306 6379 5672 15672)
SSH_PORT="${SSH_PORT:-22}"

need_root() {
  if [ "${EUID:-$(id -u)}" -ne 0 ]; then
    echo "Run as root." >&2
    exit 1
  fi
}

has_cmd() {
  command -v "$1" >/dev/null 2>&1
}

lock_with_ufw() {
  ufw allow in on lo >/dev/null || true
  ufw allow "${SSH_PORT}/tcp" >/dev/null
  for port in "${INTERNAL_TCP_PORTS[@]}"; do
    ufw deny in to any port "$port" proto tcp >/dev/null || true
  done
  echo "Applied ufw deny rules for: ${INTERNAL_TCP_PORTS[*]}"
}

lock_with_firewalld() {
  firewall-cmd --permanent --add-service=ssh >/dev/null || true
  firewall-cmd --permanent --zone=trusted --add-interface=lo >/dev/null 2>&1 || true
  for port in "${INTERNAL_TCP_PORTS[@]}"; do
    firewall-cmd --permanent --remove-port="${port}/tcp" >/dev/null 2>&1 || true
    firewall-cmd --permanent --add-rich-rule="rule family='ipv4' port port='${port}' protocol='tcp' drop" >/dev/null
  done
  firewall-cmd --reload >/dev/null
  echo "Applied firewalld drop rules for: ${INTERNAL_TCP_PORTS[*]}"
}

lock_with_iptables() {
  for port in "${INTERNAL_TCP_PORTS[@]}"; do
    if ! iptables -C INPUT -i lo -p tcp --dport "$port" -j ACCEPT >/dev/null 2>&1; then
      iptables -I INPUT 1 -i lo -p tcp --dport "$port" -j ACCEPT
    fi
    if ! iptables -C INPUT ! -i lo -p tcp --dport "$port" -j DROP >/dev/null 2>&1; then
      iptables -I INPUT 2 ! -i lo -p tcp --dport "$port" -j DROP
    fi
  done
  echo "Applied runtime iptables DROP rules for: ${INTERNAL_TCP_PORTS[*]}"
  echo "Persist iptables rules with your distro's standard tool after verifying access."
}

need_root

if has_cmd ufw && ufw status | grep -qi "Status: active"; then
  lock_with_ufw
elif has_cmd firewall-cmd && firewall-cmd --state >/dev/null 2>&1; then
  lock_with_firewalld
elif has_cmd iptables; then
  lock_with_iptables
else
  echo "No supported firewall tool found. Restrict ports in the cloud security group." >&2
  exit 1
fi

echo "Verify externally: 3306, 6379, 5672, and 15672 should be closed; 22 should remain open."
