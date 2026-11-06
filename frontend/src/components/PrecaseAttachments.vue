<template>
  <el-dialog :model-value="visible" :title="source.type === 'clues' ? '线索材料' : '行政检查材料'" width="1100px" style="max-width: 95vw" @update:model-value="$emit('update:visible', $event)">
    <el-alert type="info" :closable="false" title="保留原上传人、时间与来源，转线索和立案后自动沿用同一份材料；作废保留追溯记录。" />
    <p v-if="linkedCaseId" class="hint">材料已归入案件#{{ linkedCaseId }}，源材料入口只读。<el-button text @click="$router.push(`/case/detail/${linkedCaseId}`)">打开案件管理材料</el-button></p>
    <p v-else-if="!writable" class="hint">当前来源状态或岗位为只读；已转线索的检查材料请在线索中继续办理。</p>
    <div style="display: flex; gap: 8px; align-items: center; margin: 12px 0; flex-wrap: wrap">
      <template v-if="writable">
        <el-select v-model="category" :disabled="uploading || voiding" style="width: 160px"><el-option label="扫描件" value="DOC_SCAN" /><el-option label="执法音像" value="AV_RECORD" /><el-option label="其他" value="OTHER" /></el-select>
        <el-button :loading="uploading" :disabled="uploading || voiding" @click="openUpload">上传材料</el-button>
        <input ref="fileInput" type="file" style="display:none" @change="upload" />
      </template>
      <el-checkbox v-if="manager" v-model="includeVoided" @change="load">包含作废记录</el-checkbox>
      <el-button :disabled="loading || uploading || voiding" @click="load">刷新</el-button>
    </div>
    <el-table :data="rows" v-loading="loading" border size="small" :max-height="440">
      <el-table-column prop="id" label="材料ID" width="90" />
      <el-table-column prop="filename" label="文件名" min-width="180" show-overflow-tooltip />
      <el-table-column label="来源" min-width="150"><template #default="{ row }">{{ [row.source_inspection_id ? `检查#${row.source_inspection_id}` : '', row.source_clue_id ? `线索#${row.source_clue_id}` : ''].filter(Boolean).join('；') }}</template></el-table-column>
      <el-table-column label="大小" width="90"><template #default="{ row }">{{ Math.ceil(row.size_bytes / 1024) }} KB</template></el-table-column>
      <el-table-column prop="uploaded_by" label="上传人" width="100" />
      <el-table-column label="上传时间" width="155"><template #default="{ row }">{{ fmtTime(row.uploaded_at) }}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{ row }">{{ row.voided ? '已作废' : '有效' }}</template></el-table-column>
      <el-table-column prop="void_reason" label="作废理由" min-width="120" show-overflow-tooltip />
      <el-table-column label="操作" width="140"><template #default="{ row }">
        <el-button v-if="!row.voided" size="small" text @click="download(row)">下载</el-button>
        <el-button v-if="canVoidPrecase(auth.user?.roles, auth.user?.username, source, row)" :disabled="voiding || uploading" size="small" text type="danger" @click="voidFile(row)">作废</el-button>
      </template></el-table-column>
    </el-table>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import client, { LARGE_TRANSFER } from '../api/client'
import { useAuthStore } from '../stores/auth'
import { canWritePrecase, canVoidPrecase, type PrecaseSource } from '../api/precaseAttachments'
import { fmtTime } from '../views/case/labels'
const props = defineProps<{ visible: boolean; source: PrecaseSource }>()
defineEmits<{ 'update:visible': [visible: boolean] }>()
const auth = useAuthStore()
const rows = ref<any[]>([]), loading = ref(false), ready = ref(false), uploading = ref(false), voiding = ref(false), includeVoided = ref(false)
const category = ref('DOC_SCAN'), fileInput = ref<HTMLInputElement>()
const manager = computed(() => !!auth.user?.roles?.some(r => ['LEADER','ADMIN'].includes(r)))
const linkedCaseId = computed(() => rows.value.find(row => row.case_id)?.case_id)
const writable = computed(() => ready.value && !loading.value && canWritePrecase(auth.user?.roles, auth.user?.username, props.source, !!linkedCaseId.value))
let generation = 0
let disposed = false
let loadedContext = ''
let picker: { key: string; generation: number } | null = null
const downloading = new Set<number>()
function sourceKey() { return `${props.source.type}/${props.source.id}` }
function contextKey() {
  return JSON.stringify([props.source.type, props.source.id, props.source.status, props.source.clueId,
    props.source.owner, props.source.handler, auth.user?.username, auth.user?.roles?.join('|')])
}
function current(run: number, key: string) {
  return !disposed && run === generation && key === sourceKey() && props.visible && loadedContext === contextKey()
}
async function load() {
  if (disposed || !props.visible || !Number.isSafeInteger(props.source.id) || props.source.id <= 0) return
  const run = ++generation, key = sourceKey()
  loadedContext = contextKey()
  loading.value = true; ready.value = false; rows.value = []; picker = null
  try {
    const data = (await client.get(`/bureau/${key}/attachments`, { params: { includeVoided: manager.value && includeVoided.value } })).data.data
    if (!Array.isArray(data)) throw new Error('Invalid attachment list')
    if (current(run, key)) { rows.value = data; ready.value = true }
  } catch {
    // The interceptor reports failures. A failed refresh must not retain actionable old rows.
    if (current(run, key)) { rows.value = []; ready.value = false }
  } finally { if (run === generation) loading.value = false }
}
// A parent's v-model and source props are patched separately. Load only after
// the batch is complete, so opening a dialog cannot request its previous id 0.
watch(() => [props.visible, props.source.type, props.source.id, props.source.status, props.source.clueId,
  props.source.owner, props.source.handler, auth.user?.username, auth.user?.roles?.join('|')], () => {
  generation++; rows.value = []; ready.value = false; loading.value = false; picker = null
  includeVoided.value = false; category.value = 'DOC_SCAN'
  if (fileInput.value) fileInput.value.value = ''
  if (props.visible) load()
}, { immediate: true, flush: 'post' })
onUnmounted(() => { disposed = true; generation++; picker = null; rows.value = []; ready.value = false; loading.value = false })
function openUpload() {
  if (disposed || !props.visible || !writable.value || uploading.value || voiding.value) return
  picker = { key: sourceKey(), generation }
  if (fileInput.value) fileInput.value.value = ''
  fileInput.value?.click()
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  const selected = picker; picker = null
  if (!file || !selected || !current(selected.generation, selected.key) || uploading.value || voiding.value || !writable.value) { input.value = ''; return }
  const { key, generation: run } = selected, data = new FormData()
  data.append('file', file); data.append('category', category.value)
  uploading.value = true
  try {
    await client.post(`/bureau/${key}/attachments`, data, { ...LARGE_TRANSFER })
    if (!current(run, key)) return
    ElMessage.success('材料已上传')
    await load()
  } catch {
    // Preserve the current context; uploads are never retried automatically.
  } finally { uploading.value = false; input.value = '' }
}
async function download(row: any) {
  if (!ready.value || loading.value || disposed || !props.visible || row.voided || downloading.has(row.id) || !rows.value.some(r => r.id === row.id)) return
  const run = generation, key = sourceKey(), filename = row.filename, attachmentId = row.id
  downloading.add(attachmentId)
  try {
    const resp = await client.get(`/bureau/precase-attachments/${attachmentId}/download`, { responseType: 'blob', ...LARGE_TRANSFER })
    if (!current(run, key)) return
    const url = URL.createObjectURL(resp.data), a = document.createElement('a')
    a.href = url; a.download = filename; a.click(); URL.revokeObjectURL(url)
  } catch { /* The interceptor reports download errors; do not create a bogus file. */ }
  finally { downloading.delete(attachmentId) }
}
async function voidFile(row: any) {
  if (!ready.value || disposed || !props.visible || uploading.value || voiding.value || !canVoidPrecase(auth.user?.roles, auth.user?.username, props.source, row)) return
  const key = sourceKey(), run = generation, attachmentId = row.id
  voiding.value = true
  try {
    const { value } = await ElMessageBox.prompt('请填写作废理由（原材料及记录保留）', '作废材料', { inputPattern: /\S+/, inputErrorMessage: '理由必填' })
    if (!current(run, key) || !canVoidPrecase(auth.user?.roles, auth.user?.username, props.source, row)) return
    await client.post(`/bureau/precase-attachments/${attachmentId}/void`, { reason: value })
    if (!current(run, key)) return
    ElMessage.success('已作废并保留记录')
    await load()
  } catch { /* Includes cancellation and failures already reported by the interceptor. */ }
  finally { voiding.value = false }
}
</script>
