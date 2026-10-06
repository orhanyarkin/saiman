import { QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ReplayOffer } from "@/components/replay/replay-offer";
import { ApiError, isDailyCapError } from "@/lib/api/errors";
import { MODE_KEY } from "@/lib/mode";
import { useCapabilities } from "@/lib/use-capabilities";
import { READER_ME, testQueryClient } from "@/test/render";

describe("ReplayOffer", () => {
  const assign = vi.fn();

  beforeEach(() => {
    sessionStorage.clear();
    vi.stubGlobal("location", { assign });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    assign.mockClear();
  });

  it("switches the session to the recording", () => {
    render(<ReplayOffer text="Live runs are paused." />);
    expect(screen.getByRole("status")).toHaveTextContent("Live runs are paused.");
    fireEvent.click(screen.getByRole("button", { name: "Open the recorded demo" }));
    expect(sessionStorage.getItem(MODE_KEY)).toBe("replay");
    expect(assign).toHaveBeenCalledWith("/");
  });

  it("recognises the daily-cap error and nothing else", () => {
    const cap = new ApiError("daily-cap", 503, "x", null, 60, "LLM_DAILY_CAP_REACHED", true);
    expect(isDailyCapError(cap)).toBe(true);
    expect(isDailyCapError(new ApiError("not-ready", 503, "x"))).toBe(false);
    expect(isDailyCapError(new Error("x"))).toBe(false);
  });
});

function Probe() {
  const { canOperate, pending } = useCapabilities();
  return <p>{pending ? "pending" : canOperate ? "operator" : "reader"}</p>;
}

describe("useCapabilities", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("assumes a reader when /me is unavailable", async () => {
    vi.mocked(fetch).mockResolvedValue(new Response("{}", { status: 404 }));
    render(
      <QueryClientProvider client={testQueryClient(null)}>
        <Probe />
      </QueryClientProvider>,
    );
    expect(screen.getByText("pending")).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText("reader")).toBeInTheDocument();
    });
  });

  it("assumes a reader when /me fails on the network", async () => {
    vi.mocked(fetch).mockRejectedValue(new TypeError("offline"));
    render(
      <QueryClientProvider client={testQueryClient(null)}>
        <Probe />
      </QueryClientProvider>,
    );
    await waitFor(() => {
      expect(screen.getByText("reader")).toBeInTheDocument();
    });
  });

  it("is an operator only when /me lists OPERATOR", () => {
    render(
      <QueryClientProvider client={testQueryClient()}>
        <Probe />
      </QueryClientProvider>,
    );
    expect(screen.getByText("operator")).toBeInTheDocument();
  });

  it("is a reader for a reader token", () => {
    render(
      <QueryClientProvider client={testQueryClient(READER_ME)}>
        <Probe />
      </QueryClientProvider>,
    );
    expect(screen.getByText("reader")).toBeInTheDocument();
  });
});
