<template>
  <el-card>
    <div class="toolbar">
      <h3>参数设置<span class="hint">（期限/限额/阈值全部可配；默认=新《行政处罚法》+国家局令第4号，辽宁口径可切换，修改留痕入审计）</span></h3>
      <el-input v-model="filter" placeholder="搜索参数" clearable style="width: 220px" />
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
