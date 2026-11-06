<template>
  <el-card>
    <div class="toolbar">
      <h3>协议处理待办</h3>
      <el-checkbox v-model="pendingOnly">仅看待办理</el-checkbox>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <p class="hint">接收移交至本部门的事项，按办理期限处理。回复或退回后，移交方可查看进度和结果。</p>
    <el-alert v-if="failed" type="warning" :closable="false" title="待办加载失败，请核对账号归类或刷新重试。" />
    <el-table v-loading="loading" :data="visibleRows" border>
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
          </div>
          <div v-else-if="row.returnedAt">{{ row.returnReason }}<div class="hint">退回 {{ row.returnedAt }}</div></div>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140">
        <template #default="{ row }">
          <el-button v-if="row.status === 'PENDING'" link type="primary" :disabled="saving" @click="perform(row, 'receive', {})">接收</el-button>
          <el-button v-if="row.status === 'ACCEPTED'" link type="primary" :disabled="saving" @click="open(row, 'reply')">回复</el-button>
          <el-button v-if="actionable(row)" link type="primary" :disabled="saving" @click="open(row, 'return')">退回</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="dialogVisible" :title="mode === 'reply' ? '登记办理结果' : '退回经办事项'"
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
        <el-form-item label="回执文号"><el-input v-model="draft.reference" maxlength="128" /></el-form-item>
        <el-form-item label="结果说明" required>
          <el-input v-model="draft.result" type="textarea" :rows="4" maxlength="4000" show-word-limit />
        </el-form-item>
      </el-form>
      <el-form v-else label-width="100px">
        <el-form-item label="退回原因" required>
          <el-input v-model="draft.reason" type="textarea" :rows="4" maxlength="1000" :disabled="saving" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">{{ mode === 'reply' ? '提交回复' : '确认退回' }}</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { localDate } from '../../api/dates'
import { useAuthStore } from '../../stores/auth'

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
}

const ACTIONS: Record<string, string> = {
  INTERVIEW: '约谈', SUSPEND_AGREEMENT: '暂停协议', TERMINATE_AGREEMENT: '解除协议', REFUSE_PAY: '拒付', RECOVER: '追回',
}
const STATUS: Record<string, string> = { PENDING: '待接收', ACCEPTED: '已接收', RETURNED: '已退回', COMPLETED: '已回复' }
const auth = useAuthStore()
const identity = computed(() => JSON.stringify([auth.token, auth.user?.id, auth.user?.roles, auth.user?.accountCategory]))
const allowed = computed(() => auth.user?.accountCategory === 'AGENCY' && auth.user.roles.includes('AGENCY'))
const rows = ref<Handoff[]>([])
const pendingOnly = ref(true)
const visibleRows = computed(() => pendingOnly.value ? rows.value.filter(actionable) : rows.value)
const loading = ref(false)
const failed = ref(false)
const selected = ref<Handoff | null>(null)
const dialogVisible = ref(false)
const mode = ref<'reply' | 'return'>('reply')
const draft = reactive({ resultType: 'COMPLETED', result: '', reason: '', reference: '',
  amount: undefined as number | undefined, effectiveAt: '', endAt: '' })
const saving = ref(false)
const needsAmount = computed(() => draft.resultType === 'COMPLETED' && selected.value?.action === 'RECOVER')
const needsEnd = computed(() => draft.resultType === 'COMPLETED' && selected.value?.action === 'SUSPEND_AGREEMENT')
const needsEffective = computed(() => needsEnd.value ||
  (draft.resultType === 'COMPLETED' && selected.value?.action === 'TERMINATE_AGREEMENT'))
let active = true
let generation = 0

function actionable(row: Handoff) { return row.status === 'PENDING' || row.status === 'ACCEPTED' }

async function load() {
  const run = ++generation, who = identity.value
  rows.value = []
  failed.value = false
  loading.value = false
  if (!allowed.value) return
  loading.value = true
  const current = () => active && generation === run && identity.value === who
  try {
    const response = await client.get('/agency/agreement-actions')
    if (current()) rows.value = response.data.data
  } catch {
    if (current()) failed.value = true
  } finally {
    if (current()) loading.value = false
  }
}

function open(row: Handoff, action: 'reply' | 'return') {
  if (!active || !allowed.value || saving.value || !actionable(row)) return
  selected.value = row
  mode.value = action
  Object.assign(draft, { resultType: 'COMPLETED', result: '', reason: '', reference: '',
    amount: undefined, effectiveAt: '', endAt: '' })
  dialogVisible.value = true
}

async function perform(row: Handoff, action: 'receive' | 'reply' | 'return', data: Record<string, unknown>) {
  const who = identity.value
  if (!active || !allowed.value || saving.value) return
  saving.value = true
  try {
    await client.post(`/agency/agreement-actions/${row.id}/${action}`, { cycle: row.cycle, ...data })
    if (!active || who !== identity.value) return
    dialogVisible.value = false
    ElMessage.success({ receive: '已接收', reply: '已回复', return: '已退回' }[action])
    await load()
  } catch { /* Keep the draft open. The shared client reports the request error. */ }
  finally { saving.value = false }
}

function submit() {
  const row = selected.value
  if (!dialogVisible.value || !row) return
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
    effectiveAt: needsEffective.value ? draft.effectiveAt : null, endAt: needsEnd.value ? draft.endAt : null })
}

watch(identity, () => {
  dialogVisible.value = false
  selected.value = null
  void load()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { active = false })
</script>

<style scoped>
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.toolbar h3 { margin: 0 auto 0 0; }
.hint { color: #606266; }
.overdue { color: #b42318; font-weight: 600; }
</style>
