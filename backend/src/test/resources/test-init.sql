-- Test-environment equivalent of postgres-init/init-app-role.sql: see that
-- file for why tests must NOT run against Testcontainers' bootstrap
-- superuser role either, or the RLS tests would pass for the wrong reason
-- (no isolation enforced at all).
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE ROLE turnos_app LOGIN PASSWORD 'turnos_app_pw';
GRANT CREATE, USAGE ON SCHEMA public TO turnos_app;
