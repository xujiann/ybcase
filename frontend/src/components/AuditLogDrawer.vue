<template>
  <el-drawer :model-value="true" title="查询审计日志" size="min(1180px, 100%)" @update:model-value="close">
    <el-form :model="filters" inline class="audit-filters" @submit.prevent="search">
      <el-form-item label="操作人">
        <el-input v-model="filters.username" placeholder="登录账号" clearable />
      </el-form-item>
      <el-form-item label="开始日期">
        <el-date-picker v-model="filters.from" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="结束日期">
        <el-date-picker v-model="filters.to" type="date" value-format="YYYY-MM-DD" />
      </el-form-item>
      <el-form-item label="路径">
        <el-input v-model="filters.path" placeholder="包含路径片段" clearable />
      </el-form-item>
      <el-form-item label="请求编号">
        <el-input v-model="filters.requestId" placeholder="完整请求编号" clearable />
      </el-form-item>
      <el-form-item label="报表类型">
        <el-select v-model="filters.reportType" placeholder="全部日志" style="width: 180px">
          <el-option label="全部日志" value="" />
          <el-option label="全部报表导出" value="ALL_REPORTS" />
          <el-option v-for="(label, value) in REPORT_TYPES" :key="value" :label="label" :value="value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button native-type="submit" type="primary" :loading="loading">查询</el-button>
      </el-form-item>
    </el-form>

    <el-alert v-if="error" :title="error" type="error" :closable="false" class="audit-alert" />
    <el-table :data="rows" border stripe size="small" v-loading="loading" row-key="id"
              :empty-text="error ? '暂无可显示的查询结果' : searched ? '未查到审计日志' : '设置筛选条件后点击查询'">
      <el-table-column label="操作时间" width="155">
        <template #default="{ row }">{{ formatLocalDateTime(row.created_at) }}</template>
      </el-table-column>
      <el-table-column prop="username" label="操作人" width="100" show-overflow-tooltip>
        <template #default="{ row }">{{ row.username || '—' }}</template>
      </el-table-column>
      <el-table-column label="请求路径" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.method }} {{ row.path }}</template>
      </el-table-column>
      <el-table-column prop="http_status" label="结果码" width="75" />
      <el-table-column prop="request_id" label="请求编号" min-width="145" show-overflow-tooltip>
        <template #default="{ row }">{{ row.request_id || '—' }}</template>
      </el-table-column>
      <el-table-column label="报表导出详情" min-width="235">
        <template #default="{ row }">
          <template v-if="row.report_type">
            <div>{{ REPORT_TYPES[row.report_type] || row.report_type }}</div>
            <div v-if="row.report_start || row.report_end">统计期间 {{ row.report_start || '—' }} 至 {{ row.report_end || '—' }}</div>
            <div v-if="row.report_cutoff">截至 {{ row.report_cutoff }}</div>
            <div v-if="row.report_row_count != null">导出 {{ row.report_row_count }} 行</div>
          </template>
          <span v-else>—</span>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="searched" class="audit-pagination" :current-page="page" :page-size="PAGE_SIZE"
                   :total="total" :disabled="loading" layout="total, prev, pager, next" @current-change="changePage" />
  </el-drawer>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { adminOperationAccess } from '../api/caseWriteAccess'
import client from '../api/client'
import { formatLocalDateTime } from '../api/dates'
import { isHistoryPagePayload } from '../api/historyRead'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'
import { useAuthStore } from '../stores/auth'

interface AuditLog {
  id: number
  created_at: string
  username: string | null
  method: string
  path: string
  http_status: number
  request_id?: string | null
  report_type?: string | null
  report_start?: string | null
  report_end?: string | null
  report_cutoff?: string | null
  report_row_count?: number | null
}

const PAGE_SIZE = 20
const REPORT_TYPES: Record<string, string> = {
  PENALTIES: '行政处罚情况统计表',
  ENFORCEMENT_WORK: '执法人员办理案件情况统计表',
  INTERVIEWS: '约谈定点医药机构台账',
}

const emit = defineEmits<{ close: [] }>()
const auth = useAuthStore()
const emptyFilters = () => ({ username: '', from: '', to: '', path: '', requestId: '', reportType: '' })
const filters = reactive(emptyFilters())
const rows = ref<AuditLog[]>([])
const page = ref(1)
const total = ref(0)
const searched = ref(false)
const loading = ref(false)
const error = ref('')

let generation = 0
let active = true
let maxId: number | undefined
let submitted: Record<string, string> = {}
const authorized = () => !!auth.token && adminOperationAccess(auth.user?.roles)
const identity = () => JSON.stringify([auth.token, auth.user?.id, auth.user?.username, authorized()])

function clearResults() {
  generation++
  maxId = undefined
  submitted = {}
  rows.value = []; total.value = 0; page.value = 1
  searched.value = false; loading.value = false; error.value = ''
}

function clear() {
  active = false
  Object.assign(filters, emptyFilters())
  clearResults()
}

function dismiss() {
  clear()
  emit('close')
}

function close(visible: boolean) {
  if (!visible) dismiss()
}

watch(filters, clearResults, { flush: 'sync' })
watch(identity, dismiss, { flush: 'sync' })
onMounted(() => {
  window.addEventListener(SESSION_EXPIRED_EVENT, dismiss)
  void search()
})
onUnmounted(() => {
  clear()
  window.removeEventListener(SESSION_EXPIRED_EVENT, dismiss)
})

function validDate(value: string): boolean {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || value.startsWith('0000')) return false
  const date = new Date(value + 'T00:00:00Z')
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}

async function search() {
  if (!active || !authorized() || loading.value) return
  clearResults()
  const entries = Object.entries(filters).map(([key, value]) => [key, (value || '').trim()] as const)
  submitted = Object.fromEntries(entries.filter(([, value]) => value))
  const { from, to } = submitted
  if ((from && !validDate(from)) || (to && !validDate(to)) || (from && to && from > to)) {
    error.value = '请选择有效的起止日期，结束日期不得早于开始日期'
    return
  }
  await loadPage(1)
}

async function changePage(next: number) {
  if (!active || !authorized() || !searched.value || loading.value || next === page.value
    || !Number.isSafeInteger(next) || next < 1 || next > Math.ceil(total.value / PAGE_SIZE)) return
  await loadPage(next)
}

async function loadPage(target: number) {
  const run = ++generation, key = identity(), stamp = sessionExpiry.capture(auth.token)
  const current = () => active && authorized() && run === generation && key === identity()
    && sessionExpiry.isCurrent(stamp, auth.token) && !sessionExpiry.isExpired(stamp, auth.token)
  if (!current()) return
  const params = { ...submitted, page: target, size: PAGE_SIZE, ...(maxId ? { maxId } : {}) }
  loading.value = true
  error.value = ''
  try {
    const response = await client.get<{ data: { rows: AuditLog[]; total: number; page: number; size: number } }>('/audit/logs', { params })
    if (!current()) return
    const data = response.data.data
    const latestId = data?.rows?.[0]?.id ?? 0
    if (!isHistoryPagePayload(data) || data.page !== target || data.size !== PAGE_SIZE
      || (data.rows.length && (!Number.isSafeInteger(latestId) || latestId <= 0))) {
      throw new Error('invalid audit page')
    }
    if (target === 1 && !maxId && latestId > 0) maxId = latestId
    rows.value = data.rows; total.value = data.total; page.value = data.page
    searched.value = true
  } catch {
    if (current()) error.value = '审计日志查询失败，请重试'
  } finally {
    if (current()) loading.value = false
  }
}
</script>

<style scoped>
.audit-filters :deep(.el-input) { width: 160px; }
.audit-filters :deep(.el-date-editor) { width: 145px; }
.audit-alert { margin-bottom: 12px; }
.audit-pagination { margin-top: 12px; justify-content: flex-end; }
</style>