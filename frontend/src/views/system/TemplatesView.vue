<template>
  <el-card>
    <div class="toolbar">
      <h3>文书模板<span class="hint">（要素+模板分离，改模板不改代码；每次修改留历史可回看）</span></h3>
    </div>
    <el-alert v-if="listFailed" type="error" :closable="false" title="模板列表加载失败，请重新进入本页重试。" />
    <el-table :data="rows" border stripe size="small" v-loading="loading" :empty-text="listFailed ? '加载失败' : '暂无数据'">
      <el-table-column prop="doc_type" label="类型" width="150" />
      <el-table-column prop="title_tpl" label="标题模板" show-overflow-tooltip />
      <el-table-column prop="updated_at" label="更新时间" width="170" />
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button v-if="isAdmin" size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button size="small" text @click="openHistory(row)">历史</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="editVisible" :title="`编辑模板：${form.docType}`" width="700px" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-width="90px" :disabled="saving">
        <el-form-item label="标题模板"><el-input v-model="form.titleTpl" /></el-form-item>
        <el-form-item label="正文模板">
          <el-input v-model="form.contentTpl" type="textarea" :rows="16" style="font-family: monospace" />
        </el-form-item>
      </el-form>
      <p class="hint">可用占位符：caseNo caseName partyName partyAddress partyCreditNo partyLegalRep partyTypeText summary amountInvolved causeText basisText orgName today statementDays hearingDays proposedFine proposedRecoup decisionNo decisionContent</p>
      <template #footer>
        <el-button :disabled="saving" @click="editVisible = false">取消</el-button>
        <el-button v-if="isAdmin" type="primary" :loading="saving" @click="onSave">保存（旧版自动入历史）</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="historyVisible" :title="`模板历史：${historyType}`" width="700px" @close="clearHistory">
      <p v-if="history.loading" class="hint">历史加载中</p>
      <el-alert v-if="history.failed" type="error" :closable="false" title="模板历史加载失败，请关闭后重新打开重试。" />
      <el-collapse>
        <el-collapse-item v-for="h in history.data" :key="h.id" :title="`${h.saved_at}　${h.saved_by || ''}`">
          <b>{{ h.title_tpl }}</b>
          <pre class="tpl">{{ h.content_tpl }}</pre>
        </el-collapse-item>
      </el-collapse>
      <p v-if="history.ready && !history.data.length" class="hint">暂无修改历史</p>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import { adminOperationAccess } from '../../api/caseWriteAccess'
import { resourceState } from '../../api/loadableResource'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../../api/sessionExpiry'

const auth = useAuthStore()
const isAdmin = computed(() => adminOperationAccess(auth.user?.roles))

const rows = ref<any[]>([])
const history = reactive(resourceState<any[]>([]))
function clearHistory() {
  historyGeneration++; history.data = []; history.ready = false; history.loading = false; history.failed = false
}
const loading = ref(false)
const listFailed = ref(false)
const saving = ref(false)
const editVisible = ref(false)
const historyVisible = ref(false)
const historyType = ref('')
const form = reactive({ docType: '', titleTpl: '', contentTpl: '' })
let disposed = false, loadGeneration = 0, saveGeneration = 0, historyGeneration = 0, editGeneration = 0

function identity() {
  return JSON.stringify([auth.token, auth.user?.id, auth.user?.username, [...(auth.user?.roles || [])].sort()])
}
function context() { return { identity: identity(), stamp: sessionExpiry.capture(auth.token) } }
function active() {
  return !disposed && !!auth.token && !!auth.user
    && !sessionExpiry.isExpired(sessionExpiry.capture(auth.token), auth.token)
}
function isContextCurrent(c: ReturnType<typeof context>) {
  return active() && c.identity === identity() && sessionExpiry.isCurrent(c.stamp, auth.token)
}
function invalidate(clearDraft = true) {
  loadGeneration++; saveGeneration++; editGeneration++
  rows.value = []; loading.value = false; listFailed.value = false; saving.value = false
  historyVisible.value = false; historyType.value = ''; clearHistory()
  if (clearDraft) { editVisible.value = false; Object.assign(form, { docType: '', titleTpl: '', contentTpl: '' }) }
}
function onSessionExpired() { invalidate(false) }
watch(identity, () => invalidate(), { flush: 'sync' })
watch(editVisible, visible => { if (!visible) { editGeneration++; saving.value = false } }, { flush: 'sync' })
onUnmounted(() => { disposed = true; invalidate(); window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired) })

async function load() {
  if (!active()) return
  const run = ++loadGeneration, captured = context()
  loading.value = true
  listFailed.value = false
  rows.value = []
  try {
    const resp = await client.get('/bureau/doc-templates')
    if (run === loadGeneration && isContextCurrent(captured)) rows.value = resp.data.data
  } catch {
    if (run === loadGeneration && isContextCurrent(captured)) { rows.value = []; listFailed.value = true }
  } finally {
    if (run === loadGeneration) loading.value = false
  }
}

function openEdit(row: any) {
  if (!isAdmin.value || !active() || saving.value || loading.value || listFailed.value) return
  editGeneration++
  form.docType = row.doc_type
  form.titleTpl = row.title_tpl
  form.contentTpl = row.content_tpl
  editVisible.value = true
}

async function onSave() {
  if (!isAdmin.value || !active() || saving.value || !editVisible.value) return
  const run = ++saveGeneration, draft = editGeneration, captured = context(), snapshot = { ...form }
  saving.value = true
  const docType = form.docType, payload = { titleTpl: form.titleTpl, contentTpl: form.contentTpl }
  try {
    await client.put(`/bureau/doc-templates/${docType}`, payload)
    if (run !== saveGeneration || draft !== editGeneration || !isContextCurrent(captured) || !editVisible.value || JSON.stringify(form) !== JSON.stringify(snapshot)) return
    ElMessage.success('已保存并归档旧版')
    editVisible.value = false
    saving.value = true
    await load()
  } finally { if (run === saveGeneration) saving.value = false }
}

async function openHistory(row: any) {
  if (!active()) return
  clearHistory()
  historyType.value = row.doc_type
  historyVisible.value = true
  const run = historyGeneration, captured = context(), docType = row.doc_type
  history.loading = true
  try {
    const resp = await client.get(`/bureau/doc-templates/${docType}/history`)
    if (run === historyGeneration && isContextCurrent(captured) && historyVisible.value && historyType.value === docType) {
      history.data = resp.data.data; history.ready = true
    }
  } catch {
    if (run === historyGeneration && isContextCurrent(captured)) history.failed = true
  } finally { if (run === historyGeneration) history.loading = false }
}

onMounted(() => { window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired); load() })
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
.tpl { white-space: pre-wrap; background: #f8f9fb; padding: 8px; font-size: 12px; }
</style>
