-- Runs once, as the bootstrap superuser, the first time the data volume is
-- initialized (standard /docker-entrypoint-initdb.d behavior).
--
-- The app must NOT connect as the bootstrap POSTGRES_USER: that role is
-- always a superuser (how the official postgres image sets up initdb), and
-- superusers bypass Row-Level Security unconditionally, no matter what
-- ENABLE/FORCE ROW LEVEL SECURITY says (spec section 8's whole point). This
-- separate, ordinary login role is what the backend and Flyway actually
-- connect as, so FORCE ROW LEVEL SECURITY genuinely applies to it.
--
-- CREATE EXTENSION also needs a superuser (or an explicitly trusted
-- extension) in most Postgres setups, so it happens here too rather than in
-- a Flyway migration running as the restricted app role.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE ROLE turnos_app LOGIN PASSWORD 'turnos_app';
GRANT CREATE, USAGE ON SCHEMA public TO turnos_app;
