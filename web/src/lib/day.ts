const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;

const DATE_TIME = new Intl.DateTimeFormat("en-GB", {
  day: "numeric",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
  timeZone: "UTC",
});

const TIME_ONLY = new Intl.DateTimeFormat("en-GB", {
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
  timeZone: "UTC",
});

/**
 * "5 Oct 2026, 19:36 UTC": en-GB, UTC, 24-hour, no seconds, with the zone spelled out so it is
 * never implied. Null when the instant is invalid.
 */
export function formatDateTime(iso: string): string | null {
  const time = Date.parse(iso);
  return Number.isNaN(time) ? null : `${DATE_TIME.format(new Date(time))} UTC`;
}

/** "19:36 UTC", for places that already sit inside an obvious day. Null when invalid. */
export function formatTime(iso: string): string | null {
  const time = Date.parse(iso);
  return Number.isNaN(time) ? null : `${TIME_ONLY.format(new Date(time))} UTC`;
}

/** Today's date in UTC as `YYYY-MM-DD` (the spend day boundary is UTC). */
export function todayUtc(now: Date = new Date()): string {
  return now.toISOString().slice(0, 10);
}

/** True for a real calendar date written as `YYYY-MM-DD` (rejects `2026-02-30`). */
export function isValidDay(text: string): boolean {
  const match = DAY.exec(text);
  if (!match) {
    return false;
  }
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day
  );
}
