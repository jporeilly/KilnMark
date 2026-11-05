-- A flow's wait-for node may run a query a person wrote (ADR 0041, ADR 0028). Before Kilnmark knew the
-- node, a version holding one was saved as an unknown type, by anyone who could save a flow, and
-- marked sql_checked (V29 wrote true for every version). The YAML's text cannot say which versions
-- hold one (keys and values may be quoted, with escapes), so whether a version's author may write
-- SQL is recorded anew: query_checked, false for every version saved before this, and from now on
-- whether whoever saved it held the unmask grant. A version with a wait-for query runs only when it
-- is true, so an old one runs its query only once a designer who may see unmasked values saves it
-- again. Nothing else changes. Refers only to V20 (flow_version).

SET search_path = kilnmark, public;

ALTER TABLE flow_version ADD COLUMN query_checked boolean NOT NULL DEFAULT false;
