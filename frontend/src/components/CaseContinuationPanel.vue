<template>
  <el-card v-if="permitted" class="continuation-panel" shadow="never">
    <template #header><div class="heading"><strong>执行续办与救济结果</strong><el-button :disabled="loading || saving" @click="load">刷新</el-button></div></template>
    <el-alert title="后续事项独立留痕，经另一名法制或负责人复核后生效。原案件、决定和已归档案卷保留。实际结论、金额与日期须依据材料人工确认。" type="info" :closable="false" />
    <div class="heading filters"><span>业务截至日</span><el-date-picker v-model="cutoff" type="date" value-format="YYYY-MM-DD" :clearable="false" :disabled="saving" @change="load" /></div>
    <el-alert v-if="failed" title="后续事项读取失败，请刷新重试" type="warning" :closable="false" />
    <template v-if="state">
      <el-empty v-if="!state.eligible" description="作出处理决定后可办理后续事项" />
      <template v-else>
        <p>原状态：{{ state.originalCaseStatus }}；原案卷：{{ state.originalArchiveNo || '尚未归档' }}。有效决定：{{ decisionLabel(state.effectiveDecision.decision_type) }} {{ state.effectiveDecision.decision_no || '' }}，生效日 {{ state.effectiveDecision.effective_at || '—' }}。</p>
        <p>当前有效认定事实：{{ state.effectiveFacts?.confirmed_violation_facts || '—' }}；认定金额 {{ state.effectiveFacts?.confirmed_violation_amount ?? '—' }} 元。</p>
        <el-alert v-if="state.effectiveDecision.version_id" title="当前已有独立决定版本，原决定不能继续登记公开。请人工核对既有公示是否需要撤回，并按实际结果办理新版本公示；平台不自动撤回或发布。" type="warning" :closable="false" />
        <el-table :data="balanceRows" size="small"><el-table-column prop="name" label="款项" /><el-table-column prop="obligation" label="有效／人工核定金额" :formatter="(_r: any, _c: any, value: any) => value == null ? '未登记核定额' : value" /><el-table-column prop="paid" label="已登记净额" /><el-table-column prop="remaining" label="余额（负数待核实）" /></el-table>
        <p>加处罚款显示已批准登记的人工核定总额；没有核定记录时留空，罚款本金只作上限。结果变更后的实际义务和退付仍须按材料复核。</p>
        <div class="actions"><el-button v-for="item in actions" :key="item.value" :disabled="saving || loading || !(item.value === 'REGISTER_REMEDY' ? state.canRegisterRemedy : state.canRequest)" @click="open(item.value)">{{ item.label }}</el-button></div>
        <p v-if="!state.canRequest">有待复核申请时，须先处理或撤回后再提交下一项。历史截至日仅用于核对，申请仍按当前有效资料复核。</p>
        <h4>执行记录（含独立续记、冲正及实际退款）</h4>
        <el-table :data="state.executions" size="small"><el-table-column prop="source" label="来源" :formatter="(_r: any, _c: any, value: string) => value === 'ORIGINAL' ? '原执行' : '独立续记'" /><el-table-column prop="id" label="记录号" /><el-table-column prop="kind" label="款项" :formatter="(_r: any, _c: any, value: string) => names[value] || value" /><el-table-column prop="amount" label="金额" /><el-table-column prop="method" label="办理类型" :formatter="(_r: any, _c: any, value: string) => ({ REFUND: '实际退款', REVERSAL: '差错冲正', BANK: '银行缴付', ONSITE: '当场收缴', INSTALLMENT: '分期缴纳' } as Record<string, string>)[value] || value" /><el-table-column prop="paid_at" label="实际到账／冲正／退付日" /><el-table-column prop="receipt_no" label="凭证号" /></el-table>
        <h4>法院申请轮次</h4>
        <el-table :data="state.courtRounds" size="small"><el-table-column prop="round_no" label="轮次" /><el-table-column prop="applied_at" label="申请日期" /><el-table-column prop="application_reference" label="申请文号" /><el-table-column prop="status" label="状态" :formatter="(_r: any, _c: any, value: string) => statusLabel(value)" /><el-table-column prop="received_at" label="回执日期" /><el-table-column prop="receipt_no" label="回执文号" /></el-table>
        <h4>救济与更正受理</h4>
        <el-table :data="state.remedies" size="small"><el-table-column prop="id" label="受理记录号" /><el-table-column prop="remedy_type" label="类型" :formatter="(_r: any, _c: any, value: string) => remedyLabel(value)" /><el-table-column prop="accepted_at" label="受理日期" /><el-table-column prop="reference" label="受理文号" /><el-table-column prop="status" label="状态" :formatter="(_r: any, _c: any, value: string) => statusLabel(value)" /></el-table>
        <h4>申请与复核记录</h4>
        <el-table :data="state.requests" size="small"><el-table-column type="expand"><template #default="{ row }"><dl><dt>理由</dt><dd>{{ row.reason }}</dd><dt>材料出处</dt><dd>{{ row.sourceReference }}</dd><dt>申请资料</dt><dd><pre>{{ payloadText(row.payload) }}</pre></dd><dt>复核</dt><dd>{{ row.reviewedBy || '—' }} {{ row.reviewedAt || '' }} {{ row.opinion || '' }}</dd></dl></template></el-table-column><el-table-column prop="type" label="事项" :formatter="(_r: any, _c: any, value: string) => actionLabel(value)" /><el-table-column prop="status" label="状态" :formatter="(_r: any, _c: any, value: string) => statusLabel(value)" /><el-table-column prop="requestedBy" label="申请账号" /><el-table-column prop="requestedAt" label="申请时间" /><el-table-column label="操作" min-width="200"><template #default="{ row }"><el-button v-if="row.canReview" link type="primary" :disabled="saving || loading" @click="openReview(row, 'approve')">复核通过</el-button><el-button v-if="row.canReview" link type="warning" :disabled="saving || loading" @click="openReview(row, 'reject')">驳回</el-button><el-button v-if="row.canCancel" link :disabled="saving || loading" @click="openReview(row, 'cancel')">撤回</el-button></template></el-table-column></el-table>
      </template>
    </template>
    <el-dialog v-model="formVisible" :title="actionLabel(mode)" width="min(760px, 95vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" :before-close="closeForm">
      <el-alert v-if="mode === 'REVERSAL'" title="差错冲正完整抵销一笔错误登记，原记录保留。若已发生实际退付，请使用实际退款入口。已有退款的来源不能再整笔冲正。" type="warning" :closable="false" />
      <el-alert v-if="mode === 'REFUND'" title="登记已实际发生的退款，可以分次部分退付。须关联原款并提供真实退付凭证；平台不自动发起退款。" type="warning" :closable="false" />
      <el-alert v-if="mode === 'EXECUTION' && draft.kind === 'LATE_FEE'" title="按正式核定材料填写加处罚款总额及文号／按日核算依据，复核后续记实际已缴金额。罚款本金仅作为上限，不作为核定金额。" type="warning" :closable="false" />
      <el-alert v-if="mode === 'REMEDY_RESULT'" title="按结果文书手工确认维持、文字更正、变更或撤销。非维持结果形成独立有效决定版本；不会自动发送新决定、退款或修改原卷。" type="warning" :closable="false" />
      <el-form label-width="150px" :disabled="saving">
        <el-form-item v-if="mode==='FACT_CORRECTION' || (mode==='REMEDY_RESULT' && draft.outcome==='CHANGE')" label="本次有效事实">
          <el-checkbox v-model="withInstitution">更正完整机构资料</el-checkbox><el-checkbox v-model="withFinding">更正完整认定事实</el-checkbox>
        </el-form-item>
        <el-form-item v-for="field in formFields" :key="field.key" :label="field.label" :required="!field.optional">
          <el-select v-if="field.options" v-model="draft[field.key]" style="width:100%"><el-option v-for="option in field.options" :key="option.value" :label="option.label" :value="option.value" /></el-select>
          <el-date-picker v-else-if="field.kind === 'date'" v-model="draft[field.key]" type="date" value-format="YYYY-MM-DD" style="width:100%" />
          <el-input v-else v-model="draft[field.key]" :type="field.kind === 'long' ? 'textarea' : 'text'" :rows="3" :maxlength="field.max || 128" show-word-limit />
        </el-form-item>
        <el-form-item label="申请／登记理由" required><el-input v-model="reason" type="textarea" maxlength="1000" show-word-limit /></el-form-item>
        <el-form-item label="材料出处或凭证位置" required><el-input v-model="sourceReference" type="textarea" maxlength="1000" show-word-limit /></el-form-item>
        <el-form-item v-if="mode!=='REGISTER_REMEDY'" label="已批准材料包"><el-select v-model="materialBundleId" clearable placeholder="可关联同案已批准的原始材料包"><el-option v-for="bundle in approvedBundles" :key="bundle.id" :label="`${bundle.title} #${bundle.id}`" :value="bundle.id" /></el-select></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="formVisible = false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">{{ mode === 'REGISTER_REMEDY' ? '登记受理事实' : '提交另一人复核' }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="reviewVisible" title="后续事项独立复核" width="min(760px, 95vw)" :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving" :before-close="closeReview">
      <p>请核对材料出处、实际办理结果、有效日期、款项义务和执行净额。{{ selected?.sourceReference }}</p><pre>{{ payloadText(selected?.payload || {}) }}</pre>
      <el-input v-model="opinion" type="textarea" :rows="4" maxlength="2000" show-word-limit :disabled="saving" placeholder="填写复核意见或撤回原因" />
      <template #footer><el-button :disabled="saving" @click="reviewVisible = false">取消</el-button><el-button :type="reviewAction === 'approve' ? 'primary' : 'warning'" :loading="saving" @click="submitReview">{{ reviewAction === 'approve' ? '确认复核通过' : reviewAction === 'reject' ? '确认驳回' : '确认撤回' }}</el-button></template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'

interface Entry { id: number; type: string; payload: Record<string, unknown>; status: string; reason: string; sourceReference: string; requestedBy: string; requestedAt: string; reviewedBy?: string; reviewedAt?: string; opinion?: string; canReview: boolean; canCancel: boolean }
interface State { eligible: boolean; canRequest: boolean; canRegisterRemedy: boolean; originalCaseStatus: string; originalArchiveNo?: string; effectiveDecision: Record<string, any>; effectiveFacts?: Record<string,any>; balances: Record<string, { obligation: number; paid: number; remaining: number }>; executions: any[]; courtRounds: any[]; remedies: any[]; requests: Entry[] }
interface Field { key: string; label: string; kind?: string; max?: number; optional?: boolean; options?: { value: string; label: string }[] }
const props = defineProps<{ caseId: number }>()
const auth = useAuthStore()
const identity = computed(() => JSON.stringify([auth.token, auth.user?.id, auth.user?.roles, auth.user?.accountCategory]))
const permitted = computed(() => !!auth.token && !!auth.user && auth.user.roles.some(r => ['HANDLER', 'LEGAL', 'LEADER', 'ADMIN'].includes(r)) && auth.user.accountCategory !== 'AGENCY')
const state = ref<State>(), loading = ref(false), saving = ref(false), failed = ref(false)
const cutoff = ref(new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date()))
const formVisible = ref(false), reviewVisible = ref(false), mode = ref('EXECUTION'), reviewAction = ref<'approve' | 'reject' | 'cancel'>('approve')
const draft = reactive<Record<string, string>>({}), reason = ref(''), sourceReference = ref(''), opinion = ref(''), requestKey = ref('')
const withInstitution=ref(false),withFinding=ref(false)
const approvedBundles=ref<{id:number;title:string}[]>([]),materialBundleId=ref<number|null>(null)
const selected = ref<Entry>()
const actions = [{ value: 'EXECUTION', label: '申请执行续记' }, { value: 'REVERSAL', label: '申请整笔差错冲正' }, { value: 'REFUND', label: '登记实际退款' }, { value: 'COURT_APPLY', label: '登记法院后续申请' }, { value: 'COURT_RECEIPT', label: '登记本轮法院回执' }, { value: 'REGISTER_REMEDY', label: '登记救济／更正受理' }, { value: 'REMEDY_RESULT', label: '提交结果材料' }, {value:'FACT_CORRECTION',label:'申请独立事实更正'}]
const names: Record<string, string> = { FINE: '罚款', RECOUP: '退回基金', CONFISCATE: '没收', LATE_FEE: '加处罚款' }
const balanceRows = computed(() => Object.entries(state.value?.balances || {}).map(([key, value]) => ({ name: names[key] || key, ...value })))
const options = (values: Record<string, string>) => Object.entries(values).map(([value, label]) => ({ value, label }))
const institutionFields:Field[]=[{key:'institutionCode',label:'有效机构编码',max:64},{key:'institutionOwnership',label:'有效机构性质',options:options({PUBLIC:'公立',SOCIAL:'社会办'})},{key:'institutionGrade',label:'有效机构等级',options:options({TERTIARY:'三级',SECONDARY:'二级',PRIMARY:'一级',UNGRADED:'无等级'})},{key:'institutionKind',label:'有效机构类型',options:options({MEDICAL:'医疗机构',SINGLE_PHARMACY:'单体药店',CHAIN_PHARMACY:'连锁药店'})}]
const findingFields:Field[]=[{key:'confirmedViolationStart',label:'违法开始日期',kind:'date'},{key:'confirmedViolationEnd',label:'违法结束日期',kind:'date'},{key:'confirmedViolationFacts',label:'有效认定事实',kind:'long',max:12000},{key:'confirmedViolationAmount',label:'有效认定金额',kind:'money'}]
function selectedFactFields(){return [...(withInstitution.value?institutionFields:[]),...(withFinding.value?findingFields:[])]}
const formFields = computed<Field[]>(() => {
  if (mode.value === 'EXECUTION') {
    const fields: Field[] = [{ key: 'kind', label: '款项', options: options(names) }, { key: 'amount', label: '实际到账金额', kind: 'money', max: 20 }, { key: 'paidAt', label: '实际到账日期', kind: 'date' }, { key: 'receiptNo', label: '凭证号' }, { key: 'method', label: '缴款方式', options: options({ BANK: '银行缴付', ONSITE: '当场收缴', INSTALLMENT: '分期缴纳' }) }]
    if (draft.kind === 'LATE_FEE') fields.push({ key: 'assessedLateFeeAmount', label: '人工核定总额', kind: 'money' }, { key: 'assessmentReference', label: '核定文号及按日依据', kind: 'long', max: 2000 })
    return fields
  }
  if (mode.value === 'REVERSAL' || mode.value === 'REFUND') {
    const fields: Field[] = [{ key: 'executionSource', label: '关联原执行记录', options: (state.value?.executions || []).filter(e => Number(e.amount) > 0).map(e => ({ value: `${e.source}:${e.id}`, label: `${e.source === 'ORIGINAL' ? '原执行' : '续记'} #${e.id} ${names[e.kind] || e.kind} ${e.amount} 元 ${e.receipt_no || ''}` })) }, { key: 'paidAt', label: mode.value === 'REFUND' ? '实际退付日期' : '差错冲正生效日', kind: 'date' }, { key: 'receiptNo', label: mode.value === 'REFUND' ? '实际退款凭证号' : '差错冲正凭证号' }]
    if (mode.value === 'REFUND') fields.push({ key: 'amount', label: '本次实际退款金额', kind: 'money' })
    return fields
  }
  if (mode.value === 'COURT_APPLY') return [{ key: 'appliedAt', label: '重新申请日期', kind: 'date' }, { key: 'applicationReference', label: '申请文号' }, { key: 'eligibilityBasis', label: '补正及再次申请依据', kind: 'long', max: 2000 }]
  if (mode.value === 'COURT_RECEIPT') return [{ key: 'roundId', label: '法院申请轮次', options: (state.value?.courtRounds || []).filter(r => r.id && r.status === 'APPLIED').map(r => ({ value: String(r.id), label: `第${r.round_no}轮 ${r.application_reference}` })) }, { key: 'status', label: '实际回执结果', options: options({ ACCEPTED: '正式受理', RETURNED: '退回补正', REJECTED: '不予受理' }) }, { key: 'receivedAt', label: '回执日期', kind: 'date' }, { key: 'receiptNo', label: '回执文号' }]
  if (mode.value === 'REGISTER_REMEDY') return [{ key: 'remedyType', label: '类型', options: options({ RECONSIDERATION: '行政复议', LITIGATION: '行政诉讼', CORRECTION: '决定更正' }) }, { key: 'acceptedAt', label: '受理日期', kind: 'date' }, { key: 'reference', label: '受理文号' }]
  if(mode.value==='FACT_CORRECTION')return [{key:'effectiveAt',label:'事实更正生效日',kind:'date'},...selectedFactFields()]
  const fields: Field[] = [{ key: 'remedyId', label: '受理记录', options: (state.value?.remedies || []).filter(m => m.status === 'OPEN').map(m => ({ value: String(m.id), label: `#${m.id} ${remedyLabel(m.remedy_type)} ${m.reference}` })) }, { key: 'outcome', label: '人工确认结果', options: options({ MAINTAIN: '维持', CORRECT: '文字更正', CHANGE: '变更', REVOKE: '撤销' }) }, { key: 'effectiveAt', label: '结果生效日期', kind: 'date' }, { key: 'resultReference', label: '结果文号' }, { key: 'resultContent', label: '结果材料及确认结论', kind: 'long', max: 12000 }]
  if (draft.outcome !== 'MAINTAIN') fields.push({ key: 'decisionType', label: '有效决定类型', options: options({ PUNISH: '处罚', NO_PUNISH: '不予处罚', NOT_ESTABLISHED: '违法事实不成立', TRANSFER_ADMIN: '移送行政机关', TRANSFER_JUDICIAL: '移送司法机关', REVOKED: '撤销原决定' }) }, { key: 'decisionNo', label: '结果决定文号' }, { key: 'fineAmount', label: '有效罚款金额', kind: 'money' }, { key: 'recoupAmount', label: '有效退回基金金额', kind: 'money' }, { key: 'confiscateAmount', label: '有效没收金额', kind: 'money' }, { key: 'penaltyBasis', label: '结果决定依据', kind: 'long', max: 4000 }, { key: 'content', label: '有效决定正文', kind: 'long', max: 16000 }, { key: 'otherMeasures', label: '其他措施', optional: true, max: 512 })
  if(draft.outcome==='CHANGE')fields.push(...selectedFactFields())
  return fields
})
let generation = 0, disposed = false
function capture() { return { identity: identity.value, caseId: props.caseId, cutoff: cutoff.value, stamp: sessionExpiry.capture(auth.token) } }
function current(c: ReturnType<typeof capture>) { return !disposed && c.identity === identity.value && c.caseId === props.caseId && c.cutoff === cutoff.value && sessionExpiry.isCurrent(c.stamp, auth.token) && !sessionExpiry.isExpired(c.stamp, auth.token) }
function clear() { generation++; state.value = undefined; approvedBundles.value=[];materialBundleId.value=null;loading.value = false; failed.value = false; formVisible.value = false; reviewVisible.value = false; selected.value = undefined; reason.value = ''; sourceReference.value = ''; opinion.value = ''; requestKey.value = ''; for (const key of Object.keys(draft)) delete draft[key] }
async function load() {
  if (!permitted.value || disposed || !props.caseId) return
  const context = capture(), run = ++generation; loading.value = true; failed.value = false
  try { const [response,bundles] = await Promise.all([client.get(`/bureau/cases/${context.caseId}/continuations`, { params: { cutoff: context.cutoff } }),client.get(`/bureau/cases/${context.caseId}/supplement-bundles`)]); if (run === generation && current(context)) {state.value = response.data.data;approvedBundles.value=(bundles.data.data.bundles||[]).filter((b:any)=>b.status==='APPROVED')} }
  catch { if (run === generation && current(context)) { state.value = undefined; failed.value = true } }
  finally { if (run === generation) { loading.value = false; if (!current(context)) clear() } }
}
function actionLabel(value: string) { return actions.find(a => a.value === value)?.label || value }
function decisionLabel(value: string) { return ({ PUNISH: '处罚', NO_PUNISH: '不予处罚', NOT_ESTABLISHED: '不成立', TRANSFER_ADMIN: '行政移送', TRANSFER_JUDICIAL: '司法移送', REVOKED: '已撤销' } as Record<string, string>)[value] || '尚无有效决定' }
function remedyLabel(value: string) { return ({ RECONSIDERATION: '行政复议', LITIGATION: '行政诉讼', CORRECTION: '决定更正' } as Record<string, string>)[value] || value }
function statusLabel(value: string) { return ({ PENDING: '待复核', APPROVED: '已通过', REJECTED: '已驳回', CANCELLED: '已撤回', APPLIED: '已申请待回执', ACCEPTED: '正式受理', RETURNED: '退回补正', OPEN: '待确认结果', COMPLETED: '结果已复核' } as Record<string, string>)[value] || value }
function payloadText(value: Record<string, unknown>) { const labels: Record<string, string> = { kind: '款项', amount: '金额', paidAt: '到账／冲正日', receiptNo: '凭证号', method: '方式', source: '来源', sourceId: '原记录号', appliedAt: '申请日', applicationReference: '申请文号', eligibilityBasis: '补正依据', roundId: '轮次记录号', status: '回执结果', receivedAt: '回执日', remedyId: '受理记录号', outcome: '确认结果', effectiveAt: '生效日', resultReference: '结果文号', resultContent: '结果材料', decisionType: '决定类型', decisionNo: '决定文号', fineAmount: '罚款', recoupAmount: '退回基金', confiscateAmount: '没收', penaltyBasis: '决定依据', content: '有效决定正文', otherMeasures: '其他措施', institutionCode:'机构编码',institutionOwnership:'机构性质',institutionGrade:'机构等级',institutionKind:'机构类型',confirmedViolationStart:'违法开始日期',confirmedViolationEnd:'违法结束日期',confirmedViolationFacts:'认定违法事实',confirmedViolationAmount:'认定违法金额' }; return Object.entries(value).map(([key, item]) => `${labels[key] || key}：${item ?? '—'}`).join('\n') }
function open(value: string) { if (!state.value || saving.value || loading.value || !(value === 'REGISTER_REMEDY' ? state.value.canRegisterRemedy : state.value.canRequest)) return; mode.value = value; for (const key of Object.keys(draft)) delete draft[key]; withInstitution.value=false;withFinding.value=value==='FACT_CORRECTION';materialBundleId.value=null;const f=state.value.effectiveFacts||{};for(const field of [...institutionFields,...findingFields]){const snake=field.key.replace(/[A-Z]/g,c=>'_'+c.toLowerCase());draft[field.key]=f[snake]==null?'':String(f[snake])}reason.value = ''; sourceReference.value = ''; requestKey.value = crypto.randomUUID(); formVisible.value = true }
async function submit() {
  if (!formVisible.value || !state.value || saving.value || loading.value || !(mode.value === 'REGISTER_REMEDY' ? state.value.canRegisterRemedy : state.value.canRequest)) return
  const payload: Record<string, string | number | null> = {}
  for (const field of formFields.value) { const value = draft[field.key]?.trim(); if (!value && !field.optional) { ElMessage.warning(`请填写${field.label}`); return } if(!value && field.optional)continue; if (field.kind === 'money') { if (!/^(0|[1-9]\d{0,11})(\.\d{1,2})?$/.test(value || '')) { ElMessage.warning('金额须为非负数，最多两位小数'); return }; payload[field.key] = Number(value) } else payload[field.key] = value || null }
  if(mode.value==='FACT_CORRECTION' && !withInstitution.value && !withFinding.value){ElMessage.warning('请选择需要更正的机构资料或认定事实');return}
  if (!reason.value.trim() || !sourceReference.value.trim()) { ElMessage.warning('请填写理由和材料出处'); return }
  if (mode.value === 'REVERSAL' || mode.value === 'REFUND') { const [source, id] = String(payload.executionSource).split(':'); delete payload.executionSource; payload.source = source; payload.sourceId = Number(id) }
  for (const key of ['roundId', 'remedyId']) if (key in payload) payload[key] = Number(payload[key])
  const context = capture(), registering = mode.value === 'REGISTER_REMEDY', base = { requestKey: requestKey.value, reason: reason.value.trim(), sourceReference: sourceReference.value.trim() }
  const body = registering ? { ...base, ...payload } : { ...base, type: mode.value, payload,materialBundleId:materialBundleId.value }; saving.value = true
  try { await client.post(`/bureau/cases/${context.caseId}/continuations/${registering ? 'remedies' : 'requests'}`, body); if (!current(context)) return; formVisible.value = false; ElMessage.success(registering ? '受理事实已登记，请按材料提交结果' : '已提交独立复核'); await load() }
  catch { /* Retain the same request key and input for a safe retry after an uncertain response. */ }
  finally { saving.value = false }
}
function openReview(row: Entry, action: 'approve' | 'reject' | 'cancel') { if (saving.value || loading.value || !(action === 'cancel' ? row.canCancel : row.canReview)) return; selected.value = row; reviewAction.value = action; opinion.value = ''; reviewVisible.value = true }
async function submitReview() { const row = selected.value; if (!row || !reviewVisible.value || saving.value || loading.value || !(reviewAction.value === 'cancel' ? row.canCancel : row.canReview)) return; if (!opinion.value.trim()) { ElMessage.warning('请填写复核意见或撤回原因'); return }; const context = capture(), cancel = reviewAction.value === 'cancel', body = cancel ? { opinion: opinion.value.trim() } : { approve: reviewAction.value === 'approve', opinion: opinion.value.trim() }; saving.value = true; try { await client.post(`/bureau/cases/${context.caseId}/continuations/requests/${row.id}/${cancel ? 'cancel' : 'review'}`, body); if (!current(context)) return; reviewVisible.value = false; ElMessage.success('处理记录已保存'); await load() } catch { /* Preserve review input. */ } finally { saving.value = false } }
function closeForm(done: () => void) { if (!saving.value) done() }
function closeReview(done: () => void) { if (!saving.value) done() }
function expire() { clear() }
watch(() => [identity.value, props.caseId], () => { clear(); void load() }, { flush: 'sync' })
onMounted(() => { window.addEventListener(SESSION_EXPIRED_EVENT, expire); void load() })
onBeforeUnmount(() => { disposed = true; clear(); window.removeEventListener(SESSION_EXPIRED_EVENT, expire) })
</script>

<style scoped>
.continuation-panel { margin-top: 16px; }.heading { display:flex; align-items:center; gap:12px; justify-content:space-between; }.filters { margin:14px 0; justify-content:flex-start; }.actions { display:flex; gap:8px; flex-wrap:wrap; margin:16px 0; }.actions .el-button { margin-left:0; }pre { white-space:pre-wrap; overflow-wrap:anywhere; }p { line-height:1.7; }dd { margin:4px 0 12px; }
</style>
