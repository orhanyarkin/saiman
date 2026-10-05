/**
 * Which data source the app uses (ADR-0026). Evaluated once at load: the "recorded demo" switch
 * writes `sessionStorage` and reloads, which also throws away every live query result so recorded
 * and live data can never mix on one screen.
 *
 * `VITE_DEMO_MODE=replay` builds an always-replay app. A live build can switch to replay for the
 * session when the daily model cap is reached.
 */
export const MODE_KEY = "saiman.mode";

const buildReplay = import.meta.env.VITE_DEMO_MODE === "replay";

function sessionReplay(): boolean {
  try {
    return sessionStorage.getItem(MODE_KEY) === "replay";
  } catch {
    return false;
  }
}

/** True when this page is served from a recording (the whole build, or a switched session). */
export const isReplayMode: boolean = buildReplay || sessionReplay();

/** True only for the static replay build (no way back to live). */
export const isReplayBuild: boolean = buildReplay;

/** Switches this tab to the bundled recording and reloads from the start page. */
export function switchToReplay(): void {
  sessionStorage.setItem(MODE_KEY, "replay");
  window.location.assign("/");
}

/** Leaves a switched session and reloads live. */
export function leaveReplay(): void {
  sessionStorage.removeItem(MODE_KEY);
  window.location.assign("/");
}
