<template>
  <el-card>
    <div class="toolbar">
      <h3>案件办理<span class="hint">（普通程序自立案之日起 90 日内作出处理决定）</span></h3>
      <div>
        <el-input v-model="q" placeholder="案号/案名/当事人" clearable style="width: 200px; margin-right: 8px"
                  @keyup.enter="() => { page = 1; load() }" @clear="() => { page = 1; load() }" />
        <el-select v-model="statusFilter" clearable placeholder="全部状态" style="width: 130px; margin-right: 8px"
                   @change="() => { page = 1; load() }">
          <el-option v-for="(v, k) in CASE_STATUS" :key="k" :label="v" :value="k" />
        </el-select>
        <el-select v-if="isPrivileged" v-model="ownerFilter" clearable filterable placeholder="全部承办人"
                   style="width: 190px; margin-right: 8px" @change="() => { page = 1; load() }">
          <el-option v-for="o in owners" :key="o.username" :value="o.username"
                     :label="`${ownerLabel(o.username)} ${o.active_cases} 件在办`" />
        </el-select>
        <el-button v-if="isLeader" style="margin-right: 8px" @click="openTransfer">批量移交</el-button>
        <el-button type="primary" @click="openCreate()">{{ isLeader ? '立案' : '申请立案' }}</el-button>
      </div>
    </div>
    <el-table :data="records" v-loading="loading" border stripe @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
      <el-table-column prop="caseNo" label="案号" width="170" />
      <el-table-column prop="name" label="案件名称" show-overflow-tooltip />
      <el-table-column label="承办人" width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ ownerLabel(row.ownerUser) }}</template>
      </el-table-column>
      <el-table-column label="当事人类别" width="120">
        <template #default="{ row }">{{ PARTY_TYPE[row.partyType] }}</template>
      </el-table-column>
      <el-table-column label="程序" width="90">
        <template #default="{ row }">{{ row.procedureType === 'SUMMARY' ? '简易' : '普通' }}</template>
      </el-table-column>
      <el-table-column prop="filedAt" label="立案日期" width="110" />
      <el-table-column prop="deadlineAt" label="办案期限" width="110" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="(CASE_STATUS_TAG[row.status] as any)" size="small">{{ CASE_STATUS[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="amountInvolved" label="涉案金额" width="110" align="right" />
    </el-table>
    <el-pagination style="margin-top: 12px; justify-content: flex-end"
                   layout="total, prev, pager, next" :total="total" :page-size="pageSize"
                   :current-page="page" @current-change="(p: number) => { page = p; load() }" />

    <el-dialog v-model="createVisible" :title="isLeader ? '立案（执法人员不得少于两人）' : '立案申请（提交负责人批准后建案，第17条）'" width="640px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="当事人名称">
          <el-input v-model="form.partyName" />
        </el-form-item>
        <el-form-item label="当事人类别">
          <el-select v-model="form.partyType" style="width: 100%" @change="form.causeId = null">
            <el-option v-for="(v, k) in PARTY_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="案由">
          <el-select v-model="form.causeId" style="width: 100%" filterable placeholder="按当事人类别筛选">
            <el-option v-for="c in causes.filter((x) => x.subjectType === form.partyType)" :key="c.id"
                       :label="`${c.itemNo}. ${c.category}——${c.description}`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="执法事项">
          <el-select v-model="form.enforceItemId" style="width: 100%" clearable placeholder="关联执法事项（文书自动带出法律依据）">
            <el-option v-for="it in enforceItems.filter((x) => x.category === 'PENALTY')" :key="it.id"
                       :label="`${it.seq_no}. ${it.name}`" :value="it.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="行为终了日">
          <el-date-picker v-model="form.violationEndDate" type="date" value-format="YYYY-MM-DD" style="width: 200px"
                          placeholder="追责时效起算（第6条）" />
          <el-checkbox v-model="form.healthHarm" style="margin-left: 12px">涉生命健康且有危害后果（时效5年）</el-checkbox>
        </el-form-item>
        <el-form-item label="程序类型">
          <el-radio-group v-model="form.procedureType">
            <el-radio value="NORMAL">普通程序</el-radio>
            <el-radio value="SUMMARY">简易程序（当场处罚：公民≤200元，单位≤3000元）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="证照/证件号">
          <el-input v-model="form.partyCreditNo" placeholder="统一社会信用代码或身份证号" />
        </el-form-item>
        <el-form-item label="地址">
          <el-input v-model="form.partyAddress" />
        </el-form-item>
        <el-form-item label="法定代表人">
          <el-input v-model="form.partyLegalRep" placeholder="自然人案件可留空" />
        </el-form-item>
        <el-form-item label="联系方式">
          <el-input v-model="form.partyContact" />
        </el-form-item>
        <el-form-item label="涉案金额">
          <el-input-number v-model="form.amountInvolved" :min="0" :precision="2" style="width: 200px" />
        </el-form-item>
        <el-form-item label="案情摘要">
          <el-input v-model="form.summary" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="办案人员">
          <div style="width: 100%">
            <div v-for="(o, i) in form.officers" :key="i" class="officer-row">
              <el-input v-model="o.name" placeholder="姓名" style="width: 140px" />
              <el-input v-model="o.certNo" placeholder="执法证号" style="width: 180px" />
              <el-select v-model="o.duty" style="width: 100px">
                <el-option label="主办" value="LEAD" />
                <el-option label="协办" value="MEMBER" />
              </el-select>
              <el-button v-if="form.officers.length > 2" text type="danger" @click="form.officers.splice(i, 1)">删除</el-button>
            </div>
            <el-button text type="primary" @click="form.officers.push({ name: '', certNo: '', duty: 'MEMBER' })">+ 添加人员</el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="onCreate" :loading="submitting">{{ isLeader ? '立案' : '提交申请' }}</el-button>
      </template>
    </el-dialog>

    <!-- 人员离职/调动：把某承办人名下全部在办案件一次移交（逐案复用移交逻辑，接收人收到站内消息） -->
    <el-dialog v-model="transferVisible" title="批量移交在办案件" width="520px">
      <el-form label-width="90px">
        <el-form-item label="移出承办人">
          <el-select v-model="transfer.fromOwner" filterable placeholder="选择承办人" style="width: 100%">
            <el-option v-for="o in owners.filter((x) => x.active_cases > 0)" :key="o.username" :value="o.username"
                       :label="`${ownerLabel(o.username)} ${o.active_cases} 件在办${o.overdue_cases > 0 ? `（${o.overdue_cases} 件超期）` : ''}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="接收人">
          <el-select v-model="transfer.toOwner" filterable placeholder="启用且具办案/负责人/法制角色的账号" style="width: 100%">
            <el-option v-for="u in candidates.filter((x) => x.username !== transfer.fromOwner)" :key="u.username"
                       :value="u.username" :label="`${u.real_name}（${u.username}）`" />
          </el-select>
        </el-form-item>
        <div class="hint">将移交移出承办人名下全部在办案件（未办结、未终止，含中止）；如需逐案移交请进案件详情操作。</div>
      </el-form>
      <template #footer>
        <el-button @click="transferVisible = false">取消</el-button>
        <el-button type="primary" :loading="transferring" @click="onTransfer">移交</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import client from '../../api/client'
import { CASE_STATUS, CASE_STATUS_TAG, PARTY_TYPE } from './labels'
import { useAuthStore } from '../../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
// 第17条：立案须负责人批准。负责人可直接立案（后端补记"负责人直接批准"审批单），
// 办案人员则提交立案申请，由负责人在审批中心批准后建案——否则补记的就是一张 approver 为自己的假批准单
const isLeader = computed(() => auth.user?.roles?.some((r: string) => ['LEADER', 'ADMIN'].includes(r)))
// 承办人清单接口限负责人/法制/管理员（办案员在 SELF 范围下本就只见自己的案件）
const isPrivileged = computed(() => auth.user?.roles?.some((r: string) => ['LEADER', 'LEGAL', 'ADMIN'].includes(r)))
const records = ref<any[]>([])
const owners = ref<any[]>([])
const candidates = ref<any[]>([])
const ownerFilter = ref<string>(String(route.query.owner || ''))
const transferVisible = ref(false)
const transferring = ref(false)
const transfer = reactive({ fromOwner: '', toOwner: '' })

function ownerLabel(username: string | null | undefined) {
  if (!username) return ''
  const o = owners.value.find((x) => x.username === username)
  if (!o) return username
  return `${o.real_name || username}(${username})${o.enabled ? '' : '（已停用）'}`
}

async function loadOwners() {
  if (!isPrivileged.value) return
  owners.value = (await client.get('/bureau/owners')).data.data
}
// 刷新/新标签页直接进入时 auth.user 还没到（MainLayout 挂载后才 fetchMe），onMounted 里的 loadOwners
// 会被门禁跳过且不再重试——下拉永远为空、承办人列只剩裸账号名。用户信息到达后补拉一次。
watch(isPrivileged, (v) => { if (v && !owners.value.length) loadOwners() })

async function openTransfer() {
  transfer.fromOwner = ownerFilter.value
  transfer.toOwner = ''
  const [o, c] = await Promise.all([client.get('/bureau/owners'), client.get('/bureau/owners/candidates')])
  owners.value = o.data.data
  candidates.value = c.data.data
  transferVisible.value = true
}

async function onTransfer() {
  if (!transfer.fromOwner || !transfer.toOwner) {
    ElMessage.warning('请选择移出承办人与接收人')
    return
  }
  if (transferring.value) return
  transferring.value = true
  try {
    const res = (await client.post('/bureau/owners/transfer', transfer)).data.data
    ElMessage.success(`已移交 ${res.count} 件：${res.caseNos.join('、')}`)
    transferVisible.value = false
    await loadOwners()
    await load()
  } finally {
    transferring.value = false
  }
}
const causes = ref<any[]>([])
const loading = ref(false)
const statusFilter = ref('')
const createVisible = ref(false)
const submitting = ref(false)
const q = ref('')
const page = ref(1)
const pageSize = 20
const total = ref(0)

const emptyOfficers = () => [
  { name: '', certNo: '', duty: 'LEAD' },
  { name: '', certNo: '', duty: 'MEMBER' },
]
const form = reactive<any>({
  clueId: null, causeId: null, procedureType: 'NORMAL',
  partyName: '', partyType: 'PROVIDER', partyCreditNo: '', partyAddress: '',
  partyLegalRep: '', partyContact: '', summary: '', amountInvolved: 0,
  enforceItemId: null, violationEndDate: null, healthHarm: false,
  officers: emptyOfficers(),
})
const enforceItems = ref<any[]>([])

async function load() {
  loading.value = true
  try {
    const params: any = { page: page.value, size: pageSize }
    if (statusFilter.value) params.status = statusFilter.value
    if (q.value.trim()) params.q = q.value.trim()
    if (ownerFilter.value) params.owner = ownerFilter.value
    const resp = await client.get('/bureau/cases', { params })
    // 分页接口返回 snake_case 行（JdbcTemplate），统一映射展示字段
    records.value = resp.data.data.rows.map((r: any) => ({
      id: r.id, caseNo: r.case_no, name: r.name, partyType: r.party_type,
      procedureType: r.procedure_type, filedAt: r.filed_at, deadlineAt: r.deadline_at,
      status: r.status, amountInvolved: r.amount_involved, ownerUser: r.owner_user,
    }))
    total.value = resp.data.data.total
  } finally {
    loading.value = false
  }
}

function openCreate(fromClue = false) {
  form.officers = emptyOfficers()
  // 从线索跳转以外的入口必须清掉线索关联，否则新案会把上次那条线索标记为已立案
  if (!fromClue) {
    form.clueId = null
    form.clueVerifyResult = null
  }
  createVisible.value = true
}

async function onCreate() {
  // 防重：连点或 15s 超时后的重试会生成两条连号且无法删除的记录
  if (submitting.value) return
  submitting.value = true
  try {
    if (!form.partyName || !form.causeId) {
      ElMessage.warning('请填写当事人并选择案由')
      return
    }
    if (form.officers.some((o: any) => !o.name || !o.certNo)) {
      ElMessage.warning('办案人员须填写姓名与执法证号')
      return
    }
    if (!isLeader.value) {
      // 办案人员：提交 FILE_CASE 审批单，负责人批准时才真正建案（ApprovalService.execute）
      await client.post('/bureau/approvals', {
        kind: 'FILE_CASE', clueId: form.clueId || null,
        payload: { ...form }, reason: `立案申请：${form.partyName}`,
      })
      ElMessage.success('立案申请已提交，待负责人批准后自动建案')
      createVisible.value = false
      router.push('/case/approvals')
      return
    }
    const resp = await client.post('/bureau/cases', form)
    ElMessage.success(`已立案：${resp.data.data.caseNo}`)
    createVisible.value = false
    router.push(`/case/detail/${resp.data.data.id}`)
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  const resp = await client.get('/bureau/causes')
  causes.value = resp.data.data
  enforceItems.value = (await client.get('/bureau/enforce-items')).data.data
  // 从线索页跳转：预填当事人并直接打开立案表单
  if (route.query.clueId) {
    form.clueId = Number(route.query.clueId)
    form.partyName = String(route.query.suspectName || '')
    form.partyType = String(route.query.suspectType || 'PROVIDER')
    openCreate(true)
  }
  // 承办人清单与案件列表互不依赖，并行拉取；从用户管理"去移交"跳转时 owner 已由 query 预填
  loadOwners()
  load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
.officer-row { display: flex; gap: 8px; margin-bottom: 8px; }
</style>
