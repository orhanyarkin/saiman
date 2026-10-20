-- V9: correct the history under the old rule (ADR-0029, step 7a/7b). Runs as ledger_owner.
--
-- Until ADR-0029 a reconciliation difference was posted between a wallet and `platform:suspense:usdc`, so the entry
-- balanced but neither book did. This migration appends the correction, it never edits: for every ADJUSTMENT whose
-- postings span more than one account book it posts a REVERSAL of that entry (itself cross-book, which only the old
-- rule permits -- hence a migration of its own, before V10 tightens the trigger) and then one replacement ADJUSTMENT
-- per affected book, against that book's own suspense account.
--
-- The replacement's id is the one the new code derives, so a human comparing the two can see they agree:
-- UUID.nameUUIDFromBytes("saiman-ledger:entry:<paymentKey>:<BOOK>:ADJUSTMENT:<runId>"), a version-3 (MD5) UUID,
-- with runId taken from the reconciliation_mismatch row that points at the original entry, or failing that from the
-- reconciliation_item row of the run that posted it. The id is *not* what keeps a rerun from duplicating: runId is
-- generated per run, so no later run re-derives it; idempotency comes from payment_id being set on both the REVERSAL
-- and the replacement, so the recomputed difference is zero (ADR-0029, decision 7b).
--
-- An adjustment that no reconciliation_mismatch row points at is normal, not an error: insertMismatch is
-- `ON CONFLICT (payment_id, kind) DO NOTHING`, so a second adjustment for the same payment and kind is never linked
-- (the tamper demo run twice, or a split settlement whose seller event arrives after the first run). Where neither
-- table knows the run, the replacement id is derived from the original entry instead and the correction is posted
-- anyway: this migration must never abort over an unknown run id, because a failed migration leaves Flyway's schema
-- history broken and the ledger service unable to start, to protect an idempotency property that does not exist.
--
-- A database without a cross-book ADJUSTMENT (any fresh one) finds no targets and does nothing.

-- Java's UUID.nameUUIDFromBytes in SQL: MD5 of the name, then version 3 (byte 6 high nibble = 3) and the RFC 4122
-- variant (byte 8 high two bits = 10). In the 32-character hex digest byte 6 is characters 13-14 and byte 8 is
-- characters 17-18, so only characters 13 and 17 change. Dropped again at the end: the schema keeps no helper.
CREATE FUNCTION name_uuid_v3(p_name text) RETURNS uuid
    LANGUAGE sql IMMUTABLE AS
$$
WITH digest(h) AS (SELECT md5(p_name))
SELECT (substr(h, 1, 12) || '3' || substr(h, 14, 3)
            || to_hex((('x' || substr(h, 17, 1))::bit(4)::integer & 3) | 8) || substr(h, 18, 15))::uuid
  FROM digest;
$$;

DO
$$
DECLARE
    v_original    record;
    v_book        record;
    v_asset       record;
    v_run         uuid;
    v_suffix      text;
    v_reversal    uuid;
    v_replacement uuid;
    v_suspense    text;
BEGIN
    FOR v_original IN
        SELECT e.id, e.payment_id, e.payment_key, e.book, e.effective_at
          FROM journal_entry e
         WHERE e.kind = 'ADJUSTMENT'
           AND (SELECT count(DISTINCT a.book)
                  FROM posting p
                  JOIN account a ON a.code = p.account_code AND a.asset = p.asset AND a.decimals = p.decimals
                 WHERE p.entry_id = e.id) > 1
         ORDER BY e.recorded_at, e.id
    LOOP
        IF v_original.payment_id IS NULL OR v_original.payment_key IS NULL THEN
            RAISE EXCEPTION 'cross-book ADJUSTMENT % is not about a payment, so it cannot be replaced', v_original.id;
        END IF;
        -- The run that posted it; the replacement's id is derived from it (see the header). The finding is the usual
        -- source, but only the first adjustment per (payment, kind) is linked there, so the run's own item row is
        -- the second place to look: ReconciliationService passes the adjustment id into insertItem as well.
        v_run := NULL;
        SELECT m.run_id INTO v_run
          FROM reconciliation_mismatch m
         WHERE m.adjustment_entry_id = v_original.id
         ORDER BY m.detected_at, m.id
         LIMIT 1;
        IF v_run IS NULL THEN
            SELECT i.run_id INTO v_run
              FROM reconciliation_item i
             WHERE i.adjustment_entry_id = v_original.id
             ORDER BY i.checked_at, i.run_id
             LIMIT 1;
        END IF;
        -- Neither table knows the run: name the replacement after the entry it replaces and post anyway. Never RAISE.
        v_suffix := CASE WHEN v_run IS NULL THEN 'replaces:' || v_original.id ELSE v_run::text END;

        -- (a) Reverse it: the opposite postings, same accounts, naming the entry it reverses. Deterministic id, so
        -- re-running this migration on a copy of the same database produces the same books.
        v_reversal := name_uuid_v3('saiman-ledger:entry:' || v_original.payment_key || ':' || v_original.book
                                   || ':REVERSAL:ADJUSTMENT:' || v_original.id);
        INSERT INTO journal_entry (id, payment_id, payment_key, book, kind, reverses_entry_id, description,
                                   effective_at)
        VALUES (v_reversal, v_original.payment_id, v_original.payment_key, v_original.book, 'REVERSAL', v_original.id,
                'ADR-0029: cross-book adjustment reversed; suspense moves into the book that owns the difference',
                v_original.effective_at);
        INSERT INTO posting (entry_id, account_code, side, amount_atomic, asset, decimals)
        SELECT v_reversal, p.account_code, CASE p.side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END, p.amount_atomic,
               p.asset, p.decimals
          FROM posting p
         WHERE p.entry_id = v_original.id;

        -- (b) Post it again, one entry per affected book: that book's wallet legs unchanged, the mirror leg on that
        -- book's suspense account. The net effect on every wallet is zero and platform:suspense:usdc returns to zero.
        FOR v_book IN
            SELECT a.book
              FROM posting p
              JOIN account a ON a.code = p.account_code AND a.asset = p.asset AND a.decimals = p.decimals
             WHERE p.entry_id = v_original.id AND a.book <> 'PLATFORM'
             GROUP BY a.book
             ORDER BY a.book
        LOOP
            v_replacement := name_uuid_v3('saiman-ledger:entry:' || v_original.payment_key || ':' || v_book.book
                                          || ':ADJUSTMENT:' || v_suffix);
            -- Two adjustments of one payment and book credited to the same run would derive one id; take the
            -- entry-derived form for the later one rather than failing on the primary key.
            IF EXISTS (SELECT 1 FROM journal_entry e WHERE e.id = v_replacement) THEN
                v_replacement := name_uuid_v3('saiman-ledger:entry:' || v_original.payment_key || ':' || v_book.book
                                              || ':ADJUSTMENT:replaces:' || v_original.id);
            END IF;
            INSERT INTO journal_entry (id, payment_id, payment_key, book, kind, description, effective_at)
            VALUES (v_replacement, v_original.payment_id, v_original.payment_key, v_book.book, 'ADJUSTMENT',
                    'Reconciliation: difference moved to this book''s suspense account (ADR-0029)',
                    v_original.effective_at);
            INSERT INTO posting (entry_id, account_code, side, amount_atomic, asset, decimals)
            SELECT v_replacement, p.account_code, p.side, p.amount_atomic, p.asset, p.decimals
              FROM posting p
              JOIN account a ON a.code = p.account_code AND a.asset = p.asset AND a.decimals = p.decimals
             WHERE p.entry_id = v_original.id AND a.book = v_book.book;

            FOR v_asset IN
                SELECT p.asset, p.decimals, min(a.wallet) AS wallet,
                       sum(CASE p.side WHEN 'DEBIT' THEN p.amount_atomic ELSE -p.amount_atomic END) AS net
                  FROM posting p
                  JOIN account a ON a.code = p.account_code AND a.asset = p.asset AND a.decimals = p.decimals
                 WHERE p.entry_id = v_original.id AND a.book = v_book.book
                 GROUP BY p.asset, p.decimals
                HAVING sum(CASE p.side WHEN 'DEBIT' THEN p.amount_atomic ELSE -p.amount_atomic END) <> 0
                 ORDER BY p.asset
            LOOP
                -- buyer:<payer>:suspense:usdc / seller:<payTo>:suspense:usdc, created on first use like every account.
                v_suspense := lower(v_book.book) || ':' || v_asset.wallet || ':suspense:' || lower(v_asset.asset);
                INSERT INTO account (code, book, type, asset, decimals, wallet)
                VALUES (v_suspense, v_book.book, 'SUSPENSE', v_asset.asset, v_asset.decimals, v_asset.wallet)
                ON CONFLICT DO NOTHING;
                INSERT INTO posting (entry_id, account_code, side, amount_atomic, asset, decimals)
                VALUES (v_replacement, v_suspense, CASE WHEN v_asset.net > 0 THEN 'CREDIT' ELSE 'DEBIT' END,
                        abs(v_asset.net), v_asset.asset, v_asset.decimals);
            END LOOP;
        END LOOP;
    END LOOP;
END
$$;

DROP FUNCTION name_uuid_v3(text);
