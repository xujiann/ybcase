<template>
  <el-card>
    <div class="toolbar">
      <h3>移送台账<span class="hint">（无管辖权移送有权机关；涉嫌犯罪移送司法机关——行刑衔接）</span></h3>
      <el-button type="primary" @click="dlg = true">登记移送</el-button>
    </div>
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column label="方向" width="80">
        <template #default="{ row }">{{ row.direction === 'OUT' ? '移出' : '移入' }}</template>
      </el-table-column>
      <el-table-column label="关联" width="170">
        <template #default="{ row }">{{ row.clue_no || row.case_no || '-' }}</template>
      </el-table-column>
      <el-table-column label="类型" width="110">
        <template #default="{ row }">{{ KIND[row.kind] || row.kind }}</template>
      </el-table-column>
      <el-table-column prop="target_org" label="移送对象/来函机关" width="200" show-overflow-tooltip />
      <el-table-column prop="reason" label="理由" show-overflow-tooltip />
      <el-table-column prop="sent_at" label="移送日期" width="110" />
      <el-table-column label="接收确认" width="140">
        <template #default="{ row }">
          <span v-if="row.confirmed_at">{{ row.confirmed_at }}</span>
          <el-button v-else size="small" text type="primary" @click="onConfirm(row)">确认接收</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlg" title="登记移送" width="520px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="方向">
          <el-radio-group v-model="form.direction">
            <el-radio value="OUT">移出</el-radio>
            <el-radio value="IN">移入</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.kind" style="width: 100%">
            <el-option v-for="(v, k) in KIND" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="线索ID"><el-input-number v-model="form.clueId" :min="0" placeholder="可空" /></el-form-item>
        <el-form-item label="案件ID"><el-input-number v-model="form.caseId" :min="0" placeholder="可空" /></el-form-item>
        <el-form-item label="对象机关"><el-input v-model="form.targetOrg" /></el-form-item>
        <el-form-item label="理由"><el-input v-model="form.reason" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="onCreate">登记</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const KIND: Record<string, string> = { ADMIN: '其他行政机关', JUDICIAL: '司法机关', MSA: '医保部门间' }
const rows = ref<any[]>([])
const loading = ref(false)
const dlg = ref(false)
const form = reactive<any>({ direction: 'OUT', kind: 'ADMIN', clueId: undefined, caseId: undefined, targetOrg: '', reason: '' })

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/bureau/transfers')).data.data
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  await client.post('/bureau/transfers', {
    ...form,
    clueId: form.clueId || null,
    caseId: form.caseId || null,
  })
  ElMessage.success('已登记')
  dlg.value = false
  load()
}

async function onConfirm(row: any) {
  await client.post(`/bureau/transfers/${row.id}/confirm`)
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
