<template>
  <el-dialog
    :model-value="true" title="批量导入账号名册" width="760px"
    :close-on-click-modal="false" :close-on-press-escape="!applying"
    :show-close="!applying" @update:model-value="close"
  >
    <div v-if="result.length">
      <el-alert type="success" :closable="false" :title="`已处理 ${result.length} 条账号记录`" />
      <p v-if="result.some(row => row.initialPassword)">
        请通过受控渠道将初始密码交给对应人员。关闭后此页不再显示密码，遗失时请在用户管理中重置。
      </p>
      <el-table :data="result" border max-height="360">
        <el-table-column prop="row" label="记录序号" width="110" />
        <el-table-column prop="username" label="登录账号" />
        <el-table-column label="初始密码">
          <template #default="{ row }">{{ row.initialPassword || '原账号密码保留' }}</template>
        </el-table-column>
      </el-table>
    </div>
    <template v-else>
      <p>选择 UTF-8 编码的 CSV 文件，每次 1 至 100 条。首行按以下顺序填写。</p>
      <p><code>{{ ROSTER_HEADER }}</code></p>
      <ul>
        <li>CREATE 新建账号，填写姓名和角色编码。多个角色用半角竖线 <code>|</code> 分隔。</li>
        <li>CLASSIFY 归类已有的待归类账号，姓名须与原账号一致，角色编码留空，保留原角色和密码。</li>
        <li>机构编码填写已启用的正式机构编码。账号类别填写 DISTRICT（区市县）、BUREAU（局机关）或 AGENCY（医保中心）。</li>
      </ul>
      <label>选择名册文件
        <input type="file" accept=".csv,text/csv" :disabled="applying" @change="chooseFile" />
      </label>
      <p v-if="fileName">已选文件 {{ fileName }}</p>
      <el-alert v-if="fileError" type="error" :closable="false" :title="fileError" class="roster-status" />
      <el-alert v-if="applyError" type="warning" :closable="false" :title="applyError" class="roster-status" />
      <el-table v-if="preview.length" :data="preview" border max-height="310" class="roster-status">
        <el-table-column prop="row" label="记录序号" width="110" />
        <el-table-column prop="username" label="登录账号" width="150" />
        <el-table-column label="校验结果" width="110">
          <template #default="{ row }">{{ row.status === 'READY' ? '通过' : '有误' }}</template>
        </el-table-column>
        <el-table-column prop="message" label="说明" />
      </el-table>
    </template>
    <template #footer>
      <el-button :disabled="applying" @click="close(false)">{{ result.length ? '关闭' : '取消' }}</el-button>
      <template v-if="!result.length">
        <el-button :loading="previewing" :disabled="!rows.length || reading || applying" @click="previewRoster">校验预览</el-button>
        <el-button type="primary" :loading="applying" :disabled="!canApply" @click="applyRoster">确认导入</el-button>
      </template>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import client from '../api/client'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'
import { parseUserRoster, ROSTER_HEADER, ROSTER_MAX_BYTES } from '../api/userRoster'
import type { RosterPreview, RosterResult, RosterRow } from '../api/userRoster'
import { useAuthStore } from '../stores/auth'
import { adminOperationAccess } from '../api/caseWriteAccess'

const emit = defineEmits<{ close: []; applied: [] }>()
const auth = useAuthStore()
const rows = ref<RosterRow[]>([])
const preview = ref<RosterPreview[]>([])
const result = ref<RosterResult[]>([])
const fileName = ref('')
const fileError = ref('')
const applyError = ref('')
const reading = ref(false)
const previewing = ref(false)
const applying = ref(false)
const canApply = computed(() => rows.value.length > 0 && preview.value.length === rows.value.length
  && preview.value.every((row, index) => row.status === 'READY'
    && row.row === index + 1 && row.username === rows.value[index].username)
  && !reading.value && !previewing.value && !applying.value)

let active = true
let generation = 0
const authorized = () => !!auth.token && adminOperationAccess(auth.user?.roles)
const identity = () => JSON.stringify([auth.token, auth.user?.id, auth.user?.username, authorized()])
function capture() {
  return { generation: ++generation, identity: identity(), stamp: sessionExpiry.capture(auth.token) }
}
function current(snapshot: ReturnType<typeof capture>) {
  return active && authorized() && generation === snapshot.generation && identity() === snapshot.identity
    && sessionExpiry.isCurrent(snapshot.stamp, auth.token) && !sessionExpiry.isExpired(snapshot.stamp, auth.token)
}
function reset() {
  generation++
  rows.value = []; preview.value = []; result.value = []
  fileName.value = ''; fileError.value = ''; applyError.value = ''
  reading.value = false; previewing.value = false; applying.value = false
}
function dismiss() {
  active = false
  reset()
  emit('close')
}
function close(visible: boolean) {
  if (!visible && !applying.value) dismiss()
}
watch(identity, dismiss, { flush: 'sync' })
onMounted(() => window.addEventListener(SESSION_EXPIRED_EVENT, dismiss))
onUnmounted(() => {
  active = false
  reset()
  window.removeEventListener(SESSION_EXPIRED_EVENT, dismiss)
})

async function chooseFile(event: Event) {
  if (!active || !authorized() || applying.value) return
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  reset()
  if (!file) return
  fileName.value = file.name
  if (file.size > ROSTER_MAX_BYTES) {
    fileError.value = 'CSV 文件最大 256 KB'
    return
  }
  const snapshot = capture()
  reading.value = true
  try {
    const bytes = await file.arrayBuffer()
    if (current(snapshot)) rows.value = parseUserRoster(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
  } catch (error) {
    if (current(snapshot)) fileError.value = error instanceof TypeError
      ? '请选择 UTF-8 编码的 CSV 文件'
      : error instanceof Error ? error.message : '读取 CSV 文件失败'
  } finally {
    if (current(snapshot)) reading.value = false
  }
}

async function previewRoster() {
  if (!active || !authorized() || !rows.value.length || reading.value || applying.value || previewing.value) return
  const snapshot = capture()
  preview.value = []
  applyError.value = ''
  previewing.value = true
  try {
    const response = await client.post<{ data: { rows: RosterPreview[] } }>('/system/users/roster/preview', { rows: rows.value })
    if (current(snapshot) && Array.isArray(response.data.data?.rows)) preview.value = response.data.data.rows
  } catch {
    // The shared client displays the API error. Keep this batch unconfirmed.
  } finally {
    if (current(snapshot)) previewing.value = false
  }
}

async function applyRoster() {
  if (!active || !authorized() || !canApply.value) return
  const snapshot = capture()
  applying.value = true
  preview.value = []
  try {
    const response = await client.post<{ data: { rows: RosterResult[] } }>('/system/users/roster/apply', { rows: rows.value }, { timeout: 90000 })
    if (!current(snapshot)) return
    const applied = response.data.data?.rows
    if (!Array.isArray(applied) || applied.length !== rows.value.length || !applied.every((row, index) =>
      row.row === index + 1 && row.username === rows.value[index].username
      && (rows.value[index].action === 'CREATE' ? !!row.initialPassword : !row.initialPassword))) {
      throw new Error('Invalid roster result')
    }
    result.value = applied
    rows.value = []
    emit('applied')
  } catch (error) {
    if (!current(snapshot)) return
    const hasBusinessError = typeof (error as { bizCode?: number } | null)?.bizCode === 'number'
    applyError.value = hasBusinessError
      ? '请按提示核对当前账号和机构，再校验预览。'
      : '未收到导入结果。请先核对用户列表，确认已经建立的账号。如未收到初始密码，请在用户管理中重置。'
    if (!hasBusinessError) {
      fileName.value = ''
      rows.value = []
      emit('applied')
    }
  } finally {
    if (current(snapshot)) applying.value = false
  }
}
</script>

<style scoped>
.roster-status { margin-top: 12px; }
ul { padding-left: 1.6em; line-height: 1.7; }
</style>