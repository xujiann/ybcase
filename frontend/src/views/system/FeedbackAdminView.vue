<template>
  <el-card>
    <div class="toolbar">
      <h3>反馈管理<span class="hint">（试点反馈是版本排期的原始输入；处理结果自动通知提交人）</span></h3>
      <div>
        <el-select v-model="fStatus" clearable placeholder="全部状态" style="width: 110px; margin-right: 8px" @change="load">
          <el-option v-for="(v, k) in STATUS" :key="k" :label="v" :value="k" />
        </el-select>
        <el-select v-model="fKind" clearable placeholder="全部类型" style="width: 110px; margin-right: 8px" @change="load">
          <el-option v-for="(v, k) in KIND" :key="k" :label="v" :value="k" />
        </el-select>
        <el-button @click="onExport">导出CSV</el-button>
      </div>
    </div>

    <el-row :gutter="8" class="mb">
      <el-col :span="10">
        <el-card shadow="never"><b>状态分布：</b>
          <el-tag v-for="s in d.byStatus" :key="s.status" size="small" style="margin-right: 6px"
                  :type="(STATUS_TAG[s.status] as any)">{{ STATUS[s.status] }} {{ s.cnt }}</el-tag>
        </el-card>
      </el-col>
      <el-col :span="14">
        <el-card shadow="never"><b>BUG 高发页面：</b>
          <span v-for="p in d.byPage" :key="p.page_route" class="hint" style="margin-right: 10px">
            {{ p.page_route }}（{{ p.cnt }}）</span>
          <span v-if="!d.byPage?.length" class="hint">暂无</span>
        </el-card>
      </el-col>
    </el-row>

    <el-table :data="d.rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="id" label="#" width="55" />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ KIND[row.kind] }}</template>
      </el-table-column>
      <el-table-column prop="title" label="标题" width="200" show-overflow-tooltip />
      <el-table-column prop="username" label="提交人" width="90" />
      <el-table-column prop="page_route" label="页面" width="140" show-overflow-tooltip />
      <el-table-column label="上下文" width="150">
        <template #default="{ row }">
          <el-button v-if="row.recent_errors || row.has_screenshot" size="small" text type="primary"
                     @click="viewContext(row)">错误/截图</el-button>
          <span v-else class="hint">-</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="(STATUS_TAG[row.status] as any)">{{ STATUS[row.status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reply" label="回复" show-overflow-tooltip />
      <el-table-column label="处理" width="100">
        <template #default="{ row }">
          <el-button v-if="!['CLOSED'].includes(row.status)" size="small" type="primary"
                     @click="openHandle(row)">处理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="handleVisible" :title="`处理反馈 #${cur.id}：${cur.title}`" width="560px">
      <p style="white-space: pre-wrap">{{ cur.content }}</p>
      <p class="hint" v-if="cur.request_id">request-id：{{ cur.request_id }}（可在审计日志/服务端日志检索）</p>
      <el-form label-width="70px">
        <el-form-item label="状态">
          <el-radio-group v-model="handleForm.status">
            <el-radio value="PROCESSING">处理中</el-radio>
            <el-radio value="RESOLVED">已解决</el-radio>
            <el-radio value="REJECTED">不采纳</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="回复"><el-input v-model="handleForm.reply" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="primary" @click="onHandle">保存并通知提交人</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="ctxVisible" title="反馈上下文" width="640px">
      <img v-if="cur.has_screenshot" :src="shotUrl" style="max-width: 100%" class="mb" />
      <pre v-if="cur.recent_errors" class="ctx">{{ prettyErrors(cur.recent_errors) }}</pre>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const KIND: Record<string, string> = { BUG: '问题缺陷', FEATURE: '功能需求', QUESTION: '使用咨询' }
const STATUS: Record<string, string> = { NEW: '待处理', PROCESSING: '处理中', RESOLVED: '已解决', REJECTED: '不采纳', CLOSED: '已关闭' }
const STATUS_TAG: Record<string, string> = { NEW: 'warning', PROCESSING: 'primary', RESOLVED: 'success', REJECTED: 'info', CLOSED: 'info' }
const d = ref<any>({ rows: [] })
const loading = ref(false)
const fStatus = ref('')
const fKind = ref('')
const handleVisible = ref(false)
const ctxVisible = ref(false)
const cur = ref<any>({})
const shotUrl = ref('')
const handleForm = reactive({ status: 'PROCESSING', reply: '' })

async function load() {
  loading.value = true
  try {
    const params: any = {}
    if (fStatus.value) params.status = fStatus.value
    if (fKind.value) params.kind = fKind.value
    d.value = (await client.get('/bureau/feedback', { params })).data.data
  } finally {
    loading.value = false
  }
}

function openHandle(row: any) {
  cur.value = row
  handleForm.status = row.status === 'NEW' ? 'PROCESSING' : row.status
  handleForm.reply = row.reply || ''
  handleVisible.value = true
}

async function onHandle() {
  await client.post(`/bureau/feedback/${cur.value.id}/handle`, handleForm)
  ElMessage.success('已处理并通知提交人')
  handleVisible.value = false
  load()
}

async function viewContext(row: any) {
  cur.value = row
  shotUrl.value = ''
  if (row.has_screenshot) {
    const resp = await client.get(`/bureau/feedback/${row.id}/screenshot`, { responseType: 'blob' })
    shotUrl.value = URL.createObjectURL(resp.data)
  }
  ctxVisible.value = true
}

function prettyErrors(s: string) {
  try {
    return JSON.stringify(JSON.parse(s), null, 2)
  } catch {
    return s
  }
}

async function onExport() {
  const resp = await client.get('/bureau/feedback/export', { responseType: 'blob' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(resp.data)
  a.download = 'feedback.csv'
  a.click()
  URL.revokeObjectURL(a.href)
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
.mb { margin-bottom: 12px; }
.ctx { background: #f8f9fb; padding: 10px; font-size: 12px; white-space: pre-wrap; }
</style>
