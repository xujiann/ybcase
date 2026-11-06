<template>
  <el-card>
    <div class="toolbar">
      <h3>线索管理<span class="hint">（收到之日起 15 个工作日内核查并决定是否立案，可延长一次）</span></h3>
      <div>
        <el-input v-model="q" placeholder="线索编号/嫌疑人/内容" clearable style="width: 200px; margin-right: 8px"
                  @keyup.enter="reload" @clear="reload" />
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" unlink-panels
                        start-placeholder="收到起" end-placeholder="收到止" style="width: 240px; margin-right: 8px"
                        @change="reload" />
        <el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 130px; margin-right: 8px" @change="reload">
          <el-option v-for="(v, k) in CLUE_STATUS" :key="k" :label="v" :value="k" />
        </el-select>
        <el-select v-model="sourceFilter" clearable placeholder="全部来源" style="width: 150px; margin-right: 8px" @change="reload">
          <el-option v-for="(v, k) in SOURCE" :key="k" :label="v" :value="k" />
        </el-select>
        <el-button v-if="canPullMonitor" :loading="pullingMonitor" @click="onPullMonitor">拉取监控疑点</el-button>
        <el-button v-if="canOperate" @click="onImport">监控疑点导入</el-button>
        <el-button v-if="canOperate" type="primary" @click="createVisible = true">登记线索</el-button>
      </div>
    </div>
    <el-alert v-if="failed" type="error" :closable="false" title="线索列表加载失败，请重新搜索或筛选重试。" />
    <el-table :data="records" v-loading="loading" border stripe :empty-text="failed ? '加载失败' : '暂无数据'">
      <el-table-column prop="clueNo" label="线索编号" width="120" />
      <el-table-column label="来源" width="125">
        <template #default="{ row }">{{ SOURCE[row.source] || row.source }}</template>
      </el-table-column>
      <el-table-column prop="suspectName" label="违法嫌疑人" width="160" />
      <el-table-column label="类别" width="120">
        <template #default="{ row }">{{ PARTY_TYPE[row.suspectType] }}</template>
      </el-table-column>
      <el-table-column prop="content" label="线索内容" show-overflow-tooltip />
      <el-table-column prop="receivedAt" label="收到日期" width="110" />
      <el-table-column label="核查期限" width="130">
        <template #default="{ row }">
          <span :class="{ overdue: row.status === 'PENDING' && row.deadlineAt < today }">
            {{ row.deadlineAt }}{{ row.extended ? '（已延期）' : '' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 'FILED' ? 'success' : row.status === 'REJECTED' ? 'info' : 'primary'" size="small">
            {{ CLUE_STATUS[row.status] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="360">
        <template #default="{ row }">
          <el-button size="small" @click="openMaterials(row)">材料</el-button>
          <template v-if="row.status === 'PENDING'">
            <el-button v-if="canManageClue(row)" size="small" type="primary" @click="toFile(row)">立案</el-button>
            <el-button v-if="canManageClue(row)" size="small" :disabled="row.extended" @click="onExtend(row)">申请延期</el-button>
            <el-button v-if="canManageClue(row)" size="small" @click="onExclusion(row)">期限扣除</el-button>
            <el-button v-if="canManageClue(row)" size="small" type="warning" @click="onReject(row)">不予立案</el-button>
          </template>
          <span v-else class="hint">{{ row.verifyResult || '-' }}</span>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top: 12px; justify-content: flex-end"
                   layout="total, prev, pager, next" :total="total" :page-size="pageSize"
                   :current-page="page" @current-change="changePage" />

    <PrecaseAttachments v-model:visible="materialsVisible" :source="materialsSource" />
    <el-dialog v-model="createVisible" title="登记线索" width="560px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="线索来源">
          <el-select v-model="form.source" style="width: 100%">
            <el-option v-for="(v, k) in SOURCE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="违法嫌疑人">
          <el-input v-model="form.suspectName" placeholder="单位名称或个人姓名" />
        </el-form-item>
        <el-form-item label="嫌疑人类别">
          <el-select v-model="form.suspectType" style="width: 100%">
            <el-option v-for="(v, k) in PARTY_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="收到日期">
          <el-date-picker v-model="form.receivedAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="线索内容">
          <el-input v-model="form.content" type="textarea" :rows="4" placeholder="违法行为线索描述" />
        </el-form-item>
        <el-form-item label="核查人">
          <el-input v-model="form.handler" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button v-if="canOperate" type="primary" @click="onCreate" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import { caseOperationAccess } from '../../api/caseWriteAccess'
import PrecaseAttachments from '../../components/PrecaseAttachments.vue'
import { canWritePrecase, type PrecaseSource } from '../../api/precaseAttachments'
import { CLUE_STATUS, PARTY_TYPE, SOURCE } from './labels'

const auth = useAuthStore()
const canOperate = computed(() => caseOperationAccess(auth.user?.roles))
const canPullMonitor = computed(() => auth.user?.accountCategory === 'BUREAU'
  && auth.user.roles.some(role => ['LEADER', 'ADMIN'].includes(role)))
const pullingMonitor = ref(false)
function canManageClue(row: any) {
  return canWritePrecase(auth.user?.roles, auth.user?.username, { type: 'clues', id: row.id, status: row.status, owner: row.createdBy, handler: row.handler }, false)
}


const materialsVisible = ref(false)
const materialsSource = ref<PrecaseSource>({ id: 0, type: 'clues' })
function openMaterials(row: any) {
  materialsSource.value = { id: row.id, type: 'clues', status: row.status, owner: row.createdBy, handler: row.handler }
  materialsVisible.value = true
}
const router = useRouter()
const records = ref<any[]>([])
const loading = ref(false)
const failed = ref(false)
let loadGeneration = 0
let disposed = false
onUnmounted(() => { disposed = true; loadGeneration++; loading.value = false })
const statusFilter = ref('')
const sourceFilter = ref('')
const createVisible = ref(false)
const submitting = ref(false)
function todayLocal() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const today = todayLocal()
const form = reactive({ source: 'COMPLAINT', suspectName: '', suspectType: 'PROVIDER', receivedAt: today, content: '', handler: '' })
const q = ref('')
const range = ref<[string, string] | null>(null)
const page = ref(1)
const pageSize = 20
const total = ref(0)

/** 筛选条件变化回到第一页，否则新条件下当前页可能越界成空表 */
function reload() {
  page.value = 1
  load()
}

async function load() {
  if (disposed) return
  const run = ++loadGeneration
  loading.value = true
  failed.value = false
  records.value = []
  try {
    const params: any = { page: page.value, size: pageSize }
    if (statusFilter.value) params.status = statusFilter.value
    if (sourceFilter.value) params.source = sourceFilter.value
    if (q.value.trim()) params.q = q.value.trim()
    if (range.value?.[0]) params.from = range.value[0]
    if (range.value?.[1]) params.to = range.value[1]
    const resp = await client.get('/bureau/clues', { params })
    if (run !== loadGeneration) return
    // 分页接口返回 snake_case 行（JdbcTemplate），统一映射为模板与操作按钮读取的驼峰字段
    records.value = resp.data.data.rows.map((r: any) => ({
      id: r.id, clueNo: r.clue_no, source: r.source, suspectName: r.suspect_name, suspectType: r.suspect_type,
      content: r.content, receivedAt: r.received_at, deadlineAt: r.deadline_at, extended: r.extended,
      createdBy: r.created_by, handler: r.handler, status: r.status, verifyResult: r.verify_result,
    }))
    total.value = resp.data.data.total
  } catch {
    if (run === loadGeneration) { records.value = []; total.value = 0; page.value = 1; failed.value = true }
  } finally {
    if (run === loadGeneration) loading.value = false
  }
}

function changePage(value: number) {
  if (value === page.value) return
  page.value = value
  return load()
}

async function onCreate() {
  if (!canOperate.value) return
  // 防重：连点或 15s 超时后的重试会生成两条连号且无法删除的记录
  if (submitting.value) return
  submitting.value = true
  try {
    if (!form.suspectName || !form.content) {
      ElMessage.warning('请填写嫌疑人与线索内容')
      return
    }
    await client.post('/bureau/clues', form)
    ElMessage.success('线索已登记')
    createVisible.value = false
    form.suspectName = ''
    form.content = ''
    load()
  } finally {
    submitting.value = false
  }
}

async function onExclusion(row: any) {
  if (!canManageClue(row)) return
  // 鉴定/检验期间不计入核查期限（辽15条）——此前只有后端接口，UI 无入口
  const { value: days } = await ElMessageBox.prompt(
    '不计入核查期限的天数（鉴定、检验等，辽15条）', '期限扣除',
    { inputPattern: /^[1-9]\d*$/, inputErrorMessage: '请填写正整数天数' })
  const { value: reason } = await ElMessageBox.prompt('扣除事由', '期限扣除',
    { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canManageClue(row)) return
  await client.post(`/bureau/clues/${row.id}/exclusions`, { days: Number(days), reason })
  ElMessage.success('已登记扣除，核查期限相应顺延')
  load()
}

async function onExtend(row: any) {
  if (!canManageClue(row)) return
  const { value } = await ElMessageBox.prompt('特殊情况说明（经主要负责人批准，延长 15 个工作日）', '核查延期', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canManageClue(row)) return
  await client.post('/bureau/approvals', { kind: 'CLUE_EXTEND', clueId: row.id, reason: value, payload: {} })
  ElMessage.success('已提交延期审批')
  load()
}

async function onReject(row: any) {
  if (!canManageClue(row)) return
  const { value } = await ElMessageBox.prompt('核查结果（不予立案理由）', '不予立案', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canManageClue(row)) return
  await client.post(`/bureau/clues/${row.id}/reject`, { verifyResult: value })
  ElMessage.success('已办结')
  load()
}

async function onPullMonitor() {
  if (!canPullMonitor.value || pullingMonitor.value) return
  const token = auth.token, userId = auth.user?.id
  pullingMonitor.value = true
  try {
    const { data } = await client.post('/bureau/clues/fetch-monitor')
    if (auth.token !== token || auth.user?.id !== userId || !canPullMonitor.value) return
    if (data.data.adapter === 'MockMonitorFeedAdapter') {
      ElMessage.warning('监控疑点网关尚未启用')
      return
    }
    ElMessage.success(`已导入 ${data.data.fetched} 条，跳过重复 ${data.data.skipped ?? 0} 条`)
    reload()
  } catch {
    // The shared client reports the request failure.
  } finally {
    pullingMonitor.value = false
  }
}

async function onImport() {
  if (!canOperate.value) return
  const { value } = await ElMessageBox.prompt(
    '粘贴智能监控疑点 JSON 数组：[{"suspectName":"…","suspectType":"PROVIDER","content":"…"}]',
    '批量导入线索', { inputType: 'textarea', inputPattern: /^\s*\[/, inputErrorMessage: '须为 JSON 数组' })
  let rows: any
  try {
    rows = JSON.parse(value)
  } catch {
    ElMessage.error('JSON 格式有误，请检查括号与逗号后重试')
    return
  }
  if (!Array.isArray(rows) || !rows.length) {
    ElMessage.warning('请粘贴非空的线索数组')
    return
  }
  const resp = await client.post('/bureau/clues/import', rows)
  ElMessage.success(`已导入 ${resp.data.data.imported} 条线索`)
  load()
}

function toFile(row: any) {
  if (!canManageClue(row)) return
  router.push({ path: '/case/list', query: { clueId: row.id, suspectName: row.suspectName, suspectType: row.suspectType } })
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
.overdue { color: #c0392b; font-weight: 600; }
</style>
