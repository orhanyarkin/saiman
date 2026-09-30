-- V2: disclosures MKK lists but cannot serve (ER005/ER008 on the detail call) are recorded as MISSING.
ALTER TABLE source_document DROP CONSTRAINT source_document_status_check;
ALTER TABLE source_document ADD CONSTRAINT source_document_status_check
    CHECK (status IN ('PENDING', 'INDEXED', 'FAILED', 'SUPERSEDED', 'BLOCKED', 'MISSING'));
