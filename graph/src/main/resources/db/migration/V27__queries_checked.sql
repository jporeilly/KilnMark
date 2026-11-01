-- A read that runs a query is saved only by a designer who may see unmasked values (ADR 0028).
-- Versions saved before that check existed may hold a query nobody with that grant wrote: before
-- it, query was an unknown setting, so any designer or admin could save it as a draft, and the
-- server now reads it as valid. queries_checked is true for every version saved from now on, all
-- of which passed the check; a version without it that has a query read does not run until it is
-- saved again.
-- Refers only to V12 (pipeline_version).

SET search_path = kilnmark, public;

ALTER TABLE pipeline_version ADD COLUMN queries_checked boolean NOT NULL DEFAULT false;
