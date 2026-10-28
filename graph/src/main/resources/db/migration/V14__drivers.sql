-- Drivers the customer installs (docs/design/drivers.md): who accepted each driver's licence, on
-- the organisation's behalf, before the server downloaded it. One row per driver and version;
-- accepting again (after the row was removed) records the new admin and time.

SET search_path = kilnmark, public;

CREATE TABLE driver_licence (
    coordinates  text NOT NULL CHECK (length(coordinates) BETWEEN 3 AND 200),
    version      text NOT NULL CHECK (length(version) BETWEEN 1 AND 100),
    licence      text NOT NULL CHECK (length(licence) BETWEEN 1 AND 300),
    licence_url  text NOT NULL CHECK (length(licence_url) BETWEEN 1 AND 500),
    accepted_by  text NOT NULL CHECK (length(accepted_by) BETWEEN 1 AND 300),
    accepted_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (coordinates, version)
);
