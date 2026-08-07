<template>
  <el-card>
    <div class="toolbar">
      <h3>参数设置<span class="hint">（期限/限额/阈值全部可配；默认=新《行政处罚法》+国家局令第4号，辽宁口径可切换，修改留痕入审计）</span></h3>
      <div>
        <el-input v-model="auditMonth" placeholder="YYYY-MM" style="width: 110px; margin-right: 6px" />
        <el-button size="small" @click="onAuditExport">导出审计日志</el-button>
        <el-input v-model="filter" placeholder="搜索参数" clearable style="width: 200px; margin-left: 12px" />
      </div>
    </div>
    <el-table :data="rows.filter((r) => !filter || r.key.includes(filter) || (r.remark || '').includes(filter))"
              border stripe size="small" v-loading="loading">
      <el-table-column prop="key" label="参数键" width="300" />
      <el-table-column label="当前值" width="200">
        <template #default="{ row }">
          <el-input v-model="row.value" size="small" @change="onSave(row)" />
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="说明" show-overflow-tooltip />
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const rows = ref<{ key: string; value: string; remark: string }[]>([])
const loading = ref(false)
const filter = ref('')
const auditMonth = ref(new Date().toISOString().slice(0, 7))

async function onAuditExport() {
  const resp = await client.get('/bureau/audit/export', { params: { month: auditMonth.value }, responseType: 'blob' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(resp.data)
  a.download = `audit-${auditMonth.value}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
  ElMessage.success('审计日志已导出（等保：定期归档留存）')
}

async function load() {
  loading.value = true
  try {
    const resp = await client.get('/config/all')
    rows.value = resp.data.data
  } finally {
    loading.value = false
  }
}

async function onSave(row: { key: string; value: string }) {
  await client.put(`/config/${row.key}?value=${encodeURIComponent(row.value)}`)
  ElMessage.success(`已保存 ${row.key}`)
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
