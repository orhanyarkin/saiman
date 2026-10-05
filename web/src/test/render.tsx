import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  createMemoryHistory,
  createRootRoute,
  createRoute,
  createRouter,
  Outlet,
  RouterProvider,
} from "@tanstack/react-router";
import { render, type RenderResult } from "@testing-library/react";
import type { ReactElement } from "react";

import { queryKeys } from "@/lib/api/queries";
import type { Me } from "@/lib/api/types";

export const OPERATOR_ME: Me = { name: "operator:test0001", roles: ["OPERATOR", "READER"] };
export const READER_ME: Me = { name: "reader:test0002", roles: ["READER"] };

/**
 * A client without retries, so a failing request surfaces immediately. `/me` is pre-seeded (an
 * operator unless `me` says otherwise; null leaves it unseeded) so role-aware components render
 * their actions without a network call.
 */
export function testQueryClient(me: Me | null = OPERATOR_ME): QueryClient {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  if (me !== null) {
    client.setQueryData(queryKeys.me, me);
  }
  return client;
}

/**
 * Renders `ui` at `/` inside a real memory router (components use `Link`) and a query client.
 * Resolves after the router has mounted the element.
 */
export async function renderWithProviders(
  ui: ReactElement,
  client: QueryClient = testQueryClient(),
): Promise<RenderResult> {
  const rootRoute = createRootRoute({ component: Outlet });
  const index = createRoute({ getParentRoute: () => rootRoute, path: "/", component: () => ui });
  const run = createRoute({
    getParentRoute: () => rootRoute,
    path: "/runs/$runId",
    component: () => null,
  });
  const others = [
    "/runs/new",
    "/ledger",
    "/ledger/payments/$paymentId",
    "/reconciliation",
    "/reconciliation/$reconRunId",
  ].map((path) => createRoute({ getParentRoute: () => rootRoute, path, component: () => null }));
  const router = createRouter({
    routeTree: rootRoute.addChildren([index, run, ...others]),
    history: createMemoryHistory({ initialEntries: ["/"] }),
  });
  await router.load();
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
}
