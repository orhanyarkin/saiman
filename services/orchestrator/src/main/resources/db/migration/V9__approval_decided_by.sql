-- V9: who decided an approval (ADR-0023 audit trail).
-- The authenticated principal name, "<role>:<8 hex of the token digest>" (never a token). NULL while PENDING and when
-- the approval expired (nobody decided); approvals decided before authentication existed stay NULL.
ALTER TABLE approval ADD COLUMN decided_by text;
