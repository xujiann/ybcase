<template>
  <el-dialog :model-value="visible" :title="includeVoided ? '证据材料追溯目录（含作废记录）' : '有效证据卷目录'" width="1100px" style="max-width: 95vw" @update:model-value="$emit('update:visible', $event)">
    <el-alert type="info" :closable="false" title="文字索引使用固定材料编号；附件正文需单独下载，不将本目录视为二进制材料原件。" />
    <div style="margin: 12px 0">
      <el-checkbox v-if="manager" v-model="includeVoided" @change="load">包含作废记录（追溯）</el-checkbox>
      <el-button :disabled="loading" @click="load">刷新</el-button><el-button :disabled="loading || !ready" @click="printIndex">{{ includeVoided ? '打印追溯索引' : '打印有效卷索引' }}</el-button>
    </div>
    <el-table :data="items" v-loading="loading" border size="small" :max-height="440">
      <el-table-column prop="number" label="材料编号" width="145" />
      <el-table-column label="类型" width="90"><template #default="{ row }">{{ row.kind === 'EVIDENCE' ? '证据记录' : '附件' }}</template></el-table-column>
      <el-table-column label="名称" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ row.title || row.filename }}</template></el-table-column>
      <el-table-column label="关联与来源" min-width="180"><template #default="{ row }">{{ catalogAssociation(row) }}</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{ row }">{{ row.voided ? '已作废' : '有效' }}</template></el-table-column>
      <el-table-column prop="voidReason" label="作废理由" min-width="120" show-overflow-tooltip />
      <el-table-column label="操作" width="90"><template #default="{ row }"><el-button v-if="row.kind !== 'EVIDENCE' && !row.voided" size="small" text @click="download(row)">下载</el-button></template></el-table-column>
    </el-table>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import client, { LARGE_TRANSFER } from '../api/client'
import { useAuthStore } from '../stores/auth'
import { catalogAssociation, evidenceCatalogHtml, type EvidenceCatalogItem } from '../api/evidenceCatalog'
const props = defineProps<{ visible: boolean; caseId: string }>()
defineEmits<{ 'update:visible': [visible: boolean] }>()
const auth = useAuthStore()
const manager = computed(() => !!auth.user?.roles?.some(r => ['LEADER','ADMIN'].includes(r)))
const items = ref<EvidenceCatalogItem[]>([])
const includeVoided = ref(false)
const loading = ref(false)
const ready = ref(false)
let generation = 0
let disposed = false
const downloading = new Set<number>()
function current(run: number, caseId: string) { return !disposed && run === generation && props.visible && caseId === props.caseId }
async function load() {
  if (disposed || !props.visible) return
  const run = ++generation, caseId = props.caseId
  loading.value = true; ready.value = false; items.value = []
  try {
    const result = (await client.get(`/bureau/cases/${caseId}/evidence-catalog`, { params: { includeVoided: manager.value && includeVoided.value } })).data.data
    if (!Array.isArray(result?.items)) throw new Error('Invalid evidence catalog')
    if (current(run, caseId)) { items.value = result.items; ready.value = true }
  } catch {
    if (current(run, caseId)) { items.value = []; ready.value = false }
  } finally { if (run === generation) loading.value = false }
}
watch(() => [props.visible, props.caseId, auth.user?.username, auth.user?.roles?.join('|')], () => {
  generation++; items.value = []; ready.value = false; loading.value = false; includeVoided.value = false
  if (props.visible) load()
}, { immediate: true, flush: 'sync' })
onUnmounted(() => { disposed = true; generation++; items.value = []; ready.value = false; loading.value = false })
async function download(item: EvidenceCatalogItem) {
  if (!ready.value || loading.value || disposed || !props.visible || item.voided || item.kind === 'EVIDENCE' || downloading.has(item.id) || !items.value.some(r => r.id === item.id && r.kind === item.kind)) return
  const run = generation, caseId = props.caseId, attachmentId = item.id, filename = item.filename || item.title || item.number
  downloading.add(attachmentId)
  try {
    const resp = await client.get(`/bureau/cases/attachments/${attachmentId}/download`, { responseType: 'blob', ...LARGE_TRANSFER })
    if (!current(run, caseId)) return
    const url = URL.createObjectURL(resp.data), a = document.createElement('a')
    a.href = url; a.download = filename; a.click(); URL.revokeObjectURL(url)
  } catch { /* A failed or obsolete download must never create a bogus file. */ }
  finally { downloading.delete(attachmentId) }
}
function printIndex() {
  if (disposed || !props.visible || !ready.value || loading.value) return
  const w = window.open('', '_blank')
  if (!w) return
  w.document.write(`<html><head><title>证据卷目录</title><style>body{font-family:SimSun,serif;padding:24px}table{border-collapse:collapse;width:100%}td,th{border:1px solid #333;padding:5px;overflow-wrap:anywhere}</style></head><body>${evidenceCatalogHtml(items.value, includeVoided.value)}</body></html>`)
  w.document.close(); w.print()
}
</script>
