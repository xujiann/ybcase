<template>
  <el-card>
    <div class="toolbar">
      <h3>待办审批<span class="hint">（申请→负责人批准两步留痕；批准即执行对应动作。展开左侧箭头查看申请内容再裁决）</span></h3>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table :data="rows" border stripe size="small" v-loading="loading" row-key="id">
      <!-- 批准即执行（立案会真的建案、延期会真的顺延期限），负责人必须先看得见申请内容 -->
      <el-table-column type="expand">
        <template #default="{ row }">
          <el-descriptions v-if="pl(row).__empty" :column="1" border size="small">
            <el-descriptions-item label="申请内容">（本次申请无附加内容，见申请理由）</el-descriptions-item>
          </el-descriptions>
          <el-descriptions v-else-if="row.kind === 'FILE_CASE'" :column="2" border size="small" title="立案审批表">
            <el-descriptions-item label="当事人">{{ pl(row).partyName }}</el-descriptions-item>
            <el-descriptions-item label="当事人类别">{{ PARTY_TYPE[pl(row).partyType] || pl(row).partyType }}</el-descriptions-item>
            <el-descriptions-item label="案由">{{ causeName(pl(row).causeId) }}</el-descriptions-item>
            <el-descriptions-item label="程序">{{ pl(row).procedureType === 'SUMMARY' ? '简易程序' : '普通程序' }}</el-descriptions-item>
            <el-descriptions-item label="涉案金额">{{ pl(row).amountInvolved }}</el-descriptions-item>
            <el-descriptions-item label="违法行为终了日">{{ pl(row).violationEndDate || '—' }}</el-descriptions-item>
            <el-descriptions-item label="办案人员" :span="2">
              {{ (pl(row).officers || []).map((o: any) => `${o.name}（${o.certNo}${o.duty === 'LEAD' ? ' 主办' : ' 协办'}）`).join('、') || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="统一社会信用代码">{{ pl(row).partyCreditNo || '—' }}</el-descriptions-item>
            <el-descriptions-item label="法定代表人">{{ pl(row).partyLegalRep || '—' }}</el-descriptions-item>
            <el-descriptions-item label="地址" :span="2">{{ pl(row).partyAddress || '—' }}</el-descriptions-item>
            <el-descriptions-item label="案情摘要" :span="2">{{ pl(row).summary || '—' }}</el-descriptions-item>
          </el-descriptions>
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
            <el-button size="small" type="success" @click="onDecide(row, true)">批准</el-button>
            <el-button size="small" type="danger" @click="onDecide(row, false)">驳回</el-button>
          </template>
          <span v-else class="hint">待负责人裁决</span>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'

import { PARTY_TYPE, fmtTime } from './labels'

const KIND: Record<string, string> = { FILE_CASE: '立案', EXTEND: '延期', SUSPEND: '中止', TERMINATE: '终止', DEFER: '暂缓分期' }
const causes = ref<any[]>([])

/** payload 是 jsonb 文本列，可能为 null；解析失败不能让整页白屏 */
function parsePayload(raw: any): any {
  let v: any = {}
  try {
    v = raw ? (typeof raw === 'string' ? JSON.parse(raw) : raw) : {}
  } catch { v = {} }
  return !v || Object.keys(v).length === 0 ? { __empty: true } : v
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
const rows = ref<any[]>([])
const loading = ref(false)
const isLeader = computed(() => auth.user?.roles?.some((r) => ['LEADER', 'ADMIN'].includes(r)))

async function load() {
  loading.value = true
  try {
    rows.value = ((await client.get('/bureau/approvals/pending')).data.data as any[])
      .map((r) => ({ ...r, __pl: parsePayload(r.payload) }))
  } finally {
    loading.value = false
  }
}

async function onDecide(row: any, approve: boolean) {
  const { value } = await ElMessageBox.prompt(approve ? '批准意见' : '驳回理由', KIND[row.kind] + '审批',
    { inputValue: approve ? '同意' : '', inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/approvals/${row.id}/decide`, { approve, opinion: value })
  ElMessage.success(approve ? '已批准并执行' : '已驳回')
  load()
}

onMounted(async () => {
  // 案由 id → 名称：负责人看到的应是"重复收费"而不是一个数字主键
  try { causes.value = (await client.get('/bureau/causes')).data.data } catch { /* 不阻断审批列表 */ }
  await load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
