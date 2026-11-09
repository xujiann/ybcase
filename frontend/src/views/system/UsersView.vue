<template>
  <el-card>
    <el-alert v-if="!isAdmin" type="info" :closable="false" title="当前页面仅供管理员使用。" style="margin-bottom: 12px" />
    <div class="toolbar">
      <h3>用户管理</h3>
      <div v-if="isAdmin">
        <el-button :disabled="loading || failed || !directoriesReady || directoryFailed || saving || rowAction !== null" @click="rosterVisible = true">批量导入账号名册</el-button>
        <el-button :disabled="saving || rowAction !== null" @click="participantVisible = true">历史参办授权核对</el-button>
        <el-button :disabled="loading || failed || !directoriesReady || directoryFailed || saving || rowAction !== null" type="primary" @click="openCreate">新增用户</el-button>
      </div>
    </div>
    <el-alert v-if="failed || directoryFailed" type="error" :closable="false" title="用户或岗位目录加载失败，请刷新页面后重试。" />
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="realName" label="姓名" width="120" />
      <el-table-column prop="title" label="岗位" width="100" />
      <el-table-column label="账号类别" width="160">
        <template #default="{ row }">{{ row.accountCategory ? ACCOUNT_CATEGORY[row.accountCategory] : '待归类' }}</template>
      </el-table-column>
      <el-table-column label="所属单位／处室" width="160">
        <template #default="{ row }">{{ deptName(row.deptId) }}</template>
      </el-table-column>
      <el-table-column label="角色">
        <template #default="{ row }">
          <el-tag v-for="c in row.roleCodes" :key="c" size="small" style="margin-right: 4px">
            {{ roleName(c) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'danger'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170">
        <template #default="{ row }">
          <el-button v-if="isAdmin" :disabled="saving || rowAction !== null" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="isAdmin && (row.username !== 'admin')" link :type="row.enabled ? 'danger' : 'success'"
                     :disabled="saving || rowAction !== null" @click="toggleEnabled(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
          <el-button v-if="isAdmin && row.lockedUntil && new Date(row.lockedUntil) > new Date()" size="small" text type="warning"
                     :disabled="saving || rowAction !== null" @click="onUnlock(row)">解锁</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="pageNo" :page-size="pageSize" :total="total"
                   layout="total, prev, pager, next" style="margin-top: 12px"
                   @current-change="load" />

    <UserRosterDialog v-if="isAdmin && rosterVisible" @close="rosterVisible = false" @applied="load" />
    <ParticipantAuthorizationPanel v-if="isAdmin && participantVisible" @close="participantVisible=false" />

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑用户' : '新增用户'" width="480px" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form :model="form" label-width="90px" :disabled="saving">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item :label="form.id ? '重置密码' : '初始密码'" :required="!form.id">
          <el-input v-model="form.password" type="password" show-password
                    :placeholder="form.id ? '留空则不修改' : '至少 8 位，须含字母和数字'" />
        </el-form-item>
        <el-form-item label="姓名" required>
          <el-input v-model="form.realName" />
        </el-form-item>
        <el-form-item label="岗位">
          <el-select v-model="form.title" clearable style="width: 100%">
            <el-option v-for="t in ['医师', '护士', '药师', '技师', '收费员', '管理员']" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="账号类别" required>
          <el-select v-model="form.accountCategory" style="width: 100%" @change="onCategoryChange">
            <el-option v-for="(label, value) in ACCOUNT_CATEGORY" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="所属单位／处室" required>
          <el-select v-model="form.deptId" clearable style="width: 100%">
            <el-option v-for="d in eligibleDepts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.roleCodes" multiple style="width: 100%">
            <el-option v-for="r in eligibleRoles" :key="r.code" :label="r.name" :value="r.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.phone" />
        </el-form-item>
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
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import UserRosterDialog from '../../components/UserRosterDialog.vue'
import ParticipantAuthorizationPanel from '../../components/ParticipantAuthorizationPanel.vue'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import { adminOperationAccess } from '../../api/caseWriteAccess'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../../api/sessionExpiry'

const auth = useAuthStore()
const isAdmin = computed(() => adminOperationAccess(auth.user?.roles))

const router = useRouter()

const ACCOUNT_CATEGORY: Record<string, string> = {
  DISTRICT: '区市县办案人员', BUREAU: '局机关各处室', AGENCY: '医保中心经办人员',
}

interface UserRow {
  id: number | null
  username: string
  realName: string
  title: string | null
  deptId: number | null
  accountCategory: string | null
  phone: string | null
  enabled: boolean
  roleCodes: string[]
}

const records = ref<UserRow[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = 20
const loading = ref(false)
const failed = ref(false)
const directoryFailed = ref(false)
const directoriesReady = ref(false)
const saving = ref(false)
const rowAction = ref<number | null>(null)
const dialogVisible = ref(false)
const rosterVisible = ref(false)
const participantVisible=ref(false)
const depts = ref<{ id: number; name: string; enabled?: boolean; orgKind?: string | null }[]>([])
const roles = ref<{ code: string; name: string }[]>([])

const form = reactive<UserRow & { password: string }>({
  id: null, username: '', password: '', realName: '', title: null,
  deptId: null, accountCategory: null, phone: null, enabled: true, roleCodes: [],
})
const eligibleDepts = computed(() => depts.value.filter(dept => dept.enabled !== false
  && (dept.orgKind ? dept.orgKind !== 'ROOT' && dept.orgKind === form.accountCategory
    : form.accountCategory !== 'AGENCY')))
const eligibleRoles = computed(() => roles.value.filter(role => form.accountCategory === 'AGENCY'
  ? role.code === 'AGENCY'
  : role.code !== 'AGENCY' && (form.accountCategory !== 'DISTRICT' || role.code !== 'ADMIN')))

function onCategoryChange() {
  if (!eligibleDepts.value.some(dept => dept.id === form.deptId)) form.deptId = null
  if (form.accountCategory === 'AGENCY') form.roleCodes = ['AGENCY']
  else form.roleCodes = form.roleCodes.filter(code => eligibleRoles.value.some(role => role.code === code))
}

let disposed = false, loadGeneration = 0, directoryGeneration = 0, saveGeneration = 0, actionGeneration = 0

function identity() {
  return JSON.stringify([auth.token, auth.user?.id, auth.user?.username, [...(auth.user?.roles || [])].sort()])
}
function context() { return { identity: identity(), stamp: sessionExpiry.capture(auth.token) } }
function active() { return !disposed && isAdmin.value }
function isContextCurrent(c: ReturnType<typeof context>) {
  return active() && c.identity === identity() && sessionExpiry.isCurrent(c.stamp, auth.token)
    && !sessionExpiry.isExpired(c.stamp, auth.token)
}
function resetForm() {
  Object.assign(form, { id: null, username: '', password: '', realName: '', title: null, deptId: null, accountCategory: null, phone: null, enabled: true, roleCodes: [] })
}
function invalidate(clearDraft = true) {
  loadGeneration++; directoryGeneration++; saveGeneration++; actionGeneration++
  records.value = []; total.value = 0; loading.value = false; failed.value = false; saving.value = false; rowAction.value = null
  rosterVisible.value = false
  participantVisible.value = false
  if (clearDraft) {
    dialogVisible.value = false; resetForm(); depts.value = []; roles.value = []; directoriesReady.value = false; directoryFailed.value = false
  }
}
function onSessionExpired() { invalidate(false) }
watch(identity, () => invalidate(), { flush: 'sync' })
watch(dialogVisible, visible => { if (!visible) { saveGeneration++; saving.value = false; form.password = '' } }, { flush: 'sync' })
onUnmounted(() => { disposed = true; invalidate(); window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired) })

function deptName(id: number | null) {
  return depts.value.find((d) => d.id === id)?.name ?? ''
}
function roleName(code: string) {
  return roles.value.find((r) => r.code === code)?.name ?? code
}

async function load() {
  if (!isAdmin.value || !active()) return
  const run = ++loadGeneration, captured = context(), page = pageNo.value
  loading.value = true
  failed.value = false
  records.value = []; total.value = 0
  try {
    const resp = await client.get('/system/users', { params: { page: page - 1, size: pageSize } })
    if (run === loadGeneration && isContextCurrent(captured) && page === pageNo.value) {
      records.value = resp.data.data.records
      total.value = resp.data.data.total
    }
  } catch {
    if (run === loadGeneration && isContextCurrent(captured)) { records.value = []; total.value = 0; failed.value = true }
  } finally {
    if (run === loadGeneration) loading.value = false
  }
}

function openCreate() {
  if (!isAdmin.value || !active() || saving.value || rowAction.value !== null || loading.value || failed.value || !directoriesReady.value || directoryFailed.value) return
  saveGeneration++; resetForm()
  dialogVisible.value = true
}

function openEdit(row: UserRow) {
  if (!isAdmin.value || !active() || saving.value || rowAction.value !== null || loading.value || failed.value || !directoriesReady.value || directoryFailed.value) return
  saveGeneration++
  Object.assign(form, { ...row, roleCodes: [...row.roleCodes], password: '' })
  dialogVisible.value = true
}

async function save() {
  if (!isAdmin.value || !active() || saving.value || rowAction.value !== null || loading.value || failed.value || !dialogVisible.value || !directoriesReady.value || directoryFailed.value) return
  if ((!form.id && !form.accountCategory) || (form.accountCategory && form.deptId === null)) {
    ElMessage.warning('请选择账号类别及所属单位或处室')
    return
  }
  const id = form.id, payload = { ...form, roleCodes: [...form.roleCodes] }, run = ++saveGeneration, captured = context()
  const unchanged = () => run === saveGeneration && isContextCurrent(captured) && dialogVisible.value
    && form.id === id && JSON.stringify({ ...form, roleCodes: [...form.roleCodes] }) === JSON.stringify(payload)
  saving.value = true
  try {
    if (id) {
      await client.put(`/system/users/${id}`, payload)
    } else {
      await client.post('/system/users', payload)
    }
    if (!unchanged()) return
    ElMessage.success('保存成功')
    dialogVisible.value = false
    await load()
  } finally {
    if (run === saveGeneration) saving.value = false
  }
}

/** 公网上任何人对着用户名错 5 次即可把账号锁死（含 admin），管理员须能解锁 */
async function onUnlock(row: any) {
  if (!isAdmin.value || !active() || saving.value || rowAction.value !== null || loading.value || failed.value || !row.id) return
  const id = row.id, run = ++actionGeneration, captured = context()
  rowAction.value = id
  try {
    await client.put(`/system/users/${id}/unlock`)
    if (run !== actionGeneration || !isContextCurrent(captured)) return
    ElMessage.success('已解锁')
    await load()
  } finally { if (run === actionGeneration) rowAction.value = null }
}

async function toggleEnabled(row: UserRow) {
  if (!isAdmin.value || !active() || saving.value || rowAction.value !== null || loading.value || failed.value || !row.id) return
  const id = row.id, username = row.username, enabled = !row.enabled, run = ++actionGeneration, captured = context()
  rowAction.value = id
  try {
    try {
      await client.put(`/system/users/${id}/enabled`, null, { params: { enabled } })
    } catch (e) {
      if (run !== actionGeneration || !isContextCurrent(captured)) return
      // Use this request's error, rather than a different request's global error snapshot.
      const error = e as { bizCode?: number; message?: string }
      if (error.bizCode === 1105) {
        try {
          await ElMessageBox.confirm(error.message || '请先移交在办案件', '停用受阻：请先移交在办案件', {
            confirmButtonText: '去移交', cancelButtonText: '知道了', type: 'warning',
          })
          if (run === actionGeneration && isContextCurrent(captured)) router.push({ path: '/case/list', query: { owner: username } })
        } catch { /* 知道了 */ }
      }
      return
    }
    if (run === actionGeneration && isContextCurrent(captured)) await load()
  } finally { if (run === actionGeneration) rowAction.value = null }
}

onMounted(async () => {
  window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired)
  if (!active()) return
  const run = ++directoryGeneration, captured = context()
  directoriesReady.value = false; directoryFailed.value = false
  try {
    const [d, r] = await Promise.all([client.get('/system/depts'), client.get('/system/roles')])
    if (run !== directoryGeneration || !isContextCurrent(captured)) return
    depts.value = d.data.data
    roles.value = r.data.data
    directoriesReady.value = true
    await load()
  } catch {
    if (run === directoryGeneration && isContextCurrent(captured)) { depts.value = []; roles.value = []; directoriesReady.value = false; directoryFailed.value = true }
  }
})
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
