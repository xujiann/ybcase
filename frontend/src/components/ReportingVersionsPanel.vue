<template>
  <el-card v-if="allowed" class="reporting-versions">
    <h4>报表预览与冻结版本</h4>
    <p>按业务日期和生成时已批准的信息取数。生成后可逐行核对来源，另一名局机关法制或负责人确认后形成正式批次。</p>
    <el-alert v-if="focusFailed" type="warning" :closable="false" title="目标报表批次不存在或当前无权限查看，请从版本列表核对。" />
    <el-form :inline="true" :disabled="saving">
      <el-form-item label="报表">
        <el-select v-model="form.type" style="width: 200px">
          <el-option v-for="(label, type) in types" :key="type" :value="type" :label="label" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="form.type !== 'ENFORCEMENT_WORK'" label="年度">
        <el-input-number v-model="form.year" :min="1900" :max="currentYear" :precision="0" />
      </el-form-item>
      <el-form-item label="起始日"><el-date-picker v-model="form.start" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item label="结束日"><el-date-picker v-model="form.end" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item v-if="form.type !== 'ENFORCEMENT_WORK'" label="业务截至日"><el-date-picker v-model="form.cutoff" value-format="YYYY-MM-DD" /></el-form-item>
      <el-form-item><el-button type="primary" :loading="saving" @click="generate">生成待确认版本</el-button></el-form-item>
    </el-form>
    <p v-if="form.parentBatchId">更正重报前版为批次 {{ form.parentBatchId }}。
      <el-button link :disabled="saving" @click="clearParent">取消前版关联</el-button>
    </p>
    <div class="toolbar">
      <el-select v-model="filter" clearable placeholder="全部报表" :disabled="saving" style="width: 200px">
        <el-option v-for="(label, type) in types" :key="type" :value="type" :label="label" />
      </el-select>
      <el-button :loading="loading" :disabled="saving" @click="loadList()">刷新版本</el-button>
    </div>
    <el-alert v-if="failed" type="error" :closable="false" title="报表批次加载失败，请刷新重试。" />
    <el-table v-loading="loading" :data="batches" border size="small">
      <el-table-column prop="id" label="批次" width="75" />
      <el-table-column label="报表" min-width="160"><template #default="{ row }">{{ types[row.report_type as Kind] }}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{ row }">{{ statusLabel(row.status) }}</template></el-table-column>
      <el-table-column label="业务期间" min-width="215"><template #default="{ row }">{{ row.period_start }} 至 {{ row.period_end }}</template></el-table-column>
      <el-table-column prop="business_cutoff" label="业务截至日" width="112" />
      <el-table-column prop="known_at" label="生成取数时点" min-width="180" show-overflow-tooltip />
      <el-table-column prop="generated_username" label="生成者" width="110" show-overflow-tooltip />
      <el-table-column prop="row_count" label="行数" width="70" />
      <el-table-column label="操作" width="225">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="saving" @click="openBatch(row)">预览／核对</el-button>
          <el-button link :disabled="saving || downloading" @click="download(row)">原样下载</el-button>
          <el-button v-if="row.status === 'CONFIRMED'" link :disabled="saving" @click="reissue(row)">更正重报</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination :current-page="page" :page-size="20" :total="total" :disabled="loading || saving"
                   layout="total, prev, pager, next" @current-change="changePage" />

    <el-dialog v-model="previewVisible" title="冻结报表预览" width="95%" :close-on-click-modal="!saving" :show-close="!saving">
      <template v-if="selected">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="批次">{{ selected.id }} · {{ statusLabel(selected.status) }}</el-descriptions-item>
          <el-descriptions-item label="前版">{{ selected.parent_batch_id || '—' }}</el-descriptions-item>
          <el-descriptions-item label="模板版本">{{ selected.template_version }}</el-descriptions-item>
          <el-descriptions-item label="口径版本">{{ selected.rule_version }}</el-descriptions-item>
          <el-descriptions-item label="生成时点">{{ selected.known_at }}</el-descriptions-item>
          <el-descriptions-item label="生成者">{{ selected.generated_username }}</el-descriptions-item>
          <el-descriptions-item label="文件 SHA-256" :span="2"><code>{{ selected.file_sha256 }}</code></el-descriptions-item>
          <el-descriptions-item label="模板 SHA-256" :span="2"><code>{{ selected.template_sha256 }}</code></el-descriptions-item>
          <el-descriptions-item label="确认人">{{ selected.confirmed_username || '—' }}</el-descriptions-item>
          <el-descriptions-item label="确认意见">{{ selected.confirmation_note || '—' }}</el-descriptions-item>
        </el-descriptions>
        <el-tabs v-model="tab" class="preview-tabs">
          <el-tab-pane label="逐行来源预览" name="rows">
            <el-alert v-if="rowsFailed" type="error" :closable="false" title="预览行加载失败，请重试。" />
            <el-button :loading="rowsLoading" @click="loadRows()">刷新预览</el-button>
            <el-table v-loading="rowsLoading" :data="previewRows" border size="small" max-height="430">
              <el-table-column label="来源" fixed width="185">
                <template #default="{ row }"><el-button link @click="openSource(row)">{{ row.source_number || row.row_key }}</el-button>
                  <div>工作表 {{ row.sheet_index + 1 }}，第 {{ row.row_number }} 行</div>
                </template>
              </el-table-column>
              <el-table-column v-for="(column, index) in selected.columns" :key="index" :label="column" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">{{ value(row.cells[index]) }}</template>
              </el-table-column>
            </el-table>
            <el-pagination :current-page="rowPage" :page-size="30" :total="selected.row_count" :disabled="rowsLoading"
                           layout="total, prev, pager, next" @current-change="loadRows" />
          </el-tab-pane>
          <el-tab-pane v-if="selected.parent_batch_id" label="与前版差异" name="changes">
            <el-alert v-if="changesFailed" type="error" :closable="false" title="版本差异加载失败，请重试。" />
            <el-button :loading="changesLoading" @click="loadChanges()">刷新差异</el-button>
            <el-table v-loading="changesLoading" :data="changes" border size="small" max-height="430">
              <el-table-column label="来源" width="180">
                <template #default="{ row }"><el-button link @click="openSource(row)">{{ row.source_number || row.row_key }}</el-button></template>
              </el-table-column>
              <el-table-column label="变化" width="90"><template #default="{ row }">{{ changeLabel(row.change_type) }}</template></el-table-column>
              <el-table-column label="字段差异" min-width="400" show-overflow-tooltip>
                <template #default="{ row }">{{ describeChange(row) }}</template>
              </el-table-column>
            </el-table>
            <el-pagination :current-page="changePageNumber" :page-size="30" :total="changeTotal" :disabled="changesLoading"
                           layout="total, prev, pager, next" @current-change="loadChanges" />
          </el-tab-pane>
        </el-tabs>
        <el-table v-if="selected.events?.length" :data="selected.events" border size="small">
          <el-table-column prop="actor_username" label="处理人" /><el-table-column prop="resulting_status" label="终态" />
          <el-table-column prop="opinion" label="处理理由" /><el-table-column prop="created_at" label="时间" />
        </el-table>
        <el-input v-if="selected.canConfirm || selected.canWithdraw || selected.canReturn || selected.canVoid" v-model="opinion" :disabled="saving" type="textarea" maxlength="2000"
                  :rows="2" placeholder="填写逐行核对及文件确认意见" />
      </template>
      <template #footer>
        <el-button :disabled="saving" @click="previewVisible = false">关闭</el-button>
        <el-button v-if="selected" :loading="downloading" :disabled="saving" @click="download(selected)">原样下载</el-button>
        <el-button v-if="selected?.canWithdraw" :loading="saving" @click="transition('WITHDRAW')">撤回批次</el-button>
        <el-button v-if="selected?.canReturn" :loading="saving" @click="transition('RETURN')">退回批次</el-button>
        <el-button v-if="selected?.canVoid" type="danger" :loading="saving" @click="transition('VOID')">作废批次</el-button>
        <el-button v-if="selected?.canConfirm" type="primary" :loading="saving" @click="confirm">确认冻结批次</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'
import { localDate } from '../api/dates'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'

type Kind = 'PENALTIES' | 'ENFORCEMENT_WORK' | 'INTERVIEWS'
interface Batch {
  id: number; report_type: Kind; status: string; period_start: string; period_end: string; business_cutoff: string
  known_at: string; generated_username: string; row_count: number; parent_batch_id?: number; canConfirm: boolean; canWithdraw?: boolean; canReturn?: boolean; canVoid?: boolean; events?: Record<string, unknown>[]
  template_version: string; rule_version: string; file_sha256: string; template_sha256: string; filename: string
  confirmed_username?: string; confirmation_note?: string; columns: string[]; criteria: { year?: number }
}
interface PreviewRow { row_key: string; source_number: string; sheet_index: number; row_number: number; cells: unknown[]; link: string }
interface Change extends PreviewRow { change_type: string; previous_cells: unknown[] | null; current_cells: unknown[] | null }
const props = defineProps<{ initialType?: Kind; focusBatchId?: number }>()
const auth = useAuthStore(), router = useRouter()
const allowed = computed(() => auth.user?.accountCategory === 'BUREAU'
  && auth.user.roles.some(role => ['LEGAL', 'LEADER', 'ADMIN'].includes(role)))
const identity = computed(() => JSON.stringify([auth.token, auth.user?.id, auth.user?.roles, auth.user?.accountCategory]))
const types: Record<Kind, string> = { PENALTIES: '行政处罚情况', ENFORCEMENT_WORK: '执法人员办理情况', INTERVIEWS: '部门约谈台账' }
const currentYear = new Date().getFullYear()
const form = reactive({ type: props.initialType || 'PENALTIES' as Kind, year: currentYear,
  start: currentYear + '-01-01', end: localDate(), cutoff: localDate(), parentBatchId: undefined as number | undefined })
const filter = ref<Kind>()
const batches = ref<Batch[]>([]), total = ref(0), page = ref(1)
const loading = ref(false), failed = ref(false), saving = ref(false), downloading = ref(false)
const selected = ref<Batch>(), previewVisible = ref(false), tab = ref('rows'), opinion = ref('')
const previewRows = ref<PreviewRow[]>([]), rowPage = ref(1), rowsLoading = ref(false), rowsFailed = ref(false)
const changes = ref<Change[]>([]), changeTotal = ref(0), changePageNumber = ref(1)
const changesLoading = ref(false), changesFailed = ref(false)
const focusFailed = ref(false)
let disposed = false, listSequence = 0, rowSequence = 0, changeSequence = 0, selectionSequence = 0
let requestKey: string | undefined
const base = '/bureau/report-versions/batches'
function capture() { return { identity: identity.value, stamp: sessionExpiry.capture(auth.token) } }
function current(context: ReturnType<typeof capture>) {
  return !disposed && allowed.value && context.identity === identity.value
    && sessionExpiry.isCurrent(context.stamp, auth.token) && !sessionExpiry.isExpired(context.stamp, auth.token)
}
function clear() {
  listSequence++; rowSequence++; changeSequence++; selectionSequence++
  batches.value = []; total.value = 0; page.value = 1
  selected.value = undefined; previewVisible.value = false; previewRows.value = []; changes.value = []
  loading.value = false; rowsLoading.value = false; changesLoading.value = false; failed.value = false
  opinion.value = ''; requestKey = undefined
  focusFailed.value = false
}
async function loadList(next = page.value) {
  if (disposed || !allowed.value || !auth.token) return
  const run = ++listSequence, context = capture()
  loading.value = true; failed.value = false
  try {
    const { data } = await client.get(base, { params: { type: filter.value, page: next, size: 20 } })
    if (run !== listSequence || !current(context)) return
    const last = Math.max(1, Math.ceil(data.data.total / 20))
    if (next > last) return await loadList(last)
    batches.value = data.data.rows; total.value = data.data.total; page.value = next
  } catch {
    if (run === listSequence && current(context)) { batches.value = []; total.value = 0; failed.value = true }
  } finally { if (run === listSequence) loading.value = false }
}
function changePage(next: number) { if (!loading.value && next !== page.value) void loadList(next) }
function clearParent() { form.parentBatchId = undefined }
function reissue(row: Batch) {
  if (!allowed.value || saving.value || row.status !== 'CONFIRMED' || !batches.value.some(item => item.id === row.id)) return
  form.type = row.report_type; form.year = row.criteria.year || Number(row.period_start.slice(0, 4))
  form.start = row.period_start; form.end = row.period_end; form.cutoff = row.business_cutoff; form.parentBatchId = row.id
}
async function generate() {
  if (disposed || !allowed.value || saving.value) return
  const context = capture()
  requestKey ||= crypto.randomUUID()
  const body = { type: form.type, year: form.type === 'ENFORCEMENT_WORK' ? null : form.year,
    start: form.start || null, end: form.end || null, cutoff: form.type === 'ENFORCEMENT_WORK' ? form.end : form.cutoff || null,
    parentBatchId: form.parentBatchId || null, requestKey }
  saving.value = true
  try {
    const { data } = await client.post(base, body, { timeout: 120_000 })
    if (!current(context)) return
    requestKey = undefined
    ElMessage.success('待确认版本已生成')
    await loadList(1)
    if (current(context)) await openBatch(data.data, true)
  } catch { /* Keep the request key so a retry identifies the same generation. */ }
  finally { saving.value = false }
}
async function openBatch(row: Batch, generated = false, focused = false) {
  if (disposed || !allowed.value || saving.value && !generated) return
  const run = ++selectionSequence, context = capture()
  previewRows.value = []; changes.value = []; selected.value = undefined
  rowSequence++; changeSequence++; rowPage.value = 1; changePageNumber.value = 1; changeTotal.value = 0
  rowsFailed.value = false; changesFailed.value = false; opinion.value = ''; previewVisible.value = true; tab.value = 'rows'
  try {
    const { data } = await client.get(base + '/' + row.id)
    if (run !== selectionSequence || !previewVisible.value || !current(context)) return
    selected.value = data.data
    await loadRows(1)
    if (selected.value?.parent_batch_id) void loadChanges(1)
  } catch {
    if (run === selectionSequence && current(context)) {
      previewVisible.value = false
      if (focused) focusFailed.value = true
    }
  }
}
async function loadRows(next = rowPage.value) {
  const batch = selected.value
  if (!batch || !previewVisible.value || disposed) return
  const run = ++rowSequence, context = capture()
  rowsLoading.value = true; rowsFailed.value = false
  try {
    const { data } = await client.get(base + '/' + batch.id + '/rows', { params: { page: next, size: 30 } })
    if (run !== rowSequence || selected.value?.id !== batch.id || !previewVisible.value || !current(context)) return
    previewRows.value = data.data.rows; rowPage.value = next
  } catch { if (run === rowSequence && current(context)) { previewRows.value = []; rowsFailed.value = true } }
  finally { if (run === rowSequence) rowsLoading.value = false }
}
async function loadChanges(next = changePageNumber.value) {
  const batch = selected.value
  if (!batch?.parent_batch_id || !previewVisible.value || disposed) return
  const run = ++changeSequence, context = capture()
  changesLoading.value = true; changesFailed.value = false
  try {
    const { data } = await client.get(base + '/' + batch.id + '/changes', { params: { page: next, size: 30 } })
    if (run !== changeSequence || selected.value?.id !== batch.id || !previewVisible.value || !current(context)) return
    changes.value = data.data.rows; changeTotal.value = data.data.total; changePageNumber.value = next
  } catch { if (run === changeSequence && current(context)) { changes.value = []; changesFailed.value = true } }
  finally { if (run === changeSequence) changesLoading.value = false }
}
async function confirm() {
  const batch = selected.value
  if (!batch?.canConfirm || !previewVisible.value || disposed || saving.value || !allowed.value) return
  if (!opinion.value.trim()) { ElMessage.warning('请填写核对确认意见'); return }
  const context = capture(), body = { opinion: opinion.value.trim() }
  saving.value = true
  try {
    const { data } = await client.post(base + '/' + batch.id + '/confirm', body)
    if (!current(context) || selected.value?.id !== batch.id || !previewVisible.value) return
    selected.value = data.data
    ElMessage.success('冻结批次已确认')
    await loadList()
  } catch { /* Keep the confirmation opinion for correction or retry. */ }
  finally { saving.value = false }
}
function statusLabel(status: string) { return ({ GENERATED: '待确认', CONFIRMED: '已确认', WITHDRAWN: '已撤回', REJECTED: '已退回', VOIDED: '已作废' } as Record<string,string>)[status] || status }
async function transition(action: 'WITHDRAW' | 'RETURN' | 'VOID') {
  const batch = selected.value
  if (!batch || disposed || saving.value || !previewVisible.value || !allowed.value) return
  if (!(action === 'WITHDRAW' ? batch.canWithdraw : action === 'RETURN' ? batch.canReturn : batch.canVoid)) return
  if (!opinion.value.trim()) { ElMessage.warning('请填写撤回、退回或作废理由'); return }
  const context = capture(), body = { action, opinion: opinion.value.trim() }
  saving.value = true
  try {
    const { data } = await client.post(base + '/' + batch.id + '/transition', body)
    if (!current(context) || selected.value?.id !== batch.id || !previewVisible.value) return
    selected.value = data.data
    ElMessage.success('批次已处理，原文件及来源记录保留')
    await loadList()
  } catch { /* Preserve reason for a safe retry. */ }
  finally { saving.value = false }
}
async function download(batch: Batch) {
  if (disposed || !allowed.value || downloading.value
    || selected.value?.id !== batch.id && !batches.value.some(row => row.id === batch.id)) return
  const context = capture()
  downloading.value = true
  try {
    const response = await client.get<Blob>(base + '/' + batch.id + '/download', { responseType: 'blob', timeout: 120_000 })
    if (!current(context)) return
    const checksum = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', await response.data.arrayBuffer())))
      .map(byte => byte.toString(16).padStart(2, '0')).join('')
    if (!current(context)) return
    if (checksum !== batch.file_sha256) { ElMessage.error('下载文件摘要不一致，请联系管理员核对'); return }
    const link = document.createElement('a')
    link.href = URL.createObjectURL(response.data); link.download = batch.filename
    try { link.click() } finally { URL.revokeObjectURL(link.href) }
  } catch { /* The API client reports download failures. */ }
  finally { downloading.value = false }
}
function value(cell: unknown) { return cell === null || cell === undefined || cell === '' ? '—' : String(cell) }
function changeLabel(type: string) { return ({ ADDED: '新增', REMOVED: '移出', CHANGED: '更正' } as Record<string, string>)[type] || type }
function describeChange(row: Change) {
  return (selected.value?.columns || []).map((column, index) => {
    if (index === 0 || JSON.stringify(row.previous_cells?.[index]) === JSON.stringify(row.current_cells?.[index])) return ''
    return column + ' ' + value(row.previous_cells?.[index]) + ' → ' + value(row.current_cells?.[index])
  }).filter(Boolean).join('；')
}
function openSource(row: PreviewRow) {
  if (disposed || !allowed.value || !previewVisible.value
    || !previewRows.value.includes(row) && !changes.value.some(change => change === row)
    || !/^\/case\/(detail\/\d+|inspections\?focus=\d+|interviews\?focus=\d+)$/.test(row.link)) return
  void router.push(row.link)
}
watch(identity, () => { clear(); void loadList(1) }, { immediate: true, flush: 'sync' })
watch([identity, () => props.focusBatchId], () => {
  focusFailed.value = false
  if (allowed.value && Number.isSafeInteger(props.focusBatchId) && props.focusBatchId! > 0)
    void openBatch({ id: props.focusBatchId! } as Batch, false, true)
}, { immediate: true, flush: 'sync' })
watch(filter, () => { void loadList(1) })
watch(form, () => { requestKey = undefined }, { deep: true, flush: 'sync' })
watch(previewVisible, shown => { if (!shown) { selectionSequence++; rowSequence++; changeSequence++; selected.value = undefined } }, { flush: 'sync' })
onMounted(() => window.addEventListener(SESSION_EXPIRED_EVENT, clear))
onBeforeUnmount(() => { disposed = true; clear(); window.removeEventListener(SESSION_EXPIRED_EVENT, clear) })
</script>

<style scoped>
.reporting-versions { margin-bottom: 12px; }
.toolbar { display: flex; gap: 10px; margin-bottom: 12px; }
.el-pagination, .preview-tabs { margin-top: 12px; }
code { overflow-wrap: anywhere; }
</style>
