/** Keep retries confined to the three idempotent historical list reads. */
export interface HistoryReadConfig { method?: string; url?: string; historyRead?: boolean }
export interface HistoryReadFailure {
  isAxiosError?: boolean
  code?: string
  response?: { status?: number; headers?: Record<string, unknown> }
  httpStatus?: number
  requestId?: string | null
  traceId?: string | null
  bizCode?: number
}

export function safeHistoryId(value: unknown): string | null {
  return typeof value === 'string' && /^[A-Za-z0-9-]{1,64}$/.test(value) ? value : null
}

export function isHistoryPagePayload(value: unknown): boolean {
  if (!value || typeof value !== 'object') return false
  const page = value as { rows?: unknown; total?: number; page?: number; size?: number }
  return Array.isArray(page.rows) && page.rows.every(row => row !== null && typeof row === 'object' && !Array.isArray(row))
    && Number.isSafeInteger(page.total) && page.total! >= 0
    && Number.isSafeInteger(page.page) && page.page! >= 1
    && Number.isSafeInteger(page.size) && page.size! >= 1 && page.size! <= 100
    && page.rows.length <= page.size! && page.rows.length <= page.total!
}

/** Fixed endpoint and numeric/allowlisted metadata only; never log bodies or queries. */
export function historyReadDiagnostic(config: HistoryReadConfig | undefined, error: unknown, elapsedMs: number) {
  if (!isManagedHistoryRead(config)) return null
  const failure = error as HistoryReadFailure | null
  if (failure?.code === 'ERR_CANCELED') return null
  return {
    endpoint: config!.url,
    code: ['ERR_NETWORK','ECONNABORTED','ETIMEDOUT','ECONNRESET','ERR_BAD_RESPONSE','ERR_BAD_REQUEST','ERR_HISTORY_RESPONSE'].includes(failure?.code || '') ? failure!.code : 'API_ERROR',
    httpStatus: failure?.response?.status ?? failure?.httpStatus ?? null,
    bizCode: Number.isSafeInteger(failure?.bizCode) ? failure!.bizCode : null,
    requestId: safeHistoryId(failure?.response?.headers?.['x-request-id'] ?? failure?.requestId),
    traceId: safeHistoryId(failure?.response?.headers?.['x-trace-id'] ?? failure?.traceId),
    elapsedMs: Number.isFinite(elapsedMs) ? Math.max(0, Math.round(elapsedMs)) : null,
  }
}

export function isManagedHistoryRead(config?: HistoryReadConfig): boolean {
  return config?.historyRead === true && config.method?.toLowerCase() === 'get'
    && ['/bureau/rewards', '/bureau/inspections', '/bureau/transfers'].includes(config.url || '')
}

export function isTransientHistoryReadFailure(error: unknown): boolean {
  const failure = error as HistoryReadFailure | null
  if (!failure?.isAxiosError || failure.code === 'ERR_CANCELED') return false
  if (failure.response) return [502, 503, 504].includes(failure.response.status || 0)
  return ['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT', 'ECONNRESET'].includes(failure.code || '')
}

export function historyReadErrorMessage(error: unknown): string {
  const failure = error as HistoryReadFailure | null
  const status = failure?.response?.status ?? failure?.httpStatus
  const requestId = failure?.response?.headers?.['x-request-id'] ?? failure?.requestId
  const traceId = failure?.response?.headers?.['x-trace-id'] ?? failure?.traceId
  const detail = [status ? `HTTP ${status}` : '',
    safeHistoryId(requestId) ? `请求编号 ${requestId}` : '',
    safeHistoryId(traceId) ? `链路编号 ${traceId}` : ''].filter(Boolean)
  const message = failure?.code === 'ERR_HISTORY_RESPONSE' ? '列表响应格式异常，请刷新重试。' : '列表加载失败，请刷新重试。'
  return message + (detail.length ? `（${detail.join('；')}）` : '')
}
