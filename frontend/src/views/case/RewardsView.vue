<template>
  <el-card>
    <div class="toolbar">
      <h3>举报奖励台账<span class="hint">（条例第35条；举报人信息严格保密，线索查实立案后启动奖励）</span></h3>
      <el-button type="primary" @click="dlg = true">登记奖励</el-button>
    </div>
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="clue_no" label="线索编号" width="120" />
      <el-table-column prop="suspect_name" label="被举报对象" width="160" show-overflow-tooltip />
      <el-table-column prop="reporter_name" label="举报人" width="100" />
      <el-table-column prop="amount" label="奖励金额" width="110" align="right" />
      <el-table-column label="审批" width="150">
        <template #default="{ row }">
          <span v-if="row.approved_at">{{ row.approved_by }}（{{ row.approved_at }}）</span>
          <el-button v-else size="small" text type="primary" @click="onApprove(row)">审批</el-button>
        </template>
      </el-table-column>
      <el-table-column label="发放" width="140">
        <template #default="{ row }">
          <span v-if="row.paid_at">已发放 {{ row.paid_at }}</span>
          <el-button v-else-if="row.approved_at" size="small" text type="success" @click="onPay(row)">登记发放</el-button>
          <span v-else class="hint">待审批</span>
        </template>
      </el-table-column>
      <el-table-column prop="note" label="备注" show-overflow-tooltip />
    </el-table>

    <el-dialog v-model="dlg" title="登记举报奖励（线索须已立案查实）" width="460px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="线索ID"><el-input-number v-model="form.clueId" :min="1" /></el-form-item>
        <el-form-item label="举报人"><el-input v-model="form.reporterName" /></el-form-item>
        <el-form-item label="联系方式"><el-input v-model="form.reporterContact" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
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
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'

const rows = ref<any[]>([])
const loading = ref(false)
const dlg = ref(false)
const form = reactive<any>({ clueId: undefined, reporterName: '', reporterContact: '', note: '' })

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/bureau/rewards')).data.data
  } finally {
    loading.value = false
  }
}

async function onCreate() {
  if (!form.clueId || !form.reporterName) {
    ElMessage.warning('线索与举报人必填')
    return
  }
  await client.post('/bureau/rewards', form)
  ElMessage.success('已登记')
  dlg.value = false
  load()
}

async function onApprove(row: any) {
  const { value } = await ElMessageBox.prompt('奖励金额（元）', '奖励审批', { inputPattern: /^\d+(\.\d{1,2})?$/, inputErrorMessage: '金额无效' })
  await client.post(`/bureau/rewards/${row.id}/approve`, { amount: Number(value) })
  ElMessage.success('已审批')
  load()
}

async function onPay(row: any) {
  await client.post(`/bureau/rewards/${row.id}/pay`)
  ElMessage.success('已发放')
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
