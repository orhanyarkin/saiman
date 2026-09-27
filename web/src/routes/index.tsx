import { createFileRoute } from "@tanstack/react-router";

import { SystemCheckCard } from "@/components/system-check-card";

export const Route = createFileRoute("/")({
  component: Index,
});

function Index() {
  return (
    <main className="flex flex-col items-center gap-6">
      <h1 className="text-2xl font-semibold">Saiman</h1>
      <SystemCheckCard />
    </main>
  );
}
