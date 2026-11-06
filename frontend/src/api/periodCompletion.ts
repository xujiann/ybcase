export interface PeriodCase { status?: string; archiveNo?: string | null; decidedAt?: string | null; terminatedAt?: string | null; closedAt?: string | null }
export function canCompletePeriod(roles: string[] | undefined, c: PeriodCase): boolean {
  return !!roles?.some(role => ['HANDLER','LEADER','ADMIN'].includes(role)) && !!c.status && c.status !== 'CLOSED' && !c.archiveNo
}
export function canStartPeriod(roles: string[] | undefined, c: PeriodCase): boolean {
  return canCompletePeriod(roles,c) && ['INVESTIGATING','REPORTED','NOTIFIED'].includes(c.status || '')
}
export function periodCutoff(c: PeriodCase, today: string): string {
  return [today,c.decidedAt,c.terminatedAt,c.closedAt].filter((value): value is string => !!value).sort()[0]!
}
function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0,10) === value
}
export function periodEndError(start: string, end: string, c: PeriodCase, today: string): string {
  if (!validDate(start) || !validDate(end)) return '请选择有效的开始与结束日期'
  if (end < start) return '结束日期不得早于登记的开始日期'
  const cutoff=periodCutoff(c,today)
  if (end > cutoff) return `结束日期不得晚于调查办理截止日（${cutoff}，含今天及决定/终止/结案日期）`
  return ''
}
