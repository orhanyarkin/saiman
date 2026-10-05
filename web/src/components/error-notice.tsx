import { describeError } from "@/lib/api/describe-error";

export function ErrorNotice({ error, className }: { error: unknown; className?: string }) {
  return (
    <p role="alert" className={`text-destructive text-sm font-medium ${className ?? ""}`}>
      <span aria-hidden="true">{"⚠ "}</span>
      {describeError(error)}
    </p>
  );
}
