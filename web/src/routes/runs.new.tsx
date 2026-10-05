import { useMutation } from "@tanstack/react-query";
import { createFileRoute, useNavigate } from "@tanstack/react-router";
import { useState, type SyntheticEvent } from "react";

import { ErrorNotice } from "@/components/error-notice";
import { Button } from "@/components/ui/button";
import { startRun } from "@/lib/api/queries";
import { isReplayMode } from "@/lib/api/source";
import { useDocumentTitle } from "@/lib/hooks";
import { EXAMPLE_QUESTION, limits, QUESTION_MAX, QUESTION_MIN } from "@/lib/limits";
import { formatMoney, parseUsdcInput, usdcInputValue } from "@/lib/money";

export const Route = createFileRoute("/runs/new")({
  component: NewRun,
});

const usdc = (atomicUnits: number) => ({ atomicUnits, asset: "USDC", decimals: 6 });
const inputClass =
  "border-input bg-background w-full rounded-md border px-3 py-2 text-sm aria-invalid:border-destructive";

function NewRun() {
  useDocumentTitle("New run");
  const navigate = useNavigate();
  const [question, setQuestion] = useState("");
  const [budget, setBudget] = useState(usdcInputValue(limits.defaultRunBudgetAtomic));
  const [submitted, setSubmitted] = useState(false);

  const trimmed = question.trim();
  const questionError =
    trimmed.length < QUESTION_MIN || trimmed.length > QUESTION_MAX
      ? `Enter between ${String(QUESTION_MIN)} and ${String(QUESTION_MAX)} characters.`
      : null;
  const budgetAtomic = parseUsdcInput(budget);
  const budgetError =
    budgetAtomic === null || budgetAtomic <= 0
      ? "Enter an amount in USDC, for example 0.05."
      : budgetAtomic > limits.maxRunBudgetAtomic
        ? `The maximum budget is ${formatMoney(usdc(limits.maxRunBudgetAtomic))}.`
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
    if (questionError !== null || budgetError !== null || budgetAtomic === null) {
      return;
    }
    start.mutate({ question: trimmed, budgetAtomic });
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
              setBudget(event.target.value);
            }}
          />
          <p id="budget-help" className="text-muted-foreground text-sm">
            Payments above {formatMoney(usdc(limits.approvalThresholdAtomic))} ask for your approval
            first. Approving a payment never raises this budget.
          </p>
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
