-- Measured quality of pipeline runs (docs/design/data-quality.md sections 7 and 8,
-- docs/guide/pipelines.md "Quality gates" and "Quality of what a run writes"): for each write step
-- (an output) and each gate step of a run, its measured score per dimension and overall, and for a
-- gate what it decided and each rule's result. "predicted" sits beside "measured" for the scores
-- worked out at design time from the inputs' metadata, which a later change fills; it is null until
-- then. Refers only to V1 (install_info) and V12 (pipeline_run).

SET search_path = kilnmark, public;

CREATE TABLE pipeline_step_quality (
    run_id                bigint NOT NULL REFERENCES pipeline_run (id) ON DELETE CASCADE,
    step_id               text NOT NULL,
    role                  text NOT NULL CHECK (role IN ('output', 'gate')),
    -- the asset written (output), or the asset whose rules the gate checked; null for none
    asset_urn             text,
    -- overall score from 0 to 1; null when not measured (an append is not profiled, a gate with no rules)
    measured              double precision CHECK (measured IS NULL OR measured BETWEEN 0 AND 1),
    measured_dimensions   jsonb NOT NULL DEFAULT '{}',   -- {"completeness": 0.98, "validity": 1.0, ...}
    measured_reasons      jsonb NOT NULL DEFAULT '[]',   -- words and counts only, never values from the data
    predicted             double precision CHECK (predicted IS NULL OR predicted BETWEEN 0 AND 1),
    predicted_dimensions  jsonb,
    -- why there is no measured score, in words, or null
    note                  text,
    -- gates only: {onFail, decision, rowsIn, rowsPassed, rowsFailing, rowsQuarantined, minScore,
    --  maxFailingShare, stopReason, rules: [{name, type, field, dimension, origin, status, failing, total,
    --  message}]}; counts and rule names only, no failing rows are kept
    gate                  jsonb,
    PRIMARY KEY (run_id, step_id)
);

INSERT INTO install_info (key, value) VALUES ('schema_pipeline_quality', '14')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now();
