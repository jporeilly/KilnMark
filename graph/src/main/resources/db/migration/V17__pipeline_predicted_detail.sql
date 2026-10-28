-- Predicted quality of pipeline runs (docs/design/data-quality.md section 8, docs/guide/pipelines.md
-- "Predicted quality"): when a run starts, each write and gate gets the score Kilnmark predicts from
-- the catalogue's metadata about what the run reads, in V15's "predicted" and
-- "predicted_dimensions". This adds what the prediction says beside the numbers: its main reasons,
-- what it could not tell, what it assumed, the rows it expected and, for a gate, what it expected
-- the gate to do. Words, counts and shares only, never values from the data. Refers only to V1
-- (install_info) and V15 (pipeline_step_quality); V17, as V16 is the generic JDBC change's.

SET search_path = kilnmark, public;

-- {"reasons": [...], "unknown": [...], "assumptions": [...], "rows": 1234,
--  "gate": {onFail, rowsIn, rowsPassed, rowsQuarantined, decision, rules: [{name, type, field, dimension,
--  origin, failingShare, status, basis}]}}; null when nothing was predicted
ALTER TABLE pipeline_step_quality ADD COLUMN predicted_detail jsonb;

INSERT INTO install_info (key, value) VALUES ('schema_pipeline_predicted', '17')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();
