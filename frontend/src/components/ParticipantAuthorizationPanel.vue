<template>
  <el-dialog :model-value="true" title="历史参办访问授权核对" width="min(760px,95vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @update:model-value="close">
    <el-alert title="只对原卷、有效执法证和当前机构均核对一致的历史参办记录建立独立访问授权。原人员行、原卷、账号岗位和密码保留，不按姓名推断权限。" type="info" :closable="false" />
    <el-alert v-if="!isAdmin" title="仅管理员可核对和批准历史参办授权。" type="warning" :closable="false" />
    <el-form label-width="145px" :disabled="saving || !isAdmin">
      <el-form-item label="案件记录号" required><el-input v-model="input.caseId" placeholder="原案件的记录号" /></el-form-item>
      <el-form-item label="原参办记录号" required><el-input v-model="input.officerId" placeholder="原案件参办人员行的记录号" /></el-form-item>
      <el-form-item label="目标登录账号" required><el-input v-model="input.username" maxlength="64" placeholder="须与当前有效执法证绑定账号一致" /></el-form-item>
    </el-form>
    <el-button :loading="loading" :disabled="saving || !isAdmin" @click="inspect">核对授权条件</el-button>
    <el-alert v-if="failed" title="核对请求失败，请重新核对；未取得有效预览不能批准。" type="warning" :closable="false" class="status" />
    <template v-if="preview">
      <el-alert :type="preview.eligible ? 'success' : 'warning'" :title="preview.reason || (preview.eligible ? '核对条件通过，请检查原卷依据后批准' : '当前条件不允许授权')" :closable="false" class="status" />
      <el-descriptions border :column="1" class="status"><el-descriptions-item v-for="(value,key) in displayedSource" :key="key" :label="labels[key] || key">{{ typeof value==='boolean' ? (value ? '是' : '否') : value ?? '—' }}</el-descriptions-item></el-descriptions>
      <el-form label-width="145px" :disabled="saving || !preview.eligible || !isAdmin" class="status">
        <el-form-item label="批准理由" required><el-input v-model="reason" type="textarea" :rows="3" maxlength="1000" /></el-form-item>
        <el-form-item label="原卷核对依据" required><el-input v-model="sourceReference" type="textarea" :rows="3" maxlength="1000" placeholder="原卷号、参办人员记录、证件及机构核对材料位置" /></el-form-item>
      </el-form>
    </template>
    <el-alert v-if="applied" title="已保存独立参办访问授权。原卷和原参办记录未被回填。" type="success" :closable="false" class="status" />
    <h4>本案授权记录 <el-button :loading="historyLoading" :disabled="saving || !isAdmin" @click="loadHistory">查询本案授权记录</el-button></h4>
    <el-alert v-if="historyFailed" title="授权历史读取失败，请重新查询。" type="warning" :closable="false" />
    <el-table :data="history" size="small" border>
      <el-table-column prop="id" label="授权记录号" /><el-table-column prop="officer_id" label="原参办记录号" /><el-table-column prop="cert_no" label="原证号" /><el-table-column prop="username" label="目标账号" /><el-table-column prop="approved_by" label="批准人" /><el-table-column prop="approved_at" label="批准时间" />
      <el-table-column label="当前状态"><template #default="{row}">{{ row.revoked_at ? '已收回' : '有效授权' }}</template></el-table-column>
      <el-table-column prop="revoke_reason" label="收回理由" /><el-table-column label="操作"><template #default="{row}"><el-button v-if="!row.revoked_at && isAdmin" link type="warning" :disabled="saving || historyLoading" @click="openRevoke(row)">收回本次授权</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="revokeVisible" title="收回单案历史参办授权" width="min(600px,90vw)" append-to-body :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving">
      <el-alert :title="`仅收回授权记录 #${selectedGrant?.id ?? ''}，保留原批准记录；不改账号、证件或其他案件权限。`" type="warning" :closable="false" />
      <el-form label-width="110px" :disabled="saving"><el-form-item label="收回理由" required><el-input v-model="revokeReason" type="textarea" maxlength="1000" /></el-form-item><el-form-item label="核对材料依据" required><el-input v-model="revokeReference" type="textarea" maxlength="1000" /></el-form-item></el-form>
      <template #footer><el-button :disabled="saving" @click="revokeVisible=false">取消</el-button><el-button type="warning" :loading="saving" @click="revoke">确认收回本次授权</el-button></template>
    </el-dialog>
    <template #footer><el-button :disabled="saving" @click="close">关闭</el-button><el-button type="primary" :loading="saving" :disabled="!isAdmin || loading || !preview?.eligible || applied" @click="approve">批准本次核对的访问授权</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
import client from '../api/client'
import {useAuthStore} from '../stores/auth'
import {sessionExpiry,SESSION_EXPIRED_EVENT} from '../api/sessionExpiry'
const emit=defineEmits<{close:[]}>(),auth=useAuthStore()
const isAdmin=computed(()=>!!auth.token && !!auth.user && auth.user.roles.includes('ADMIN'))
const identity=computed(()=>JSON.stringify([auth.token,auth.user?.id,auth.user?.roles]))
const input=reactive({caseId:'',officerId:'',username:''}),preview=ref<any>(),reason=ref(''),sourceReference=ref('')
const loading=ref(false),saving=ref(false),failed=ref(false),applied=ref(false)
const history=ref<any[]>([]),historyLoading=ref(false),historyFailed=ref(false),revokeVisible=ref(false),selectedGrant=ref<any>(),revokeReason=ref(''),revokeReference=ref('')
const labels:Record<string,string>={case_id:'案件记录号',case_no:'案号',officer_id:'原参办记录号',officer_name:'原参办人员',name:'原参办人员',cert_no:'原执法证号',username:'目标账号',user_id:'目标账号记录号',real_name:'账号姓名',dept_id:'所属机构记录号',department:'当前机构',dept_name:'当前机构',expires_at:'证件有效期',duty:'原参办分工',org_kind:'当前机构类别',original_user_id:'原记录账号映射',avoided:'是否已回避',cert_expire_at:'当前证件有效期'}
const displayedSource=computed(()=>Object.fromEntries(Object.entries(preview.value?.source||{}).filter(([key])=>['case_no','cert_no','username','dept_id','org_kind','original_user_id','avoided','cert_expire_at'].includes(key))))
let seq=0,disposed=false,approvedInput='',controller:AbortController|undefined
let historySeq=0
function stamp(){return JSON.stringify([input.caseId.trim(),input.officerId.trim(),input.username.trim()])}
function context(){return {identity:identity.value,input:stamp(),session:sessionExpiry.capture(auth.token)}}
function current(value:ReturnType<typeof context>){return !disposed && isAdmin.value && value.identity===identity.value && value.input===stamp() && sessionExpiry.isCurrent(value.session,auth.token) && !sessionExpiry.isExpired(value.session,auth.token)}
function clearPreview(){seq++;controller?.abort();preview.value=undefined;approvedInput='';reason.value='';sourceReference.value='';loading.value=false;failed.value=false;applied.value=false}
function clearHistory(){historySeq++;history.value=[];historyLoading.value=false;historyFailed.value=false;revokeVisible.value=false;selectedGrant.value=undefined;revokeReason.value='';revokeReference.value=''}
function historyContext(){return {caseId:input.caseId.trim(),identity:identity.value,session:sessionExpiry.capture(auth.token)}}
function historyCurrent(value:ReturnType<typeof historyContext>){return !disposed && isAdmin.value && value.caseId===input.caseId.trim() && value.identity===identity.value && sessionExpiry.isCurrent(value.session,auth.token) && !sessionExpiry.isExpired(value.session,auth.token)}
async function loadHistory(){if(!isAdmin.value||disposed||historyLoading.value)return;if(!/^[1-9]\d{0,14}$/.test(input.caseId.trim())){ElMessage.warning('请先填写有效案件记录号');return}const ctx=historyContext(),own=++historySeq;historyLoading.value=true;historyFailed.value=false;try{const response=await client.get('/system/participant-access',{params:{caseId:ctx.caseId}});if(historyCurrent(ctx)&&own===historySeq)history.value=response.data.data||[]}catch{if(historyCurrent(ctx)&&own===historySeq){history.value=[];historyFailed.value=true}}finally{if(historyCurrent(ctx)&&own===historySeq)historyLoading.value=false}}
function openRevoke(row:any){if(!isAdmin.value||saving.value||historyLoading.value||row.revoked_at||!history.value.some(value=>value.id===row.id)||String(row.case_id)!==input.caseId.trim())return;selectedGrant.value=row;revokeReason.value='';revokeReference.value='';revokeVisible.value=true}
async function revoke(){const row=selectedGrant.value;if(!isAdmin.value||saving.value||historyLoading.value||!revokeVisible.value||!row||row.revoked_at||String(row.case_id)!==input.caseId.trim()||!history.value.some(value=>value.id===row.id))return;if(!revokeReason.value.trim()||!revokeReference.value.trim()){ElMessage.warning('请填写收回理由及核对材料依据');return}const ctx=historyContext();saving.value=true;try{await client.post(`/system/participant-access/${row.id}/revoke`,{reason:revokeReason.value.trim(),sourceReference:revokeReference.value.trim()});if(!historyCurrent(ctx))return;revokeVisible.value=false;selectedGrant.value=undefined;ElMessage.success('本次单案授权已收回，原批准记录保留');clearHistory();await loadHistory()}catch{/* Preserve review input for an explicit idempotent retry. */}finally{saving.value=false}}
function validInput(){if(!/^[1-9]\d{0,14}$/.test(input.caseId.trim())||!/^[1-9]\d{0,14}$/.test(input.officerId.trim())||!input.username.trim()){ElMessage.warning('请填写有效案件、参办记录号及目标账号');return false}if(input.username.trim()===auth.user?.username){ElMessage.warning('不能为本人批准历史参办授权');return false}return true}
async function inspect(){if(!isAdmin.value||loading.value||saving.value||!validInput())return;clearPreview();const ctx=context(),own=++seq;controller=new AbortController();loading.value=true;try{const response=await client.get('/system/participant-access/preview',{params:{caseId:input.caseId.trim(),officerId:input.officerId.trim(),username:input.username.trim()},signal:controller.signal});if(!current(ctx)||own!==seq)return;preview.value=response.data.data;approvedInput=ctx.input}catch{if(current(ctx)&&own===seq){preview.value=undefined;failed.value=true}}finally{if(current(ctx)&&own===seq)loading.value=false}}
async function approve(){if(!isAdmin.value||loading.value||saving.value||applied.value||!preview.value?.eligible||approvedInput!==stamp()||!validInput())return;if(!reason.value.trim()||!sourceReference.value.trim()){ElMessage.warning('请填写批准理由和原卷依据');return}const ctx=context(),value=preview.value,caseId=input.caseId.trim(),officerId=input.officerId.trim();saving.value=true;try{await client.post(`/system/participant-access/${caseId}/${officerId}`,{username:input.username.trim(),fingerprint:value.fingerprint,reason:reason.value.trim(),sourceReference:sourceReference.value.trim()});if(!current(ctx))return;clearPreview();applied.value=true;ElMessage.success('独立参办访问授权已保存');clearHistory();await loadHistory()}catch{/* Keep explicit review inputs for an operator retry; backend rechecks the fingerprint. */}finally{saving.value=false}}
function close(){if(saving.value)return;clearPreview();clearHistory();emit('close')}
function expired(){clearPreview();clearHistory()}
watch(()=>[input.caseId,input.officerId,input.username],clearPreview,{flush:'sync'})
watch(()=>input.caseId,clearHistory,{flush:'sync'})
watch(identity,()=>{clearPreview();clearHistory();input.caseId='';input.officerId='';input.username=''},{flush:'sync'})
onMounted(()=>window.addEventListener(SESSION_EXPIRED_EVENT,expired))
onBeforeUnmount(()=>{disposed=true;clearPreview();clearHistory();window.removeEventListener(SESSION_EXPIRED_EVENT,expired)})
</script>
<style scoped>.status{margin-top:14px}</style>
