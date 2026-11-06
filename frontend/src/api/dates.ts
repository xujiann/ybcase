const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/
const pad = (value: number) => String(value).padStart(2, '0')

/** Calendar dates have no timezone; only timestamps are converted to the browser's local time. */
export function localDate(date: Date = new Date()): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
export function formatLocalDate(value?: string | null): string {
  if (!value) return '—'
  if (DATE_ONLY.test(value)) return value
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : localDate(date)
}
export function formatLocalDateTime(value?: string | null): string {
  if (!value) return '—'
  if (DATE_ONLY.test(value)) return value
  // Offset-less database timestamps are already local wall times; parsing keeps that clock time.
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return `${localDate(date)} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}
/** Read the clock on each reset, so a long-lived page cannot reuse yesterday's defaults. */
export function createTodayReset<T extends object>(target: T, defaults: (today: string) => Partial<T>, now: () => Date = () => new Date()): () => void {
  return () => { Object.assign(target, defaults(localDate(now()))) }
}
