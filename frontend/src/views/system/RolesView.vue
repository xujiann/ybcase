<template>
  <el-card>
    <el-alert v-if="!isAdmin" type="info" :closable="false" title="当前页面仅供管理员使用。" style="margin-bottom: 12px" />
    <div class="toolbar"><h3>角色权限</h3></div>
    <el-alert v-if="failed || menusFailed" type="error" :closable="false" title="角色或菜单加载失败，请刷新页面后重试。" />
    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="角色名称" width="160" />
      <el-table-column prop="code" label="编码" width="160" />
      <el-table-column prop="remark" label="说明" />
      <el-table-column prop="menuCount" label="已授权菜单数" width="130" />
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button v-if="isAdmin && (row.code !== 'ADMIN')" :disabled="loading || failed || !menusReady || menusLoading || menusFailed || saving" link type="primary" @click="openGrant(row)">编辑授权</el-button>
          <el-tag v-else size="small" type="info">全量权限</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="`授权：${current?.name}`" width="420px" :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-tree ref="treeRef" :data="menuTree" show-checkbox node-key="id" default-expand-all
               :props="{ label: 'name', children: 'children' }" />
      <template #footer>
        <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
        <el-button v-if="isAdmin" type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { ElTree } from 'element-plus'
import client from '../../api/client'
import { useAuthStore } from '../../stores/auth'
import { adminOperationAccess } from '../../api/caseWriteAccess'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../../api/sessionExpiry'

interface MenuNode {
  id: number
  parentId: number | null
  name: string
  children?: MenuNode[]
}

const auth = useAuthStore()
const isAdmin = computed(() => adminOperationAccess(auth.user?.roles))

const records = ref<Record<string, unknown>[]>([])
const loading = ref(false)
const failed = ref(false)
const menusLoading = ref(false)
const menusFailed = ref(false)
const menusReady = ref(false)
const dialogVisible = ref(false)
const saving = ref(false)
const current = ref<Record<string, unknown> | null>(null)
const menuTree = ref<MenuNode[]>([])
const treeRef = ref<InstanceType<typeof ElTree>>()
let disposed = false, loadGeneration = 0, menuGeneration = 0, grantGeneration = 0

function identity() {
  return JSON.stringify([auth.token, auth.user?.id, auth.user?.username, [...(auth.user?.roles || [])].sort()])
}
function context() { return { identity: identity(), stamp: sessionExpiry.capture(auth.token) } }
function active() {
  const stamp = sessionExpiry.capture(auth.token)
  return !disposed && isAdmin.value && !sessionExpiry.isExpired(stamp, auth.token)
}
function isContextCurrent(c: ReturnType<typeof context>) {
  return active() && c.identity === identity() && sessionExpiry.isCurrent(c.stamp, auth.token)
}
function invalidate() {
  loadGeneration++; menuGeneration++; grantGeneration++
  records.value = []; menuTree.value = []; menusReady.value = false
  loading.value = false; failed.value = false; menusLoading.value = false; menusFailed.value = false
  dialogVisible.value = false; current.value = null; saving.value = false
  treeRef.value?.setCheckedKeys([])
}
function onSessionExpired() { invalidate() }
watch(identity, invalidate, { flush: 'sync' })
watch(dialogVisible, visible => { if (!visible) { grantGeneration++; saving.value = false } }, { flush: 'sync' })
onUnmounted(() => { disposed = true; invalidate(); window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired) })

async function load() {
  if (!isAdmin.value || !active()) return
  const run = ++loadGeneration, captured = context()
  loading.value = true
  failed.value = false
  records.value = []
  try {
    const resp = await client.get('/system/roles')
    if (run === loadGeneration && isContextCurrent(captured)) records.value = resp.data.data
  } catch {
    if (run === loadGeneration && isContextCurrent(captured)) { records.value = []; failed.value = true }
  } finally {
    if (run === loadGeneration) loading.value = false
  }
}

async function loadMenus() {
  if (!isAdmin.value || !active()) return
  const run = ++menuGeneration, captured = context()
  grantGeneration++; dialogVisible.value = false; current.value = null
  menusLoading.value = true; menusFailed.value = false; menusReady.value = false; menuTree.value = []
  try {
    const menus: MenuNode[] = (await client.get('/system/roles/menus')).data.data
    if (run !== menuGeneration || !isContextCurrent(captured)) return
    menuTree.value = menus.filter((m) => !m.parentId)
      .map((dir) => ({ ...dir, children: menus.filter((m) => m.parentId === dir.id) }))
    menusReady.value = true
  } catch {
    if (run === menuGeneration && isContextCurrent(captured)) { menuTree.value = []; menusFailed.value = true }
  } finally { if (run === menuGeneration) menusLoading.value = false }
}

async function openGrant(row: Record<string, unknown>) {
  if (!isAdmin.value || !active() || loading.value || failed.value || !menusReady.value || menusLoading.value || menusFailed.value || saving.value || row.code === 'ADMIN') return
  const run = ++grantGeneration, captured = context()
  const selected: Record<string, unknown> & { menuIds: number[] } = { ...row, menuIds: [...(row.menuIds as number[])] }
  current.value = selected
  dialogVisible.value = true
  await nextTick()
  if (run !== grantGeneration || !isContextCurrent(captured) || !dialogVisible.value || current.value?.id !== selected.id) return
  // 只回显叶子节点，父节点由半选状态自动呈现
  const leafIds = selected.menuIds.filter((id) =>
    !menuTree.value.some((dir) => dir.id === id && dir.children?.length))
  treeRef.value?.setCheckedKeys(leafIds)
}

async function save() {
  if (!isAdmin.value || !active() || saving.value || loading.value || failed.value || !dialogVisible.value || !menusReady.value || menusLoading.value || menusFailed.value) return
  if (!current.value || !treeRef.value || current.value.code === 'ADMIN') return
  const id = current.value.id, run = ++grantGeneration, captured = context()
  const selectedIds = () => [...treeRef.value!.getCheckedKeys(), ...treeRef.value!.getHalfCheckedKeys()]
  const ids = selectedIds()
  const selectionKey = (values: (string | number)[]) => JSON.stringify([...new Set(values.map(String))].sort())
  const unchanged = () => run === grantGeneration && isContextCurrent(captured) && dialogVisible.value
    && current.value?.id === id && treeRef.value && selectionKey(selectedIds()) === selectionKey(ids)
  saving.value = true
  try {
    await client.put(`/system/roles/${id}/menus`, { menuIds: ids })
    if (!unchanged()) return
    ElMessage.success('授权已保存')
    dialogVisible.value = false
    await load()
  } finally {
    if (run === grantGeneration) saving.value = false
  }
}

onMounted(() => {
  window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired)
  load()
  loadMenus()
})
</script>

<style scoped>
.toolbar { margin-bottom: 12px; }
.toolbar h3 { margin: 0; }
</style>
