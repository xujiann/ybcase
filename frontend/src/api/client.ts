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
      router.push('/login')
      ElMessage.warning('登录已过期，请重新登录')
    } else {
      ElMessage.error(error.response?.data?.message || '网络请求失败')
    }
    return Promise.reject(error)
  },
)

export default client
