-- A flow with a sql node is saved only by a designer who may see unmasked values (ADR 0028).
-- Versions saved before that check existed may hold one nobody with that grant wrote: before it,
-- sql was an unknown node type, so any designer or admin could save it as a draft, and the server
-- now reads it as valid. sql_checked is true for every version saved from now on, all of which
-- passed the check; a version without it that has a sql node does not run until it is saved again.
-- Refers only to V20 (flow_version).

SET search_path = kilnmark, public;

ALTER TABLE flow_version ADD COLUMN sql_checked boolean NOT NULL DEFAULT false;
