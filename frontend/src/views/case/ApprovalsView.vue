<template>
  <el-card>
    <div class="toolbar">
      <h3>待办审批<span class="hint">（申请→负责人批准两步留痕；批准即执行对应动作。展开左侧箭头查看申请内容再裁决）</span></h3>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-alert v-if="approvalState.failed" type="error" :closable="false" title="审批列表加载失败，请刷新后重试。" style="margin-bottom: 12px" />
    <el-table :data="rows" border stripe size="small" v-loading="loading" row-key="id">
      <!-- 批准即执行（立案会真的建案、延期会真的顺延期限），负责人必须先看得见申请内容 -->
      <el-table-column type="expand">
        <template #default="{ row }">
          <el-alert v-if="pl(row).__invalid" type="warning" :closable="false" title="申请材料格式异常，请驳回后重新提交。" />
          <el-descriptions v-else-if="pl(row).__empty" :column="1" border size="small">
            <el-descriptions-item label="申请内容">（本次申请无附加内容，见申请理由）</el-descriptions-item>
          </el-descriptions>
          <template v-else-if="row.kind === 'FILE_CASE'">
          <el-descriptions :column="2" border size="small" title="立案审批表">
            <el-descriptions-item label="当事人">{{ pl(row).partyName }}</el-descriptions-item>
            <el-descriptions-item label="当事人类别">{{ PARTY_TYPE[pl(row).partyType] || pl(row).partyType }}</el-descriptions-item>
            <el-descriptions-item label="案由">{{ causeName(pl(row).causeId) }}</el-descriptions-item>
            <el-descriptions-item label="程序">{{ pl(row).procedureType === 'SUMMARY' ? '简易程序' : '普通程序' }}</el-descriptions-item>
            <el-descriptions-item label="涉案金额">{{ pl(row).amountInvolved }}</el-descriptions-item>
            <el-descriptions-item v-if="pl(row).institution" label="机构编码">{{ pl(row).institution.code }}</el-descriptions-item>
            <el-descriptions-item v-if="pl(row).institution" label="机构性质">{{ pl(row).institution.ownership === 'PUBLIC' ? '公立' : '社会办' }}</el-descriptions-item>
            <el-descriptions-item v-if="pl(row).institution" label="机构等级">{{ INSTITUTION_GRADE[pl(row).institution.grade] }}</el-descriptions-item>
            <el-descriptions-item label="违法行为终了日">{{ pl(row).violationEndDate || '—' }}</el-descriptions-item>
            <el-descriptions-item label="办案人员" :span="2">
              {{ officerNames(pl(row)) }}
            </el-descriptions-item>
            <el-descriptions-item label="统一社会信用代码">{{ pl(row).partyCreditNo || '—' }}</el-descriptions-item>
            <el-descriptions-item label="法定代表人">{{ pl(row).partyLegalRep || '—' }}</el-descriptions-item>
            <el-descriptions-item label="地址" :span="2">{{ pl(row).partyAddress || '—' }}</el-descriptions-item>
            <el-descriptions-item label="案情摘要" :span="2">{{ pl(row).summary || '—' }}</el-descriptions-item>
          </el-descriptions>
          <h4>立案申请管辖事实快照</h4>
          <JurisdictionSummary :value="pl(row).jurisdiction" />
          </template>
          <el-descriptions v-else-if="row.kind === 'EXTEND'" :column="1" border size="small" title="延期申请">
            <el-descriptions-item label="申请延长">{{ pl(row).days }} 日</el-descriptions-item>
          </el-descriptions>
          <el-descriptions v-else :column="1" border size="small" title="申请内容">
            <el-descriptions-item v-for="(v, k) in pl(row)" :key="k" :label="String(k)">{{ v }}</el-descriptions-item>
          </el-descriptions>
        </template>
      </el-table-column>
      <el-table-column prop="id" label="单号" width="70" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ KIND[row.kind] || row.kind }}</template>
      </el-table-column>
      <el-table-column label="关联" width="180">
        <template #default="{ row }">{{ row.case_no || row.clue_no || '（立案申请）' }}</template>
      </el-table-column>
      <el-table-column label="要点" width="150">
        <template #default="{ row }">
          <template v-if="row.kind === 'FILE_CASE'">{{ pl(row).partyName || '—' }}</template>
          <template v-else-if="row.kind === 'CLUE_EXTEND'">延长 15 个工作日</template>
          <template v-else-if="row.kind === 'EXTEND'">延长 {{ pl(row).days }} 日</template>
          <span v-else class="hint">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="申请理由" show-overflow-tooltip />
      <el-table-column prop="applicant" label="申请人" width="90" />
      <el-table-column label="申请时间" width="140">
        <template #default="{ row }">{{ fmtTime(row.applied_at) }}</template>
      </el-table-column>
      <el-table-column label="裁决" width="170">
        <template #default="{ row }">
          <template v-if="isLeader">
            <el-button size="small" type="success" :loading="deciding === row.id" :disabled="!approvalState.ready || deciding !== null || pl(row).__invalid" @click="onDecide(row, true)">批准</el-button>
            <el-button size="small" type="danger" :disabled="!approvalState.ready || deciding !== null" @click="onDecide(row, false)">驳回</el-button>
          </template>
          <span v-else class="hint">待负责人裁决</span>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { createResourceLoader, resourceState } from '../../api/loadableResource'
import JurisdictionSummary from '../../components/JurisdictionSummary.vue'
import { useAuthStore } from '../../stores/auth'
import { INSTITUTION_GRADE } from '../../api/violationFinding'

import { PARTY_TYPE, fmtTime } from './labels'

const KIND: Record<string, string> = { FILE_CASE: '立案', CLUE_EXTEND: '线索延期', EXTEND: '延期', SUSPEND: '中止', TERMINATE: '终止', DEFER: '暂缓分期' }
const causes = ref<any[]>([])

/** payload 是 jsonb 文本列，可能为 null；解析失败不能让整页白屏 */
function parsePayload(raw: any, kind: string): any {
  let value: any
  try { value = raw == null || raw === '' ? {} : (typeof raw === 'string' ? JSON.parse(raw) : raw) }
  catch { return { __invalid: true } }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return { __invalid: true }
  if (kind === 'FILE_CASE' && (!Array.isArray(value.officers)
      || value.officers.some((o: any) => !o || typeof o !== 'object' || Array.isArray(o)
        || typeof o.name !== 'string' || typeof o.certNo !== 'string'))) return { __invalid: true }
  return { ...value, __empty: Object.keys(value).length === 0, __invalid: false }
}

function officerNames(payload: any): string {
  if (!Array.isArray(payload.officers)) return '—'
  return payload.officers.filter((o: any) => o && typeof o === 'object')
    .map((o: any) => String(o.name || '') + '（' + String(o.certNo || '') + (o.duty === 'LEAD' ? ' 主办' : ' 协办') + '）')
    .join('、') || '—'
}

// 解析在 load() 里一次做完，pl() 只读不写：
// 曾在渲染期写 row.__pl，而 el-table 对 :data 有 deep watch，写入触发 setData 重建展开状态，
// 结果是展开行第一次点击立刻塌陷（要点两下才展开）
function pl(row: any): any {
  return row.__pl || { __empty: true }
}

function causeName(id: number) {
  // CaseCause 没有 name 字段，与立案表单下拉同口径：序号. 类别——描述
  const c = causes.value.find((x: any) => x.id === id)
  return c ? `${c.itemNo}. ${c.category}——${c.description}` : (id ?? '—')
}
const auth = useAuthStore()
const approvalState = reactive(resourceState<any[]>([]))
const rows = computed(() => approvalState.ready ? approvalState.data : [])
const loading = computed(() => approvalState.loading)
const isLeader = computed(() => auth.user?.roles?.some((r) => ['LEADER', 'ADMIN'].includes(r)))
const deciding = ref<number | null>(null)
let disposed = false
let listGeneration = 0
const approvalLoader = createResourceLoader(approvalState, async () => {
  const data = (await client.get('/bureau/approvals/pending')).data.data
  if (!Array.isArray(data)) throw new Error('Invalid approval list')
  return data.map((r: any) => ({ ...r, __pl: parsePayload(r.payload, r.kind) }))
})

async function load() {
  if (disposed) return false
  listGeneration++
  return approvalLoader.load()
}
onUnmounted(() => {
  disposed = true
  listGeneration++
  approvalLoader.invalidate()
  approvalState.data = []
})

async function onDecide(row: any, approve: boolean) {
  if (!isLeader.value || disposed || deciding.value !== null || !approvalState.ready
      || !rows.value.some((current: any) => current.id === row.id) || (approve && pl(row).__invalid)) return
  const approvalId = row.id, kind = row.kind, generation = listGeneration
  deciding.value = approvalId
  let submitted = false
  try {
    const { value } = await ElMessageBox.prompt(approve ? '批准意见' : '驳回理由', KIND[kind] + '审批',
      { inputValue: approve ? '同意' : '', inputPattern: /\S+/, inputErrorMessage: '必填' })
    if (disposed || !isLeader.value || generation !== listGeneration || !approvalState.ready
        || !rows.value.some((current: any) => current.id === approvalId)) return
    submitted = true
    await client.post('/bureau/approvals/' + approvalId + '/decide', { approve, opinion: value })
    if (disposed) return
    ElMessage.success(approve ? '已批准并执行' : '已驳回')
    await load()
  } catch {
    // The API interceptor already reports request errors; cancel is also an expected result.
    if (submitted && !disposed) await load()
  } finally {
    deciding.value = null
  }
}

onMounted(async () => {
  // 案由 id → 名称：负责人看到的应是"重复收费"而不是一个数字主键
  try { const data = (await client.get('/bureau/causes')).data.data; if (!disposed) causes.value = data } catch { /* 不阻断审批列表 */ }
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
