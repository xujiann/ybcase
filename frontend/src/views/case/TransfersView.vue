<template>
  <el-card>
    <div class="toolbar">
      <h3>移送台账<span class="hint">（无管辖权移送有权机关；涉嫌犯罪移送司法机关——行刑衔接）</span></h3>
      <div class="list-controls">
        <el-input v-model="pagination.q" clearable placeholder="关联编号/机关/理由" style="width: 220px" @keyup.enter="paging.search" @clear="paging.search" />
        <el-button @click="paging.search">搜索</el-button>
        <el-button @click="load">刷新</el-button>
        <el-button v-if="canOperate" type="primary" @click="dlg = true">登记移送</el-button>
      </div>
    </div>
    <el-alert v-if="pagination.failed" type="error" :closable="false" :title="pagination.error" style="margin-bottom: 12px" />
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
          <el-button v-else-if="canOperate" size="small" text type="primary" @click="onConfirm(row)">确认接收</el-button>
        </template>
      </el-table-column>
      <el-table-column label="受案回执（行刑衔接）" width="180">
        <template #default="{ row }">
          <span v-if="row.receipt_no">{{ row.receipt_no }}（{{ row.receipt_at }}）</span>
          <el-button v-else-if="canOperate && (row.kind === 'JUDICIAL')" size="small" text type="primary" @click="onReceipt(row)">登记回执</el-button>
          <span v-else class="hint">-</span>
        </template>
      </el-table-column>
      <el-table-column label="司法判决" width="200">
        <template #default="{ row }">
          <span v-if="row.judgment_no" :title="row.judgment_result">{{ row.judgment_no }}（{{ row.judgment_at }}）</span>
          <el-button v-else-if="canOperate && row.kind === 'JUDICIAL' && row.direction === 'OUT'"
                     text type="primary" size="small" @click="openJudgment(row)">登记判决</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination style="margin-top: 12px; justify-content: flex-end" :current-page="pagination.page" :page-size="pagination.size" :page-sizes="[20,50,100]" :total="pagination.total" layout="total, sizes, prev, pager, next, jumper" @current-change="paging.changePage" @size-change="paging.changeSize" />


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
        <el-button v-if="canOperate" type="primary" @click="onCreate">登记</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="judgmentVisible" title="登记司法判决" width="520px">
      <el-form label-width="100px">
        <el-form-item label="判决文号" required><el-input v-model="judgment.number" maxlength="128" /></el-form-item>
        <el-form-item label="判决日期" required><el-date-picker v-model="judgment.date" value-format="YYYY-MM-DD" /></el-form-item>
        <el-form-item label="判决结果" required><el-input v-model="judgment.result" type="textarea" :rows="4" maxlength="4000" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="judgmentVisible = false">取消</el-button>
        <el-button type="primary" :loading="judgmentSaving" @click="saveJudgment">保存</el-button>
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
import { caseOperationAccess } from '../../api/caseWriteAccess'

const auth = useAuthStore()
const canOperate = computed(() => caseOperationAccess(auth.user?.roles))

const KIND: Record<string, string> = { ADMIN: '其他行政机关', JUDICIAL: '司法机关', MSA: '医保部门间' }
const pagination = reactive(historyPageState<any>())
const paging = createHistoryPagination(pagination, async (params, { signal }) => (await client.get('/bureau/transfers', { params, signal, historyRead: true })).data.data)
const rows = computed(() => pagination.rows)
onUnmounted(paging.dispose)
const loading = computed(() => pagination.loading)
const dlg = ref(false)
const form = reactive<any>({ direction: 'OUT', kind: 'ADMIN', clueId: undefined, caseId: undefined, targetOrg: '', reason: '' })

async function load() {
  await paging.load()
}

async function onCreate() {
  if (!canOperate.value) return
  await client.post('/bureau/transfers', {
    ...form,
    clueId: form.clueId || null,
    caseId: form.caseId || null,
  })
  ElMessage.success('已登记')
  dlg.value = false
  await paging.firstPage()
}

async function onConfirm(row: any) {
  if (!canOperate.value) return
  await client.post(`/bureau/transfers/${row.id}/confirm`)
  load()
}

async function onReceipt(row: any) {
  if (!canOperate.value) return
  const { value } = await ElMessageBox.prompt('受案回执文号（公安/检察机关出具）', '登记回执', { inputPattern: /\S+/, inputErrorMessage: '必填' })
  if (!canOperate.value) return
  await client.post(`/bureau/transfers/${row.id}/receipt`, { receiptNo: value })
  ElMessage.success('回执已登记')
  load()
}

const judgmentVisible = ref(false)
const judgmentSaving = ref(false)
const judgment = reactive({ id: 0, number: '', date: '', result: '' })

function openJudgment(row: { id: number }) {
  if (!canOperate.value || judgmentSaving.value) return
  Object.assign(judgment, { id: row.id, number: '', date: '', result: '' })
  judgmentVisible.value = true
}

async function saveJudgment() {
  if (!canOperate.value || !judgmentVisible.value || judgmentSaving.value) return
  const { id, number, date, result } = judgment
  if (!number.trim() || !date || !result.trim()) {
    ElMessage.warning('请填写判决文号、日期和结果')
    return
  }
  const token = auth.token
  judgmentSaving.value = true
  try {
    await client.post(`/bureau/transfers/${id}/judgment`, { number, date, result })
    if (auth.token !== token || judgment.id !== id || !judgmentVisible.value) return
    judgmentVisible.value = false
    await load()
  } catch {
    // The shared client shows the request error and the dialog retains the draft.
  } finally {
    judgmentSaving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.list-controls { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.toolbar { display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
