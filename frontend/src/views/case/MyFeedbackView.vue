<template>
  <el-card>
    <div class="toolbar">
      <h3>我的反馈<span class="hint">（处理进展会同步到站内消息；"已解决"请验证后确认关闭）</span></h3>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-alert v-if="failed" type="error" :closable="false" title="反馈列表加载失败，请刷新重试。" />
    <el-table :data="rows" border stripe size="small" v-loading="loading" :empty-text="failed ? '加载失败' : '暂无数据'">
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ KIND[row.kind] }}</template>
      </el-table-column>
      <el-table-column prop="title" label="标题" width="220" show-overflow-tooltip />
      <el-table-column prop="content" label="描述" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="(STATUS_TAG[row.status] as any)">{{ STATUS[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reply" label="处理回复" show-overflow-tooltip />
      <el-table-column label="提交时间" width="140">
        <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-if="row.status === 'RESOLVED'" size="small" type="success"
                     :loading="closing === row.id" :disabled="closing !== null"
                     @click="onClose(row)">确认关闭</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { fmtTime } from './labels'
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../../api/sessionExpiry'

const KIND: Record<string, string> = { BUG: '问题缺陷', FEATURE: '功能需求', QUESTION: '使用咨询' }
const STATUS: Record<string, string> = { NEW: '待处理', PROCESSING: '处理中', RESOLVED: '已解决', REJECTED: '不采纳', CLOSED: '已关闭' }
const STATUS_TAG: Record<string, string> = { NEW: 'warning', PROCESSING: 'primary', RESOLVED: 'success', REJECTED: 'info', CLOSED: 'info' }
const rows = ref<any[]>([])
const loading = ref(false)
const failed = ref(false)
const closing = ref<number | null>(null)
const auth = useAuthStore()
let generation = 0, closeGeneration = 0, disposed = false

function identity() {
  return JSON.stringify([auth.token, auth.user?.id, auth.user?.username, [...(auth.user?.roles || [])].sort()])
}
function context() { return { identity: identity(), stamp: sessionExpiry.capture(auth.token) } }
function current(c: ReturnType<typeof context>) {
  return !disposed && c.identity === identity() && sessionExpiry.isCurrent(c.stamp, auth.token)
    && !sessionExpiry.isExpired(c.stamp, auth.token)
}
function invalidate() { generation++; closeGeneration++; rows.value = []; loading.value = false; closing.value = null; failed.value = false }
watch(identity, invalidate, { flush: 'sync' })
onMounted(() => window.addEventListener(SESSION_EXPIRED_EVENT, invalidate))
onUnmounted(() => { disposed = true; invalidate(); window.removeEventListener(SESSION_EXPIRED_EVENT, invalidate) })

async function load() {
  if (disposed) return
  const run = ++generation, captured = context()
  loading.value = true; failed.value = false; rows.value = []
  try {
    const data = (await client.get('/bureau/feedback/mine')).data.data
    if (run === generation && current(captured)) rows.value = data
  } catch {
    if (run === generation && current(captured)) failed.value = true
  } finally {
    if (run === generation) loading.value = false
  }
}

async function onClose(row: any) {
  if (disposed || row.status !== 'RESOLVED' || closing.value !== null || loading.value || failed.value) return
  const id = row.id, captured = context(), run = ++closeGeneration
  closing.value = id
  // An earlier list read must never restore this row after its close completes.
  generation++
  try {
    await client.post(`/bureau/feedback/${id}/close`)
    if (run !== closeGeneration || !current(captured)) return
    ElMessage.success('已确认关闭，感谢反馈')
    await load()
  } catch {
    // The server may already have closed it, or changed its state. Refresh before allowing another action.
    if (run === closeGeneration && current(captured)) await load()
  } finally { if (run === closeGeneration) closing.value = null }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
