#!/usr/bin/env bash
# Creates the secrets the Helm chart references, with freshly generated values. For real environments prefer
# External Secrets / Sealed Secrets backed by a secret manager; this script is for a dev or test cluster.
# Nothing is written to disk.
set -euo pipefail
NS=${NS:-marketflow}
rand() { openssl rand -hex 16; }

args=()
for svc in identity vendor catalog inventory order payment notification review analytics; do
  args+=("--from-literal=${svc}-password=$(rand)")
done
kubectl -n "$NS" create secret generic marketflow-db "${args[@]}" --dry-run=client -o yaml | kubectl apply -f -
kubectl -n "$NS" create secret generic marketflow-payment --from-literal=webhook-secret="$(rand)" \
  --dry-run=client -o yaml | kubectl apply -f -
# RSA 2048 signing key for identity-service (PKCS#8 PEM)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 2>/dev/null \
  | kubectl -n "$NS" create secret generic marketflow-jwt --from-file=private-key.pem=/dev/stdin \
      --dry-run=client -o yaml | kubectl apply -f -
echo "Created marketflow-db, marketflow-payment, marketflow-jwt in namespace $NS."
echo "The database passwords must also be set on the PostgreSQL roles (see infrastructure/postgres/init)."
