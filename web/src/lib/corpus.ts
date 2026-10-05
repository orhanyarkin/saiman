/**
 * The corpus is a frozen KAP snapshot in live mode too (ADR-0010). The live app has no data source
 * for its date, so a build-time constant names it (`VITE_CORPUS_SNAPSHOT_LABEL`).
 */
export const DEFAULT_CORPUS_LABEL = "KAP disclosures up to 29 Dec 2023";

export function corpusLabel(): string {
  const configured = import.meta.env.VITE_CORPUS_SNAPSHOT_LABEL?.trim();
  return configured !== undefined && configured !== "" ? configured : DEFAULT_CORPUS_LABEL;
}

/** "29 Dec 2023": en-GB, UTC, deterministic. Null when the instant is invalid. */
export function formatSnapshotDate(iso: string): string | null {
  const time = Date.parse(iso);
  if (Number.isNaN(time)) {
    return null;
  }
  return new Intl.DateTimeFormat("en-GB", {
    day: "numeric",
    month: "short",
    year: "numeric",
    timeZone: "UTC",
  }).format(new Date(time));
}

/** The replay banner's second sentence; null when the recording has no (valid) corpus. */
export function corpusBannerText(
  corpus: { newestDisclosureAt: string } | null | undefined,
): string | null {
  const date = corpus ? formatSnapshotDate(corpus.newestDisclosureAt) : null;
  return date === null
    ? null
    : `Answers come from a frozen KAP snapshot: disclosures up to ${date}.`;
}
