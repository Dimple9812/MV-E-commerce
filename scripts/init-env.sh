#!/usr/bin/env bash
# Creates .env from .env.example, replacing every change-me placeholder with a random value.
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && { echo ".env exists; delete it first to regenerate" >&2; exit 1; }
rand() { openssl rand -hex 12; }
while IFS= read -r line; do
  if [[ "$line" =~ ^([A-Z0-9_]+)=change-me ]]; then
    v=$(rand)
    # the admin password must satisfy the password policy (letters and a digit)
    [[ "${BASH_REMATCH[1]}" == BOOTSTRAP_ADMIN_PASSWORD ]] && v="${v}a1"
    echo "${BASH_REMATCH[1]}=${v}"
  else
    echo "$line"
  fi
done < .env.example > .env
chmod 600 .env
echo "Wrote .env. Admin login: $(grep ^BOOTSTRAP_ADMIN_EMAIL .env | cut -d= -f2) / see BOOTSTRAP_ADMIN_PASSWORD in .env"
