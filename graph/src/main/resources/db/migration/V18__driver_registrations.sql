-- JDBC drivers an admin registered for the generic JDBC connector (docs/design/drivers.md,
-- "Registered drivers"; ADR 0025): a jar in the drivers folder, its driver class, the URLs it takes,
-- and the SHA-256 it had when it was registered, which it must keep to be loaded. One row per
-- registration, removed when an admin removes it; driver_registration_log keeps who registered and
-- removed what, and when, for good.

SET search_path = kilnmark, public;

CREATE TABLE driver_registration (
    name          text PRIMARY KEY CHECK (name ~ '^[a-z0-9][a-z0-9_-]{0,39}$'),
    file_name     text NOT NULL CHECK (length(file_name) BETWEEN 5 AND 200 AND file_name !~ '[/\\]'),
    sha256        text NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    driver_class  text NOT NULL CHECK (length(driver_class) BETWEEN 1 AND 300),
    url_prefix    text NOT NULL CHECK (url_prefix ~ '^jdbc:' AND length(url_prefix) <= 100),
    -- the driver properties sources may set, lower case: an allow-list the admin gives
    allowed_properties text[] NOT NULL DEFAULT '{}' CHECK (cardinality(allowed_properties) <= 200),
    registered_by text NOT NULL CHECK (length(registered_by) BETWEEN 1 AND 300),
    registered_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE driver_registration_log (
    id            bigserial PRIMARY KEY,
    action        text NOT NULL CHECK (action IN ('registered', 'removed')),
    name          text NOT NULL,
    file_name     text NOT NULL,
    sha256        text NOT NULL,
    driver_class  text NOT NULL,
    url_prefix    text NOT NULL,
    allowed_properties text[] NOT NULL DEFAULT '{}',
    done_by       text NOT NULL CHECK (length(done_by) BETWEEN 1 AND 300),
    done_at       timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX driver_registration_log_name ON driver_registration_log (name, done_at);

-- The log is kept for good: rows are only ever added.
CREATE FUNCTION driver_registration_log_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'driver_registration_log is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER driver_registration_log_no_change
    BEFORE UPDATE OR DELETE ON driver_registration_log
    FOR EACH ROW EXECUTE FUNCTION driver_registration_log_append_only();

CREATE TRIGGER driver_registration_log_no_truncate
    BEFORE TRUNCATE ON driver_registration_log
    FOR EACH STATEMENT EXECUTE FUNCTION driver_registration_log_append_only();
