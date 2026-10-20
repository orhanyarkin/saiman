import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { EnvironmentBanner } from "@/components/environment-banner";
import type { PingResponse } from "@/lib/api/ping";

const UNKNOWN = "Testnet only. No real money moves.";

function renderBanner() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <EnvironmentBanner />
    </QueryClientProvider>,
  );
}

function pingResolves(environment: string) {
  const body: PingResponse = {
    service: "orchestrator",
    dbTime: "2026-10-20T17:42:00Z",
    environment,
    traceId: null,
  };
  vi.mocked(fetch).mockResolvedValue(
    new Response(JSON.stringify(body), {
      status: 200,
      headers: { "content-type": "application/json" },
    }),
  );
}

describe("EnvironmentBanner", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("claims no environment while the ping is still in flight", () => {
    vi.mocked(fetch).mockReturnValue(new Promise(() => undefined));

    renderBanner();

    expect(screen.getByRole("note")).toHaveTextContent(UNKNOWN);
  });

  it("names a known environment once the ping answers", async () => {
    pingResolves("aws-demo");

    renderBanner();

    // The honest unknown form first, refined in place: never a spinner, never a reflow.
    expect(screen.getByRole("note")).toHaveTextContent(UNKNOWN);
    await waitFor(() => {
      expect(screen.getByRole("note")).toHaveTextContent(
        "Testnet only, AWS demo. No real money moves.",
      );
    });
  });

  it("claims no environment for a value outside the vocabulary", async () => {
    pingResolves("staging-7");

    renderBanner();

    await waitFor(() => {
      expect(vi.mocked(fetch)).toHaveBeenCalled();
    });
    expect(screen.getByRole("note")).toHaveTextContent(UNKNOWN);
    expect(screen.getByRole("note")).not.toHaveTextContent("staging-7");
  });

  it("claims no environment when the field is empty, instead of guessing local", async () => {
    pingResolves("");

    renderBanner();

    await waitFor(() => {
      expect(vi.mocked(fetch)).toHaveBeenCalled();
    });
    expect(screen.getByRole("note")).toHaveTextContent(UNKNOWN);
    expect(screen.getByRole("note")).not.toHaveTextContent("local");
  });

  it("claims no environment when the ping fails", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response("", { status: 500, statusText: "Internal Server Error" }),
    );

    renderBanner();

    await waitFor(() => {
      expect(vi.mocked(fetch)).toHaveBeenCalled();
    });
    expect(screen.getByRole("note")).toHaveTextContent(UNKNOWN);
  });
});
