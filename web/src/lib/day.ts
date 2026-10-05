const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;

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
