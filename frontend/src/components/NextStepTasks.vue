<template>
  <el-card class="tasks-card">
    <div class="task-toolbar">
      <h4>下一步待办</h4>
      <el-button :loading="loading" @click="refresh">刷新待办</el-button>
    </div>
    <p class="task-hint">按当前业务进度生成办理提示，进入对应页面核对具体材料和办理条件。</p>
    <el-alert v-if="failed" type="warning" :closable="false" title="待办加载失败，请刷新重试。" class="task-alert" />
    <el-table :data="rows" v-loading="loading" border size="small" row-key="taskKey"
              :empty-text="failed ? '待办加载失败' : '当前没有待办提示'">
      <el-table-column prop="sourceNo" label="关联编号" width="170" show-overflow-tooltip />
      <el-table-column prop="title" label="下一步事项" min-width="230" show-overflow-tooltip />
      <el-table-column prop="stage" label="当前进度" width="130" />
      <el-table-column label="办理期限" width="160">
        <template #default="{ row }">
          <span v-if="row.dueAt" :class="{ 'task-overdue': row.overdueDays > 0 }">
            {{ row.dueAt }}<span v-if="row.overdueDays > 0">（超期 {{ row.overdueDays }} 天）</span>
          </span>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100">
        <template #default="{ row }"><el-button link type="primary" @click="openTask(row)">{{ row.actionLabel }}</el-button></template>
      </el-table-column>
    </el-table>
    <el-pagination class="task-pagination" :current-page="page" :total="total" :page-size="PAGE_SIZE"
                   :disabled="loading" layout="total, prev, pager, next" @current-change="changePage" />
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import client from '../api/client'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'
import { useAuthStore } from '../stores/auth'

interface TaskRow {
  taskKey: string
  sourceType: string
  sourceId: number
  cycle: number | null
  sourceNo: string
  title: string
  stage: string
  dueAt: string | null
  overdueDays: number | null
  actionLabel: string
  link: string
}
interface TaskPage { rows: TaskRow[]; total: number; page: number; size: number; asOf: string }

const props = defineProps<{ domain: 'bureau' | 'agency'; refreshKey?: unknown }>()
const auth = useAuthStore()
const router = useRouter()
const PAGE_SIZE = 20
const rows = ref<TaskRow[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)
const failed = ref(false)
const permitted = computed(() => props.domain === 'agency'
  ? auth.user?.accountCategory === 'AGENCY' && auth.user.roles.length === 1 && auth.user.roles.includes('AGENCY')
  : !auth.user?.roles.includes('AGENCY') && !!auth.user?.roles.some(role => ['HANDLER', 'LEGAL', 'LEADER', 'ADMIN'].includes(role)))
let disposed = false, generation = 0
const identity = () => JSON.stringify([props.domain, auth.token, auth.user?.id, auth.user?.accountCategory, auth.user?.roles])
function active() {
  return !disposed && !!auth.token && permitted.value
    && !sessionExpiry.isExpired(sessionExpiry.capture(auth.token), auth.token)
}
function invalidate() {
  generation++
  rows.value = []; total.value = 0; page.value = 1; loading.value = false; failed.value = false
}
watch(identity, invalidate, { flush: 'sync' })
watch(() => props.refreshKey, value => { if (value !== undefined) void refresh() })

async function load(next: number) {
  if (!active()) return
  const run = ++generation, who = identity(), stamp = sessionExpiry.capture(auth.token)
  const current = () => run === generation && active() && who === identity()
    && sessionExpiry.isCurrent(stamp, auth.token)
  loading.value = true
  failed.value = false
  try {
    const response = await client.get<{ data: TaskPage }>(`/${props.domain}/my/tasks`,
      { params: { page: next, size: PAGE_SIZE } })
    if (!current()) return
    const result = response.data.data
    if (!result || !Array.isArray(result.rows) || !Number.isSafeInteger(result.total) || result.total < 0
        || result.page !== next || result.size !== PAGE_SIZE) throw new Error('Invalid tasks page')
    const lastPage = Math.max(1, Math.ceil(result.total / PAGE_SIZE))
    if (next > lastPage) return await load(lastPage)
    rows.value = result.rows; total.value = result.total; page.value = next
  } catch {
    if (current()) { rows.value = []; total.value = 0; page.value = 1; failed.value = true }
  } finally {
    if (run === generation) loading.value = false
  }
}
function refresh() {
  return load(1)
}
function changePage(next: number) {
  if (loading.value || next === page.value || !Number.isSafeInteger(next) || next < 1
      || next > Math.ceil(total.value / PAGE_SIZE)) return
  return load(next)
}
function openTask(row: TaskRow) {
  if (!active() || !rows.value.includes(row)) return
  if (props.domain === 'agency' ? !/^\/agency\/agreements\?focus=\d+&cycle=\d+$/.test(row.link)
    : !/^\/case\/(detail\/\d+|clues\?focus=\d+|approvals\?focus=\d+|department-handoffs\?focus=\d+)$/.test(row.link)) return
  void router.push(row.link)
}
onMounted(() => { window.addEventListener(SESSION_EXPIRED_EVENT, invalidate); void refresh() })
onUnmounted(() => { disposed = true; invalidate(); window.removeEventListener(SESSION_EXPIRED_EVENT, invalidate) })
</script>

<style scoped>
.tasks-card { margin-bottom: 12px; }
.task-toolbar { display: flex; justify-content: space-between; align-items: center; }
.task-toolbar h4 { margin: 0; }
.task-hint { color: #666; font-size: 12px; }
.task-alert { margin-bottom: 12px; }
.task-overdue { color: #c0392b; font-weight: 600; }
.task-pagination { margin-top: 12px; justify-content: flex-end; }
</style>
