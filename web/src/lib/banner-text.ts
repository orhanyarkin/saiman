/** "2026-10-01" from the capture time: UTC, deterministic, no locale surprises. */
export function captureDate(capturedAt: string): string {
  const time = Date.parse(capturedAt);
  return Number.isNaN(time) ? "unknown date" : new Date(time).toISOString().slice(0, 10);
}

/**
 * The environments the live app can name (docs/design/ui-refresh.md). Anything else is unknown,
 * including the empty string the orchestrator defaults to: a deployed demo must never be able to
 * call itself "local". A new deployment target needs a label here before the banner can name it.
 */
const ENVIRONMENT_LABELS = new Map<string, string>([
  ["local", "local demo"],
  ["aws-demo", "AWS demo"],
]);

/** The readable name of a known environment; `null` for empty, missing or unrecognised values. */
export function environmentLabel(environment: string | null | undefined): string | null {
  return ENVIRONMENT_LABELS.get((environment ?? "").trim()) ?? null;
}

/**
 * The live-mode testnet banner. With no known environment it claims none, which is the honest
 * state while the ping is in flight, when the field is empty, and when the ping failed.
 */
export function liveBannerText(environment: string | null | undefined): string {
  const label = environmentLabel(environment);
  return label === null
    ? "Testnet only. No real money moves."
    : `Testnet only, ${label}. No real money moves.`;
}

/** The replay banner (ADR-0026). `null` while the capture is not loaded (or failed to load). */
export function bannerText(capture: { environment: string; capturedAt: string } | null): string {
  const prefix =
    capture === null
      ? "Recorded demo"
      : `Recorded on ${capture.environment}, ${captureDate(capture.capturedAt)}`;
  return `${prefix} - Base Sepolia testnet. Nothing on this page is live.`;
}
