export interface ActualInterview {
  institutionCode: string
  interviewType: string
  reason: string
  content: string
  participants: string
  interviewDate: string
}

export function blankActualInterview(today: string): ActualInterview {
  return { institutionCode: '', interviewType: '', reason: '', content: '', participants: '', interviewDate: today }
}

export function actualInterviewError(value: ActualInterview, sentAt: string, today: string): string | undefined {
  const required = [
    [value.interviewType, 64], [value.reason, 1000], [value.content, 12000], [value.participants, 512],
  ] as const
  if (required.some(([text, max]) => !text?.trim() || text.length > max || text.includes('\0'))
    || value.institutionCode.length > 64 || value.institutionCode.includes('\0'))
    return '请填写完整的实际约谈信息，并核对字段长度'
  const date = value.interviewDate
  const parsed = new Date(date + 'T00:00:00Z')
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(parsed.getTime())
    || parsed.toISOString().slice(0, 10) !== date || date < '1900-01-01' || date < sentAt || date > today)
    return '实际约谈日期须在移交日期至今天之间'
}

export function actualInterviewPayload(value: ActualInterview) {
  const code = value.institutionCode.trim()
  return { ...(code ? { institutionCode: code } : {}), interviewType: value.interviewType.trim(),
    reason: value.reason.trim(), content: value.content.trim(), participants: value.participants.trim(),
    interviewDate: value.interviewDate }
}
