/* eslint-disable no-restricted-globals, no-restricted-syntax --
   This file is the guard for ADR-0023: it must name localStorage to prove it is never written. */
import { QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";

import { AuthProvider } from "@/components/auth/auth-provider";
import { AuthStatus } from "@/components/auth/auth-status";
import { apiGet } from "@/lib/api/source";
import { TOKEN_KEY, initTokenStore, notifyUnauthorized } from "@/lib/auth/token-store";
import { testQueryClient } from "@/test/render";

/** Minimal stand-in for user-event (not a dependency): the dialog needs only click and typing. */
const user = {
  click: (element: HTMLElement) => {
    fireEvent.click(element);
    return Promise.resolve();
  },
  type: (element: HTMLElement, text: string) => {
    fireEvent.change(element, { target: { value: text } });
    return Promise.resolve();
  },
};

const submitButton = () =>
  within(screen.getByRole("dialog", { hidden: true })).getByRole("button", {
    name: "Connect",
    hidden: true,
  });

const TOKEN = "fixture-reader-0123456789abcdef0123456789";

function renderApp() {
  return render(
    <QueryClientProvider client={testQueryClient()}>
      <AuthProvider>
        <AuthStatus />
      </AuthProvider>
    </QueryClientProvider>,
  );
}

describe("Connect dialog and token handling", () => {
  let setItem: MockInstance<Storage["setItem"]>;

  beforeEach(() => {
    sessionStorage.clear();
    localStorage.clear();
    initTokenStore();
    setItem = vi.spyOn(Storage.prototype, "setItem");
    vi.stubGlobal("fetch", vi.fn());
  });
  afterEach(() => {
    setItem.mockRestore();
    vi.unstubAllGlobals();
  });

  /** ADR-0023: the token must never reach localStorage, whatever the flow. */
  function expectNoLocalStorageWrite() {
    expect(setItem.mock.contexts.includes(localStorage)).toBe(false);
    expect(localStorage.length).toBe(0);
  }

  it("connects in memory only by default, sends the header and leaves no trace in storage", async () => {
    vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response("{}", { status: 200 })));
    renderApp();
    await user.click(screen.getByRole("button", { name: "Connect" }));
    const field = screen.getByLabelText("API token");
    expect(field).toHaveAttribute("type", "password");
    await user.type(field, TOKEN);
    await user.click(submitButton());

    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(screen.getByText("Disconnect")).toBeInTheDocument();
    await apiGet("/api/v1/runs");
    expect(
      (vi.mocked(fetch).mock.calls.at(-1)?.[1]?.headers as Record<string, string>).Authorization,
    ).toBe(`Bearer ${TOKEN}`);
    expectNoLocalStorageWrite();
  });

  it("keeps the token for the tab only when asked, and Disconnect clears memory and sessionStorage", async () => {
    vi.mocked(fetch).mockImplementation(() => Promise.resolve(new Response("{}", { status: 200 })));
    renderApp();
    await user.click(screen.getByRole("button", { name: "Connect" }));
    await user.type(screen.getByLabelText("API token"), TOKEN);
    await user.click(screen.getByLabelText(/Keep for this tab/));
    await user.click(submitButton());
    expect(sessionStorage.getItem(TOKEN_KEY)).toBe(TOKEN);

    await user.click(screen.getByRole("button", { name: "Disconnect" }));
    expect(sessionStorage.getItem(TOKEN_KEY)).toBeNull();
    await apiGet("/api/v1/runs");
    expect(
      (vi.mocked(fetch).mock.calls.at(-1)?.[1]?.headers as Record<string, string>).Authorization,
    ).toBeUndefined();
    expectNoLocalStorageWrite();
  });

  it("restores a kept token on load and rejects a malformed stored value", () => {
    sessionStorage.setItem(TOKEN_KEY, TOKEN);
    initTokenStore();
    renderApp();
    expect(screen.getByRole("button", { name: "Disconnect" })).toBeInTheDocument();
    sessionStorage.setItem(TOKEN_KEY, "has spaces and\nnewline");
    initTokenStore();
    expect(sessionStorage.getItem(TOKEN_KEY)).not.toBe(TOKEN);
  });

  it("validates the field and never echoes the token", async () => {
    renderApp();
    await user.click(screen.getByRole("button", { name: "Connect" }));
    await user.click(submitButton());
    expect(screen.getByRole("alert", { hidden: true })).toHaveTextContent("Enter a token.");
    await user.type(screen.getByLabelText("API token"), "bad token!");
    await user.click(submitButton());
    expect(screen.getByRole("alert", { hidden: true })).toHaveTextContent(
      "only letters, digits, hyphens and underscores",
    );
    expect(document.body.textContent).not.toContain("bad token!");
  });

  it("a 401 reopens the dialog once; Cancel keeps it closed until Connect is pressed", async () => {
    renderApp();
    act(() => {
      notifyUnauthorized();
    });
    const dialog = screen.getByRole("dialog", { hidden: true });
    expect(dialog).toHaveAttribute("open");
    expect(screen.getByText("The API needs a token.")).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "Cancel", hidden: true }));
    expect(dialog).not.toHaveAttribute("open");
    act(() => {
      notifyUnauthorized();
    });
    expect(dialog).not.toHaveAttribute("open");

    await user.click(screen.getByRole("button", { name: "Connect" }));
    expect(dialog).toHaveAttribute("open");
  });

  it("Escape closes the dialog (keyboard)", () => {
    renderApp();
    act(() => {
      notifyUnauthorized();
    });
    const dialog = screen.getByRole("dialog", { hidden: true });
    fireEvent(dialog, new Event("cancel", { cancelable: true }));
    expect(dialog).not.toHaveAttribute("open");
  });
});
