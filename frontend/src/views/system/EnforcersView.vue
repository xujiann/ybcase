<template>
  <el-card>
    <div class="toolbar">
      <h3>执法证台账<span class="hint">（立案与法制审核的资格校验依据；到期须换证）</span></h3>
      <el-button v-if="isAdmin" type="primary" :disabled="saving" @click="openCreate">登记/更新</el-button>
    </div>
    <el-alert v-if="ledger.failed" type="error" :closable="false" title="执法证台账加载失败，请刷新页面后重试。" style="margin-bottom: 12px" />
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="name" label="姓名" width="110" />
      <el-table-column prop="cert_no" label="执法证号" width="130" />
      <el-table-column prop="dept" label="部门" width="160" />
      <el-table-column label="有效期至" width="130">
        <template #default="{ row }">
          <span :class="{ danger: row.cert_expire_at < today, warnText: row.cert_expire_at >= today && row.cert_expire_at < soon }">
            {{ row.cert_expire_at }}</span>
          <el-tag v-if="row.cert_expire_at >= today && row.cert_expire_at < soon" type="warning" size="small" style="margin-left: 4px">30日内到期</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="法律职业资格" width="120">
        <template #default="{ row }">{{ row.legal_qualified ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="关联账号" width="150">
        <template #default="{ row }">
          <span v-if="row.username">{{ row.user_real_name }}（{{ row.username }}）</span>
          <el-tooltip v-else content="未关联账号时，该执法人员在“仅本人”数据范围下看不到自己参办的案件" placement="top">
            <span class="warnText">未关联</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '有效' : '停用' }}</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlg" title="执法证登记（证号相同则更新）" width="460px"
               :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form :model="form" label-width="110px" :disabled="saving">
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="执法证号"><el-input v-model="form.certNo" /></el-form-item>
        <el-form-item label="部门"><el-input v-model="form.dept" /></el-form-item>
        <el-form-item label="有效期至">
          <el-date-picker v-model="form.certExpireAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="关联账号">
          <el-select v-model="form.userId" clearable filterable placeholder="用于数据范围判定参办人" style="width: 100%" @change="mappingTouched = true">
            <el-option v-for="u in users" :key="u.id" :label="`${u.realName}（${u.username}）`" :value="u.id" />
          </el-select>
          <div class="hint">未操作此项时保留原关联；选择账号后清空并保存可解除关联。账号关联用于判定参办人的数据范围。</div>
        </el-form-item>
        <el-form-item label="法律职业资格"><el-switch v-model="form.legalQualified" /></el-form-item>
        <el-form-item label="有效"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="saving" @click="dlg = false">取消</el-button>
        <el-button v-if="isAdmin" type="primary" :loading="saving" @click="onSave">保存</el-button>
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
import { createResourceLoader, resourceState } from '../../api/loadableResource'

const auth = useAuthStore()
const isAdmin = computed(() => adminOperationAccess(auth.user?.roles))

const ledger = reactive(resourceState<any[]>([]))
const rows = computed(() => ledger.ready ? ledger.data : [])
const loading = computed(() => ledger.loading)
const dlg = ref(false)
const saving = ref(false)
const mappingTouched = ref(false)
let disposed = false, dialogGeneration = 0
function ymd(d: Date) {
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const today = ymd(new Date())
// 与督办看板 enforcerCertExpiring 同口径：30 日内到期即临期
const soon = ymd(new Date(Date.now() + 30 * 86400000))
const users = ref<any[]>([])
const form = reactive<any>({ name: '', certNo: '', dept: '', certExpireAt: '', legalQualified: false, enabled: true, userId: null })
const ledgerLoader = createResourceLoader(ledger, async () => {
  const data = (await client.get('/bureau/enforcers')).data.data
  if (!Array.isArray(data)) throw new Error('Invalid enforcer ledger')
  return data
})
watch(dlg, visible => { if (!visible) dialogGeneration++ }, { flush: 'sync' })
watch(isAdmin, allowed => { if (!allowed) users.value = [] }, { flush: 'sync' })
onUnmounted(() => {
  disposed = true; dialogGeneration++
  ledgerLoader.invalidate(); ledger.data = []; users.value = []
})

async function load() {
  if (disposed) return false
  return ledgerLoader.load()
}

function openCreate() {
  if (!isAdmin.value || disposed || saving.value) return
  dialogGeneration++
  Object.assign(form, { name: '', certNo: '', dept: '', certExpireAt: '', legalQualified: false, enabled: true, userId: null })
  mappingTouched.value = false
  dlg.value = true
}

async function onSave() {
  if (!isAdmin.value || disposed || saving.value || !dlg.value) return
  if (!String(form.name || '').trim() || !String(form.certNo || '').trim() || !form.certExpireAt) {
    ElMessage.warning('姓名/证号/有效期必填')
    return
  }
  const body = { ...form }, generation = dialogGeneration
  if (body.userId == null || body.userId === '') {
    if (mappingTouched.value) body.userId = null
    else delete body.userId
  }
  saving.value = true
  try {
    await client.post('/bureau/enforcers', body)
    if (disposed || generation !== dialogGeneration || !dlg.value || !isAdmin.value) return
    ElMessage.success('已保存')
    dlg.value = false
    await load()
  } catch { /* The API interceptor already reports the failure; retain the draft for correction. */ }
  finally { saving.value = false }
}

onMounted(async () => {
  await load()
  if (disposed || !isAdmin.value) return
  try {
    const data = (await client.get('/system/users', { params: { size: 200 } })).data.data.records
    if (!disposed && isAdmin.value) users.value = data
  } catch { /* 非管理员无权读用户列表，关联账号下拉留空即可 */ }
})
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
.warnText { color: #e6a23c; }
.danger { color: #c0392b; font-weight: 600; }
</style>
