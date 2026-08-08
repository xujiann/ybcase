<template>
  <div v-loading="loading">
    <el-row :gutter="12" class="mb">
      <el-col :span="6">
        <el-card><div class="stat-label">我的在办案件</div>
          <div class="stat-value">{{ d.myCases?.length || 0 }}</div></el-card>
      </el-col>
      <el-col :span="6">
        <el-card><div class="stat-label">我登记的待核线索</div>
          <div class="stat-value">{{ d.myClues?.length || 0 }}</div></el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="clickable" @click="$router.push('/case/approvals')">
          <div class="stat-label">{{ isLeader ? '待我裁决的审批' : '我提交的待批申请' }}</div>
          <div class="stat-value" style="color: #b7791f">{{ isLeader ? d.pendingForMe : (d.myApplications?.length || 0) }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card><div class="stat-label">未读消息</div>
          <div class="stat-value" style="color: #c0392b">{{ d.unread || 0 }}</div></el-card>
      </el-col>
    </el-row>

    <el-card class="mb">
      <h4>我的在办案件（按有效期限升序，临期置顶）</h4>
      <el-table :data="d.myCases" border size="small" @row-click="(r: any) => $router.push(`/case/detail/${r.id}`)">
        <el-table-column prop="case_no" label="案号" width="170" />
        <el-table-column prop="name" label="案件名称" show-overflow-tooltip />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ CASE_STATUS[row.status] || row.status }}</template>
        </el-table-column>
        <el-table-column label="有效期限 / 剩余" width="200">
          <template #default="{ row }">
            <span :class="{ danger: daysLeft(row.effective_deadline) < 0, warn: daysLeft(row.effective_deadline) <= 10 && daysLeft(row.effective_deadline) >= 0 }">
              {{ row.effective_deadline }}（{{ daysLeft(row.effective_deadline) < 0 ? `超期 ${-daysLeft(row.effective_deadline)} 天` : `剩 ${daysLeft(row.effective_deadline)} 天` }}）
            </span>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="!d.myCases?.length" class="hint">暂无在办案件</p>
    </el-card>

    <el-card>
      <h4>我登记的待核线索（15个工作日内须决定是否立案）</h4>
      <el-table :data="d.myClues" border size="small" @row-click="() => $router.push('/case/clues')">
        <el-table-column prop="clue_no" label="线索号" width="140" />
        <el-table-column prop="suspect_name" label="嫌疑人" />
        <el-table-column prop="deadline_at" label="核查期限" width="130" />
      </el-table>
      <p v-if="!d.myClues?.length" class="hint">暂无待核线索</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import { CASE_STATUS } from './labels'

const auth = useAuthStore()
const d = ref<any>({})
const loading = ref(false)
const isLeader = computed(() => auth.user?.roles?.some((r) => ['LEADER', 'ADMIN'].includes(r)))

function daysLeft(deadline: string) {
  // 'YYYY-MM-DD' 被 Date 当作 UTC 零点解析，与本地 now 相减会在 UTC+8 的同一天内漂移一天，
  // 故两端都取本地零点后再算整日差
  const [y, m, d] = deadline.split('-').map(Number)
  const end = new Date(y, m - 1, d).getTime()
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  return Math.round((end - today) / 86400000)
}

onMounted(async () => {
  loading.value = true
  try {
    d.value = (await client.get('/bureau/my/workbench')).data.data
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.mb { margin-bottom: 12px; }
.stat-label { font-size: 12px; color: #999; }
.stat-value { font-size: 26px; font-weight: 600; margin-top: 4px; color: #16417c; }
.clickable { cursor: pointer; }
.danger { color: #c0392b; font-weight: 600; }
.warn { color: #b7791f; font-weight: 600; }
.hint { font-size: 12px; color: #999; }
h4 { margin: 0 0 8px; }
</style>
