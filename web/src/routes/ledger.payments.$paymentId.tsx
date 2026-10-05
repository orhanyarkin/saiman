import { useQuery } from "@tanstack/react-query";
import { createFileRoute, Link } from "@tanstack/react-router";

import { ErrorNotice } from "@/components/error-notice";
import { PaymentDetailView } from "@/components/ledger/payment-detail";
import { buttonVariants } from "@/components/ui/button";
import { paymentDetailQuery } from "@/lib/api/queries";
import { ApiError } from "@/lib/api/source";
import { useDocumentTitle } from "@/lib/hooks";
import { isUuid } from "@/lib/ledger-model";

export const Route = createFileRoute("/ledger/payments/$paymentId")({
  component: PaymentPage,
});

function PaymentPage() {
  const { paymentId } = Route.useParams();
  useDocumentTitle("Payment");
  const valid = isUuid(paymentId);
  // Never call the API with a malformed id.
  const detail = useQuery({ ...paymentDetailQuery(paymentId), enabled: valid });
  const back = (
    <Link to="/ledger" className={buttonVariants({ variant: "outline" })}>
      Back to the ledger
    </Link>
  );

  if (!valid) {
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">Payment not found</h1>
        <p role="alert" className="text-destructive text-sm font-medium">
          <span aria-hidden="true">{"⚠ "}</span>
          This is not a valid payment id. Payment ids look like
          6ad4354c-8e79-4b49-b5d9-d45eb9689b41.
        </p>
        {back}
      </div>
    );
  }
  if (detail.isPending) {
    return (
      <div className="space-y-4" aria-busy="true">
        <h1 className="text-2xl font-semibold">Payment</h1>
        <p role="status">Loading payment…</p>
      </div>
    );
  }
  if (detail.isError) {
    const notFound = detail.error instanceof ApiError && detail.error.kind === "not-found";
    return (
      <div className="space-y-4">
        <h1 className="text-2xl font-semibold">
          {notFound ? "Payment not found" : "Payment unavailable"}
        </h1>
        <ErrorNotice error={detail.error} />
        {back}
      </div>
    );
  }
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-semibold">Payment {paymentId.slice(0, 8)}</h1>
      <p className="text-muted-foreground text-sm break-all">{paymentId}</p>
      <PaymentDetailView detail={detail.data} />
      {back}
    </div>
  );
}
