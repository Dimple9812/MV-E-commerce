#!/bin/bash
# Creates one database and one least-privilege login role per service.
# Each role owns only its own database; it cannot connect to any other service's database.
# Runs once, on first initialisation of the postgres data volume.
set -euo pipefail

create_service_db() {
  local db="$1" user="$2" password="$3"
  echo "Creating database ${db} owned by ${user}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-SQL
    DO \$\$
    BEGIN
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${user}') THEN
        CREATE ROLE ${user} LOGIN PASSWORD '${password}' NOSUPERUSER NOCREATEDB NOCREATEROLE;
      END IF;
    END \$\$;
    CREATE DATABASE ${db} OWNER ${user};
    REVOKE ALL ON DATABASE ${db} FROM PUBLIC;
    GRANT CONNECT, TEMPORARY ON DATABASE ${db} TO ${user};
SQL
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "${db}" <<-SQL
    REVOKE CREATE ON SCHEMA public FROM PUBLIC;
    ALTER SCHEMA public OWNER TO ${user};
SQL
}

create_service_db identity_db     identity_svc     "${IDENTITY_DB_PASSWORD:?}"
create_service_db vendor_db       vendor_svc       "${VENDOR_DB_PASSWORD:?}"
create_service_db catalog_db      catalog_svc      "${CATALOG_DB_PASSWORD:?}"
create_service_db inventory_db    inventory_svc    "${INVENTORY_DB_PASSWORD:?}"
create_service_db order_db        order_svc        "${ORDER_DB_PASSWORD:?}"
create_service_db payment_db      payment_svc      "${PAYMENT_DB_PASSWORD:?}"
create_service_db notification_db notification_svc "${NOTIFICATION_DB_PASSWORD:?}"
create_service_db review_db       review_svc       "${REVIEW_DB_PASSWORD:?}"
create_service_db analytics_db    analytics_svc    "${ANALYTICS_DB_PASSWORD:?}"
create_service_db chat_db         chat_svc         "${CHAT_DB_PASSWORD:?}"
