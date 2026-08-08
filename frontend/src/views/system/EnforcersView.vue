<template>
  <el-card>
    <div class="toolbar">
      <h3>执法证台账<span class="hint">（立案与法制审核的资格校验依据；到期须换证）</span></h3>
      <el-button type="primary" @click="openCreate">登记/更新</el-button>
    </div>
    <el-table :data="rows" border stripe size="small" v-loading="loading">
      <el-table-column prop="name" label="姓名" width="110" />
      <el-table-column prop="cert_no" label="执法证号" width="130" />
      <el-table-column prop="dept" label="部门" width="160" />
      <el-table-column label="有效期至" width="130">
        <template #default="{ row }">
          <span :class="{ danger: row.cert_expire_at < today }">{{ row.cert_expire_at }}</span>
        </template>
      </el-table-column>
      <el-table-column label="法律职业资格" width="120">
        <template #default="{ row }">{{ row.legal_qualified ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '有效' : '停用' }}</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dlg" title="执法证登记（证号相同则更新）" width="460px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="执法证号"><el-input v-model="form.certNo" /></el-form-item>
        <el-form-item label="部门"><el-input v-model="form.dept" /></el-form-item>
        <el-form-item label="有效期至">
          <el-date-picker v-model="form.certExpireAt" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
        <el-form-item label="法律职业资格"><el-switch v-model="form.legalQualified" /></el-form-item>
        <el-form-item label="有效"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" @click="onSave">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../../api/client'

const rows = ref<any[]>([])
const loading = ref(false)
const dlg = ref(false)
function todayLocal() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const today = todayLocal()
const form = reactive({ name: '', certNo: '', dept: '', certExpireAt: '', legalQualified: false, enabled: true })

async function load() {
  loading.value = true
  try {
    rows.value = (await client.get('/bureau/enforcers')).data.data
  } finally {
    loading.value = false
  }
}

function openCreate() {
  Object.assign(form, { name: '', certNo: '', dept: '', certExpireAt: '', legalQualified: false, enabled: true })
  dlg.value = true
}

async function onSave() {
  if (!form.name || !form.certNo || !form.certExpireAt) {
    ElMessage.warning('姓名/证号/有效期必填')
    return
  }
  await client.post('/bureau/enforcers', form)
  ElMessage.success('已保存')
  dlg.value = false
  load()
}

onMounted(load)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.hint { font-size: 12px; color: #999; font-weight: normal; }
.danger { color: #c0392b; font-weight: 600; }
</style>
