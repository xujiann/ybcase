<template>
  <el-card>
    <h3>行政执法事项目录<span class="hint">（辽宁省医疗保障局行政执法事项清单 2024 年版；立案时关联事项可自动带出法律依据）</span></h3>
    <el-table :data="items" border stripe size="small" v-loading="loading">
      <el-table-column prop="seq_no" label="序号" width="70" />
      <el-table-column prop="name" label="事项名称" show-overflow-tooltip />
      <el-table-column label="类别" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="(CAT_TAG[row.category] as any)">{{ CAT[row.category] || row.category }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="undertaker" label="承办机构" width="110" />
      <el-table-column prop="basis_refs" label="执法依据" show-overflow-tooltip />
      <el-table-column prop="target" label="实施对象" width="170" show-overflow-tooltip />
      <el-table-column prop="time_limit" label="办理时限" width="200" show-overflow-tooltip />
    </el-table>

    <h3 style="margin-top: 20px">法律依据库<span class="hint">（文书自动引用；实施期按正式文本校核维护）</span></h3>
    <el-table :data="basis" border stripe size="small">
      <el-table-column prop="law_name" label="法律法规" width="240" />
      <el-table-column prop="article" label="条文" width="100" />
      <el-table-column prop="content" label="内容" show-overflow-tooltip />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import client from '../../api/client'

const CAT: Record<string, string> = { PENALTY: '行政处罚', COERCION: '行政强制', INSPECTION: '行政检查', REWARD: '行政奖励' }
const CAT_TAG: Record<string, string> = { PENALTY: 'danger', COERCION: 'warning', INSPECTION: 'primary', REWARD: 'success' }
const items = ref<any[]>([])
const basis = ref<any[]>([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    items.value = (await client.get('/bureau/enforce-items')).data.data
    basis.value = (await client.get('/bureau/law-basis')).data.data
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
