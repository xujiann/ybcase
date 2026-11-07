<template>
  <el-dialog :model-value="true" title="核对旧机构归类" width="760px" :close-on-click-modal="false"
             :close-on-press-escape="!saving" :show-close="!saving" @close="emit('close')">
    <el-alert type="info" :closable="false" title="核对局方确认的机构类别后归类。原机构编号和业务归属保留；所属账号需另行逐个核对类别。归类成功后类别固定。" />
    <el-select v-model="kind" placeholder="选择正式机构类别" :disabled="saving" style="margin:14px 0">
      <el-option label="处室" value="BUREAU" /><el-option label="分局" value="DISTRICT" /><el-option label="医保中心" value="AGENCY" />
    </el-select>
    <el-button :loading="loading" :disabled="!kind || saving" @click="preview">重新核对</el-button>
    <el-alert v-if="failed" type="error" :closable="false" title="核对信息加载失败，请重新核对后提交。" />
    <template v-if="snapshot">
      <p>{{ snapshot.department.name }}（{{ snapshot.department.code }}） · 所属账号 {{ snapshot.accounts.length }} 个，待归类账号 {{ snapshot.unclassifiedAccounts }} 个</p>
      <el-alert v-if="snapshot.conflicts.length" type="warning" :closable="false" title="存在类别不一致的所属账号，请先在用户管理中核对账号，再重新预览。" />
      <el-table :data="snapshot.accounts" border size="small" max-height="240">
        <el-table-column prop="username" label="账号" /><el-table-column prop="real_name" label="姓名" />
        <el-table-column label="账号类别"><template #default="{row}">{{ labels[row.account_category] || '待归类' }}</template></el-table-column>
        <el-table-column label="状态"><template #default="{row}">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
      </el-table>
      <el-form label-width="110px" style="margin-top:14px">
        <el-form-item label="归类理由" required><el-input v-model="reason" type="textarea" maxlength="1000" :disabled="saving" /></el-form-item>
        <el-form-item label="局方核对依据" required><el-input v-model="sourceReference" maxlength="255" placeholder="确认文件或核对记录编号" :disabled="saving" /></el-form-item>
      </el-form>
    </template>
    <template #footer><el-button :disabled="saving" @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading || !snapshot || !!snapshot.conflicts.length || !!snapshot.department.org_kind" @click="confirm">确认归类</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'
import { adminOperationAccess } from '../api/caseWriteAccess'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'
const props=defineProps<{deptId:number}>()
const emit=defineEmits<{close:[];completed:[]}>()
const auth=useAuthStore(),kind=ref(''),reason=ref(''),sourceReference=ref('')
const snapshot=ref<any>(),loading=ref(false),saving=ref(false),failed=ref(false)
const labels:Record<string,string>={BUREAU:'局机关处室',DISTRICT:'区市县',AGENCY:'医保中心'}
let disposed=false,generation=0
const identity=()=>JSON.stringify([auth.token,auth.user?.id,auth.user?.roles,props.deptId])
function capture(){const stamp=sessionExpiry.capture(auth.token),key=identity(),orgKind=kind.value;return()=>!disposed && !!auth.token && key===identity() && kind.value===orgKind && adminOperationAccess(auth.user?.roles) && sessionExpiry.isCurrent(stamp,auth.token) && !sessionExpiry.isExpired(stamp,auth.token)}
function clear(){generation++;snapshot.value=undefined;loading.value=false;failed.value=false}
async function preview(){
  clear();if(!kind.value || saving.value || !adminOperationAccess(auth.user?.roles))return
  const run=generation,current=capture();if(!current())return;loading.value=true
  try{const result=await client.get(`/system/depts/${props.deptId}/classification-preview`,{params:{orgKind:kind.value}});if(run===generation && current())snapshot.value=result.data.data}
  catch{if(run===generation && current())failed.value=true}finally{if(run===generation)loading.value=false}
}
async function confirm(){
  if(saving.value || loading.value || !snapshot.value || snapshot.value.conflicts.length || snapshot.value.department.org_kind || !adminOperationAccess(auth.user?.roles))return
  if(!reason.value.trim() || !sourceReference.value.trim()){ElMessage.warning('请填写归类理由和局方核对依据');return}
  const current=capture();if(!current())return
  const body={orgKind:kind.value,expectedCode:snapshot.value.department.code,fingerprint:snapshot.value.fingerprint,reason:reason.value.trim(),sourceReference:sourceReference.value.trim()}
  saving.value=true
  try{await client.post(`/system/depts/${props.deptId}/classification`,body);if(current()){ElMessage.success('机构已归类，请继续核对所属正式账号');emit('completed');emit('close')}}
  catch{if(current()){snapshot.value=undefined;failed.value=true}}finally{saving.value=false}
}
watch(kind,()=>{void preview()})
watch(identity,()=>{clear();reason.value='';sourceReference.value='';emit('close')},{flush:'sync'})
window.addEventListener(SESSION_EXPIRED_EVENT,clear)
onBeforeUnmount(()=>{disposed=true;clear();window.removeEventListener(SESSION_EXPIRED_EVENT,clear)})
</script>
