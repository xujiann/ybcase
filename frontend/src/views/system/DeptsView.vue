<template>
  <el-card>
    <el-alert v-if="!isAdmin" type="info" :closable="false" title="当前为只读查看，维护操作需要管理员。" style="margin-bottom: 12px" />
    <div class="toolbar">
      <h3>机构管理</h3>
      <el-button v-if="isAdmin" type="primary" @click="openCreate">新增机构</el-button>
    </div>
    <div class="directory-filters">
      <el-radio-group v-model="categoryFilter" aria-label="机构分类">
        <el-radio-button v-for="item in categoryOptions" :key="item.value" :value="item.value">
          {{ item.label }} {{ categoryCounts[item.value] }}
        </el-radio-button>
      </el-radio-group>
      <el-input v-model="search" clearable placeholder="搜索机构名称或编码" style="width: 230px" />
    </div>
    <p class="directory-hint">{{ categoryDescription }}</p>
    <el-alert v-if="failed" type="error" :closable="false" title="机构目录加载失败，请重新进入本页重试。" class="directory-alert" />
    <el-table :data="visibleRecords" v-loading="loading" border stripe
              :empty-text="failed ? '机构目录加载失败' : '暂无符合条件的机构'">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="机构名称" width="180" />
      <el-table-column prop="code" label="编码" width="140" />

      <el-table-column label="上级机构" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ departmentName(row.parentId) }}</template>
      </el-table-column>
      <el-table-column label="机构分类" width="130">
        <template #default="{ row }">{{ orgKinds[row.orgKind] || '待归类' }}</template>
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
      <el-table-column label="操作" width="250">
        <template #default="{ row }">
          <el-button v-if="isAdmin" link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="isAdmin && !row.orgKind" link type="warning" :disabled="loading || saving" @click="classificationId = row.id">核对归类</el-button>
          <el-button v-if="isAdmin" link :type="row.enabled ? 'danger' : 'success'" @click="toggleEnabled(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <DepartmentClassificationDialog v-if="isAdmin && classificationId" :dept-id="classificationId"
                                    @close="classificationId = null" @completed="load" />

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑机构' : '新增机构'" width="420px"
               :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form :model="form" label-width="90px">
        <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="编码" required><el-input v-model="form.code" /></el-form-item>

        <el-form-item label="上级机构">
          <el-select v-model="form.parentId" clearable style="width: 100%">
            <el-option v-for="item in parentOptions" :key="item.id!"
                       :label="item.name" :value="item.id!" />
          </el-select>
        </el-form-item>
        <el-form-item label="机构分类" :required="form.id === null">
          <el-select v-if="form.id === null" v-model="form.orgKind" placeholder="请选择机构分类" style="width: 100%">
            <el-option v-for="(label, code) in businessOrgKinds" :key="code" :label="label" :value="code" />
          </el-select>
          <span v-else>{{ orgKinds[form.orgKind || ''] || '待归类' }}</span>
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
import DepartmentClassificationDialog from '../../components/DepartmentClassificationDialog.vue'
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

const businessOrgKinds: Record<string, string> = {
  BUREAU: '处室', DISTRICT: '分局', AGENCY: '医保中心',
}
const orgKinds: Record<string, string> = { ROOT: '目录分组', ...businessOrgKinds }
const categoryOptions = [
  { value: 'ALL', label: '全部' },
  { value: 'BUREAU', label: '处室' },
  { value: 'DISTRICT', label: '分局' },
  { value: 'AGENCY', label: '医保中心' },
  { value: 'ROOT', label: '目录分组' },
  { value: 'UNCLASSIFIED', label: '待归类' },
]
const categoryFilter = ref('ALL')
const search = ref('')
const records = ref<DeptRow[]>([])
const categoryCounts = computed(() => {
  const counts: Record<string, number> = { ALL: records.value.length, BUREAU: 0, DISTRICT: 0, AGENCY: 0, ROOT: 0, UNCLASSIFIED: 0 }
  for (const row of records.value) counts[row.orgKind || 'UNCLASSIFIED']++
  return counts
})
const visibleRecords = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return records.value.filter(row => (categoryFilter.value === 'ALL'
    || (categoryFilter.value === 'UNCLASSIFIED' ? !row.orgKind : row.orgKind === categoryFilter.value))
    && (!query || row.name.toLocaleLowerCase().includes(query) || row.code.toLocaleLowerCase().includes(query)))
})
const parentOptions = computed(() => records.value.filter(row => row.id !== form.id))
const categoryDescription = computed(() => {
  if (categoryFilter.value === 'AGENCY') {
    const parents = [...new Set(records.value.filter(row => row.orgKind === 'AGENCY')
      .map(row => records.value.find(parent => parent.id === row.parentId)?.name).filter(Boolean))]
    return '医保中心按实际经办机构计数。' + (parents.length ? '上级目录为' + parents.join('、') + '，各机构对应关系见上级机构列。' : '各机构对应关系见上级机构列。')
  }
  if (categoryFilter.value === 'ROOT') return '目录分组用于展示上级组织结构，业务机构列在相应分类中。'
  if (categoryFilter.value === 'UNCLASSIFIED') return '以下机构尚未设置正式分类，保留原有登记信息。'
  return '分类数量包含启用和停用机构，全部还包含目录分组及待归类机构。'
})
function departmentName(id: number | null) {
  return records.value.find(row => row.id === id)?.name || '—'
}
const loading = ref(false)
const failed = ref(false)
const saving = ref(false)
const rowAction = ref<number | null>(null)
const dialogVisible = ref(false)
const classificationId = ref<number | null>(null)
const form = reactive<DeptRow>({ id: null, parentId: null, name: '', code: '', type: 'ADMIN', sortNo: 0, enabled: true, orgKind: null, responsibility: null })
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
  Object.assign(form, { id: null, parentId: null, name: '', code: '', type: 'ADMIN', sortNo: 0, enabled: true, orgKind: null, responsibility: null })
}
function invalidate(clearDraft = true) {
  classificationId.value = null
  loadGeneration++; saveGeneration++; actionGeneration++
  records.value = []; loading.value = false; failed.value = false; saving.value = false; rowAction.value = null
  if (clearDraft) { dialogVisible.value = false; resetForm(); categoryFilter.value = 'ALL'; search.value = '' }
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
  if (form.id === null && !Object.hasOwn(businessOrgKinds, form.orgKind || '')) {
    ElMessage.warning('请选择处室、分局或医保中心')
    return
  }
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
.directory-filters { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.directory-hint { margin: 10px 0; color: #666; font-size: 12px; }
.directory-alert { margin-bottom: 12px; }
</style>
