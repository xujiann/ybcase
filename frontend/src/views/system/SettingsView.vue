<template>
  <el-card>
    <el-alert v-if="!isAdmin" type="info" :closable="false" title="当前页面仅供管理员使用。" style="margin-bottom: 12px" />
    <div class="toolbar">
      <h3>参数设置<span class="hint">（期限/限额/阈值全部可配；默认=新《行政处罚法》+国家局令第4号，辽宁口径可切换，修改留痕（可查历史））</span></h3>
      <div>
        <el-input v-model="auditMonth" placeholder="YYYY-MM" style="width: 110px; margin-right: 6px" />
        <el-button v-if="isAdmin" size="small" :loading="exporting" @click="onAuditExport">导出审计日志</el-button>
        <el-input v-model="filter" placeholder="搜索参数" clearable style="width: 200px; margin-left: 12px" />
      </div>
    </div>
    <el-table :data="rows.filter((r) => !filter || r.key.includes(filter) || (r.remark || '').includes(filter))"
              border stripe size="small" v-loading="loading">
      <el-table-column prop="key" label="参数键" width="300" />
      <el-table-column label="当前值" width="200">
        <template #default="{ row }">
          <el-input :disabled="!isAdmin || pendingKeys.has(row.key)" v-model="row.value" size="small" @change="onSave(row)" />
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="说明" show-overflow-tooltip />
      <el-table-column label="历史" width="70">
        <template #default="{ row }">
          <el-button v-if="isAdmin" size="small" text type="primary" @click="onHistory(row)">历史</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-drawer v-model="historyVisible" :title="`变更历史：${historyKey}`" size="520px" @close="clearHistory">
      <el-alert v-if="history.failed" type="error" :closable="false" title="历史加载失败，请关闭后重新打开重试。" />
      <el-table :data="history.data" border stripe size="small" v-loading="history.loading"
                :empty-text="history.failed ? '历史加载失败' : '暂无变更记录'">
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ String(row.changed_at || '').replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column prop="old_value" label="原值" show-overflow-tooltip />
        <el-table-column prop="new_value" label="新值" show-overflow-tooltip />
        <el-table-column prop="changed_by" label="操作人" width="100" />
      </el-table>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import { adminOperationAccess } from '../../api/caseWriteAccess'
import { resourceState } from '../../api/loadableResource'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../../api/sessionExpiry'

const auth = useAuthStore()
const isAdmin = computed(() => adminOperationAccess(auth.user?.roles))

const rows = ref<{ key: string; value: string; remark: string }[]>([])
const loading = ref(false)
const filter = ref('')
const pendingKeys = reactive(new Set<string>())
const exporting = ref(false)
let disposed = false, loadGeneration = 0, writeGeneration = 0, exportGeneration = 0, historyGeneration = 0

function identity() {
  return JSON.stringify([auth.token, auth.user?.id, auth.user?.username, [...(auth.user?.roles || [])].sort()])
}
function context() { return { identity: identity(), stamp: sessionExpiry.capture(auth.token) } }
function active() {
  return !disposed && isAdmin.value && !!auth.token
    && !sessionExpiry.isExpired(sessionExpiry.capture(auth.token), auth.token)
}
function isContextCurrent(c: ReturnType<typeof context>) {
  return active() && c.identity === identity() && sessionExpiry.isCurrent(c.stamp, auth.token)
}
function invalidate() {
  loadGeneration++; writeGeneration++; exportGeneration++
  rows.value = []; loading.value = false; pendingKeys.clear(); exporting.value = false
  historyVisible.value = false; historyKey.value = ''; clearHistory()
}
function onSessionExpired() { invalidate() }
watch(identity, invalidate, { flush: 'sync' })
onUnmounted(() => { disposed = true; invalidate(); window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired) })
// 本地时区月份：toISOString 取 UTC，中国时区月初 0-8 点会算成上个月
function monthLocal() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}
const auditMonth = ref(monthLocal())

async function onAuditExport() {
  if (!isAdmin.value || !active() || exporting.value) return
  const run = ++exportGeneration, captured = context(), month = auditMonth.value
  exporting.value = true
  try {
    const resp = await client.get('/bureau/audit/export', { params: { month }, responseType: 'blob' })
    if (run !== exportGeneration || !isContextCurrent(captured)) return
    const a = document.createElement('a')
    a.href = URL.createObjectURL(resp.data)
    a.download = `audit-${month}.csv`
    try { a.click() } finally { URL.revokeObjectURL(a.href) }
    ElMessage.success('审计日志已导出（等保：定期归档留存）')
  } finally { if (run === exportGeneration) exporting.value = false }
}

async function load() {
  if (!isAdmin.value || !active()) return
  const run = ++loadGeneration, captured = context()
  loading.value = true
  rows.value = []
  try {
    const resp = await client.get('/config/all')
    if (run === loadGeneration && isContextCurrent(captured)) rows.value = resp.data.data
  } catch {
    if (run === loadGeneration && isContextCurrent(captured)) rows.value = []
  } finally {
    if (run === loadGeneration) loading.value = false
  }
}

async function onSave(row: { key: string; value: string }) {
  if (!isAdmin.value || !active() || loading.value || pendingKeys.has(row.key) || !rows.value.some(current => current === row)) return
  const run = writeGeneration, captured = context(), key = row.key, value = row.value
  pendingKeys.add(key)
  try {
    await client.put(`/config/${key}?value=${encodeURIComponent(value)}`)
    if (run === writeGeneration && isContextCurrent(captured) && rows.value.some(current => current === row) && row.value === value)
      ElMessage.success(`已保存 ${key}`)
  } finally { if (run === writeGeneration) pendingKeys.delete(key) }
}

const historyVisible = ref(false)
const historyKey = ref('')
const history = reactive(resourceState<{ changed_at: string; old_value: string; new_value: string; changed_by: string }[]>([]))
function clearHistory() {
  historyGeneration++; history.data = []; history.ready = false; history.loading = false; history.failed = false
}

async function onHistory(row: { key: string }) {
  if (!isAdmin.value || !active()) return
  clearHistory()
  historyKey.value = row.key
  historyVisible.value = true
  const run = historyGeneration, captured = context(), key = row.key
  history.loading = true
  try {
    const resp = await client.get(`/config/${key}/history`)
    if (run === historyGeneration && isContextCurrent(captured) && historyVisible.value && historyKey.value === key) {
      history.data = resp.data.data; history.ready = true
    }
  } catch {
    if (run === historyGeneration && isContextCurrent(captured)) history.failed = true
  } finally { if (run === historyGeneration) history.loading = false }
}

onMounted(() => { window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired); load() })
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
