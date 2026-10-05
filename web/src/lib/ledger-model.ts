/**
 * Pure helpers for the ledger screens (no React): route-param validation, plain-language state
 * text, the trial-balance check and the run page's "In the ledger" polling rule.
 */
import { isTerminalEvent, type RunEvent } from "@/lib/api/run-events";
import type { PaymentSummary, TrialBalanceRow } from "@/lib/api/types";

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const ADDRESS = /^0x[0-9a-fA-F]{40}$/;

export const isUuid = (value: string): boolean => UUID.test(value);
export const shortId = (id: string): string => id.slice(0, 8);
export const isAddress = (value: string): boolean => ADDRESS.test(value);

export function basescanAddressUrl(address: string): string | null {
  return isAddress(address) ? `https://sepolia.basescan.org/address/${address}` : null;
}

// ---- state text ---------------------------------------------------------------------------

/** The buyer's book for one authorization. Unknown values (a newer backend) fall back to the raw text. */
const BUYER_TEXT: Record<string, string> = {
  NONE: "Nothing booked yet",
  AUTHORIZED: "Authorized: the amount is set aside (encumbered)",
  RELEASED: "Released: the authorization expired unused and the amount is free again",
  SETTLED: "Settled: the amount was spent",
};
const SELLER_TEXT: Record<string, string> = {
  NONE: "Nothing booked yet",
  SETTLE_FAILED: "Settlement failed: no sale booked",
  SETTLED: "Settled: the sale is booked as revenue",
  CREDITED: "Credited: the sale was booked, then credited back because nothing was served",
};
const CHAIN_TEXT: Record<string, string> = {
  UNKNOWN: "Not checked yet: waiting for a reconciliation run",
  USED: "Used on Base Sepolia: the transfer happened",
  UNUSED: "Unused on Base Sepolia: no transfer happened",
};

export const buyerStateText = (state: string): string => BUYER_TEXT[state] ?? state;
export const sellerStateText = (state: string): string => SELLER_TEXT[state] ?? state;
export const chainStateText = (state: string): string => CHAIN_TEXT[state] ?? state;

/** Short label (the part before the colon) for compact cells. */
export const shortState = (text: string): string => text.split(":")[0] ?? text;

const MISMATCH_TEXT: Record<string, string> = {
  AMOUNT_MISMATCH: "The amount in the books differs from the amount that moved on chain.",
  PARTY_MISMATCH: "The chain transfer involved a different payer or payee than the books say.",
  TX_NOT_FOUND: "The books name a transaction that Base Sepolia does not have.",
  TX_FAILED: "The transaction exists on chain but failed.",
  TX_NOT_FOR_AUTHORIZATION: "The transaction on chain does not belong to this authorization.",
  SETTLED_BUT_UNUSED: "The books say settled, but the authorization is unused on chain.",
  UNUSED_BUT_SETTLED: "The authorization is used on chain, but the books say it was not settled.",
  CONFLICTING_TX: "Two different transactions were reported for this authorization.",
  ENCUMBRANCE_NOT_CLEARED: "The authorization is over, but the amount is still set aside.",
  BOOKS_OPEN: "The chain is final, but the books never reached a closing state.",
  CONFLICTING_FACT: "A later event contradicted an earlier one (amount, payee or validity).",
  // The API (and the Kafka event) call this kind CREDIT_NOTE_UNCORROBORATED; the old key stays
  // harmlessly for recordings made before the rename.
  CREDIT_NOTE_UNCORROBORATED: "A credit note was booked that the seller does not confirm.",
  SELLER_CREDIT_UNCONFIRMED: "A credit note was booked that the seller does not confirm.",
};
export const mismatchText = (kind: string): string =>
  MISMATCH_TEXT[kind] ?? `Reconciliation finding: ${kind}.`;

const ITEM_STATUS_TEXT: Record<string, string> = {
  MATCHED: "Matched",
  PENDING: "Pending",
  MISMATCH: "Mismatch",
  TX_UNKNOWN: "Transaction unknown",
};
export const itemStatusText = (status: string): string => ITEM_STATUS_TEXT[status] ?? status;

export const PENDING_EXPLANATION =
  "Waiting for Base Sepolia's safe block: the public node reports a block as final a little after it appears, and a payment stays pending until then.";

const RUN_STATUS_TEXT: Record<string, string> = {
  RUNNING: "Running",
  COMPLETED: "Completed",
  PARTIAL: "Partial: some payments could not be checked (a node or the seller was unavailable)",
  FAILED: "Failed: no safe block available",
};
export const reconStatusText = (status: string): string => RUN_STATUS_TEXT[status] ?? status;

// ---- run page: "In the ledger" ------------------------------------------------------------

/**
 * Entries a payment has, derived from the two book states exactly like the ledger's
 * `impliedEntries()` (the list API has no entries count): buyer AUTHORIZED 1 (ENCUMBER),
 * RELEASED/SETTLED 2; seller SETTLED 1 (SALE), CREDITED 2 (SALE + CREDIT_NOTE).
 */
export function impliedEntryCount(payment: Pick<PaymentSummary, "buyerState" | "sellerState">) {
  const buyer = { AUTHORIZED: 1, RELEASED: 2, SETTLED: 2 }[payment.buyerState] ?? 0;
  const seller = { SETTLED: 1, CREDITED: 2 }[payment.sellerState] ?? 0;
  return buyer + seller;
}

/** True once both books of every payment show at least one entry (the settled state is complete). */
export function allPaymentsBooked(items: readonly PaymentSummary[]): boolean {
  return (
    items.length > 0 &&
    items.every(
      (item) =>
        impliedEntryCount({ buyerState: item.buyerState, sellerState: "NONE" }) > 0 &&
        impliedEntryCount({ buyerState: "NONE", sellerState: item.sellerState }) > 0,
    )
  );
}

/**
 * Epoch ms of the latest PAYMENT_* or terminal event, or null when the run has no payment event.
 * That instant arms the run page's ledger poll: after the first payment event and again after the
 * terminal one (an approval can take minutes, so the first window may be long over by then).
 */
export function ledgerArmTime(events: readonly RunEvent[]): number | null {
  if (!events.some((event) => event.type.startsWith("PAYMENT_"))) {
    return null;
  }
  const armed = events.findLast(
    (event) => event.type.startsWith("PAYMENT_") || isTerminalEvent(event),
  );
  return armed ? Date.parse(armed.occurredAt) : null;
}

export const LEDGER_POLL_MS = 2000;
export const LEDGER_POLL_WINDOW_MS = 60_000;

export type LedgerPollPhase = "idle" | "polling" | "complete" | "gave-up";

/**
 * Where the run page's ledger poll stands. `armedAt` is when the first PAYMENT_* event (or the
 * terminal event) was seen; null means no payment happened yet. Polling stops early once every
 * payment is booked in both books, and gives up after the 60 s window.
 */
export function ledgerPollPhase(
  armedAt: number | null,
  now: number,
  items: readonly PaymentSummary[],
): LedgerPollPhase {
  if (armedAt === null) {
    return "idle";
  }
  if (allPaymentsBooked(items)) {
    return "complete";
  }
  return now - armedAt >= LEDGER_POLL_WINDOW_MS ? "gave-up" : "polling";
}

// ---- trial balance ------------------------------------------------------------------------

export type BalanceStatus = "balanced" | "unbalanced" | "unverifiable";

export interface BalanceCheck {
  readonly book: string;
  readonly asset: string;
  readonly decimals: number;
  readonly status: BalanceStatus;
  /** Exact BigInt sums; null when `unverifiable`. */
  readonly debits: bigint | null;
  readonly credits: bigint | null;
}

const isSafe = (n: number): boolean => Number.isSafeInteger(n);

/**
 * Σ debits = Σ credits per (book, asset), in BigInt. If any row of a group lost precision in
 * `JSON.parse` (not a safe integer), the group is `unverifiable`: the check refuses instead of
 * claiming a result it cannot back.
 */
export function checkTrialBalance(rows: readonly TrialBalanceRow[]): BalanceCheck[] {
  const groups = new Map<string, TrialBalanceRow[]>();
  for (const row of rows) {
    const key = `${row.book}\u0000${row.asset}`;
    groups.set(key, [...(groups.get(key) ?? []), row]);
  }
  return [...groups.values()].map((group): BalanceCheck => {
    const first = group[0];
    if (first === undefined) {
      throw new Error("unreachable: empty group");
    }
    const base = { book: first.book, asset: first.asset, decimals: first.decimals };
    if (!group.every((r) => isSafe(r.debit) && isSafe(r.credit) && isSafe(r.balance))) {
      return { ...base, status: "unverifiable", debits: null, credits: null };
    }
    const debits = group.reduce((sum, r) => sum + BigInt(r.debit), 0n);
    const credits = group.reduce((sum, r) => sum + BigInt(r.credit), 0n);
    return { ...base, status: debits === credits ? "balanced" : "unbalanced", debits, credits };
  });
}
