<template>
  <NextStepTasks domain="agency" :refresh-key="loading ? undefined : rows" />
  <el-card>
    <div class="toolbar">
      <h3>协议处理待办</h3>
      <el-checkbox v-model="pendingOnly">仅看待办理</el-checkbox>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <p class="hint">接收移交至本部门的事项，按办理期限处理。回复或退回后，移交方可查看进度和结果。</p>
    <el-alert v-if="failed" type="warning" :closable="false" title="待办加载失败，请核对账号归类或刷新重试。" />
    <el-alert v-if="focusedId && focusedCycle && !loading && !failed && !rows.some(isFocused)"
              type="info" :closable="false" title="定位事项已变更或不在当前办理范围，请刷新后核对。" />
    <el-table v-loading="loading" :data="visibleRows" :row-class-name="rowClassName" border>
      <el-table-column prop="caseNo" label="案号" min-width="158" show-overflow-tooltip />
      <el-table-column prop="institutionName" label="定点医药机构" min-width="168" show-overflow-tooltip />
      <el-table-column label="处理类型" width="110">
        <template #default="{ row }">{{ ACTIONS[row.action] || row.action }}</template>
      </el-table-column>
      <el-table-column label="进度及期限" width="172">
        <template #default="{ row }">
          <div>{{ STATUS[row.status] || row.status }}</div>
          <div class="hint" :class="{ overdue: actionable(row) && row.dueAt && row.dueAt < localDate() }">
            {{ row.dueAt ? '期限 ' + row.dueAt : '期限未登记' }}
          </div>
          <div v-if="row.receivedAt" class="hint">接收 {{ row.receivedAt }}</div>
          <el-tag v-if="row.remindedAt && actionable(row)" type="warning" size="small">已催办 {{ row.remindedAt }}</el-tag>
          <div v-if="row.autoRemindedAt && actionable(row)" class="hint">系统提醒 {{ row.autoRemindedAt }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="note" label="移交说明" min-width="160" show-overflow-tooltip />
      <el-table-column label="办理结果" min-width="170">
        <template #default="{ row }">
          <div v-if="row.repliedAt">
            {{ row.result }}<div class="hint">回复 {{ row.repliedAt }}</div>
            <div v-if="row.resultType" class="hint">{{ row.resultType === 'COMPLETED' ? '已落实' : '未落实' }}</div>
            <div v-if="row.recoveredAmount != null" class="hint">实际追回 {{ row.recoveredAmount }} 元</div>
            <div v-if="row.effectiveAt" class="hint">{{ row.effectiveAt }}<span v-if="row.endAt"> 至 {{ row.endAt }}</span></div>
            <div v-if="row.reference" class="hint">回执 {{ row.reference }}</div>
            <div v-if="row.interviewId" class="hint">实际约谈已入台账</div>
          </div>
          <div v-else-if="row.returnedAt">{{ row.returnReason }}<div class="hint">退回 {{ row.returnedAt }}</div></div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="190">
        <template #default="{ row }">
          <el-button v-if="row.status === 'PENDING'" link type="primary" :disabled="saving" @click="perform(row, 'receive', {})">接收</el-button>
          <el-button v-if="row.status === 'ACCEPTED'" link type="primary" :disabled="saving" @click="open(row, 'reply')">回复</el-button>
          <el-button v-if="actionable(row)" link type="primary" :disabled="saving" @click="open(row, 'return')">退回</el-button>
          <el-button v-if="canRegisterInterview(row)" link type="primary" :disabled="saving" @click="open(row, 'interview')">登记实际约谈</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dialogVisible" :title="mode === 'reply' ? '登记办理结果' : mode === 'interview' ? '登记实际约谈' : '退回经办事项'"
               width="560px" style="max-width: 95vw" :close-on-click-modal="!saving"
               :close-on-press-escape="!saving" :show-close="!saving">
      <p v-if="selected">{{ selected.caseNo }} · {{ selected.institutionName }}</p>
      <el-form v-if="mode === 'reply'" label-width="110px" :disabled="saving">
        <el-form-item label="处理结果" required>
          <el-radio-group v-model="draft.resultType">
            <el-radio value="COMPLETED">已落实</el-radio>
            <el-radio value="NOT_APPLICABLE">未落实</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="needsAmount" label="实际追回金额" required>
          <el-input-number v-model="draft.amount" :min="0.01" :max="999999999999.99" :precision="2" /> 元
        </el-form-item>
        <el-form-item v-if="needsEffective" label="生效日期" required>
          <el-date-picker v-model="draft.effectiveAt" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item v-if="needsEnd" label="暂停截止日期" required>
          <el-date-picker v-model="draft.endAt" value-format="YYYY-MM-DD" />
        </el-form-item>
        <ActualInterviewForm v-if="needsInterview && selected" v-model="actualInterview" :sent-at="selected.sentAt" />
        <el-form-item label="回执文号"><el-input v-model="draft.reference" maxlength="128" /></el-form-item>
        <el-form-item label="结果说明" required>
          <el-input v-model="draft.result" type="textarea" :rows="4" maxlength="4000" show-word-limit />
        </el-form-item>
      </el-form>
      <el-form v-else-if="mode === 'interview' && selected" label-width="110px" :disabled="saving">
        <ActualInterviewForm v-model="actualInterview" :sent-at="selected.sentAt" />
      </el-form>
      <el-form v-else label-width="100px">
        <el-form-item label="退回原因" required>
          <el-input v-model="draft.reason" type="textarea" :rows="4" maxlength="1000" :disabled="saving" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{ mode === 'reply' ? '提交回复' : mode === 'interview' ? '登记约谈' : '确认退回' }}</el-button>
      </template>
    </el-dialog>
  </el-card>
  <InterviewChangesPanel />
</template>

<script setup lang="ts">
import InterviewChangesPanel from '../../components/InterviewChangesPanel.vue'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import NextStepTasks from '../../components/NextStepTasks.vue'
import { localDate } from '../../api/dates'
import { useAuthStore } from '../../stores/auth'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../../api/sessionExpiry'
import { actualInterviewError, actualInterviewPayload, blankActualInterview } from '../../api/actualInterview'
import ActualInterviewForm from '../../components/ActualInterviewForm.vue'

interface Handoff {
  id: number
  caseNo: string
  institutionName: string
  action: string
  sentAt: string
  note: string | null
  status: string
  cycle: number
  dueAt: string | null
  receivedAt: string | null
  returnedAt: string | null
  returnReason: string | null
  remindedAt: string | null
  repliedAt: string | null
  result: string | null
  resultType: string | null
  recoveredAmount: number | null
  effectiveAt: string | null
  endAt: string | null
  reference: string | null
  interviewId?: number | null
  autoRemindedAt?: string | null
}

const ACTIONS: Record<string, string> = {
  INTERVIEW: '约谈', SUSPEND_AGREEMENT: '暂停协议', TERMINATE_AGREEMENT: '解除协议', REFUSE_PAY: '拒付', RECOVER: '追回',
}
const STATUS: Record<string, string> = { PENDING: '待接收', ACCEPTED: '已接收', RETURNED: '已退回', COMPLETED: '已回复' }
const auth = useAuthStore()
const route = useRoute()
const focusedId = computed(() => positiveQueryId(route.query.focus))
const focusedCycle = computed(() => positiveQueryId(route.query.cycle))
const focusKey = computed(() => JSON.stringify([route.query.focus, route.query.cycle]))
const identity = computed(() => JSON.stringify([auth.token, auth.user?.id, auth.user?.roles, auth.user?.accountCategory]))
const allowed = computed(() => auth.user?.accountCategory === 'AGENCY' && auth.user.roles.includes('AGENCY'))
const rows = ref<Handoff[]>([])
const pendingOnly = ref(true)
const visibleRows = computed(() => pendingOnly.value ? rows.value.filter(actionable) : rows.value)
const loading = ref(false)
const failed = ref(false)
const selected = ref<Handoff | null>(null)
const dialogVisible = ref(false)
const mode = ref<'reply' | 'return' | 'interview'>('reply')
const actualInterview = ref(blankActualInterview(localDate()))
const draft = reactive({ resultType: 'COMPLETED', result: '', reason: '', reference: '',
  amount: undefined as number | undefined, effectiveAt: '', endAt: '' })
const saving = ref(false)
const needsInterview = computed(() => draft.resultType === 'COMPLETED' && selected.value?.action === 'INTERVIEW')
const needsAmount = computed(() => draft.resultType === 'COMPLETED' && selected.value?.action === 'RECOVER')
const needsEnd = computed(() => draft.resultType === 'COMPLETED' && selected.value?.action === 'SUSPEND_AGREEMENT')
const needsEffective = computed(() => needsEnd.value ||
  (draft.resultType === 'COMPLETED' && selected.value?.action === 'TERMINATE_AGREEMENT'))
let active = true
let generation = 0
let draftGeneration = 0

function actionable(row: Handoff) { return row.status === 'PENDING' || row.status === 'ACCEPTED' }
function canRegisterInterview(row: Handoff) {
  return row.action === 'INTERVIEW' && row.status === 'COMPLETED' && !!row.repliedAt && !row.interviewId
    && (row.resultType == null || row.resultType === 'COMPLETED')
}
function positiveQueryId(value: unknown) {
  if (typeof value !== 'string' || !/^[1-9]\d*$/.test(value)) return null
  const id = Number(value)
  return Number.isSafeInteger(id) ? id : null
}
function isFocused(row: Handoff) { return row.id === focusedId.value && row.cycle === focusedCycle.value }
function rowClassName({ row }: { row: Handoff }) { return isFocused(row) ? 'focused-agreement' : '' }
function sourceCurrent(row: Handoff, action: 'receive' | 'reply' | 'return' | 'interview') {
  const current = rows.value.find(item => item.id === row.id && item.cycle === row.cycle)
  if (!current || current.status !== row.status) return false
  if (action === 'interview') return canRegisterInterview(current)
  return action === 'receive' ? current.status === 'PENDING'
    : action === 'reply' ? current.status === 'ACCEPTED' : actionable(current)
}
function clearDialog() {
  draftGeneration++
  dialogVisible.value = false; selected.value = null
  actualInterview.value = blankActualInterview(localDate())
  Object.assign(draft, { resultType: 'COMPLETED', result: '', reason: '', reference: '',
    amount: undefined, effectiveAt: '', endAt: '' })
}
function onSessionExpired() {
  generation++; rows.value = []; loading.value = false; failed.value = false; clearDialog()
}

async function load() {
  const run = ++generation, who = identity.value, stamp = sessionExpiry.capture(auth.token)
  rows.value = []
  failed.value = false
  loading.value = false
  if (!active || !allowed.value) return
  loading.value = true
  const current = () => active && generation === run && identity.value === who
    && sessionExpiry.isCurrent(stamp, auth.token) && !sessionExpiry.isExpired(stamp, auth.token)
  try {
    const response = await client.get('/agency/agreement-actions')
    if (current()) rows.value = response.data.data
  } catch {
    if (current()) failed.value = true
  } finally {
    if (generation === run) {
      loading.value = false
      if (!current()) { rows.value = []; clearDialog() }
    }
  }
}

function open(row: Handoff, action: 'reply' | 'return' | 'interview') {
  if (!active || !allowed.value || saving.value || !sourceCurrent(row, action)) return
  clearDialog()
  selected.value = { ...row }
  mode.value = action
  dialogVisible.value = true
}

async function perform(row: Handoff, action: 'receive' | 'reply' | 'return' | 'interview', data: Record<string, unknown>) {
  const who = identity.value, stamp = sessionExpiry.capture(auth.token), focus = focusKey.value, version = draftGeneration
  if (!active || !allowed.value || saving.value) return
  if (!sourceCurrent(row, action)) { ElMessage.warning('事项状态或分派轮次已变化，请刷新后核对'); return }
  saving.value = true
  try {
    await client.post(`/agency/agreement-actions/${row.id}/${action}`, { ...data, cycle: row.cycle })
    if (!active || who !== identity.value || focus !== focusKey.value || version !== draftGeneration
      || !sessionExpiry.isCurrent(stamp, auth.token) || sessionExpiry.isExpired(stamp, auth.token)) return
    clearDialog()
    ElMessage.success({ receive: '已接收', reply: '已回复', return: '已退回', interview: '实际约谈已登记' }[action])
    await load()
  } catch { /* Keep the draft open. The shared client reports the request error. */ }
  finally { saving.value = false }
}

function submit() {
  const row = selected.value
  if (!dialogVisible.value || !row) return
  if (mode.value === 'interview' || mode.value === 'reply' && needsInterview.value) {
    const error = actualInterviewError(actualInterview.value, row.sentAt, localDate())
    if (error) { ElMessage.warning(error); return }
  }
  if (mode.value === 'interview') return perform(row, 'interview', actualInterviewPayload(actualInterview.value))
  if (mode.value === 'return') {
    if (!draft.reason.trim()) { ElMessage.warning('请填写退回原因'); return }
    return perform(row, 'return', { reason: draft.reason })
  }
  if (!draft.result.trim() || (needsAmount.value && !draft.amount)
    || (needsEffective.value && (!draft.effectiveAt || draft.effectiveAt > localDate()))
    || (needsEnd.value && (!draft.endAt || draft.endAt < draft.effectiveAt))) {
    ElMessage.warning('请填写完整的办理结果，并核对实际金额或生效日期')
    return
  }
  return perform(row, 'reply', { result: draft.result, resultType: draft.resultType, reference: draft.reference,
    recoveredAmount: needsAmount.value ? draft.amount : null,
    effectiveAt: needsEffective.value ? draft.effectiveAt : null, endAt: needsEnd.value ? draft.endAt : null,
    ...(needsInterview.value ? { interview: actualInterviewPayload(actualInterview.value) } : {}) })
}

watch(identity, () => {
  clearDialog()
  void load()
}, { immediate: true, flush: 'sync' })
watch(focusKey, () => {
  clearDialog()
  if (focusedId.value && focusedCycle.value) pendingOnly.value = false
}, { immediate: true, flush: 'sync' })
onMounted(() => window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired))
onBeforeUnmount(() => { active = false; onSessionExpired(); window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired) })
</script>

<style scoped>
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.toolbar h3 { margin: 0 auto 0 0; }
.hint { color: #606266; }
.overdue { color: #b42318; font-weight: 600; }
:deep(.focused-agreement) { --el-table-tr-bg-color: #ecf5ff; }
</style>
