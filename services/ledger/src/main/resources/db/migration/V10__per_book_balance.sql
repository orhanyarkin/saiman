-- V10: tighten the rule (ADR-0029, decision 1 and step 7). Runs as ledger_owner.
--
-- An entry now belongs to exactly one book: every posting's account book equals the entry's book. Per-book balance
-- per asset then follows from per-entry balance, so "this book balances" is an invariant the database enforces
-- instead of a claim the dashboard makes.
--
-- Why this is a migration of its own. `journal_entry_balanced` and `posting_balanced` are DEFERRABLE INITIALLY
-- DEFERRED constraint triggers, so their events fire at COMMIT, and CREATE OR REPLACE FUNCTION keeps the function's
-- OID: replacing the body in V9's transaction would check V9's deliberately cross-book REVERSAL against the new rule
-- and fail the migration. Flyway runs each migration in its own transaction (`group` is not set), so V9 is checked
-- and committed under the old body before this body exists. Constraint triggers fire per row on INSERT, so the rows
-- V9 wrote are never re-checked here.

-- Safety net for a database where V9 found no target but the accounts are expected: the suspense account of every
-- book that already carries an ADJUSTMENT. After V9 this is a no-op, because V9 creates them on first use.
INSERT INTO account (code, book, type, asset, decimals, wallet)
SELECT DISTINCT lower(a.book) || ':' || a.wallet || ':suspense:' || lower(a.asset), a.book, 'SUSPENSE', a.asset,
                a.decimals, a.wallet
  FROM account a
 WHERE a.book IN ('BUYER', 'SELLER') AND a.wallet IS NOT NULL AND a.type <> 'SUSPENSE'
   AND EXISTS (SELECT 1
                 FROM posting p
                 JOIN journal_entry e ON e.id = p.entry_id
                WHERE p.account_code = a.code AND p.asset = a.asset AND e.kind = 'ADJUSTMENT' AND e.book = a.book)
ON CONFLICT DO NOTHING;

CREATE OR REPLACE FUNCTION check_entry_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_entry      uuid;
    v_postings   integer;
    v_unbalanced integer;
    v_foreign    integer;
BEGIN
    IF TG_TABLE_NAME = 'posting' THEN
        v_entry := NEW.entry_id;
    ELSE
        v_entry := NEW.id;
    END IF;

    SELECT count(*) INTO v_postings FROM ${flyway:defaultSchema}.posting WHERE entry_id = v_entry;
    IF v_postings < 2 THEN
        RAISE EXCEPTION 'journal entry % has % posting(s); at least two are required', v_entry, v_postings
            USING ERRCODE = 'check_violation';
    END IF;

    SELECT count(*) INTO v_unbalanced
      FROM (SELECT asset, decimals
              FROM ${flyway:defaultSchema}.posting
             WHERE entry_id = v_entry
             GROUP BY asset, decimals
            HAVING sum(CASE side WHEN 'DEBIT' THEN amount_atomic ELSE -amount_atomic END) <> 0) AS unbalanced;
    IF v_unbalanced > 0 THEN
        RAISE EXCEPTION 'journal entry % is unbalanced: debits differ from credits for % asset(s)', v_entry, v_unbalanced
            USING ERRCODE = 'check_violation';
    END IF;

    -- One book per entry (ADR-0029): with balance per entry and per asset, every book balances per asset.
    SELECT count(*) INTO v_foreign
      FROM ${flyway:defaultSchema}.posting p
      JOIN ${flyway:defaultSchema}.account a
        ON a.code = p.account_code AND a.asset = p.asset AND a.decimals = p.decimals
      JOIN ${flyway:defaultSchema}.journal_entry e ON e.id = p.entry_id
     WHERE p.entry_id = v_entry AND a.book <> e.book;
    IF v_foreign > 0 THEN
        RAISE EXCEPTION 'journal entry % crosses books: % posting(s) are on an account of another book',
            v_entry, v_foreign
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;
