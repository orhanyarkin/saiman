import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  backoffMs,
  connectSse,
  SSE_MAX_BACKOFF_MS,
  SseParser,
  type SseMessage,
} from "@/lib/api/sse";

function parseAll(...chunks: string[]): SseMessage[] {
  const parser = new SseParser();
  return chunks.flatMap((chunk) => parser.push(chunk));
}

describe("SseParser", () => {
  it("parses a simple event with name, id and data", () => {
    expect(parseAll("id: 7\nevent: STEP_STARTED\ndata: {}\n\n")).toEqual([
      { event: "STEP_STARTED", data: "{}", id: "7" },
    ]);
  });

  it("defaults the event name to message", () => {
    expect(parseAll("data: hi\n\n")).toEqual([{ event: "message", data: "hi", id: null }]);
  });

  it("joins multi-line data with newlines", () => {
    expect(parseAll("data: a\ndata: b\ndata:\ndata: c\n\n")[0]?.data).toBe("a\nb\n\nc");
  });

  it("strips exactly one leading space of a value", () => {
    expect(parseAll("data:  two\n\n")[0]?.data).toBe(" two");
    expect(parseAll("data:none\n\n")[0]?.data).toBe("none");
  });

  it("treats a field without a colon as an empty value", () => {
    expect(parseAll("data\n\n")).toEqual([{ event: "message", data: "", id: null }]);
  });

  it.each([
    ["LF", "\n"],
    ["CRLF", "\r\n"],
    ["CR", "\r"],
  ])("accepts %s line endings", (_name, eol) => {
    const text = `id: 1${eol}event: A${eol}data: x${eol}${eol}id: 2${eol}data: y${eol}${eol}`;
    expect(parseAll(text)).toEqual([
      { event: "A", data: "x", id: "1" },
      { event: "message", data: "y", id: "2" },
    ]);
  });

  it("does not turn CRLF split across chunks into an extra blank line", () => {
    // CR at the end of one chunk, LF at the start of the next: one line end, not two.
    const messages = parseAll("data: a\r", "\ndata: b\r\n\r", "\n");
    expect(messages).toEqual([{ event: "message", data: "a\nb", id: null }]);
  });

  it("handles a chunk boundary anywhere, byte by byte", () => {
    const text =
      "retry: 250\r\n: comment\r\nid: 5\r\nevent: X\r\ndata: one\r\ndata: two\r\n\r\ndata: three\n\n";
    const parser = new SseParser();
    const out: SseMessage[] = [];
    for (const ch of text) {
      out.push(...parser.push(ch));
    }
    expect(out).toEqual([
      { event: "X", data: "one\ntwo", id: "5" },
      { event: "message", data: "three", id: "5" },
    ]);
    expect(parser.retryMs).toBe(250);
    expect(parser.lastEventId).toBe("5");
  });

  it("ignores comment lines and heartbeats", () => {
    expect(parseAll(":heartbeat\n\n: another\ndata: x\n\n")).toEqual([
      { event: "message", data: "x", id: null },
    ]);
  });

  it("records retry only when it is all digits", () => {
    const parser = new SseParser();
    parser.push("retry: abc\n\n");
    expect(parser.retryMs).toBeNull();
    parser.push("retry: 1500\n\n");
    expect(parser.retryMs).toBe(1500);
  });

  it("ignores an id with a NUL and unknown fields", () => {
    const messages = parseAll("id: a\0b\nfoo: bar\ndata: x\n\n");
    expect(messages).toEqual([{ event: "message", data: "x", id: null }]);
  });

  it("keeps the last event id sticky and lets an empty id reset it", () => {
    const messages = parseAll("id: 3\ndata: a\n\ndata: b\n\nid\ndata: c\n\n");
    expect(messages.map((m) => m.id)).toEqual(["3", "3", ""]);
  });

  it("does not dispatch an event without data and resets the event name", () => {
    expect(parseAll("event: A\n\ndata: x\n\n")).toEqual([
      { event: "message", data: "x", id: null },
    ]);
  });

  it("drops a leading byte order mark", () => {
    expect(parseAll("﻿data: x\n\n")[0]?.data).toBe("x");
  });

  it("keeps an incomplete trailing event until its blank line arrives", () => {
    const parser = new SseParser();
    expect(parser.push("data: part")).toEqual([]);
    expect(parser.push("ial\n")).toEqual([]);
    expect(parser.push("\n")).toEqual([{ event: "message", data: "partial", id: null }]);
  });
});

describe("backoffMs", () => {
  it("doubles from the base and is capped at 30 s", () => {
    expect(backoffMs(1000, 0)).toBe(1000);
    expect(backoffMs(1000, 1)).toBe(2000);
    expect(backoffMs(1000, 4)).toBe(16_000);
    expect(backoffMs(1000, 5)).toBe(SSE_MAX_BACKOFF_MS);
    expect(backoffMs(1000, 1000)).toBe(SSE_MAX_BACKOFF_MS);
    expect(backoffMs(60_000, 0)).toBe(SSE_MAX_BACKOFF_MS);
  });
});

function streamOf(chunks: string[], keepOpen = false): Response {
  const encoder = new TextEncoder();
  return new Response(
    new ReadableStream<Uint8Array>({
      start(controller) {
        for (const chunk of chunks) {
          controller.enqueue(encoder.encode(chunk));
        }
        if (!keepOpen) {
          controller.close();
        }
      },
    }),
    { status: 200, headers: { "content-type": "text/event-stream; charset=utf-8" } },
  );
}

describe("connectSse", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("decodes multi-byte characters split across network chunks", async () => {
    const bytes = new TextEncoder().encode("data: şirket\n\n");
    const split = bytes.indexOf(0xc5) + 1; // between the two bytes of "ş"
    const response = new Response(
      new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(bytes.slice(0, split));
          controller.enqueue(bytes.slice(split));
          controller.close();
        },
      }),
      { status: 200, headers: { "content-type": "text/event-stream" } },
    );
    const received: string[] = [];
    const connection = connectSse({
      url: "/x",
      fetchImpl: () => Promise.resolve(response),
      onMessage: (m) => received.push(m.data),
    });
    await vi.waitFor(() => {
      expect(received).toEqual(["şirket"]);
    });
    connection.close();
  });

  it("reconnects with the server's retry: value, backing off up to 30 s, and resets after data", async () => {
    const delays: number[] = [];
    const calls: Headers[] = [];
    const fetchImpl = vi.fn((_url: string | URL | Request, init?: RequestInit) => {
      calls.push(new Headers(init?.headers));
      return Promise.resolve(new Response("down", { status: 503 }));
    });
    const realSetTimeout = globalThis.setTimeout;
    const spy = vi.spyOn(globalThis, "setTimeout").mockImplementation(((
      fn: () => void,
      ms?: number,
    ) => {
      delays.push(ms ?? 0);
      return realSetTimeout(fn, 0);
    }) as typeof setTimeout);
    const onReconnecting = vi.fn();
    const connection = connectSse({
      url: "/x",
      fetchImpl,
      onMessage: () => undefined,
      onReconnecting,
    });
    await vi.waitFor(() => {
      expect(fetchImpl.mock.calls.length).toBeGreaterThanOrEqual(8);
    });
    connection.close();
    spy.mockRestore();
    expect(delays.slice(0, 8)).toEqual([1000, 2000, 4000, 8000, 16_000, 30_000, 30_000, 30_000]);
    expect(onReconnecting).toHaveBeenCalled();
    expect(calls[0]?.get("accept")).toBe("text/event-stream");
  });

  it("honours retry: from the stream as the backoff base and resends Last-Event-ID", async () => {
    const delays: number[] = [];
    let n = 0;
    const heads: Headers[] = [];
    const fetchImpl = vi.fn((_url: string | URL | Request, init?: RequestInit) => {
      heads.push(new Headers(init?.headers));
      n++;
      return Promise.resolve(
        n === 1
          ? streamOf(["retry: 3000\n\nid: 9\ndata: x\n\n"])
          : new Response(null, { status: 204 }),
      );
    });
    const realSetTimeout = globalThis.setTimeout;
    const spy = vi.spyOn(globalThis, "setTimeout").mockImplementation(((
      fn: () => void,
      ms?: number,
    ) => {
      delays.push(ms ?? 0);
      return realSetTimeout(fn, 0);
    }) as typeof setTimeout);
    const onEnd = vi.fn();
    connectSse({ url: "/x", fetchImpl, onMessage: () => undefined, onEnd });
    await vi.waitFor(() => {
      expect(onEnd).toHaveBeenCalledTimes(1);
    });
    spy.mockRestore();
    expect(delays).toContain(3000);
    expect(heads[1]?.get("last-event-id")).toBe("9");
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("does not retry a 404 or a non-SSE content type, and reports 401 separately", async () => {
    for (const [response, hook] of [
      [new Response("no", { status: 404 }), "onEnd"],
      [new Response("<html>", { status: 200, headers: { "content-type": "text/html" } }), "onEnd"],
      [new Response("no", { status: 401 }), "onUnauthorized"],
    ] as const) {
      const fetchImpl = vi.fn(() => Promise.resolve(response));
      const hooks = { onEnd: vi.fn(), onUnauthorized: vi.fn() };
      connectSse({ url: "/x", fetchImpl, onMessage: () => undefined, ...hooks });
      await vi.waitFor(() => {
        expect(hooks[hook]).toHaveBeenCalledTimes(1);
      });
      expect(fetchImpl).toHaveBeenCalledTimes(1);
    }
  });

  it("close() aborts a stream that is waiting for more data", async () => {
    let aborted = false;
    const fetchImpl = vi.fn((_url: string | URL | Request, init?: RequestInit) => {
      init?.signal?.addEventListener("abort", () => {
        aborted = true;
      });
      return Promise.resolve(streamOf(["data: a\n\n"], true));
    });
    const received: string[] = [];
    const connection = connectSse({
      url: "/x",
      fetchImpl,
      onMessage: (m) => received.push(m.data),
    });
    await vi.waitFor(() => {
      expect(received).toEqual(["a"]);
    });
    connection.close();
    expect(aborted).toBe(true);
  });
});
