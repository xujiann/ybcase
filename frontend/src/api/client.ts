import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

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
}
export const recentErrors: ApiErrorSnap[] = []

function recordError(snap: ApiErrorSnap) {
  recentErrors.push(snap)
  if (recentErrors.length > 5) recentErrors.shift()
}

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('bureau_token')
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
        return (resp.data as Blob).text().then((txt) => {
          let msg = '下载失败'
          try {
            const j = JSON.parse(txt)
            msg = j.message || msg
            recordError({
              time: new Date().toISOString(), path: resp.config.url || '',
              httpStatus: resp.status, bizCode: j.code ?? null, message: msg,
              requestId: (resp.headers['x-request-id'] as string) || null,
            })
          } catch { /* 非 JSON 文本，用默认提示 */ }
          ElMessage.error(msg)
          return Promise.reject(new Error(msg))
        })
      }
      return resp
    }
    const r = resp.data as R
    if (r.code !== 0) {
      recordError({
        time: new Date().toISOString(), path: resp.config.url || '',
        httpStatus: resp.status, bizCode: r.code, message: r.message || '',
        requestId: resp.headers['x-request-id'] || null,
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
      return Promise.reject(new Error(r.message))
    }
    return resp
  },
  (error) => {
    recordError({
      time: new Date().toISOString(), path: error.config?.url || '',
      httpStatus: error.response?.status ?? null, bizCode: null,
      message: error.message || '', requestId: error.response?.headers?.['x-request-id'] || null,
    })
    if (error.response?.status === 401) {
      localStorage.removeItem('bureau_token')
      // 带回跳地址：办案人员常在详情页填写长表单，重登后回首页会找不回刚才的位置。
      // 已在登录页时不再重复跳转（并发请求同时 401 会触发多次 push）
      const cur = router.currentRoute.value
      if (cur.path !== '/login') {
        router.push({ path: '/login', query: { redirect: cur.fullPath } })
        ElMessage.warning('登录已过期，请重新登录')
      }
    } else {
      ElMessage.error(error.response?.data?.message || '网络请求失败')
    }
    return Promise.reject(error)
  },
)

export default client
