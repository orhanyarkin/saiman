/** "2026-10-01" from the capture time: UTC, deterministic, no locale surprises. */
export function captureDate(capturedAt: string): string {
  const time = Date.parse(capturedAt);
  return Number.isNaN(time) ? "unknown date" : new Date(time).toISOString().slice(0, 10);
}

/** The replay banner (ADR-0026). `null` while the capture is not loaded (or failed to load). */
export function bannerText(capture: { environment: string; capturedAt: string } | null): string {
  const prefix =
    capture === null
      ? "Recorded demo"
      : `Recorded on ${capture.environment}, ${captureDate(capture.capturedAt)}`;
  return `${prefix} - Base Sepolia testnet. Nothing on this page is live.`;
}
