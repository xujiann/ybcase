import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'
import { downloadError } from './download'
import { historyReadDiagnostic, isHistoryPagePayload, isManagedHistoryRead, isTransientHistoryReadFailure, safeHistoryId } from './historyRead'
import { sessionExpiry, SESSION_EXPIRED_EVENT, type SessionStamp } from './sessionExpiry'

declare module 'axios' {
  interface AxiosRequestConfig { historyRead?: boolean; historyStartedAt?: number; authSession?: SessionStamp }
}

export interface R<T = unknown> {
  code: number
  message: string
  data: T
}

const client = axios.create({ baseURL: '/api', timeout: 15000 })

/** 附件上传/下载走大文件通道：默认 15s 会把执法音像（最大 200MB）传到一半掐断 */
// 10 分钟上限而非 0（永不超时）：网络静默中断时 timeout:0 会让请求既不成功也不失败，
// 界面永久卡在“上传中”，只能刷新整页
export const LARGE_TRANSFER = { timeout: 600000 }

// 最近 API 错误环形缓存（反馈提交时自动附带，含 request-id 供服务端对账定位）
export interface ApiErrorSnap {
  time: string
  path: string
  httpStatus: number | null
  bizCode: number | null
  message: string
  requestId: string | null
  traceId?: string | null
}
export const recentErrors: ApiErrorSnap[] = []

function recordError(snap: ApiErrorSnap) {
  recentErrors.push(snap)
  if (recentErrors.length > 5) recentErrors.shift()
}

function diagnoseHistory(config: import('axios').AxiosRequestConfig | undefined, error: unknown) {
  const detail = historyReadDiagnostic(config, error, performance.now() - (config?.historyStartedAt ?? performance.now()))
  if (detail) console.warn('[ybcase-history-read]', JSON.stringify(detail))
}

function isBackground(config: import('axios').AxiosRequestConfig | undefined) {
  const headers = config?.headers
  return !!(headers && (typeof headers.get === 'function' ? headers.get('X-Background') : headers['X-Background']))
}

function redirectExpiredSession(stamp: SessionStamp) {
  const token = localStorage.getItem('bureau_token') || ''
  if (!sessionExpiry.beginRedirect(stamp, token)) return
  localStorage.removeItem('bureau_token')
  const cur = router.currentRoute.value
  if (cur.path !== '/login') router.push({ path: '/login', query: { redirect: cur.fullPath } })
}

client.interceptors.request.use((config) => {
  if (isManagedHistoryRead(config)) config.historyStartedAt = performance.now()
  const token = localStorage.getItem('bureau_token') || ''
  config.authSession = sessionExpiry.capture(token)
  if (sessionExpiry.isExpired(config.authSession, token)) {
    // Keep a background 401 from destroying an unsaved form. The next deliberate
    // action goes to login without sending another request with the known stale token.
    if (!isBackground(config)) redirectExpiredSession(config.authSession)
    throw Object.assign(new Error('登录已过期'), { code: 'ERR_SESSION_EXPIRED', sessionExpiryHandled: true })
  }
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

client.interceptors.response.use(
  (resp) => {
    // 文件下载（附件/审计CSV/反馈截图等）返回的是原始字节，没有 R 包装——
    // 不放行会把 Blob 的 code=undefined 误判为失败，所有下载 100% 报"操作失败"
    if (resp.config.responseType === 'blob') {
      // 后端出错时返回的是 JSON 而非文件（业务码走 HTTP 200），此时 blob 里装的是错误对象。
      // 不识别就会把错误当文件存盘：用户拿到一个打不开的"附件"，界面还提示成功。
      const ct = String(resp.headers['content-type'] || '')
      if (ct.includes('application/json')) {
        return downloadError(resp.data as Blob, ct, String(resp.headers['content-disposition'] || '')).then((j) => {
          if (j) {
            const msg = j.message || '下载失败'
            recordError({
              time: new Date().toISOString(), path: resp.config.url || '',
              httpStatus: resp.status, bizCode: j.code ?? null, message: msg,
              requestId: (resp.headers['x-request-id'] as string) || null,
            })
            ElMessage.error(msg)
            return Promise.reject(new Error(msg))
          }
          return resp
        })
      }
      return resp
    }
    const r = resp.data as R
    // A 200 HTML page, null envelope or malformed page must not look like an empty
    // successful list or throw an untracked TypeError before recording its IDs.
    if (isManagedHistoryRead(resp.config) && (!r || typeof r !== 'object' || typeof r.code !== 'number'
      || (r.code === 0 && !isHistoryPagePayload(r.data)))) {
      const error = Object.assign(new Error('Invalid history response'), {
        code: 'ERR_HISTORY_RESPONSE', httpStatus: resp.status,
        requestId: resp.headers['x-request-id'] || null, traceId: resp.headers['x-trace-id'] || null,
      })
      diagnoseHistory(resp.config, error)
      recordError({ time: new Date().toISOString(), path: resp.config.url || '', httpStatus: resp.status,
        bizCode: null, message: '历史列表响应格式异常', requestId: error.requestId, traceId: safeHistoryId(error.traceId) })
      return Promise.reject(error)
    }
    if (r.code !== 0) {
      diagnoseHistory(resp.config, {httpStatus: resp.status, bizCode: r.code, requestId: resp.headers['x-request-id'], traceId: resp.headers['x-trace-id']})
      recordError({
        time: new Date().toISOString(), path: resp.config.url || '',
        httpStatus: resp.status, bizCode: r.code, message: r.message || '',
        requestId: resp.headers['x-request-id'] || null,
        traceId: safeHistoryId(resp.headers['x-trace-id']),
      })
      if (r.code === 500) {
        // 系统级错误：提示可一键报告（点击打开反馈弹窗，自动带上错误上下文）
        ElMessage({
          type: 'error', duration: 6000,
          message: `${r.message || '系统内部错误'}（点顶栏"反馈"可一键报告）`,
        })
        window.dispatchEvent(new CustomEvent('ybcase-api-500'))
      } else {
        ElMessage.error(r.message || '操作失败')
      }
      return Promise.reject(Object.assign(new Error(r.message), {
        bizCode: r.code, httpStatus: resp.status, requestId: resp.headers['x-request-id'] || null, traceId: resp.headers['x-trace-id'] || null,
      }))
    }
    return resp
  },
  (error) => {
    if (error.sessionExpiryHandled) return Promise.reject(error)
    // Superseded history reads are deliberate cancellations, not user-visible failures.
    if (isManagedHistoryRead(error.config) && error.code === 'ERR_CANCELED') return Promise.reject(error)
    diagnoseHistory(error.config, error)
    recordError({
      time: new Date().toISOString(), path: error.config?.url || '',
      httpStatus: error.response?.status ?? null, bizCode: null,
      message: error.message || '', requestId: error.response?.headers?.['x-request-id'] || null,
      traceId: safeHistoryId(error.response?.headers?.['x-trace-id']),
    })
    if (error.response?.status === 401) {
      // 后台轮询（未读数）撞到令牌过期时不要 router.push：办案员可能正在弹窗里写长文本，
      // 硬导航会把整个组件连同表单一起销毁。轮询只提示、停止；等用户下一次主动操作再跳登录。
      const stamp = error.config?.authSession
      // A late 401 from a prior token or sign-in must not clear the new account.
      if (!stamp) return Promise.reject(error)
      const result = sessionExpiry.expire(stamp, localStorage.getItem('bureau_token') || '')
      if (!result.current) return Promise.reject(error)
      if (result.first) window.dispatchEvent(new CustomEvent(SESSION_EXPIRED_EVENT))
      const background = isBackground(error.config)
      if (result.notify) ElMessage.warning(background
        ? '登录已过期，请保存好正在填写的内容后重新登录' : '登录已过期，请重新登录')
      if (!background) redirectExpiredSession(stamp)
    } else if (!(isManagedHistoryRead(error.config) && isTransientHistoryReadFailure(error))) {
      // Pagination owns the bounded retry and final inline error for transient list reads.
      ElMessage.error(error.response?.data?.message || '网络请求失败')
    }
    return Promise.reject(error)
  },
)

export default client
