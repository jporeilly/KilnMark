-- The library (ADR 0043): pipelines and flows added from Kilnmark's samples are marked, so the
-- Library page lists them and the Pipelines and Flows pages, which hold a team's own work, do not.
-- NULL is a team's own pipeline or flow; 'sample' one added from the samples.

SET search_path = kilnmark, public;

ALTER TABLE pipeline ADD COLUMN library text CHECK (library IS NULL OR library = 'sample');
ALTER TABLE flow ADD COLUMN library text CHECK (library IS NULL OR library = 'sample');

-- Samples added before this marking, by their fixed names: the samples bundled with 0.0.9 and the
-- pipeline that creates the sample tables. Only the samples' own names, never a pattern, so a team's
-- own pipeline is never moved.
UPDATE pipeline SET library = 'sample' WHERE name IN ('sample-tables',
    'sample-read-columns-where', 'sample-read-query', 'sample-read-write-csv', 'sample-write-parquet',
    'sample-filter', 'sample-limit', 'sample-route', 'sample-compute', 'sample-dedupe', 'sample-select',
    'sample-lookup', 'sample-union', 'sample-catalogue-summary', 'sample-aggregate', 'sample-gate-quarantine',
    'sample-gate-stop', 'sample-gate-warn', 'sample-orders-by-status', 'sample-server-variable', 'sample-volume');
