<template>
  <el-card>
    <div class="toolbar">
      <h3>约谈台账</h3>
      <el-input-number v-model="year" :min="1900" :max="currentYear" :precision="0" aria-label="约谈年度" />
      <el-button :loading="loading" @click="load">刷新</el-button>
      <el-button v-if="canWrite" type="primary" :disabled="saving" @click="open()">登记约谈</el-button>
    </div>
    <el-alert v-if="failed" type="warning" :closable="false" title="台账加载失败，请核对账号归类或刷新重试。" />
    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="interviewDate" label="约谈时间" width="112" />
      <el-table-column prop="departmentName" label="约谈部门" min-width="125" show-overflow-tooltip />
      <el-table-column prop="institutionCode" label="机构编码" width="130" show-overflow-tooltip />
      <el-table-column prop="institutionName" label="机构名称" min-width="150" show-overflow-tooltip />
      <el-table-column prop="interviewType" label="约谈类型" width="110" show-overflow-tooltip />
      <el-table-column prop="reason" label="约谈事由" min-width="130" show-overflow-tooltip />
      <el-table-column prop="content" label="约谈内容" min-width="160" show-overflow-tooltip />
      <el-table-column prop="participants" label="约谈人员" min-width="110" show-overflow-tooltip />
      <el-table-column label="操作" width="76">
        <template #default="{ row }">
          <el-button v-if="canWrite && row.editable" link type="primary" :disabled="saving" @click="open(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination class="pagination" :current-page="page" :page-size="pageSize" :total="total"
                   :disabled="loading" layout="total, prev, pager, next" @current-change="changePage" />

    <el-dialog v-model="visible" :title="editing ? '编辑约谈' : '登记约谈'" width="640px" style="max-width: 95vw"
               :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <p>约谈部门取当前账号所属单位或处室。请填写已经开展的约谈及实际日期。</p>
      <el-form label-width="94px">
        <el-form-item label="机构编码" required><el-input v-model="form.institutionCode" maxlength="64" /></el-form-item>
        <el-form-item label="机构名称" required><el-input v-model="form.institutionName" maxlength="128" /></el-form-item>
        <el-form-item label="约谈类型" required><el-input v-model="form.interviewType" maxlength="64" /></el-form-item>
        <el-form-item label="约谈事由" required><el-input v-model="form.reason" type="textarea" :rows="2" maxlength="1000" /></el-form-item>
        <el-form-item label="约谈内容" required><el-input v-model="form.content" type="textarea" :rows="3" maxlength="12000" /></el-form-item>
        <el-form-item label="约谈人员" required><el-input v-model="form.participants" maxlength="512" /></el-form-item>
        <el-form-item label="约谈时间" required><el-date-picker v-model="form.interviewDate" value-format="YYYY-MM-DD" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="visible = false">取消</el-button>
        <el-button v-if="canWrite" type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { caseOperationAccess } from '../../api/caseWriteAccess'
import { localDate } from '../../api/dates'
import { useAuthStore } from '../../stores/auth'

interface Interview {
  institutionCode: string
  institutionName: string
  interviewType: string
  reason: string
  content: string
  participants: string
  interviewDate: string
}
interface InterviewRow extends Interview {
  id: number
  departmentName: string
  editable: boolean
}

const auth = useAuthStore()
const identity = computed(() => JSON.stringify([auth.token, auth.user?.id, auth.user?.accountCategory, auth.user?.roles]))
const canWrite = computed(() => !!auth.user?.accountCategory && caseOperationAccess(auth.user.roles))
const currentYear = new Date().getFullYear()
const year = ref<number | undefined>(currentYear)
const rows = ref<InterviewRow[]>([])
const page = ref(1)
const pageSize = 50
const total = ref(0)
const loading = ref(false)
const failed = ref(false)
const saving = ref(false)
const visible = ref(false)
const editing = ref<number | null>(null)
const form = reactive<Interview>(blank())
let active = true
let readGeneration = 0

function blank(): Interview {
  return { institutionCode: '', institutionName: '', interviewType: '', reason: '',
    content: '', participants: '', interviewDate: localDate() }
}

async function load() {
  const run = ++readGeneration, who = identity.value, selectedYear = year.value, selectedPage = page.value
  rows.value = []
  failed.value = false
  loading.value = false
  if (!auth.user || !selectedYear) return
  loading.value = true
  const isCurrent = () => active && run === readGeneration && who === identity.value
    && selectedYear === year.value && selectedPage === page.value
  try {
    const { data } = await client.get('/bureau/interviews/page',
      { params: { year: selectedYear, page: selectedPage, size: pageSize } })
    if (!isCurrent()) return
    total.value = data.data.total
    const lastPage = Math.max(1, Math.ceil(total.value / pageSize))
    if (selectedPage > lastPage) {
      changePage(lastPage)
      return
    }
    rows.value = data.data.rows
  } catch {
    if (isCurrent()) failed.value = true
  } finally {
    if (isCurrent()) loading.value = false
  }
}

function changePage(next: number) {
  if (page.value === next) return
  page.value = next
  void load()
}

function resetPages() {
  total.value = 0
  page.value = 1
  void load()
}

function open(row?: InterviewRow) {
  if (!canWrite.value || saving.value || (row && !row.editable)) return
  editing.value = row?.id ?? null
  const draft = blank()
  if (row) {
    for (const field of Object.keys(draft) as (keyof Interview)[]) draft[field] = row[field]
  }
  Object.assign(form, draft)
  visible.value = true
}

async function save() {
  if (!active || !visible.value || !canWrite.value || saving.value) return
  const who = identity.value, id = editing.value, payload = { ...form }
  if (Object.values(payload).some(value => !value?.trim()) || payload.interviewDate > localDate()) {
    ElMessage.warning('请填写完整的约谈信息，约谈日期不得晚于今天')
    return
  }
  saving.value = true
  try {
    if (id === null) await client.post('/bureau/interviews', payload)
    else await client.put(`/bureau/interviews/${id}`, payload)
    if (!active || who !== identity.value) return
    ElMessage.success('约谈记录已保存')
    visible.value = false
    const savedYear = Number(payload.interviewDate.slice(0, 4))
    if (year.value === savedYear) await load()
    else year.value = savedYear
  } catch {
    // The shared client reports errors and the draft remains open.
  } finally {
    saving.value = false
  }
}

watch(identity, () => {
  visible.value = false
  editing.value = null
  Object.assign(form, blank())
  resetPages()
}, { immediate: true, flush: 'sync' })
watch(year, resetPages)
onBeforeUnmount(() => { active = false })
</script>

<style scoped>
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-bottom: 12px; }
.toolbar h3 { margin: 0 auto 0 0; }
.pagination { margin-top: 12px; }
</style>
