import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRouter, RouterProvider } from "@tanstack/react-router";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";

import { AuthProvider } from "@/components/auth/auth-provider";
import { loadCapture } from "@/lib/api/replay";
import { ApiError } from "@/lib/api/source";
import { isReplayMode } from "@/lib/mode";
import { initTelemetry } from "@/lib/telemetry";
import { routeTree } from "@/routeTree.gen";

import "@/index.css";

// Must run before the app renders so the fetch patch is installed before the first request.
initTelemetry();

// A 4xx answer is a final answer (not found, invalid, forbidden): retrying only delays the error
// state. Network and 5xx failures get one retry.
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) =>
        failureCount < 1 &&
        !(error instanceof ApiError && error.status >= 400 && error.status < 500),
    },
  },
});
const router = createRouter({ routeTree });

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Root element #root not found");
}

// In replay mode the capture is loaded before the first render, so the banner always knows the
// environment and date, and screens never see a "recording still loading" state. A failed load
// still renders: every read then reports the recording as unavailable.
if (isReplayMode) {
  await loadCapture().catch(() => undefined);
}

createRoot(rootElement).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </QueryClientProvider>
  </StrictMode>,
);
