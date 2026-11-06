<template>
  <el-card>
    <el-alert v-if="!isAdmin" type="info" :closable="false" title="当前页面仅供管理员使用。" style="margin-bottom: 12px" />
    <div class="toolbar">
      <h3>反馈管理<span class="hint">（试点反馈是版本排期的原始输入；处理结果自动通知提交人）</span></h3>
      <div>
        <el-select v-model="fStatus" clearable placeholder="全部状态" style="width: 110px; margin-right: 8px" @change="load">
          <el-option v-for="(v, k) in STATUS" :key="k" :label="v" :value="k" />
        </el-select>
        <el-select v-model="fKind" clearable placeholder="全部类型" style="width: 110px; margin-right: 8px" @change="load">
          <el-option v-for="(v, k) in KIND" :key="k" :label="v" :value="k" />
        </el-select>
        <el-button v-if="isAdmin" :loading="exporting" @click="onExport">导出CSV</el-button>
      </div>
    </div>

    <el-row :gutter="8" class="mb">
      <el-col :span="10">
        <el-card shadow="never"><b>状态分布：</b>
          <el-tag v-for="s in d.byStatus" :key="s.status" size="small" style="margin-right: 6px"
                  :type="(STATUS_TAG[s.status] as any)">{{ STATUS[s.status] }} {{ s.cnt }}</el-tag>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="never"><b>BUG 高发页面：</b>
          <span v-for="p in d.byPage" :key="p.page_route" class="hint" style="margin-right: 10px">
            {{ p.page_route }}（{{ p.cnt }}）</span>
          <span v-if="!d.byPage?.length" class="hint">暂无</span>
        </el-card>
      </el-col>
    </el-row>

    <el-alert v-if="failed" type="error" :closable="false" title="反馈列表加载失败，请重新选择筛选条件重试。" />
    <el-table :data="d.rows" border stripe size="small" v-loading="loading" :empty-text="failed ? '加载失败' : '暂无数据'">
      <el-table-column prop="id" label="#" width="55" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ KIND[row.kind] }}</template>
      </el-table-column>
      <el-table-column prop="title" label="标题" width="200" show-overflow-tooltip />
      <el-table-column prop="username" label="提交人" width="90" />
      <el-table-column prop="page_route" label="页面" width="140" show-overflow-tooltip />
      <el-table-column label="上下文" width="150">
        <template #default="{ row }">
          <el-button v-if="isAdmin && (row.recent_errors || row.has_screenshot)" size="small" text type="primary"
                     @click="viewContext(row)">错误/截图</el-button>
          <span v-else class="hint">-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="(STATUS_TAG[row.status] as any)">{{ STATUS[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reply" label="回复" show-overflow-tooltip />
      <el-table-column label="处理" width="100">
        <template #default="{ row }">
          <el-button v-if="isAdmin && (!['CLOSED'].includes(row.status))" size="small" type="primary"
                     @click="openHandle(row)">处理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="handleVisible" :title="`处理反馈 #${handling.id}：${handling.title}`" width="560px" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <p style="white-space: pre-wrap">{{ handling.content }}</p>
      <p class="hint" v-if="handling.request_id">request-id：{{ handling.request_id }}（可在审计日志/服务端日志检索）</p>
      <el-form label-width="70px" :disabled="saving">
        <el-form-item label="状态">
          <el-radio-group v-model="handleForm.status">
            <el-radio value="PROCESSING">处理中</el-radio>
            <el-radio value="RESOLVED">已解决</el-radio>
            <el-radio value="REJECTED">不采纳</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="回复"><el-input v-model="handleForm.reply" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="handleVisible = false">取消</el-button>
        <el-button v-if="isAdmin" type="primary" :loading="saving" @click="onHandle">保存并通知提交人</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="ctxVisible" title="反馈上下文" width="640px">
      <p v-if="shot.loading" class="hint">截图加载中</p>
      <el-alert v-if="shot.failed" type="warning" :closable="false" title="截图加载失败，请重新打开上下文重试。" />
      <img v-if="shot.url" :src="shot.url" style="max-width: 100%" class="mb" />
      <pre v-if="cur.recent_errors" class="ctx">{{ prettyErrors(cur.recent_errors) }}</pre>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import { createBlobPreview } from '../../api/blobPreview'
import { adminOperationAccess } from '../../api/caseWriteAccess'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../../api/sessionExpiry'

const auth = useAuthStore()
const isAdmin = computed(() => adminOperationAccess(auth.user?.roles))

const KIND: Record<string, string> = { BUG: '问题缺陷', FEATURE: '功能需求', QUESTION: '使用咨询' }
const STATUS: Record<string, string> = { NEW: '待处理', PROCESSING: '处理中', RESOLVED: '已解决', REJECTED: '不采纳', CLOSED: '已关闭' }
const STATUS_TAG: Record<string, string> = { NEW: 'warning', PROCESSING: 'primary', RESOLVED: 'success', REJECTED: 'info', CLOSED: 'info' }
const d = ref<any>({ rows: [] })
const loading = ref(false)
const failed = ref(false)
let loadGeneration = 0
let disposed = false
let handleGeneration = 0
let exportGeneration = 0
let contextGeneration = 0
const fStatus = ref('')
const fKind = ref('')
const handleVisible = ref(false)
const ctxVisible = ref(false)
const cur = ref<any>({})
const handling = ref<any>({})
const saving = ref(false)
const exporting = ref(false)
const shot = reactive({ url: '', loading: false, failed: false })
const screenshotPreview = createBlobPreview(shot)
watch(ctxVisible, visible => { if (!visible) { contextGeneration++; screenshotPreview.clear() } }, { flush: 'sync' })
onUnmounted(screenshotPreview.clear)
const handleForm = reactive({ status: 'PROCESSING', reply: '' })

function identity() {
  return JSON.stringify([auth.token, auth.user?.id, auth.user?.username, [...(auth.user?.roles || [])].sort()])
}
function context() { return { identity: identity(), stamp: sessionExpiry.capture(auth.token) } }
function current(c: ReturnType<typeof context>) {
  return !disposed && isAdmin.value && c.identity === identity()
    && sessionExpiry.isCurrent(c.stamp, auth.token) && !sessionExpiry.isExpired(c.stamp, auth.token)
}
function invalidate(clearDraft = true) {
  loadGeneration++; handleGeneration++; exportGeneration++; contextGeneration++
  loading.value = false; saving.value = false; exporting.value = false; failed.value = false
  d.value = { rows: [] }; ctxVisible.value = false; screenshotPreview.clear(); cur.value = {}
  if (clearDraft) { handleVisible.value = false; handling.value = {}; handleForm.status = 'PROCESSING'; handleForm.reply = '' }
}
watch(identity, () => invalidate(), { flush: 'sync' })
watch(handleVisible, visible => { if (!visible) { handleGeneration++; saving.value = false } }, { flush: 'sync' })
function onSessionExpired() { invalidate(false) }
onMounted(() => window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired))
onUnmounted(() => { disposed = true; invalidate(); window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired) })

async function load() {
  if (!isAdmin.value || disposed) return
  const run = ++loadGeneration, captured = context()
  loading.value = true
  failed.value = false
  d.value = { rows: [] }
  try {
    const params: any = {}
    if (fStatus.value) params.status = fStatus.value
    if (fKind.value) params.kind = fKind.value
    const data = (await client.get('/bureau/feedback', { params })).data.data
    if (run === loadGeneration && current(captured)) d.value = data
  } catch {
    if (run === loadGeneration && current(captured)) { d.value = { rows: [] }; failed.value = true }
  } finally {
    if (run === loadGeneration) loading.value = false
  }
}

function openHandle(row: any) {
  if (!isAdmin.value || disposed || row.status === 'CLOSED' || saving.value || loading.value || failed.value) return
  handleGeneration++
  handling.value = { ...row }
  handleForm.status = row.status === 'NEW' ? 'PROCESSING' : row.status
  handleForm.reply = row.reply || ''
  handleVisible.value = true
}

async function onHandle() {
  if (!isAdmin.value || disposed || !handleVisible.value || handling.value.status === 'CLOSED' || saving.value) return
  const id = handling.value.id, payload = { ...handleForm }
  if (!['PROCESSING', 'RESOLVED', 'REJECTED'].includes(payload.status)
      || (payload.status !== 'PROCESSING' && !payload.reply.trim())) {
    ElMessage.warning('解决/不采纳须填写回复说明'); return
  }
  const run = ++handleGeneration, captured = context()
  const unchanged = () => run === handleGeneration && current(captured) && handleVisible.value
    && handling.value.id === id && handleForm.status === payload.status && handleForm.reply === payload.reply
  saving.value = true
  try {
    await client.post(`/bureau/feedback/${id}/handle`, payload)
    if (!unchanged()) return
    ElMessage.success('已处理并通知提交人')
    handleVisible.value = false
    await load()
  } finally { if (run === handleGeneration) saving.value = false }
}

async function viewContext(row: any) {
  if (!isAdmin.value || disposed) return
  const captured = context(), run = ++contextGeneration
  cur.value = row
  ctxVisible.value = true
  await screenshotPreview.show(row.has_screenshot ? async () => {
    const blob = (await client.get(`/bureau/feedback/${row.id}/screenshot`, { responseType: 'blob' })).data
    if (!current(captured)) {
      if (run === contextGeneration) screenshotPreview.clear()
      throw new Error('Feedback context changed')
    }
    return blob
  } : undefined)
}

function prettyErrors(s: string) {
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}

async function onExport() {
  if (!isAdmin.value || disposed || exporting.value) return
  const run = ++exportGeneration, captured = context()
  exporting.value = true
  try {
    const resp = await client.get('/bureau/feedback/export', { responseType: 'blob' })
    if (run !== exportGeneration || !current(captured)) return
    const a = document.createElement('a')
    a.href = URL.createObjectURL(resp.data)
    a.download = 'feedback.csv'
    try { a.click() } finally { URL.revokeObjectURL(a.href) }
  } finally { if (run === exportGeneration) exporting.value = false }
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
.mb { margin-bottom: 12px; }
.ctx { background: #f8f9fb; padding: 10px; font-size: 12px; white-space: pre-wrap; }
</style>
