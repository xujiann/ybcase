export interface PrecaseSource { id: number; type: 'clues' | 'inspections'; owner?: string; handler?: string; status?: string; clueId?: number | null }
export function canWritePrecase(roles: string[] | undefined, username: string | undefined, source: PrecaseSource, linkedCase: boolean): boolean {
  if (linkedCase || source.type === 'clues' && source.status !== 'PENDING' || source.type === 'inspections' && !!source.clueId) return false
  const manager = roles?.some(role => ['LEADER','ADMIN'].includes(role))
  const ownSource = source.owner === username || source.type === 'clues' && !!source.handler?.split(/[、,，;；\s]+/).filter(Boolean).includes(username || '')
  return !!manager || !!(roles?.includes('HANDLER') && username && ownSource)
}
export function canVoidPrecase(roles: string[] | undefined, username: string | undefined, source: PrecaseSource, row: {case_id?: number; uploaded_by?: string; voided?: boolean}): boolean {
  return !row.voided && canWritePrecase(roles, username, source, !!row.case_id)
    && (!!roles?.some(role => ['LEADER','ADMIN'].includes(role)) || row.uploaded_by === username)
}
