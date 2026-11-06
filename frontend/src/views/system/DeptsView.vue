<template>
  <el-card>
    <el-alert v-if="!isAdmin" type="info" :closable="false" title="当前为只读查看，维护操作需要管理员。" style="margin-bottom: 12px" />
    <div class="toolbar">
      <h3>机构管理</h3>
      <el-button v-if="isAdmin" type="primary" @click="openCreate">新增科室</el-button>
    </div>
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="科室名称" width="180" />
      <el-table-column prop="code" label="编码" width="140" />
      <el-table-column label="类型" width="120">
        <template #default="{ row }">{{ typeNames[row.type] ?? row.type }}</template>
      </el-table-column>
      <el-table-column label="上级机构" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ departmentName(row.parentId) }}</template>
      </el-table-column>
      <el-table-column label="账号归属类别" width="130">
        <template #default="{ row }">{{ orgKinds[row.orgKind] || '通用科室' }}</template>
      </el-table-column>
      <el-table-column prop="responsibility" label="职责" min-width="220" show-overflow-tooltip />
      <el-table-column prop="sortNo" label="排序" width="80" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'danger'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <el-button v-if="isAdmin" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="isAdmin" link :type="row.enabled ? 'danger' : 'success'" @click="toggleEnabled(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑科室' : '新增科室'" width="420px"
               :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="编码" required><el-input v-model="form.code" /></el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" style="width: 100%">
            <el-option v-for="(label, value) in typeNames" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="上级机构">
          <el-select v-model="form.parentId" clearable style="width: 100%">
            <el-option v-for="item in records.filter(row => row.id !== form.id)" :key="item.id!"
                       :label="item.name" :value="item.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="归属类别">
          <el-select v-model="form.orgKind" :disabled="form.id !== null" placeholder="通用科室" style="width: 100%">
            <el-option v-for="(label, code) in orgKinds" :key="code" :label="label" :value="code" />
          </el-select>
        </el-form-item>
        <el-form-item label="机构职责">
          <el-input v-model="form.responsibility" type="textarea" :rows="3" maxlength="1000" />
        </el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sortNo" :min="0" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button v-if="isAdmin" type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import { adminOperationAccess } from '../../api/caseWriteAccess'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../../api/sessionExpiry'

interface DeptRow {
  id: number | null
  parentId: number | null
  name: string
  code: string
  type: string
  sortNo: number
  enabled: boolean
  orgKind: string | null
  responsibility: string | null
}

const auth = useAuthStore()
const isAdmin = computed(() => adminOperationAccess(auth.user?.roles))

const typeNames: Record<string, string> = {
  CLINICAL: '临床科室',
  MEDTECH: '医技科室',
  NURSING: '护理单元',
  ADMIN: '行政后勤',
}

const orgKinds: Record<string, string> = {
  ROOT: '机构分组', BUREAU: '局机关处室', DISTRICT: '区市县单位', AGENCY: '医保中心经办',
}
const records = ref<DeptRow[]>([])
function departmentName(id: number | null) {
  return records.value.find(row => row.id === id)?.name || '—'
}
const loading = ref(false)
const failed = ref(false)
const saving = ref(false)
const rowAction = ref<number | null>(null)
const dialogVisible = ref(false)
const form = reactive<DeptRow>({ id: null, parentId: null, name: '', code: '', type: 'CLINICAL', sortNo: 0, enabled: true, orgKind: null, responsibility: null })
let disposed = false, loadGeneration = 0, saveGeneration = 0, actionGeneration = 0

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
function resetForm() {
  Object.assign(form, { id: null, parentId: null, name: '', code: '', type: 'CLINICAL', sortNo: 0, enabled: true, orgKind: null, responsibility: null })
}
function invalidate(clearDraft = true) {
  loadGeneration++; saveGeneration++; actionGeneration++
  records.value = []; loading.value = false; failed.value = false; saving.value = false; rowAction.value = null
  if (clearDraft) { dialogVisible.value = false; resetForm() }
}
function onSessionExpired() { invalidate(false) }
watch(identity, () => invalidate(), { flush: 'sync' })
watch(dialogVisible, visible => { if (!visible) { saveGeneration++; saving.value = false } }, { flush: 'sync' })
onUnmounted(() => { disposed = true; invalidate(); window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired) })

async function load() {
  if (!active()) return
  const run = ++loadGeneration, captured = context()
  loading.value = true
  failed.value = false; records.value = []
  try {
    const resp = await client.get('/system/depts')
    if (run === loadGeneration && isContextCurrent(captured)) records.value = resp.data.data
  } catch {
    if (run === loadGeneration && isContextCurrent(captured)) { records.value = []; failed.value = true }
  } finally {
    if (run === loadGeneration) loading.value = false
  }
}

function openCreate() {
  if (!isAdmin.value || !active() || saving.value || rowAction.value !== null || loading.value || failed.value) return
  saveGeneration++; resetForm()
  dialogVisible.value = true
}

function openEdit(row: DeptRow) {
  if (!isAdmin.value || !active() || saving.value || rowAction.value !== null || loading.value || failed.value) return
  saveGeneration++
  Object.assign(form, row)
  dialogVisible.value = true
}

async function save() {
  if (!isAdmin.value || !active() || saving.value || rowAction.value !== null || loading.value || failed.value || !dialogVisible.value) return
  const run = ++saveGeneration, captured = context(), id = form.id, payload = { ...form }
  const unchanged = () => run === saveGeneration && isContextCurrent(captured) && dialogVisible.value
    && JSON.stringify(form) === JSON.stringify(payload)
  saving.value = true
  try {
    if (id) {
      await client.put(`/system/depts/${id}`, payload)
    } else {
      await client.post('/system/depts', payload)
    }
    if (!unchanged()) return
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await load()
  } finally {
    if (run === saveGeneration) saving.value = false
  }
}

async function toggleEnabled(row: DeptRow) {
  if (!isAdmin.value || !active() || saving.value || rowAction.value !== null || loading.value || failed.value || !row.id) return
  const run = ++actionGeneration, captured = context(), id = row.id, enabled = !row.enabled
  rowAction.value = id
  try {
    await client.put(`/system/depts/${id}/enabled`, null, { params: { enabled } })
    if (run !== actionGeneration || !isContextCurrent(captured)) return
    await load()
  } finally { if (run === actionGeneration) rowAction.value = null }
}

onMounted(() => { window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired); load() })
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.toolbar h3 { margin: 0; }
</style>
