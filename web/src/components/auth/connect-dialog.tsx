import { useEffect, useRef, useState, type SyntheticEvent } from "react";

import { useAuth } from "@/components/auth/auth-context";
import { Button } from "@/components/ui/button";

const inputClass =
  "border-input bg-background w-full rounded-md border px-3 py-2 font-mono text-sm aria-invalid:border-destructive";

/**
 * The Connect dialog (ADR-0023). A native modal `<dialog>`: the browser traps focus, closes on
 * Escape and restores focus to the opener. The token field is a password input, never autofilled.
 */
export function ConnectDialog() {
  const { state, connect, closeDialog } = useAuth();
  const ref = useRef<HTMLDialogElement>(null);
  const [value, setValue] = useState("");
  const [keep, setKeep] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const dialog = ref.current;
    if (dialog === null) {
      return;
    }
    if (state.dialogOpen && !dialog.open) {
      if (typeof dialog.showModal === "function") {
        dialog.showModal();
      } else {
        dialog.setAttribute("open", "");
      }
    } else if (!state.dialogOpen && dialog.open) {
      if (typeof dialog.close === "function") {
        dialog.close();
      } else {
        dialog.removeAttribute("open");
      }
    }
  }, [state.dialogOpen]);

  function onSubmit(event: SyntheticEvent) {
    event.preventDefault();
    const problem = connect(value, keep);
    setError(problem);
    if (problem === null) {
      setValue("");
    }
  }

  const message =
    error ??
    (state.rejected
      ? "The API refused the token. Enter a valid one."
      : state.authRequired
        ? "The API needs a token."
        : null);

  return (
    <dialog
      ref={ref}
      aria-labelledby="connect-title"
      className="bg-background text-foreground m-auto w-full max-w-md rounded-lg border p-6 shadow-lg backdrop:bg-black/50"
      onCancel={(event) => {
        event.preventDefault();
        closeDialog();
      }}
    >
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <h2 id="connect-title" className="text-lg font-semibold">
          Connect to the API
        </h2>
        <p className="text-muted-foreground text-sm">
          Paste an API token. A reader token shows the data; an operator token can also start runs,
          decide approvals and run reconciliation. The token stays in this page&apos;s memory.
        </p>
        <div className="space-y-2">
          <label htmlFor="api-token" className="block text-sm font-medium">
            API token
          </label>
          <input
            id="api-token"
            type="password"
            autoComplete="off"
            spellCheck={false}
            className={inputClass}
            value={value}
            aria-invalid={error !== null}
            aria-describedby="api-token-message"
            onChange={(event) => {
              setValue(event.target.value);
              setError(null);
            }}
          />
          <p id="api-token-message" role="alert" className="text-destructive text-sm font-medium">
            {message ?? ""}
          </p>
        </div>
        <div className="flex items-start gap-2">
          <input
            id="keep-token"
            type="checkbox"
            className="mt-1"
            checked={keep}
            onChange={(event) => {
              setKeep(event.target.checked);
            }}
          />
          <label htmlFor="keep-token" className="text-sm">
            Keep for this tab
            <span className="text-muted-foreground block">
              Stores the token in this tab&apos;s session storage until the tab closes. Off by
              default.
            </span>
          </label>
        </div>
        <div className="flex justify-end gap-3">
          <Button type="button" variant="outline" onClick={closeDialog}>
            Cancel
          </Button>
          <Button type="submit">Connect</Button>
        </div>
      </form>
    </dialog>
  );
}
