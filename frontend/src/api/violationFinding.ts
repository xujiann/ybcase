export const INSTITUTION_GRADE: Record<string, string> = {
  TERTIARY: '三级', SECONDARY: '二级', PRIMARY: '一级', UNGRADED: '未定级',
}

export interface ViolationFinding {
  start: string | null
  end: string | null
  facts: string
  amount: number | null
}
