<template>
  <el-card v-if="!caseId || state?.eligible || failed" class="report-supplement">
    <div class="toolbar">
      <h4>{{ caseId ? '历史报表缺项补录' : '历史报表补录审核' }}</h4>
      <el-button :loading="loading" :disabled="saving" @click="load()">刷新</el-button>
      <el-button v-if="state?.canRequest" type="primary" :disabled="saving || loading" @click="openRequest">申请补录</el-button>
    </div>
    <p>补录依据原案卷材料提交，由另一名局机关法制或负责人审核后供报表取数。来源与审核意见记录在补录台账中。</p>
    <el-alert v-if="failed" type="error" :closable="false" title="报表补录台账加载失败，请刷新重试。" />
    <p v-if="state?.eligible && !state.canRequest && !rows.some(row => row.status === 'PENDING')">
      当前没有可申请补录的缺项，或当前岗位无申请权限。
    </p>
    <el-collapse v-if="state?.eligible">
      <el-collapse-item title="原登记与当前报表取值">
        <el-table :data="fields" border size="small">
          <el-table-column prop="label" label="报表字段" width="150" />
          <el-table-column label="原登记值" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ display(row.key, state.original[row.key]) }}</template>
          </el-table-column>
          <el-table-column label="当前报表取值" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ display(row.key, state.effective[row.key]) }}</template>
          </el-table-column>
        </el-table>
      </el-collapse-item>
    </el-collapse>
    <el-table v-loading="loading" :data="loading ? [] : rows" border size="small"
              :empty-text="failed ? '暂未获取台账' : '暂无补录申请'">
      <el-table-column type="expand">
        <template #default="{ row }">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="补录理由">{{ row.reason }}</el-descriptions-item>
            <el-descriptions-item label="材料出处">{{ row.sourceReference }}</el-descriptions-item>
            <el-descriptions-item v-for="(value, key) in row.values" :key="key" :label="fieldLabel(String(key))">
              {{ display(String(key), value) }}
            </el-descriptions-item>
            <el-descriptions-item label="审核／撤回意见">{{ row.opinion || '—' }}</el-descriptions-item>
            <el-descriptions-item label="处理人及时间">{{ row.reviewedBy || '—' }} {{ row.reviewedAt || '' }}</el-descriptions-item>
          </el-descriptions>
        </template>
      </el-table-column>
      <el-table-column v-if="!caseId" prop="caseNo" label="案号" min-width="150" show-overflow-tooltip />
      <el-table-column v-if="!caseId" prop="partyName" label="当事人" min-width="130" show-overflow-tooltip />
      <el-table-column label="状态" width="95"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
      <el-table-column prop="requestedBy" label="申请人" width="110" show-overflow-tooltip />
      <el-table-column prop="requestedAt" label="申请时间" min-width="155" show-overflow-tooltip />
      <el-table-column label="操作" width="158">
        <template #default="{ row }">
          <el-button v-if="row.canReview" link type="primary" :disabled="saving || loading" @click="openDecision(row, 'approve')">审核通过</el-button>
          <el-button v-if="row.canReview" link type="danger" :disabled="saving || loading" @click="openDecision(row, 'reject')">退回</el-button>
          <el-button v-if="row.canCancel" link :disabled="saving || loading" @click="openDecision(row, 'cancel')">撤回申请</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-if="!caseId" :current-page="page" :page-size="pageSize" :total="total" :disabled="loading || saving"
                   layout="total, prev, pager, next" @current-change="changePage" />

    <el-dialog v-model="requestVisible" title="申请历史报表补录" width="650px" style="max-width: 95vw"
               :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-width="140px" :disabled="saving">
        <el-form-item label="本次补录字段" required>
          <el-checkbox-group v-model="selectedFields">
            <el-checkbox v-for="field in availableFields" :key="field.key" :value="field.key">{{ field.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item v-for="field in selectedFormFields" :key="field.key" :label="field.label" required>
          <el-select v-if="field.key === 'institutionOwnership'" v-model="draft[field.key]" style="width: 100%">
            <el-option label="公立" value="PUBLIC" /><el-option label="社会办" value="SOCIAL" />
          </el-select>
          <el-select v-else-if="field.key === 'institutionGrade'" v-model="draft[field.key]" style="width: 100%">
            <el-option label="三级" value="TERTIARY" /><el-option label="二级" value="SECONDARY" />
            <el-option label="一级" value="PRIMARY" /><el-option label="未定级" value="UNGRADED" />
          </el-select>
          <el-date-picker v-else-if="field.kind === 'date'" v-model="draft[field.key]" value-format="YYYY-MM-DD" />
          <el-input v-else v-model="draft[field.key]" :type="field.kind === 'long' ? 'textarea' : 'text'"
                    :rows="3" :maxlength="field.max" />
        </el-form-item>
        <el-form-item label="补录理由" required><el-input v-model="reason" type="textarea" :rows="2" maxlength="512" /></el-form-item>
        <el-form-item label="材料出处" required><el-input v-model="sourceReference" type="textarea" :rows="2" maxlength="1000" placeholder="原案卷名称、文号或材料页码" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="requestVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitRequest">提交申请</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="decisionVisible" :title="decisionAction === 'cancel' ? '撤回补录申请' : '审核历史报表补录'"
               width="650px" style="max-width: 95vw" :close-on-click-modal="!saving"
               :close-on-press-escape="!saving" :show-close="!saving">
      <template v-if="selectedEntry">
        <p>{{ selectedEntry.caseNo || '' }}　{{ selectedEntry.partyName || '' }}</p>
        <p>补录理由　{{ selectedEntry.reason }}</p>
        <p>材料出处　{{ selectedEntry.sourceReference }}</p>
        <el-descriptions :column="1" border>
          <el-descriptions-item v-for="(value, key) in selectedEntry.values" :key="key" :label="fieldLabel(String(key))">
            原登记 {{ display(String(key), selectedEntry.originalSnapshot?.[key]) }}，
            原报表值 {{ display(String(key), selectedEntry.effectiveSnapshot?.[key]) }}，
            申请值 {{ display(String(key), value) }}
          </el-descriptions-item>
        </el-descriptions>
        <el-input v-model="opinion" :disabled="saving" type="textarea" :rows="3" maxlength="2000" placeholder="填写审核或撤回意见" class="opinion" />
      </template>
      <template #footer>
        <el-button :disabled="saving" @click="decisionVisible = false">取消</el-button>
        <el-button :type="decisionAction === 'approve' ? 'primary' : 'warning'" :loading="saving" @click="submitDecision">
          {{ decisionAction === 'approve' ? '确认通过' : decisionAction === 'reject' ? '退回申请' : '确认撤回' }}
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../../api/sessionExpiry'
import { useAuthStore } from '../../stores/auth'

interface Field { key: string; label: string; kind?: string; max?: number }
interface Entry {
  id: number; caseId: number; caseNo?: string; partyName?: string; status: string
  reason: string; sourceReference: string; values: Record<string, string | number>
  originalSnapshot?: Record<string, string | number | null>; effectiveSnapshot?: Record<string, string | number | null>
  requestedBy: string; requestedAt: string; reviewedBy?: string; reviewedAt?: string; opinion?: string
  canReview: boolean; canCancel: boolean
}
interface CaseSupplements {
  eligible: boolean; canRequest: boolean; canReview: boolean; missingFields: string[]
  original: Record<string, string | number | null>; effective: Record<string, string | number | null>; history: Entry[]
}

const props = defineProps<{ caseId?: number }>()
const auth = useAuthStore()
const identity = computed(() => JSON.stringify([auth.token, auth.user?.id, auth.user?.accountCategory, auth.user?.roles]))
const fields: Field[] = [
  { key: 'institutionCode', label: '机构编码', max: 64 },
  { key: 'institutionOwnership', label: '机构性质' },
  { key: 'institutionGrade', label: '机构等级' },
  { key: 'partyCreditNo', label: '参保人证件号码', max: 64 },
  { key: 'confirmedViolationStart', label: '违法发生起始日', kind: 'date' },
  { key: 'confirmedViolationEnd', label: '违法发生结束日', kind: 'date' },
  { key: 'confirmedViolationFacts', label: '认定违法事实', kind: 'long', max: 12000 },
  { key: 'confirmedViolationAmount', label: '认定违法违规金额', max: 20 },
  { key: 'penaltyBasis', label: '处罚依据', kind: 'long', max: 4000 },
]
const state = ref<CaseSupplements>()
const queue = ref<Entry[]>([])
const rows = computed(() => props.caseId ? state.value?.history || [] : queue.value)
const availableFields = computed(() => fields.filter(field => state.value?.missingFields.includes(field.key)))
const selectedFields = ref<string[]>([])
const selectedFormFields = computed(() => availableFields.value.filter(field => selectedFields.value.includes(field.key)))
const loading = ref(false), failed = ref(false), saving = ref(false)
const total = ref(0), page = ref(1), pageSize = 20
const requestVisible = ref(false), decisionVisible = ref(false)
const reason = ref(''), sourceReference = ref(''), opinion = ref('')
const draft = reactive<Record<string, string>>({})
const selectedEntry = ref<Entry>()
const decisionAction = ref<'approve' | 'reject' | 'cancel'>('approve')
let readGeneration = 0
let disposed = false

function capture() {
  return { identity: identity.value, caseId: props.caseId, stamp: sessionExpiry.capture(auth.token) }
}
function current(context: ReturnType<typeof capture>) {
  return !disposed && context.identity === identity.value && context.caseId === props.caseId
    && sessionExpiry.isCurrent(context.stamp, auth.token) && !sessionExpiry.isExpired(context.stamp, auth.token)
}
function clear() {
  readGeneration++
  state.value = undefined; queue.value = []; total.value = 0; page.value = 1
  loading.value = false; failed.value = false
  requestVisible.value = false; decisionVisible.value = false; selectedEntry.value = undefined
  selectedFields.value = []; reason.value = ''; sourceReference.value = ''; opinion.value = ''
  for (const key of Object.keys(draft)) delete draft[key]
}
function permittedRead() {
  const user = auth.user
  if (!user || !auth.token) return false
  return props.caseId ? user.roles.some(role => ['HANDLER', 'LEADER', 'LEGAL', 'ADMIN'].includes(role))
    : user.accountCategory === 'BUREAU' && user.roles.some(role => ['LEGAL', 'LEADER'].includes(role))
}
async function load(next = page.value) {
  if (disposed || !permittedRead()) return
  const context = capture(), run = ++readGeneration
  loading.value = true; failed.value = false
  try {
    const url = props.caseId ? `/bureau/cases/${props.caseId}/report-supplements` : '/bureau/report-supplements/pending'
    const { data } = await client.get(url, { params: props.caseId ? undefined : { page: next, size: pageSize } })
    if (run !== readGeneration || !current(context)) return
    if (props.caseId) state.value = data.data
    else {
      const last = Math.max(1, Math.ceil(data.data.total / pageSize))
      if (next > last) return await load(last)
      queue.value = data.data.rows; total.value = data.data.total; page.value = next
    }
  } catch {
    if (run === readGeneration && current(context)) {
      state.value = undefined; queue.value = []; total.value = 0; failed.value = true
    }
  } finally {
    if (run === readGeneration) {
      loading.value = false
      if (!current(context)) clear()
    }
  }
}
function changePage(next: number) {
  if (!saving.value && !loading.value && next !== page.value) void load(next)
}
function fieldLabel(key: string) {
  return fields.find(field => field.key === key)?.label || key
}
function display(key: string, value: unknown) {
  if (value === null || value === undefined || value === '') return '—'
  const enums: Record<string, string> = { PUBLIC: '公立', SOCIAL: '社会办', TERTIARY: '三级', SECONDARY: '二级', PRIMARY: '一级', UNGRADED: '未定级' }
  return key === 'institutionOwnership' || key === 'institutionGrade' ? enums[String(value)] || String(value) : String(value)
}
function statusLabel(status: string) {
  return ({ PENDING: '待审核', APPROVED: '已通过', REJECTED: '已退回', CANCELLED: '已撤回' } as Record<string, string>)[status] || status
}
function openRequest() {
  if (disposed || !state.value?.canRequest || saving.value || loading.value) return
  selectedFields.value = []; reason.value = ''; sourceReference.value = ''
  for (const key of Object.keys(draft)) delete draft[key]
  requestVisible.value = true
}
async function submitRequest() {
  if (disposed || !requestVisible.value || !state.value?.canRequest || !props.caseId || saving.value || loading.value) return
  const values: Record<string, string | number> = {}
  for (const field of selectedFormFields.value) {
    const text = draft[field.key]?.trim()
    if (!text) { ElMessage.warning('请填写选中的补录字段'); return }
    if (field.key === 'confirmedViolationAmount') {
      if (!/^(0|[1-9]\d{0,11})(\.\d{1,2})?$/.test(text)) { ElMessage.warning('金额须为非负数，最多两位小数'); return }
      values[field.key] = Number(text)
    } else values[field.key] = text
  }
  if ('confirmedViolationStart' in values || 'confirmedViolationEnd' in values) {
    const start = values.confirmedViolationStart ?? state.value.effective.confirmedViolationStart
    const end = values.confirmedViolationEnd ?? state.value.effective.confirmedViolationEnd
    if (!start || !end || String(start) > String(end)) {
      ElMessage.warning('请补齐有效的违法发生起止日期')
      return
    }
  }
  if (!Object.keys(values).length || !reason.value.trim() || !sourceReference.value.trim()) {
    ElMessage.warning('请选择补录字段，并填写理由和材料出处')
    return
  }
  const context = capture(), body = { values, reason: reason.value.trim(), sourceReference: sourceReference.value.trim() }
  saving.value = true
  try {
    await client.post(`/bureau/cases/${context.caseId}/report-supplements`, body)
    if (!current(context)) return
    requestVisible.value = false
    ElMessage.success('补录申请已提交')
    await load()
  } catch {
    // The API client reports the failure and the draft remains available.
  } finally { saving.value = false }
}
function openDecision(row: Entry, action: 'approve' | 'reject' | 'cancel') {
  if (disposed || saving.value || loading.value || (action === 'cancel' ? !row.canCancel : !row.canReview)) return
  selectedEntry.value = row; decisionAction.value = action; opinion.value = ''; decisionVisible.value = true
}
async function submitDecision() {
  const row = selectedEntry.value, action = decisionAction.value
  if (disposed || !row || !decisionVisible.value || saving.value || loading.value || (action === 'cancel' ? !row.canCancel : !row.canReview)) return
  if (!opinion.value.trim()) { ElMessage.warning('请填写处理意见'); return }
  const context = capture(), endpoint = `/bureau/cases/${props.caseId || row.caseId}/report-supplements/${row.id}/`
  const body = action === 'cancel' ? { opinion: opinion.value.trim() } : { approve: action === 'approve', opinion: opinion.value.trim() }
  saving.value = true
  try {
    await client.post(endpoint + (action === 'cancel' ? 'cancel' : 'review'), body)
    if (!current(context)) return
    decisionVisible.value = false
    ElMessage.success('补录申请已处理')
    await load()
  } catch {
    // The API client reports the failure.
  } finally { saving.value = false }
}
watch([identity, () => props.caseId], () => { clear(); void load(1) }, { immediate: true, flush: 'sync' })
onMounted(() => window.addEventListener(SESSION_EXPIRED_EVENT, clear))
onBeforeUnmount(() => { disposed = true; clear(); window.removeEventListener(SESSION_EXPIRED_EVENT, clear) })
</script>

<style scoped>
.report-supplement { margin-bottom: 12px; }
.toolbar { display: flex; align-items: center; gap: 10px; }
.toolbar h4 { margin: 0 auto 0 0; }
.el-pagination, .opinion { margin-top: 12px; }
</style>
