-- V4: the sanitised result of each paid tool call, so an identical call later in the same run
-- (same tool, same code-rendered arguments) is answered from here and paid only once (ADR-0013).
-- Only the allowlisted, cleaned fields are stored (ToolResultSanitizer), never the raw seller body.

CREATE TABLE tool_result (
    payment_intent_id uuid PRIMARY KEY REFERENCES payment_intent (id),
    run_id            uuid        NOT NULL REFERENCES run (id),
    tool              text        NOT NULL,
    args_hash         text        NOT NULL,
    result            jsonb       NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX tool_result_lookup_idx ON tool_result (run_id, tool, args_hash);
