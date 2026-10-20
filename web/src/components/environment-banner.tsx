import { useQuery } from "@tanstack/react-query";

import { pingQuery } from "@/lib/api/queries";
import { liveBannerText } from "@/lib/banner-text";

/**
 * The live-mode page chrome: which deployment this is, on one line, on every route.
 *
 * It never waits for `GET /api/v1/ping`. The unknown form renders immediately and the text is
 * refined in place when the orchestrator answers, so there is no spinner and no layout shift. An
 * empty `environment`, a missing field and a failed ping all keep the unknown form: a deployed
 * demo that cannot read its own environment must not claim to be local
 * (docs/design/ui-refresh.md, "Environment banner").
 */
export function EnvironmentBanner() {
  const { data } = useQuery(pingQuery());
  return (
    <div
      role="note"
      data-testid="environment-banner"
      data-tone="warning"
      className="tone-tint px-4 py-2 text-center text-sm font-medium"
    >
      {liveBannerText(data?.environment)}
    </div>
  );
}
