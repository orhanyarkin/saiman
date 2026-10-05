/**
 * Server-Sent Events over `fetch` (ADR-0023). The native `EventSource` cannot send an
 * `Authorization` header, and putting a token in the URL is not an option, so this reads the
 * `text/event-stream` body itself: a small spec parser (`SseParser`) plus a reconnecting loop.
 *
 * Behaviour:
 * - sends `Authorization: Bearer` (via `headers()`), `Accept: text/event-stream` and `Last-Event-ID`
 * - reconnects after the stream ends or a transient failure, with exponential backoff capped at
 *   30 s, starting from the server's `retry:` value (default 1 s)
 * - stops on HTTP 204 (nothing left), on 401 (the caller re-prompts), on other 4xx and on a wrong
 *   content type; the caller stops it on a terminal event with `close()`
 */

export interface SseMessage {
  /** The `event:` name, `message` when absent. */
  event: string;
  /** The `data:` lines joined with `\n`. */
  data: string;
  /** The last event id in effect (sticky across events, per the spec). */
  id: string | null;
}

/**
 * Incremental `text/event-stream` parser. Feed it decoded text in chunks split anywhere (inside a
 * line, between CR and LF, inside a field name); it returns the messages completed by each chunk.
 * Line ends may be CRLF, LF or CR. Lines starting with `:` are comments; unknown fields are ignored.
 */
/** Bounds on what one stream may make the parser hold (a hostile or broken server). */
export const SSE_MAX_LINE_CHARS = 1024 * 1024;
export const SSE_MAX_DATA_CHARS = 1024 * 1024;
/** A server `retry: 0` must not become a tight reconnect loop. */
export const SSE_MIN_RETRY_MS = 250;
/** A connection must live this long before the failure counter (and so the backoff) resets. */
const STABLE_CONNECTION_MS = 10_000;

export class SseParser {
  /** Set when the pending line or the event data exceeded its bound: the stream must be dropped. */
  overflowed = false;
  private scanFrom = 0;
  private dataChars = 0;

  /** Server-requested reconnect delay in ms (`retry:`), or null. */
  retryMs: number | null = null;
  /** Last `id:` seen on a dispatched message. */
  lastEventId: string | null = null;

  private buffer = "";
  private skipLf = false;
  private started = false;
  private event = "";
  private data: string[] = [];
  private pendingId: string | null = null;
  private hasId = false;

  push(chunk: string): SseMessage[] {
    if (this.overflowed) {
      return [];
    }
    let text = chunk;
    if (!this.started && text.length > 0) {
      this.started = true;
      if (text.startsWith("﻿")) {
        text = text.slice(1);
      }
    }
    if (this.skipLf && text.length > 0) {
      // The previous chunk ended with CR: a LF now belongs to that CRLF, not to a new line.
      this.skipLf = false;
      if (text.startsWith("\n")) {
        text = text.slice(1);
      }
    }
    this.buffer += text;

    const out: SseMessage[] = [];
    let start = 0;
    // Resume where the last call stopped: the kept tail has no line end, so no rescan from 0.
    for (let i = this.scanFrom; i < this.buffer.length; i++) {
      const ch = this.buffer[i];
      if (ch !== "\n" && ch !== "\r") {
        continue;
      }
      if (ch === "\r") {
        if (i + 1 < this.buffer.length) {
          if (this.buffer[i + 1] === "\n") {
            this.line(this.buffer.slice(start, i), out);
            i++;
            start = i + 1;
            continue;
          }
        } else {
          // CR is the last char of the buffer: we cannot tell CR from CRLF yet.
          this.line(this.buffer.slice(start, i), out);
          this.skipLf = true;
          start = i + 1;
          continue;
        }
      }
      this.line(this.buffer.slice(start, i), out);
      start = i + 1;
    }
    this.buffer = this.buffer.slice(start);
    this.scanFrom = this.buffer.length;
    if (this.buffer.length > SSE_MAX_LINE_CHARS || this.dataChars > SSE_MAX_DATA_CHARS) {
      this.overflowed = true;
      this.buffer = "";
      this.data = [];
    }
    return out;
  }

  private line(line: string, out: SseMessage[]): void {
    if (line === "") {
      this.dispatch(out);
      return;
    }
    if (line.startsWith(":")) {
      return;
    }
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) {
      value = value.slice(1);
    }
    switch (field) {
      case "event":
        this.event = value;
        break;
      case "data":
        this.data.push(value);
        this.dataChars += value.length + 1;
        break;
      case "id":
        if (!value.includes("\0")) {
          this.pendingId = value;
          this.hasId = true;
        }
        break;
      case "retry":
        if (/^\d+$/.test(value)) {
          this.retryMs = Number(value);
        }
        break;
      default:
        break;
    }
  }

  private dispatch(out: SseMessage[]): void {
    if (this.hasId) {
      this.lastEventId = this.pendingId;
    }
    if (this.data.length > 0) {
      out.push({
        event: this.event === "" ? "message" : this.event,
        data: this.data.join("\n"),
        id: this.lastEventId,
      });
    }
    this.event = "";
    this.data = [];
    this.dataChars = 0;
    this.pendingId = null;
    this.hasId = false;
  }
}

export interface SseOptions {
  url: string;
  /** Fresh headers for every (re)connect, so a changed token is picked up. */
  headers?: () => Record<string, string>;
  onMessage: (message: SseMessage) => void;
  /** The stream is over for good (204, permanent HTTP error, wrong content type). */
  onEnd?: () => void;
  /** The API answered 401: the loop stops, the caller re-prompts. */
  onUnauthorized?: () => void;
  /** A transient failure or a clean end: another attempt follows after the backoff. */
  onReconnecting?: () => void;
  /** Injection points for tests. */
  fetchImpl?: typeof fetch;
}

export interface SseConnection {
  close(): void;
}

export const SSE_DEFAULT_RETRY_MS = 1000;
export const SSE_MAX_BACKOFF_MS = 30_000;

/** Backoff for the n-th consecutive failure (n starts at 0): base * 2^n, capped at 30 s. */
export function backoffMs(base: number, attempt: number): number {
  return Math.min(SSE_MAX_BACKOFF_MS, base * 2 ** Math.min(attempt, 16));
}

const wait = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener(
      "abort",
      () => {
        clearTimeout(timer);
        resolve();
      },
      { once: true },
    );
  });

export function connectSse(options: SseOptions): SseConnection {
  const controller = new AbortController();
  const { signal } = controller;
  const doFetch = options.fetchImpl ?? ((input, init) => fetch(input, init));
  const parser = new SseParser();
  let failures = 0;
  // A function, so the checks inside loops are not narrowed to a constant by the compiler.
  const aborted = () => signal.aborted;

  async function readBody(body: ReadableStream<Uint8Array>): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    try {
      for (;;) {
        const { done, value } = await reader.read();
        if (done) {
          const rest = decoder.decode();
          if (rest !== "") {
            deliver(parser.push(rest));
          }
          return;
        }
        deliver(parser.push(decoder.decode(value, { stream: true })));
        if (parser.overflowed) {
          await reader.cancel();
          return;
        }
        if (signal.aborted) {
          return;
        }
      }
    } finally {
      reader.releaseLock();
    }
  }

  function deliver(messages: SseMessage[]): void {
    for (const message of messages) {
      if (signal.aborted) {
        return;
      }
      options.onMessage(message);
    }
  }

  /** Returns true when the loop must stop for good. */
  async function attempt(): Promise<boolean> {
    const headers: Record<string, string> = {
      ...options.headers?.(),
      Accept: "text/event-stream",
    };
    if (parser.lastEventId !== null) {
      headers["Last-Event-ID"] = parser.lastEventId;
    }
    let response: Response;
    try {
      response = await doFetch(options.url, { headers, signal, cache: "no-store" });
    } catch {
      return false; // network failure (or abort, handled by the caller): retry
    }
    if (response.status === 401) {
      options.onUnauthorized?.();
      return true;
    }
    if (response.status === 204) {
      options.onEnd?.();
      return true;
    }
    if (!response.ok) {
      const transient =
        response.status >= 500 || response.status === 408 || response.status === 429;
      if (!transient) {
        options.onEnd?.();
        return true;
      }
      return false;
    }
    const type = response.headers.get("content-type") ?? "";
    if (!type.toLowerCase().startsWith("text/event-stream") || response.body === null) {
      options.onEnd?.();
      return true;
    }
    try {
      await readBody(response.body);
    } catch {
      // Connection dropped mid-stream: fall through to a reconnect.
    }
    if (parser.overflowed) {
      options.onEnd?.(); // a line or event beyond the bounds: drop the stream, do not reconnect
      return true;
    }
    return false;
  }

  async function loop(): Promise<void> {
    while (!aborted()) {
      const startedAt = Date.now();
      const stop = await attempt();
      if (stop || aborted()) {
        return;
      }
      // Only a connection that stayed up resets the backoff; a server that sends one message and
      // closes, over and over, is backed off like any failure.
      if (Date.now() - startedAt >= STABLE_CONNECTION_MS) {
        failures = 0;
      }
      options.onReconnecting?.();
      const base = Math.max(SSE_MIN_RETRY_MS, parser.retryMs ?? SSE_DEFAULT_RETRY_MS);
      const delay = backoffMs(base, failures);
      failures++;
      await wait(delay, signal);
    }
  }

  void loop();

  return {
    close() {
      controller.abort();
    },
  };
}
