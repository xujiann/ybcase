export interface WritableCase { status?: string; archiveNo?: string | null }
export function caseOperationAccess(roles: string[] | undefined): boolean {
  return !!roles?.some(role => ['HANDLER','LEADER','ADMIN'].includes(role))
}
export function caseWriteAccess(roles: string[] | undefined, c: WritableCase): boolean {
  return !!c.status && c.status !== 'CLOSED' && !c.archiveNo && caseOperationAccess(roles)
}
export function attachmentVoidAccess(roles: string[] | undefined, username: string | undefined, c: WritableCase, attachment: { uploaded_by?: string; voided?: boolean }): boolean {
  return caseWriteAccess(roles,c) && !attachment.voided && (!!roles?.some(role => ['LEADER','ADMIN'].includes(role)) || !!username && attachment.uploaded_by === username)
}

export function governmentRecordAvailable(roles: string[] | undefined, decision?: { decisionType?: string; govRecordNo?: string | null; govRecordAt?: string | null }): boolean {
  return caseOperationAccess(roles) && decision?.decisionType === 'PUNISH' && !decision.govRecordNo && !decision.govRecordAt
}

export function leaderOperationAccess(roles: string[] | undefined): boolean {
  return !!roles?.some(role => ['LEADER','ADMIN'].includes(role))
}
export function adminOperationAccess(roles: string[] | undefined): boolean {
  return !!roles?.includes('ADMIN')
}

export function businessReadAccess(roles: string[] | undefined): boolean {
  return !!roles?.some(role => ['HANDLER','LEGAL','LEADER','ADMIN'].includes(role))
}
