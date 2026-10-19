#!/bin/bash
# Idempotently creates one service database and its least-privilege login role on an already running server.
#
# The init script (init/01-create-service-databases.sh) only runs when the data volume is first created, so a
# database added later (chat_db) would be missing on existing installations. Compose runs this script as a
# one-shot container before the service starts; on a fresh volume it finds everything in place and does nothing.
#
#   ensure-service-database.sh <database> <role> <password>      (PGHOST, PGUSER, PGPASSWORD from the environment)
set -euo pipefail
db="$1" user="$2" password="$3"
[[ "$db" =~ ^[a-z_]+$ && "$user" =~ ^[a-z_]+$ ]] || { echo "invalid database or role name" >&2; exit 2; }
password_sql=${password//\'/\'\'}

psql -v ON_ERROR_STOP=1 --dbname postgres <<-SQL
  DO \$\$
  BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${user}') THEN
      CREATE ROLE ${user} LOGIN PASSWORD '${password_sql}' NOSUPERUSER NOCREATEDB NOCREATEROLE;
    END IF;
  END \$\$;
SQL

if psql --dbname postgres -tAc "SELECT 1 FROM pg_database WHERE datname = '${db}'" | grep -q 1; then
  echo "${db} already exists"
  exit 0
fi
echo "Creating database ${db} owned by ${user}"
psql -v ON_ERROR_STOP=1 --dbname postgres -c "CREATE DATABASE ${db} OWNER ${user}"
psql -v ON_ERROR_STOP=1 --dbname postgres <<-SQL
  REVOKE ALL ON DATABASE ${db} FROM PUBLIC;
  GRANT CONNECT, TEMPORARY ON DATABASE ${db} TO ${user};
SQL
psql -v ON_ERROR_STOP=1 --dbname "${db}" <<-SQL
  REVOKE CREATE ON SCHEMA public FROM PUBLIC;
  ALTER SCHEMA public OWNER TO ${user};
SQL
