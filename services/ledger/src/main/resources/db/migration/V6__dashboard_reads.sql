-- M5 dashboard reads (ADR-0022): keyset pages over payments, newest first, optionally per agent run.
CREATE INDEX payment_newest_first ON payment (created_at DESC, id DESC);
CREATE INDEX payment_by_run_newest_first ON payment (run_id, created_at DESC, id DESC) WHERE run_id IS NOT NULL;
