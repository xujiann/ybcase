<template>
  <el-card>
    <div class="toolbar">
      <h3>行政检查<span class="hint">（清单第10-12项；发现违法一键转线索，打通 检查→线索→立案 闭环）</span></h3>
      <div class="list-controls">
        <el-input v-model="pagination.q" clearable placeholder="编号/对象/结果" style="width: 220px" @keyup.enter="paging.search" @clear="paging.search" />
        <el-button @click="paging.search">搜索</el-button>
        <el-button @click="load">刷新</el-button>
        <el-button v-if="canOperate" type="primary" @click="dlg = true">新建检查任务</el-button>
      </div>
    </div>
    <el-alert v-if="pagination.failed" type="error" :closable="false" :title="pagination.error" style="margin-bottom: 12px" />
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="insp_no" label="检查编号" width="110" />
      <el-table-column prop="item_name" label="检查事项" show-overflow-tooltip />
      <el-table-column label="任务类别" width="130">
        <template #default="{ row }">{{ row.task_level ? taskLevels[row.task_level] || row.task_level : '日常检查' }}</template>
      </el-table-column>
      <el-table-column prop="object_name" label="检查对象" width="160" show-overflow-tooltip />
      <el-table-column prop="officers" label="检查人员" width="150" show-overflow-tooltip />
      <el-table-column prop="planned_at" label="计划日期" width="105" />
      <el-table-column label="状态" width="200">
        <template #default="{ row }">
          <template v-if="!row.done_at">
            <el-tag size="small">待检查</el-tag>
            <el-button v-if="canOperate" size="small" text type="primary" @click="onComplete(row)">登记结果</el-button>
          </template>
          <template v-else>
            <el-tag size="small" :type="row.violation_found ? 'danger' : 'success'">
              {{ row.violation_found ? '发现违法' : '未见违法' }}
            </el-tag>
            <el-button v-if="canOperate && (row.violation_found && !row.clue_id)" size="small" text type="danger" @click="onToClue(row)">转线索</el-button>
            <span v-if="row.clue_id" class="hint">已转线索#{{ row.clue_id }}</span>
          </template>
        </template>
      </el-table-column>
      <el-table-column prop="result" label="检查结果" show-overflow-tooltip />
      <el-table-column label="材料" width="90"><template #default="{ row }"><el-button size="small" @click="openMaterials(row)">材料</el-button></template></el-table-column>
    </el-table>
    <el-pagination style="margin-top: 12px; justify-content: flex-end" :current-page="pagination.page" :page-size="pagination.size" :page-sizes="[20,50,100]" :total="pagination.total" layout="total, sizes, prev, pager, next, jumper" @current-change="paging.changePage" @size-change="paging.changeSize" />


    <PrecaseAttachments v-model:visible="materialsVisible" :source="materialsSource" />
    <el-dialog v-model="dlg" title="新建检查任务（检查人员不得少于2人）" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="检查事项">
          <el-select v-model="form.itemId" style="width: 100%">
            <el-option v-for="it in items" :key="it.id" :label="`${it.seq_no}. ${it.name}`" :value="it.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="检查任务类别">
          <el-select v-model="form.taskLevel" placeholder="日常检查">
            <el-option label="日常检查" :value="null" />
            <el-option v-for="(label, level) in taskLevels" :key="level" :label="label" :value="level" />
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
        <el-button v-if="canOperate" type="primary" @click="onCreate">创建</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="resultVisible" title="登记检查结果" width="520px"
               :close-on-click-modal="!resultSaving" :close-on-press-escape="!resultSaving" :show-close="!resultSaving">
      <el-form label-width="138px">
        <el-form-item label="是否发现违法违规" required>
          <el-radio-group v-model="resultForm.violationFound">
            <el-radio :value="false">未发现</el-radio>
            <el-radio :value="true">发现</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="检查结果" required>
          <el-input v-model="resultForm.result" type="textarea" :rows="4" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="resultSaving" @click="resultVisible = false">取消</el-button>
        <el-button v-if="canOperate" :loading="resultSaving" type="primary" @click="submitResult">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { sessionExpiry } from '../../api/sessionExpiry'
import client from '../../api/client'
import { historyPageState, createHistoryPagination } from '../../api/historyPagination'
import { useAuthStore } from '../../stores/auth'
import { caseOperationAccess } from '../../api/caseWriteAccess'
import PrecaseAttachments from '../../components/PrecaseAttachments.vue'
import type { PrecaseSource } from '../../api/precaseAttachments'
import { PARTY_TYPE } from './labels'

const auth = useAuthStore()
const canOperate = computed(() => caseOperationAccess(auth.user?.roles))
const taskLevels: Record<string, string> = {
  NATIONAL: '国家飞检', PROVINCIAL: '省飞检', MUNICIPAL: '市级专项检查', CITY_CROSS_CHECK: '市级交叉检查',
}

const materialsVisible = ref(false)
const materialsSource = ref<PrecaseSource>({ id: 0, type: 'inspections' })
function openMaterials(row: any) {
  materialsSource.value = { id: row.id, type: 'inspections', clueId: row.clue_id, owner: row.created_by }
  materialsVisible.value = true
}
const pagination = reactive(historyPageState<any>())
const paging = createHistoryPagination(pagination, async (params, { signal }) => (await client.get('/bureau/inspections', { params, signal, historyRead: true })).data.data)
const rows = computed(() => pagination.rows)
onUnmounted(paging.dispose)
const items = ref<any[]>([])
const loading = computed(() => pagination.loading)
const dlg = ref(false)
function todayLocal() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const form = reactive<any>({ itemId: null, objectName: '', objectType: 'PROVIDER', officers: '', plannedAt: todayLocal(), taskLevel: null })

async function load() {
  await paging.load()
}

async function onCreate() {
  if (!canOperate.value) return
  if (!form.objectName || !form.officers) {
    ElMessage.warning('检查对象与人员必填')
    return
  }
  await client.post('/bureau/inspections', form)
  ElMessage.success('已创建')
  dlg.value = false
  await paging.firstPage()
}

const resultVisible = ref(false)
const resultSaving = ref(false)
const resultForm = reactive<{ id: number | null; result: string; violationFound: boolean | null }>({
  id: null, result: '', violationFound: null,
})
let resultDisposed = false

watch(() => [auth.token, auth.user?.id], () => { resultVisible.value = false }, { flush: 'sync' })
onUnmounted(() => { resultDisposed = true })

function onComplete(row: { id: number; done_at?: string }) {
  if (!canOperate.value || row.done_at || resultSaving.value) return
  Object.assign(resultForm, { id: row.id, result: '', violationFound: null })
  resultVisible.value = true
}

async function submitResult() {
  if (!canOperate.value || !resultVisible.value || resultSaving.value || resultForm.id === null) return
  const { id, result, violationFound } = resultForm
  if (!result.trim() || typeof violationFound !== 'boolean') {
    ElMessage.warning('请选择是否发现违法违规并填写检查结果')
    return
  }
  const userId = auth.user?.id, stamp = sessionExpiry.capture(auth.token)
  if (sessionExpiry.isExpired(stamp, auth.token)) return
  resultSaving.value = true
  try {
    await client.post(`/bureau/inspections/${id}/complete`, { result: result.trim(), violationFound })
    if (resultDisposed || !resultVisible.value || userId !== auth.user?.id
      || !sessionExpiry.isCurrent(stamp, auth.token) || sessionExpiry.isExpired(stamp, auth.token)) return
    resultVisible.value = false
    ElMessage.success('已登记')
    await load()
  } catch {
    // The shared client reports errors while the user keeps their draft.
  } finally {
    resultSaving.value = false
  }
}

async function onToClue(row: any) {
  if (!canOperate.value) return
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
.list-controls { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.toolbar { display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
</style>
