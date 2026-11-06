export function executionDateError(paidAt: string, decidedAt: string | undefined, today: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(paidAt)) return '请选择有效缴付日期'
  const date = new Date(`${paidAt}T00:00:00Z`)
  if (Number.isNaN(date.getTime()) || date.toISOString().slice(0,10) !== paidAt) return '请选择有效缴付日期'
  if (!decidedAt) return '决定日期尚未明确，请先核实决定信息'
  if (paidAt < decidedAt) return '缴付日期不得早于决定日期'
  if (paidAt > today) return '缴付日期不得晚于今天'
  return ''
}
export function lateFeeTimingError(paidAt: string, decisionType: string | undefined, status: string | undefined, deliveredAt: string | undefined, paymentDeadline: string | undefined): string {
  if (decisionType !== 'PUNISH' || status !== 'DELIVERED' || !deliveredAt || paidAt < deliveredAt) return '处罚决定有效送达后方可登记加处罚款'
  if (!paymentDeadline) return '缴款到期日尚未明确，请先登记已批准的暂缓期限或分期计划'
  if (paidAt <= paymentDeadline) return `缴款期限（${paymentDeadline}）届满后方可登记加处罚款`
  return ''
}
