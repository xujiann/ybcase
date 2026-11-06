export const JURISDICTION_TYPES: Record<string, string> = {
  PENDING: '待补录', LOCAL: '本地管辖事实', TRANSFERRED: '移送承接', DESIGNATED: '指定管辖', OTHER: '其他',
}
export interface Jurisdiction {
  type: string
  basis: string
  violationPlace: string
  treatmentPlace: string
  insuredPlace: string
  designatedNo: string
  disputeNote: string
}
export function emptyJurisdiction(): Jurisdiction {
  return { type: 'PENDING', basis: '', violationPlace: '', treatmentPlace: '', insuredPlace: '', designatedNo: '', disputeNote: '' }
}
export function serializeJurisdiction(value?: Partial<Jurisdiction> | null): Jurisdiction {
  const result = emptyJurisdiction()
  for (const key of Object.keys(result) as (keyof Jurisdiction)[]) {
    result[key] = String(value?.[key] ?? result[key]).trim()
  }
  return result
}
export function jurisdictionError(value: Partial<Jurisdiction>): string {
  const j = serializeJurisdiction(value)
  if (!Object.hasOwn(JURISDICTION_TYPES, j.type)) return '请选择有效的管辖记录类型'
  if (j.type !== 'PENDING' && !j.basis) return '请填写管辖依据与事实说明'
  if (j.type === 'DESIGNATED' && !j.designatedNo) return '指定管辖须填写批复文号'
  if (j.type === 'LOCAL' && ![j.violationPlace, j.treatmentPlace, j.insuredPlace].some(Boolean)) return '本地管辖事实须至少填写发生地、就医地或参保地之一'
  const limits = { type: 24, basis: 2000, violationPlace: 255, treatmentPlace: 255, insuredPlace: 255, designatedNo: 128, disputeNote: 2000 }
  for (const key of Object.keys(limits) as (keyof Jurisdiction)[]) {
    if (j[key].length > limits[key]) return '管辖记录字段超过允许长度，请精简后保存'
  }
  return ''
}
export function canEditJurisdiction(roles: string[] | undefined, c: { status?: string; archiveNo?: string | null }): boolean {
  return !c.archiveNo && ['INVESTIGATING', 'REPORTED', 'NOTIFIED'].includes(c.status || '')
    && !!roles?.some(role => ['HANDLER', 'LEADER', 'ADMIN'].includes(role))
}
