-- Indexes for the unauthenticated dashboard reads (polled every 2-5 s): keep each one an index scan.

-- GET /api/v1/runs: newest-first keyset page on (created_at, id).
CREATE INDEX run_created_idx ON run (created_at DESC, id DESC);

-- GET /api/v1/approvals?status=...: one status, newest request first.
CREATE INDEX approval_status_requested_idx ON approval (status, requested_at DESC, id DESC);

-- GET /api/v1/spend byTool: an intent belongs to its reserved_day, or (never reserved) to its UTC
-- creation day. One index serves both arms of the OR (reserved_day = d; reserved_day IS NULL plus a
-- created_at range); Postgres combines the two index probes with a BitmapOr.
CREATE INDEX payment_intent_day_idx ON payment_intent (reserved_day, created_at);
