/**
 * Money formatting, the only place amounts become text (CLAUDE.md rule 4).
 *
 * Amounts arrive as integer atomic units plus the asset's decimals. All arithmetic here is BigInt
 * or string based; there is no floating point anywhere. A value that already lost precision in
 * `JSON.parse` (not a safe integer) is refused rather than shown wrong.
 */
export interface MoneyLike {
  readonly atomicUnits: number;
  readonly asset: string;
  readonly decimals: number;
}

/** Shown instead of an amount that cannot be rendered faithfully. */
export const UNAVAILABLE = "—";

const MIN_FRACTION_DIGITS = 2;
/** LLM spend is tiny; four decimals show a typical $0.0069 step cost. */
const USD_FRACTION_DIGITS = 4;

function isRenderable(money: MoneyLike): boolean {
  return (
    Number.isSafeInteger(money.atomicUnits) &&
    Number.isInteger(money.decimals) &&
    money.decimals >= 0 &&
    money.decimals <= 18
  );
}

/** Splits atomic units into a sign, whole part and zero-padded fraction (all strings). */
function split(money: {
  readonly atomicUnits: number | bigint;
  readonly asset?: string;
  readonly decimals: number;
}): { negative: boolean; whole: string; fraction: string } {
  const value = BigInt(money.atomicUnits);
  const negative = value < 0n;
  const digits = (negative ? -value : value).toString().padStart(money.decimals + 1, "0");
  const cut = digits.length - money.decimals;
  return { negative, whole: digits.slice(0, cut), fraction: digits.slice(cut) };
}

/** Rounds a decimal string pair half-up to `places` fraction digits, using BigInt only. */
function roundHalfUp(
  whole: string,
  fraction: string,
  places: number,
): { whole: string; fraction: string } {
  if (fraction.length <= places) {
    return { whole, fraction: fraction.padEnd(places, "0") };
  }
  const roundUp = fraction.charCodeAt(places) >= "5".charCodeAt(0);
  const kept = BigInt(whole + fraction.slice(0, places)) + (roundUp ? 1n : 0n);
  const text = kept.toString().padStart(places + 1, "0");
  const cut = text.length - places;
  return { whole: text.slice(0, cut), fraction: text.slice(cut) };
}

function groupThousands(whole: string): string {
  return whole.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

/** `20000` USDC atomic units become `0.02 USDC`; USD micros become `$0.0069`. */
export function formatMoney(money: MoneyLike): string {
  if (!isRenderable(money)) {
    return UNAVAILABLE;
  }
  return render(money);
}

/**
 * Same text for an exact BigInt amount, e.g. a trial-balance total summed from safe integers.
 * Unlike `formatMoney` there is nothing to refuse: the value is exact by construction.
 */
export function formatBigMoney(atomicUnits: bigint, asset: string, decimals: number): string {
  if (!Number.isInteger(decimals) || decimals < 0 || decimals > 18) {
    return UNAVAILABLE;
  }
  return render({ atomicUnits, asset, decimals });
}

function render(money: {
  readonly atomicUnits: number | bigint;
  readonly asset: string;
  readonly decimals: number;
}): string {
  const { negative, whole, fraction } = split(money);
  const sign = negative ? "-" : "";
  if (money.asset === "USD") {
    const rounded = roundHalfUp(whole, fraction, USD_FRACTION_DIGITS);
    return `${sign}$${groupThousands(rounded.whole)}.${rounded.fraction}`;
  }
  const trimmed = fraction.replace(/0+$/, "").padEnd(MIN_FRACTION_DIGITS, "0");
  const shown = money.decimals === 0 ? "" : `.${trimmed}`;
  return `${sign}${groupThousands(whole)}${shown} ${money.asset}`;
}

/**
 * Text for a `title`/tooltip: the exact integer amount, or the reason the amount is hidden. Always
 * safe to show because it never contains more than the integer and the asset.
 */
export function moneyTitle(money: MoneyLike): string {
  if (!isRenderable(money)) {
    return "This amount is too large to display exactly, so it is hidden instead of shown wrong.";
  }
  return `${String(money.atomicUnits)} atomic units of ${money.asset} (${String(money.decimals)} decimals)`;
}

/** USDC (6 decimals) from a plain atomic count, e.g. the approvals API's `amountAtomic`. */
export function usdc(atomicUnits: number): MoneyLike {
  return { atomicUnits, asset: "USDC", decimals: 6 };
}

/**
 * `cap - sum(used)` clamped at zero, in BigInt. Null when an input is not renderable, the assets
 * or decimals differ, or the result is not a safe integer.
 */
export function remainingMoney(cap: MoneyLike, ...used: MoneyLike[]): MoneyLike | null {
  const all = [cap, ...used];
  if (!all.every((m) => isRenderable(m) && m.asset === cap.asset && m.decimals === cap.decimals)) {
    return null;
  }
  let left = BigInt(cap.atomicUnits);
  for (const m of used) {
    left -= BigInt(m.atomicUnits);
  }
  if (left < 0n) {
    left = 0n;
  }
  return { asset: cap.asset, decimals: cap.decimals, atomicUnits: Number(left) };
}

const USDC_INPUT = /^(\d{1,6})(?:\.(\d{1,6}))?$/;

/** Parses a typed USDC amount (`0.05`) into atomic units without floats; null when invalid. */
export function parseUsdcInput(text: string): number | null {
  const match = USDC_INPUT.exec(text.trim());
  if (!match) {
    return null;
  }
  const fraction = (match[2] ?? "").padEnd(6, "0");
  const atomic = BigInt(`${match[1] ?? "0"}${fraction}`);
  return atomic <= BigInt(Number.MAX_SAFE_INTEGER) ? Number(atomic) : null;
}

/** Atomic USDC as an editable decimal string (`50000` becomes `0.05`), for form defaults. */
export function usdcInputValue(atomicUnits: number): string {
  const { whole, fraction } = split({ atomicUnits, asset: "USDC", decimals: 6 });
  const trimmed = fraction.replace(/0+$/, "");
  return trimmed === "" ? whole : `${whole}.${trimmed}`;
}

/**
 * USD micros (the model router's unit) at full micro-dollar precision: `6900` becomes `$0.006900`.
 * BigInt only; null when the count is not a safe integer.
 */
export function formatUsdMicros(micros: number): string | null {
  if (!Number.isSafeInteger(micros)) {
    return null;
  }
  const { negative, whole, fraction } = split({ atomicUnits: micros, decimals: 6 });
  return `${negative ? "-" : ""}$${groupThousands(whole)}.${fraction}`;
}

/** `cap - spent` in USD micros, clamped at zero, in BigInt; null when an input is unusable. */
export function remainingUsdMicros(cap: number, spent: number): number | null {
  if (!Number.isSafeInteger(cap) || !Number.isSafeInteger(spent)) {
    return null;
  }
  const left = BigInt(cap) - BigInt(spent);
  return Number(left < 0n ? 0n : left);
}
