import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { SystemCheckCard } from "@/components/system-check-card";
import type { PingResponse } from "@/lib/api/ping";

function renderWithQueryClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

describe("SystemCheckCard", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows a loading state while the request is in flight", () => {
    vi.mocked(fetch).mockReturnValue(new Promise(() => undefined));

    renderWithQueryClient(<SystemCheckCard />);

    expect(screen.getByRole("status")).toHaveTextContent(/checking/i);
  });

  it("renders the ping response, including a link to the trace in Jaeger, once it resolves", async () => {
    const body: PingResponse = {
      service: "orchestrator",
      dbTime: "2026-09-27T10:15:30.123456Z",
      traceId: "4bf92f3577b34da6a3ce929d0e0e4736",
    };
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    renderWithQueryClient(<SystemCheckCard />);

    await waitFor(() => {
      expect(screen.getByText("orchestrator")).toBeInTheDocument();
    });
    expect(screen.getByText(body.dbTime)).toBeInTheDocument();

    const traceLink = screen.getByRole("link", {
      name: `Open trace ${body.traceId ?? ""} in Jaeger (new tab)`,
    });
    expect(traceLink).toHaveAttribute(
      "href",
      `http://localhost:16686/trace/${encodeURIComponent(body.traceId ?? "")}`,
    );
  });

  it("shows 'not available' instead of a link when the response has no traceId", async () => {
    const body: PingResponse = {
      service: "orchestrator",
      dbTime: "2026-09-27T10:15:30.123456Z",
      traceId: null,
    };
    vi.mocked(fetch).mockResolvedValue(
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );

    renderWithQueryClient(<SystemCheckCard />);

    await waitFor(() => {
      expect(screen.getByText("not available")).toBeInTheDocument();
    });
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("shows an error state with the failure reason when the request fails", async () => {
    vi.mocked(fetch).mockResolvedValue(
      new Response("", { status: 500, statusText: "Internal Server Error" }),
    );

    renderWithQueryClient(<SystemCheckCard />);

    await waitFor(() => {
      expect(screen.getByRole("alert")).toBeInTheDocument();
    });
    expect(screen.getByRole("alert")).toHaveTextContent(/500/);
  });
});
