import { useQuery } from "@tanstack/react-query";
import { createFileRoute } from "@tanstack/react-router";
import { useState } from "react";

import { ErrorNotice } from "@/components/error-notice";
import { SpendOverviewView } from "@/components/spend/spend-overview";
import { spendQuery } from "@/lib/api/queries";
import { isValidDay, todayUtc } from "@/lib/day";
import { useDocumentTitle } from "@/lib/hooks";

export const Route = createFileRoute("/spend")({
  component: SpendPage,
});

function SpendPage() {
  useDocumentTitle("Spend");
  const [day, setDay] = useState(() => todayUtc());
  const valid = isValidDay(day);
  const spend = useQuery({ ...spendQuery(day), enabled: valid });

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Spend control</h1>

      <div className="space-y-2">
        <label htmlFor="spend-day" className="block text-sm font-medium">
          Day (UTC)
        </label>
        <input
          id="spend-day"
          type="date"
          value={day}
          aria-invalid={!valid}
          aria-describedby="spend-day-error"
          className="border-input bg-background rounded-md border px-3 py-2 text-sm"
          onChange={(event) => {
            setDay(event.target.value);
          }}
        />
        <p id="spend-day-error" role="alert" className="text-destructive text-sm font-medium">
          {valid ? "" : "Enter a valid date, for example 2026-10-05."}
        </p>
      </div>

      {!valid ? null : spend.isPending ? (
        <p role="status" aria-busy="true">
          Loading spend…
        </p>
      ) : spend.isError ? (
        <ErrorNotice error={spend.error} />
      ) : (
        <SpendOverviewView spend={spend.data} />
      )}
    </div>
  );
}
