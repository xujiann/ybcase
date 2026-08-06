<template>
  <el-card>
    <div class="toolbar">
      <h3>法定节假日/调休表<span class="hint">（期限计算依据，第58条；每年按国办通知维护）</span></h3>
      <div>
        <el-date-picker v-model="form.day" type="date" value-format="YYYY-MM-DD" placeholder="日期" style="width: 150px; margin-right: 8px" />
        <el-select v-model="form.kind" style="width: 130px; margin-right: 8px">
          <el-option label="放假" value="HOLIDAY" />
          <el-option label="调休上班" value="SHIFT_WORK" />
        </el-select>
        <el-input v-model="form.name" placeholder="名称" style="width: 120px; margin-right: 8px" />
        <el-button type="primary" @click="onAdd">添加/更新</el-button>
      </div>
    </div>
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="day" label="日期" width="140" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.kind === 'HOLIDAY' ? 'danger' : 'success'">
            {{ row.kind === 'HOLIDAY' ? '放假' : '调休上班' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" />
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button size="small" text type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const rows = ref<any[]>([])
const loading = ref(false)
const form = reactive({ day: '', kind: 'HOLIDAY', name: '' })

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/bureau/holidays')).data.data
  } finally {
    loading.value = false
  }
}

async function onAdd() {
  if (!form.day) {
    ElMessage.warning('请选择日期')
    return
  }
  await client.post('/bureau/holidays', form)
  ElMessage.success('已保存')
  load()
}

async function onDelete(row: any) {
  await client.delete(`/bureau/holidays/${row.day}`)
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; flex-wrap: wrap; gap: 8px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
