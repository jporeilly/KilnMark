-- Server settings: switches an admin sets for the whole server, one row per setting, such as
-- whether the samples are offered. A setting not stored has its default, kept in the code.

SET search_path = kilnmark, public;

CREATE TABLE server_setting (
    key         text PRIMARY KEY CHECK (key ~ '^[a-z][a-z0-9_.]{0,63}$'),
    value       text NOT NULL,
    updated_by  text NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);
