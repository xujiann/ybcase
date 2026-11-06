<template>
  <el-card>
    <div class="toolbar">
      <h3>举报奖励台账<span class="hint">（条例第35条；举报人信息严格保密，线索查实立案后启动奖励）</span></h3>
      <div class="list-controls">
        <el-input v-model="pagination.q" clearable placeholder="线索编号/对象/备注" style="width: 220px" @keyup.enter="paging.search" @clear="paging.search" />
        <el-button @click="paging.search">搜索</el-button>
        <el-button @click="load">刷新</el-button>
        <el-button v-if="canOperate" type="primary" @click="dlg = true">登记奖励</el-button>
      </div>
    </div>
    <el-alert v-if="pagination.failed" type="error" :closable="false" :title="pagination.error" style="margin-bottom: 12px" />
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="clue_no" label="线索编号" width="120" />
      <el-table-column prop="suspect_name" label="被举报对象" width="160" show-overflow-tooltip />
      <el-table-column prop="reporter_name" label="举报人" width="100" />
      <el-table-column prop="amount" label="奖励金额" width="110" align="right" />
      <el-table-column label="审批" width="150">
        <template #default="{ row }">
          <span v-if="row.approved_at">{{ row.approved_by }}（{{ row.approved_at }}）</span>
          <el-button v-else-if="isLeader" size="small" text type="primary" @click="onApprove(row)">审批</el-button>
          <span v-else class="hint">待负责人审批</span>
        </template>
      </el-table-column>
      <el-table-column label="发放" width="140">
        <template #default="{ row }">
          <span v-if="row.paid_at">已发放 {{ row.paid_at }}</span>
          <el-button v-else-if="isLeader && (row.approved_at)" size="small" text type="success" @click="onPay(row)">登记发放</el-button>
          <span v-else class="hint">{{ row.approved_at ? '待负责人登记发放' : '待审批' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="note" label="备注" show-overflow-tooltip />
    </el-table>
    <el-pagination style="margin-top: 12px; justify-content: flex-end" :current-page="pagination.page" :page-size="pagination.size" :page-sizes="[20,50,100]" :total="pagination.total" layout="total, sizes, prev, pager, next, jumper" @current-change="paging.changePage" @size-change="paging.changeSize" />


    <el-dialog v-model="dlg" title="登记举报奖励（线索须已立案查实）" width="460px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="线索ID"><el-input-number v-model="form.clueId" :min="1" /></el-form-item>
        <el-form-item label="举报人"><el-input v-model="form.reporterName" /></el-form-item>
        <el-form-item label="联系方式"><el-input v-model="form.reporterContact" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.note" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button v-if="canOperate" type="primary" @click="onCreate">登记</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client from '../../api/client'
import { historyPageState, createHistoryPagination } from '../../api/historyPagination'
import { useAuthStore } from '../../stores/auth'
import { caseOperationAccess, leaderOperationAccess } from '../../api/caseWriteAccess'

const auth = useAuthStore()
const canOperate = computed(() => caseOperationAccess(auth.user?.roles))
const isLeader = computed(() => leaderOperationAccess(auth.user?.roles))

const pagination = reactive(historyPageState<any>())
const paging = createHistoryPagination(pagination, async (params, { signal }) => (await client.get('/bureau/rewards', { params, signal, historyRead: true })).data.data)
const rows = computed(() => pagination.rows)
onUnmounted(paging.dispose)
const loading = computed(() => pagination.loading)
const dlg = ref(false)
const form = reactive<any>({ clueId: undefined, reporterName: '', reporterContact: '', note: '' })

async function load() {
  await paging.load()
}

async function onCreate() {
  if (!canOperate.value) return
  if (!form.clueId || !form.reporterName) {
    ElMessage.warning('线索与举报人必填')
    return
  }
  await client.post('/bureau/rewards', form)
  ElMessage.success('已登记')
  dlg.value = false
  await paging.firstPage()
}

async function onApprove(row: any) {
  if (!isLeader.value) return
  const { value } = await ElMessageBox.prompt('奖励金额（元）', '奖励审批', { inputPattern: /^\d+(\.\d{1,2})?$/, inputErrorMessage: '金额无效' })
  if (!isLeader.value) return
  await client.post(`/bureau/rewards/${row.id}/approve`, { amount: Number(value) })
  ElMessage.success('已审批')
  load()
}

async function onPay(row: any) {
  if (!isLeader.value) return
  await client.post(`/bureau/rewards/${row.id}/pay`)
  ElMessage.success('已发放')
  load()
}

onMounted(load)
</script>

<style scoped>
.list-controls { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.toolbar { display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
