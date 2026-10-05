/**
 * The API bearer token (ADR-0023). It lives in this module's memory. "Keep for this tab" also
 * writes it to `sessionStorage` (cleared when the tab closes). It is NEVER written to
 * `localStorage` (an ESLint rule and a Vitest enforce this) and never put in a URL.
 *
 * A plain module (not only React state) because `source.ts` and `sse.ts` are not React code. React
 * reads it through `useSyncExternalStore` (see `auth-provider.tsx`).
 */
import { isReplayMode } from "@/lib/mode";

export const TOKEN_KEY = "saiman.apiToken";

/** Header-safe token alphabet (the server additionally enforces 32-128 characters). */
const TOKEN_SHAPE = /^[A-Za-z0-9_-]+$/;

export interface AuthState {
  /** Whether a token is held (the value itself is never part of React state). */
  connected: boolean;
  keptForTab: boolean;
  /** The API answered 401 and no valid token is held. */
  authRequired: boolean;
  /** The last token was refused by the API (401), as opposed to never entered. */
  rejected: boolean;
  dialogOpen: boolean;
}

let token: string | null = null;
let state: AuthState = {
  connected: false,
  keptForTab: false,
  authRequired: false,
  rejected: false,
  dialogOpen: false,
};
/** The user closed the dialog; automatic 401s do not reopen it until an explicit action. */
let dismissed = false;
const listeners = new Set<() => void>();
const tokenChangeListeners = new Set<() => void>();

function set(patch: Partial<AuthState>): void {
  state = { ...state, ...patch };
  for (const listener of listeners) {
    listener();
  }
}

function readKept(): string | null {
  try {
    const value = sessionStorage.getItem(TOKEN_KEY);
    return value !== null && TOKEN_SHAPE.test(value) ? value : null;
  } catch {
    return null;
  }
}

function writeKept(value: string | null): void {
  try {
    if (value === null) {
      sessionStorage.removeItem(TOKEN_KEY);
    } else {
      sessionStorage.setItem(TOKEN_KEY, value);
    }
  } catch {
    // sessionStorage unavailable (private mode quota): the token simply stays in memory.
  }
}

/** Loads a tab-kept token. Called once at startup; tests call it after seeding sessionStorage. */
export function initTokenStore(): void {
  token = null;
  dismissed = false;
  const kept = isReplayMode ? null : readKept();
  token = kept;
  state = {
    connected: kept !== null,
    keptForTab: kept !== null,
    authRequired: false,
    rejected: false,
    dialogOpen: false,
  };
  for (const listener of listeners) {
    listener();
  }
}

initTokenStore();

export function subscribeAuth(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getAuthState(): AuthState {
  return state;
}

/** Called after connect or disconnect so cached data of the other identity is dropped. */
export function onTokenChange(listener: () => void): () => void {
  tokenChangeListeners.add(listener);
  return () => {
    tokenChangeListeners.delete(listener);
  };
}

function tokenChanged(): void {
  for (const listener of tokenChangeListeners) {
    listener();
  }
}

/** `Authorization: Bearer <token>` when connected, else nothing. Used by fetch and SSE. */
export function authHeaders(): Record<string, string> {
  return token === null ? {} : { Authorization: `Bearer ${token}` };
}

/** True when a previous call was answered 401 and no token has been entered since. */
export function mustAuthenticate(): boolean {
  return state.authRequired && token === null;
}

/** Returns an error message for a token that cannot be a valid header value, else null. */
export function validateToken(value: string): string | null {
  const trimmed = value.trim();
  if (trimmed === "") {
    return "Enter a token.";
  }
  return TOKEN_SHAPE.test(trimmed)
    ? null
    : "A token has only letters, digits, hyphens and underscores.";
}

/** Stores the token in memory (and `sessionStorage` when `keepForTab`). Returns an error text or null. */
export function connect(value: string, keepForTab: boolean): string | null {
  const problem = validateToken(value);
  if (problem !== null) {
    return problem;
  }
  token = value.trim();
  writeKept(keepForTab ? token : null);
  dismissed = false;
  set({
    connected: true,
    keptForTab: keepForTab,
    authRequired: false,
    rejected: false,
    dialogOpen: false,
  });
  tokenChanged();
  return null;
}

/** Forgets the token everywhere (memory and `sessionStorage`). */
export function disconnect(): void {
  token = null;
  writeKept(null);
  dismissed = false;
  set({ connected: false, keptForTab: false, authRequired: false, rejected: false });
  tokenChanged();
}

/** The API answered 401: drop a refused token and ask for a new one (once, until dismissed). */
export function notifyUnauthorized(): void {
  if (isReplayMode) {
    return;
  }
  const hadToken = token !== null;
  if (hadToken) {
    token = null;
    writeKept(null);
  }
  set({
    connected: false,
    keptForTab: false,
    authRequired: true,
    rejected: hadToken || state.rejected,
    dialogOpen: dismissed ? state.dialogOpen : true,
  });
}

/** Opens the Connect dialog on purpose (header button, `/connect`). */
export function openConnectDialog(): void {
  dismissed = false;
  set({ dialogOpen: true });
}

/** The user closed the dialog without connecting. */
export function dismissConnectDialog(): void {
  dismissed = true;
  set({ dialogOpen: false });
}
