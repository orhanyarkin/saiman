import type { Report } from "@/lib/api/run-events";
import { safeKapUrl } from "@/lib/run-view-model";

/**
 * The answer is plain text (React escapes it; there is no HTML or markdown rendering). A citation
 * links out only for a validated https://www.kap.org.tr/ URL; anything else is shown as text.
 */
export function ReportPanel({ report }: { report: Report }) {
  return (
    <div>
      <p lang="tr" className="whitespace-pre-wrap">
        {report.answer}
      </p>
      <h3 className="mt-4 font-semibold">Citations</h3>
      {report.citations.length === 0 ? (
        <p className="text-muted-foreground text-sm">No citations.</p>
      ) : (
        <ol lang="tr" className="list-decimal space-y-1 pl-5 text-sm">
          {report.citations.map((citation) => {
            const url = safeKapUrl(citation.sourceUrl);
            return (
              <li key={citation.chunkId}>
                {url ? (
                  <a
                    className="underline underline-offset-4"
                    href={url}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    {citation.title}
                  </a>
                ) : (
                  <span>{citation.title}</span>
                )}{" "}
                <span className="text-muted-foreground">({citation.chunkId})</span>
              </li>
            );
          })}
        </ol>
      )}
    </div>
  );
}
