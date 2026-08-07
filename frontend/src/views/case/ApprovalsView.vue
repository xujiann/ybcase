<template>
  <el-card>
    <div class="toolbar">
      <h3>待办审批<span class="hint">（申请→负责人批准两步留痕；批准即执行对应动作）</span></h3>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="id" label="单号" width="70" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ KIND[row.kind] || row.kind }}</template>
      </el-table-column>
      <el-table-column label="关联" width="180">
        <template #default="{ row }">{{ row.case_no || row.clue_no || '（立案申请）' }}</template>
      </el-table-column>
      <el-table-column prop="reason" label="申请理由" show-overflow-tooltip />
      <el-table-column prop="applicant" label="申请人" width="90" />
      <el-table-column prop="applied_at" label="申请时间" width="160" />
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

const KIND: Record<string, string> = { FILE_CASE: '立案', EXTEND: '延期', SUSPEND: '中止', TERMINATE: '终止', DEFER: '暂缓分期' }
const auth = useAuthStore()
const rows = ref<any[]>([])
const loading = ref(false)
const isLeader = computed(() => auth.user?.roles?.some((r) => ['LEADER', 'ADMIN'].includes(r)))

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/bureau/approvals/pending')).data.data
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

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
