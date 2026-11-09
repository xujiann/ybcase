<template>
  <el-card>
    <div class="toolbar">
      <h3>机构交接</h3>
      <div class="list-controls">
        <el-select v-model="status" clearable placeholder="全部状态" style="width: 140px" @change="paging.firstPage">
          <el-option v-for="(label, key) in STATES" :key="key" :label="label" :value="key" />
        </el-select>
        <el-button @click="paging.load">刷新</el-button>
        <el-button type="primary" @click="openRequest">发起交接</el-button>
      </div>
    </div>
    <TaskFocusBanner :loading="pagination.loading" :failed="pagination.failed" :count="pagination.rows.length" label="机构交接单" @change="setTaskFocus" />
    <el-alert v-if="pagination.failed" :title="pagination.error" type="error" :closable="false" />
    <el-table :data="pagination.rows" v-loading="pagination.loading" border stripe>
      <el-table-column label="来源" min-width="175">
        <template #default="{ row }">{{ SOURCES[row.source_type as SourceType].label }} {{ row.source_no }}</template>
      </el-table-column>
      <el-table-column label="移出机构" min-width="140">
        <template #default="{ row }">{{ row.from_department || '历史记录待认领' }}</template>
      </el-table-column>
      <el-table-column prop="to_department" label="接收机构" min-width="140" />
      <el-table-column label="状态" width="96">
        <template #default="{ row }">{{ STATES[row.status as Status] }}</template>
      </el-table-column>
      <el-table-column prop="requested_by" label="发起人" min-width="100" />
      <el-table-column prop="reason" label="交接原因" min-width="145" show-overflow-tooltip />
      <el-table-column prop="decided_by" label="处理人" min-width="100" />
      <el-table-column prop="opinion" label="处理意见" min-width="145" show-overflow-tooltip />
      <el-table-column label="操作" width="165">
        <template #default="{ row }">
          <el-button v-if="row.can_decide" type="primary" text :disabled="saving" @click="openDecision(row, 'accept')">接收</el-button>
          <el-button v-if="row.can_decide" text :disabled="saving" @click="openDecision(row, 'reject')">驳回</el-button>
          <el-button v-if="row.can_cancel" type="warning" text :disabled="saving" @click="openDecision(row, 'cancel')">撤回</el-button>
          <el-button v-if="row.can_assign_inspection" type="primary" text :disabled="saving" @click="openAssignment(row)">指派承办</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="handoff-pagination" :current-page="pagination.page" :page-size="pagination.size" :page-sizes="[20,50,100]"
      :total="pagination.total" :disabled="focusId !== undefined || pagination.loading" layout="total, sizes, prev, pager, next" @current-change="paging.changePage" @size-change="paging.changeSize" />

    <el-dialog v-model="requestVisible" title="发起机构交接" width="min(540px, 94vw)" :close-on-click-modal="!saving">
      <el-form label-width="100px">
        <el-form-item label="来源类型" required>
          <el-select v-model="request.sourceType" @change="changeSourceType">
            <el-option v-for="(option, key) in SOURCES" :key="key" :label="option.label" :value="key" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源编号" required>
          <el-select v-model="request.sourceId" filterable remote :remote-method="searchSources" :loading="sourceLoading"
            placeholder="输入编号搜索" style="width: 100%">
            <el-option v-for="option in sources" :key="option.id" :label="option.number" :value="option.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="接收机构" required>
          <el-select v-model="request.targetDeptId" filterable :loading="deptLoading" style="width: 100%">
            <el-option v-for="dept in departments" :key="dept.id" :label="dept.name" :value="dept.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="交接原因" required>
          <el-input v-model="request.reason" type="textarea" :rows="3" maxlength="512" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="requestVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitRequest">提交交接</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="decisionVisible" :title="ACTIONS[decision.action]" width="min(500px, 94vw)" :close-on-click-modal="!saving">
      <p>{{ decision.number }}</p>
      <el-input v-model="decision.opinion" type="textarea" :rows="3" maxlength="512" show-word-limit placeholder="填写处理意见" />
      <template #footer>
        <el-button :disabled="saving" @click="decisionVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitDecision">确认{{ ACTIONS[decision.action] }}</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="assignmentVisible" title="指派检查承办人" width="min(500px, 94vw)"
      :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <p>{{ assignment.number }}</p>
      <el-alert v-if="assignmentReady && !assignees.length" type="info" :closable="false"
        title="本机构暂无可指派的办案员，接收负责人可直接办理。" />
      <el-form label-width="90px">
        <el-form-item label="承办人" required>
          <el-select v-model="assignment.username" filterable :loading="assignmentLoading" :disabled="!assignmentReady || saving"
            placeholder="选择本机构办案员" style="width:100%">
            <el-option v-for="person in assignees" :key="person.id"
              :label="`${person.real_name}（${person.username}）`" :value="person.username" />
          </el-select>
        </el-form-item>
        <el-form-item label="指派原因" required>
          <el-input v-model="assignment.reason" type="textarea" :rows="3" maxlength="512" show-word-limit :disabled="saving" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="assignmentVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!assignmentReady || !assignment.username || !assignment.reason.trim()"
          :loading="saving" @click="submitAssignment">确认指派</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { createHistoryPagination, historyPageState } from '../../api/historyPagination'
import { useAuthStore } from '../../stores/auth'
import TaskFocusBanner from '../../components/TaskFocusBanner.vue'

const SOURCES = {
  CLUE: { label: '线索', path: 'clues', column: 'clue_no' },
  INSPECTION: { label: '检查', path: 'inspections', column: 'insp_no' },
  CASE: { label: '案件', path: 'cases', column: 'case_no' },
}
const STATES = { PENDING: '待接收', ACCEPTED: '已接收', REJECTED: '已驳回', CANCELLED: '已撤回' }
const ACTIONS = { accept: '接收交接', reject: '驳回交接', cancel: '撤回交接' }
type SourceType = keyof typeof SOURCES
type Status = keyof typeof STATES
type Action = keyof typeof ACTIONS
interface Handoff { id: number; source_no: string; source_type: SourceType; status: Status; can_decide: boolean; can_cancel: boolean; can_assign_inspection?: boolean }
interface Department { id: number; name: string; enabled: boolean; orgKind: string }
interface SourceOption { id: number; number: string }
interface Assignee { id: number; username: string; real_name: string }

const auth = useAuthStore()
const endpoint = '/bureau/department-handoffs'
const status = ref<Status>()
const focusId = ref<number>()
const readIdentity = () => JSON.stringify([auth.token, auth.user?.id, auth.user?.accountCategory, auth.user?.roles])
const pagination = reactive(historyPageState<Handoff>())
const paging = createHistoryPagination(pagination, async (params, { signal }) => {
  if (focusId.value === 0) return { rows: [], total: 0, page: 1, size: 20 }
  const identity=readIdentity(),focused=focusId.value
  const response=await client.get(endpoint, { params: { ...params, ...(focused === undefined ? { status: status.value || undefined } : { focusId: focused }) }, signal })
  if(identity!==readIdentity() || focused!==focusId.value) throw new Error('List identity changed')
  return response.data.data
})
const requestVisible = ref(false)
const decisionVisible = ref(false)
const assignmentVisible = ref(false)
const assignmentLoading = ref(false)
const assignmentReady = ref(false)
const assignees = ref<Assignee[]>([])
const saving = ref(false)
const deptLoading = ref(false)
const sourceLoading = ref(false)
const departments = ref<Department[]>([])
const sources = ref<SourceOption[]>([])
const request = reactive({ sourceType: 'CLUE' as SourceType, sourceId: undefined as number | undefined,
  targetDeptId: undefined as number | undefined, reason: '' })
const decision = reactive({ id: 0, number: '', action: 'accept' as Action, opinion: '' })
const assignment = reactive({ id: 0, number: '', username: '', reason: '', expectedAssignedUserId: null as number | null })
let sourceSearch: AbortController | undefined
let assignmentSearch: AbortController | undefined
let disposed = false
let mounted = false
function setTaskFocus(value: number | undefined) {
  if (value === focusId.value) return
  focusId.value = value
  decisionVisible.value = false
  assignmentVisible.value = false
  if (value === undefined) pagination.size = 20
  if (mounted) void paging.firstPage()
}

function changeSourceType() {
  sourceSearch?.abort()
  sourceLoading.value = false
  request.sourceId = undefined
  sources.value = []
}

async function searchSources(query: string) {
  sourceSearch?.abort()
  const q = query.trim()
  if (!q || !requestVisible.value) {
    sourceLoading.value = false
    return
  }
  const current = sourceSearch = new AbortController()
  const source = SOURCES[request.sourceType]
  const token = auth.token
  sourceLoading.value = true
  try {
    const { data } = await client.get('/bureau/' + source.path, { params: { q, page: 1, size: 20 }, signal: current.signal })
    if (disposed || current.signal.aborted || !requestVisible.value || token !== auth.token) return
    sources.value = data.data.rows.map((row: Record<string, unknown>) => ({ id: row.id, number: row[source.column] }))
  } catch {
    if (sourceSearch === current && !current.signal.aborted) sources.value = []
  } finally {
    if (sourceSearch === current) sourceLoading.value = false
  }
}

async function openRequest() {
  if (saving.value) return
  Object.assign(request, { sourceType: 'CLUE', sourceId: undefined, targetDeptId: undefined, reason: '' })
  changeSourceType()
  requestVisible.value = true
  const token = auth.token
  deptLoading.value = true
  departments.value = []
  try {
    const { data } = await client.get<{ data: Department[] }>('/system/depts')
    if (disposed || token !== auth.token || !requestVisible.value) return
    departments.value = data.data.filter(dept => dept.enabled && ['BUREAU', 'DISTRICT'].includes(dept.orgKind))
  } catch {
    // The API client displays the request failure.
  } finally {
    deptLoading.value = false
  }
}

async function submitRequest() {
  if (saving.value) return
  const body = { ...request, reason: request.reason.trim() }
  if (!body.sourceId || !body.targetDeptId || !body.reason) {
    ElMessage.warning('请选择来源与接收机构，并填写交接原因')
    return
  }
  await submit(endpoint, body, requestVisible, '交接已提交', paging.firstPage)
}

function openDecision(row: Handoff, action: Action) {
  if (saving.value) return
  Object.assign(decision, { id: row.id, number: row.source_no, action, opinion: '' })
  decisionVisible.value = true
}

async function submitDecision() {
  if (saving.value) return
  const { action, id } = decision
  const opinion = decision.opinion.trim()
  if (!opinion) {
    ElMessage.warning('请填写处理意见')
    return
  }
  const path = action === 'cancel' ? 'cancel' : 'decision'
  const body = action === 'cancel' ? { opinion } : { opinion, accept: action === 'accept' }
  await submit(endpoint + '/' + id + '/' + path, body, decisionVisible, '交接处理已保存', paging.load)
}

async function openAssignment(row: Handoff) {
  if (saving.value || !row.can_assign_inspection) return
  assignmentSearch?.abort()
  const current = assignmentSearch = new AbortController()
  const identity = readIdentity()
  Object.assign(assignment, { id: row.id, number: row.source_no, username: '', reason: '', expectedAssignedUserId: null })
  assignees.value = []
  assignmentReady.value = false
  assignmentLoading.value = true
  assignmentVisible.value = true
  try {
    const { data } = await client.get<{ data: { candidates: Assignee[]; assignedUserId: number | null } }>(
      `${endpoint}/${row.id}/inspection-assignees`, { signal: current.signal })
    if (disposed || current.signal.aborted || !assignmentVisible.value || identity !== readIdentity()) return
    assignees.value = data.data.candidates
    assignment.expectedAssignedUserId = data.data.assignedUserId
    assignment.username = assignees.value.find(person => person.id === assignment.expectedAssignedUserId)?.username ?? ''
    assignmentReady.value = true
  } catch {
    // An unsuccessful lookup leaves the dialog read-only.
  } finally {
    if (assignmentSearch === current) assignmentLoading.value = false
  }
}

async function submitAssignment() {
  if (saving.value || !assignmentVisible.value || !assignmentReady.value) return
  const { id, username, expectedAssignedUserId, reason } = assignment
  if (!assignees.value.some(person => person.username === username) || !reason.trim()) return
  await submit(`${endpoint}/${id}/inspection-assignment`, { username, expectedAssignedUserId, reason: reason.trim() },
    assignmentVisible, '检查承办人已指派', paging.load)
}

async function submit(path: string, body: object, visible: typeof requestVisible, message: string,
  refresh: () => Promise<boolean>) {
  const identity = readIdentity()
  saving.value = true
  try {
    await client.post(path, body)
    if (disposed || identity !== readIdentity() || !visible.value) return
    visible.value = false
    ElMessage.success(message)
    await refresh()
  } catch {
    // The API client displays the request failure.
  } finally {
    saving.value = false
  }
}

watch(requestVisible, visible => { if (!visible) sourceSearch?.abort() })
watch(assignmentVisible, visible => { if (!visible) assignmentSearch?.abort() })
watch(readIdentity, () => {
  pagination.rows=[]; pagination.total=0; requestVisible.value=false; decisionVisible.value=false; departments.value=[]; sources.value=[]; sourceSearch?.abort()
  assignmentVisible.value=false; assignmentReady.value=false; assignees.value=[]; assignmentSearch?.abort()
}, { flush: 'sync' })
onMounted(() => { mounted = true; void paging.load() })
onUnmounted(() => {
  disposed = true
  sourceSearch?.abort()
  assignmentSearch?.abort()
  paging.dispose()
})
</script>

<style scoped>
.handoff-pagination { margin-top: 12px; justify-content: flex-end; }
</style>
