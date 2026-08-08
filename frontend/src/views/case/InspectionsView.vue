<template>
  <el-card>
    <div class="toolbar">
      <h3>行政检查<span class="hint">（清单第10-12项；发现违法一键转线索，打通 检查→线索→立案 闭环）</span></h3>
      <el-button type="primary" @click="dlg = true">新建检查任务</el-button>
    </div>
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="insp_no" label="检查编号" width="110" />
      <el-table-column prop="item_name" label="检查事项" show-overflow-tooltip />
      <el-table-column prop="object_name" label="检查对象" width="160" show-overflow-tooltip />
      <el-table-column prop="officers" label="检查人员" width="150" show-overflow-tooltip />
      <el-table-column prop="planned_at" label="计划日期" width="105" />
      <el-table-column label="状态" width="200">
        <template #default="{ row }">
          <template v-if="!row.done_at">
            <el-tag size="small">待检查</el-tag>
            <el-button size="small" text type="primary" @click="onComplete(row)">登记结果</el-button>
          </template>
          <template v-else>
            <el-tag size="small" :type="row.violation_found ? 'danger' : 'success'">
              {{ row.violation_found ? '发现违法' : '未见违法' }}
            </el-tag>
            <el-button v-if="row.violation_found && !row.clue_id" size="small" text type="danger" @click="onToClue(row)">转线索</el-button>
            <span v-if="row.clue_id" class="hint">已转线索#{{ row.clue_id }}</span>
          </template>
        </template>
      </el-table-column>
      <el-table-column prop="result" label="检查结果" show-overflow-tooltip />
    </el-table>

    <el-dialog v-model="dlg" title="新建检查任务（检查人员不得少于2人）" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="检查事项">
          <el-select v-model="form.itemId" style="width: 100%">
            <el-option v-for="it in items" :key="it.id" :label="`${it.seq_no}. ${it.name}`" :value="it.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="检查对象"><el-input v-model="form.objectName" /></el-form-item>
        <el-form-item label="对象类别">
          <el-select v-model="form.objectType" style="width: 100%">
            <el-option v-for="(v, k) in PARTY_TYPE" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="检查人员"><el-input v-model="form.officers" placeholder="以顿号分隔，至少2人" /></el-form-item>
        <el-form-item label="计划日期">
          <el-date-picker v-model="form.plannedAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { PARTY_TYPE } from './labels'

const rows = ref<any[]>([])
const items = ref<any[]>([])
const loading = ref(false)
const dlg = ref(false)
function todayLocal() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const form = reactive<any>({ itemId: null, objectName: '', objectType: 'PROVIDER', officers: '', plannedAt: todayLocal() })

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/bureau/inspections')).data.data
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  if (!form.objectName || !form.officers) {
    ElMessage.warning('检查对象与人员必填')
    return
  }
  await client.post('/bureau/inspections', form)
  ElMessage.success('已创建')
  dlg.value = false
  load()
}

async function onComplete(row: any) {
  const { value } = await ElMessageBox.prompt('检查结果（发现违法请以"违法："开头）', '登记检查结果', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  await client.post(`/bureau/inspections/${row.id}/complete`, { result: value, violationFound: value.startsWith('违法') })
  ElMessage.success('已登记')
  load()
}

async function onToClue(row: any) {
  const resp = await client.post(`/bureau/inspections/${row.id}/to-clue`)
  ElMessage.success(`已转线索 ${resp.data.data.clueNo}`)
  load()
}

onMounted(async () => {
  items.value = ((await client.get('/bureau/enforce-items')).data.data as any[]).filter((x) => x.category === 'INSPECTION')
  load()
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
