import { useMutation, useQuery } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useState, type SyntheticEvent } from "react";

import { ErrorNotice } from "@/components/error-notice";
import { Button } from "@/components/ui/button";
import { spendQuery, startRun } from "@/lib/api/queries";
import { isReplayMode } from "@/lib/api/source";
import { useDocumentTitle } from "@/lib/hooks";
import { todayUtc } from "@/lib/day";
import { EXAMPLE_QUESTION, QUESTION_MAX, QUESTION_MIN } from "@/lib/limits";
import { formatMoney, parseUsdcInput, usdcInputValue } from "@/lib/money";

export const Route = createFileRoute("/runs/new")({
  component: NewRun,
});

const inputClass =
  "border-input bg-background w-full rounded-md border px-3 py-2 text-sm aria-invalid:border-destructive";

function NewRun() {
  useDocumentTitle("New run");
  const navigate = useNavigate();
  const [question, setQuestion] = useState("");
  // Limits come from the server (display hints; the server enforces them). Until the user types,
  // the budget field shows the server's default run budget.
  const spend = useQuery(spendQuery(todayUtc()));
  const limits = spend.data?.limits;
  const [typedBudget, setTypedBudget] = useState<string | null>(null);
  const budget = typedBudget ?? (limits ? usdcInputValue(limits.defaultRunBudget.atomicUnits) : "");
  const [submitted, setSubmitted] = useState(false);

  const trimmed = question.trim();
  const questionError =
    trimmed.length < QUESTION_MIN || trimmed.length > QUESTION_MAX
      ? `Enter between ${String(QUESTION_MIN)} and ${String(QUESTION_MAX)} characters.`
      : null;
  const budgetAtomic = parseUsdcInput(budget);
  // Without loaded limits an empty budget means "use the server default".
  const budgetOptional = limits === undefined && budget.trim() === "";
  const budgetError = budgetOptional
    ? null
    : budgetAtomic === null || budgetAtomic <= 0
      ? "Enter an amount in USDC, for example 0.05."
      : limits && budgetAtomic > limits.maxRunBudget.atomicUnits
        ? `The maximum budget is ${formatMoney(limits.maxRunBudget)}.`
        : null;

  const start = useMutation({
    mutationFn: startRun,
    onSuccess: async (started) => {
      await navigate({ to: "/runs/$runId", params: { runId: started.runId } });
    },
  });

  function onSubmit(event: SyntheticEvent) {
    event.preventDefault();
    setSubmitted(true);
    if (questionError !== null || budgetError !== null) {
      return;
    }
    start.mutate({ question: trimmed, ...(budgetAtomic === null ? {} : { budgetAtomic }) });
  }

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Start a research run</h1>
      <form onSubmit={onSubmit} noValidate className="space-y-5">
        <div className="space-y-2">
          <label htmlFor="question" className="block text-sm font-medium">
            Your question
          </label>
          <textarea
            id="question"
            lang="tr"
            rows={3}
            className={inputClass}
            value={question}
            maxLength={QUESTION_MAX * 2}
            aria-invalid={submitted && questionError !== null}
            aria-describedby="question-help question-error"
            onChange={(event) => {
              setQuestion(event.target.value);
            }}
          />
          <p id="question-help" className="text-muted-foreground text-sm">
            Do not enter personal data. Questions go to a language model.
          </p>
          <p id="question-error" role="alert" className="text-destructive text-sm font-medium">
            {submitted && questionError !== null ? questionError : ""}
          </p>
          <button
            type="button"
            lang="tr"
            className="rounded-full border px-3 py-1 text-sm underline-offset-4 hover:underline"
            onClick={() => {
              setQuestion(EXAMPLE_QUESTION);
            }}
          >
            Try: {EXAMPLE_QUESTION}
          </button>
        </div>

        <div className="space-y-2">
          <label htmlFor="budget" className="block text-sm font-medium">
            Budget for this run (USDC)
          </label>
          <input
            id="budget"
            inputMode="decimal"
            className={`${inputClass} max-w-40`}
            value={budget}
            aria-invalid={submitted && budgetError !== null}
            aria-describedby="budget-help budget-error"
            onChange={(event) => {
              setTypedBudget(event.target.value);
            }}
          />
          <p id="budget-help" className="text-muted-foreground text-sm">
            {limits
              ? `Payments above ${formatMoney(limits.approvalThreshold)} ask for your approval first. `
              : "Large payments ask for your approval first. "}
            Approving a payment never raises this budget.
          </p>
          {spend.isError ? (
            <>
              <ErrorNotice error={spend.error} />
              <p className="text-muted-foreground text-sm">
                The limits could not be loaded. You can still start a run: leave the budget empty to
                use the default, and the server enforces every limit.
              </p>
            </>
          ) : null}
          <p id="budget-error" role="alert" className="text-destructive text-sm font-medium">
            {submitted && budgetError !== null ? budgetError : ""}
          </p>
        </div>

        {start.isError ? <ErrorNotice error={start.error} /> : null}

        {isReplayMode ? (
          <p className="text-muted-foreground text-sm">
            This is a recorded demo, so starting a run is disabled here.
          </p>
        ) : null}
        <Button type="submit" disabled={start.isPending || isReplayMode}>
          {start.isPending ? "Starting…" : "Start run"}
        </Button>
      </form>
    </div>
  );
}
