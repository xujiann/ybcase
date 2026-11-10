<template>
  <el-card>
    <div class="toolbar"><h4>电子送达与回证</h4><el-button :loading="loading" :disabled="busy" @click="refresh">刷新回证</el-button></div>
    <el-alert v-if="failed" type="warning" :closable="false" title="电子送达记录加载失败，请刷新重试。" />
    <template v-if="data">
      <p class="hint">由办案人员发起发送，供应商通过专用账号回传签收、拒收或失败事件；回证通过规则校验后进入对应文书和案卷。</p>
      <el-alert v-if="!data.gatewayConfigured" type="info" :closable="false" title="电子送达网关尚未启用；发送前需管理员完成配置和业务联调验收。" />
      <el-alert v-if="!hasAddress" type="warning" :closable="false" title="请先核实并登记电子送达确认书及当事人同意地址。历史自由文本不会自动推测地址。" />
      <p v-else>受送达人：{{ data.e_delivery_receiver }}　同意地址：{{ data.e_delivery_channel }}</p>
      <div v-if="canWrite" class="gap">
        <el-button v-if="data.activeNotice && !data.activeNotice.document_id && !data.activeNotice.delivery_id" :disabled="busy" @click="prepareNotice">
          制作当前告知版本 #{{ data.activeNotice.id }} 的电子文书
        </el-button>
        <el-select v-model="documentId" placeholder="选择本案允许送达的文书" style="width:330px" :disabled="busy">
          <el-option v-for="doc in availableDocuments" :key="doc.id" :value="doc.id" :label="doc.title" />
        </el-select>
        <el-button :loading="busy" :disabled="!documentId || !hasAddress || !data.gatewayConfigured" @click="create">建立发送记录</el-button>
        <el-button :disabled="busy" @click="expire">检查超时回执</el-button>
      </div>
      <p v-if="canWrite && data.activeNotice && !data.activeNotice.delivery_id" class="hint">当前告知按实际回证起算期限。正式模式下，请先在案卷目录给本版电子文书办理正式 CA 签章。</p>
      <p v-if="!canWrite && canSupplement" class="hint">原卷已封存，仍可办理独立补充文书的发送；请在补充卷中核实该文书的同意地址。</p>
      <el-table :data="data.deliveries" border size="small" class="gap">
        <el-table-column prop="id" label="编号" width="70" /><el-table-column prop="title" label="文书" min-width="160" />
        <el-table-column label="状态"><template #default="{row}">{{ labels[row.status] || row.status }}<small v-if="row.status !== 'DELIVERED' && inactiveNotice(row)">（该告知轮次已停用）</small></template></el-table-column>
        <el-table-column prop="provider_id" label="供应商编号" min-width="130" /><el-table-column prop="sent_at" label="首次发送时间" min-width="170" />
        <el-table-column prop="deadline_at" label="回执截至时间" min-width="170" /><el-table-column prop="error_code" label="错误码" />
        <el-table-column prop="replaces_id" label="替代旧轮次" width="100" />
        <el-table-column v-if="canWrite || canSupplement" label="操作" width="180"><template #default="{row}"><template v-if="canOperate(row) && !row.superseded_at && ['DRAFT','FAILED','TIMED_OUT'].includes(row.status)"><el-button link :disabled="busy || !data.gatewayConfigured" @click="send(row)">{{ row.attempts ? '重试发送' : '发送文书' }}</el-button><el-button link :disabled="busy || !data.gatewayConfigured" @click="openReplace(row)">核查换轮次</el-button></template></template></el-table-column>
      </el-table>
      <h4 class="gap">回执事件</h4>
      <el-table :data="data.events" border size="small" class="gap">
        <el-table-column prop="envelope_id" label="发送编号" width="90" /><el-table-column prop="event_key" label="事件编号" min-width="140" />
        <el-table-column label="供应商结果"><template #default="{row}">{{ labels[row.status] || row.status }}</template></el-table-column>
        <el-table-column prop="occurred_at" label="发生时间" min-width="170" /><el-table-column prop="receipt_no" label="回证编号" min-width="120" />
        <el-table-column label="接收结果"><template #default="{row}">{{ row.outcome==='APPLIED' ? '已接收' : '拒绝写入案卷' }}</template></el-table-column>
        <el-table-column prop="reason" label="原因" min-width="180" />
        <el-table-column label="原始证据" min-width="140"><template #default="{row}"><el-button v-if="row.evidence_sha256" link :disabled="busy" @click="downloadEvidence(row)">下载回证原件</el-button><span v-else>未附原件</span></template></el-table-column>
      </el-table>
    </template>
    <el-dialog v-model="replaceVisible" title="核查旧发送结果并建立替代轮次" width="560px">
      <p>旧记录及回执会保留。请先核对供应商的实际发送结果；新轮次不会自动发送。</p>
      <el-input v-model="replacement.reason" type="textarea" :rows="3" maxlength="1000" placeholder="填写地址变更、供应商核查结论和替代理由" />
      <el-checkbox v-model="replacement.providerChecked">已人工核对供应商实际结果，确认需要建立替代轮次</el-checkbox>
      <template #footer><el-button :disabled="busy" @click="replaceVisible=false">取消</el-button><el-button type="primary" :loading="busy" :disabled="!replacement.reason.trim() || !replacement.providerChecked" @click="replaceRound">保留旧记录并新建</el-button></template>
    </el-dialog>
  </el-card>
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'
const props=defineProps<{caseId:number;writable:boolean;refreshKey?:unknown}>()
const emit=defineEmits<{(e:'changed'):void}>()
const auth=useAuthStore(),data=ref<any>(null),loading=ref(false),failed=ref(false),busy=ref(false),documentId=ref<number>()
const labels:Record<string,string>={DRAFT:'待发送',SENT:'已受理待回执',DELIVERED:'已签收',REJECTED:'拒收',TIMED_OUT:'回执超时',FAILED:'发送／处理失败',SUPERSEDED:'已失效，由新轮次替代'}
const permitted=computed(()=>!auth.user?.roles.some(role=>['AGENCY','INTEGRATION'].includes(role))&&!!auth.user?.roles.some(role=>['HANDLER','LEGAL','LEADER','ADMIN'].includes(role)))
const canWrite=computed(()=>!!data.value&&props.writable&&permitted.value&&!!auth.user?.roles.some(role=>['HANDLER','LEADER','ADMIN'].includes(role))&&data.value.archive_no==null&&data.value.status!=='CLOSED')
const canSupplement=computed(()=>!!data.value&&permitted.value&&!!auth.user?.roles.some(role=>['HANDLER','LEADER','ADMIN'].includes(role)))
const replaceVisible=ref(false),replacementRow=ref<any>(null),replacement=ref({requestKey:'',reason:'',providerChecked:false})
function inactiveNotice(row:any) {
  if(row.doc_type!=='NOTICE')return false
  const notice=data.value?.activeNotice
  return row.notice_id ? row.notice_id!==notice?.id || !!notice?.delivery_id : !!data.value?.trackedNoticeId
}
const availableDocuments=computed(()=>data.value?.documents.filter((doc:any)=>!inactiveNotice(doc))||[])
const canOperate=(row:any)=>!!row&&(row.supplement_document_id ? canSupplement.value : canWrite.value&&!inactiveNotice(row))
const hasAddress=computed(()=>!!data.value?.e_delivery_consent&&!!data.value?.e_delivery_receiver&&!!data.value?.e_delivery_channel)
let disposed=false,generation=0,requestKey=''
const identity=()=>JSON.stringify([props.caseId,props.writable,auth.token,auth.user?.id,auth.user?.roles,auth.user?.accountCategory])
function active(){return !disposed&&!!auth.token&&permitted.value&&Number.isSafeInteger(props.caseId)&&props.caseId>0&&!sessionExpiry.isExpired(sessionExpiry.capture(auth.token),auth.token)}
function capture(){const who=identity(),gen=generation,stamp=sessionExpiry.capture(auth.token);return()=>active()&&who===identity()&&gen===generation&&sessionExpiry.isCurrent(stamp,auth.token)}
function clear(){generation++;data.value=null;loading.value=false;failed.value=false;busy.value=false;documentId.value=undefined;requestKey='';replaceVisible.value=false;replacementRow.value=null;replacement.value={requestKey:'',reason:'',providerChecked:false}}
watch(identity,()=>{clear();if(active())void load()},{flush:'sync'})
watch(()=>props.refreshKey,()=>{if(!busy.value)void load()})
watch(documentId,()=>{requestKey=''},{flush:'sync'})
async function load(){
  if(!active()||busy.value)return
  generation++;const current=capture();loading.value=true;failed.value=false;data.value=null;documentId.value=undefined
  try{const response=await client.get(`/bureau/cases/${props.caseId}/electronic-deliveries`);if(current()){const result=response.data.data;if(!result||!Array.isArray(result.documents)||!Array.isArray(result.deliveries)||!Array.isArray(result.events))throw new Error('Invalid receipt list');data.value=result}}
  catch{if(current()){failed.value=true;data.value=null}}finally{if(current())loading.value=false}
}
function refresh(){if(active()&&!busy.value)emit('changed')}
async function create(){
  if(!active()||!canWrite.value||!hasAddress.value||busy.value||!data.value?.gatewayConfigured||!availableDocuments.value.some((doc:any)=>doc.id===documentId.value))return
  const current=capture(),id=props.caseId,doc=documentId.value;requestKey ||= crypto.randomUUID();busy.value=true
  try{await client.post(`/bureau/cases/${id}/electronic-deliveries`,{documentId:doc,requestKey});if(!current())return;busy.value=false;requestKey='';await load()}
  catch{if(current())ElMessage.warning('未取得发送记录结果，请先刷新核对；相同文书不会重复建立记录。')}
  finally{if(current())busy.value=false}
}
async function prepareNotice(){
  const notice=data.value?.activeNotice
  if(!active()||!canWrite.value||busy.value||!notice||notice.document_id||notice.delivery_id)return
  const current=capture(),caseId=props.caseId;busy.value=true
  try{
    await client.post(`/bureau/cases/${caseId}/electronic-deliveries/notices/${notice.id}/document`,{})
    if(!current())return
    ElMessage.success('已制作并绑定当前版本，可在案卷目录查看文书')
    emit('changed')
  } catch { /* The same version can be safely retried. */ }
  finally{if(current()){busy.value=false;await load()}}
}
async function send(row:any){
  if(!active()||!canOperate(row)||busy.value||!data.value?.deliveries.includes(row)||!data.value.gatewayConfigured||row.superseded_at||!['DRAFT','FAILED','TIMED_OUT'].includes(row.status))return
  const current=capture(),caseId=props.caseId;busy.value=true
  try{await client.post(`/bureau/cases/${caseId}/electronic-deliveries/${row.id}/send`,{},{timeout:15000});if(!current())return;busy.value=false;await load()}
  catch{if(current())ElMessage.warning('发送结果未确认，请刷新后核对；重试沿用原发送请求号。')}
  finally{if(current())busy.value=false}
}
function openReplace(row:any){
  if(!active()||busy.value||!canOperate(row)||!data.value?.deliveries.includes(row)||row.superseded_at||!['DRAFT','FAILED','TIMED_OUT'].includes(row.status))return
  replacementRow.value=row;replacement.value={requestKey:crypto.randomUUID(),reason:'',providerChecked:false};replaceVisible.value=true
}
async function replaceRound(){
  const row=replacementRow.value
  if(!active()||busy.value||!replaceVisible.value||!canOperate(row)||!data.value?.deliveries.includes(row)||!replacement.value.reason.trim()||!replacement.value.providerChecked)return
  const current=capture(),body={...replacement.value};busy.value=true
  try{await client.post(`/bureau/cases/${props.caseId}/electronic-deliveries/${row.id}/replace`,body);if(!current())return;replaceVisible.value=false;replacementRow.value=null;busy.value=false;await load()}
  catch{if(current())ElMessage.warning('替代结果未确认，请先刷新核对；重试保持原新轮次请求号。')}
  finally{if(current())busy.value=false}
}
async function downloadEvidence(row:any){
  if(!active()||busy.value||!data.value?.events.includes(row)||!row.evidence_sha256)return
  const current=capture();busy.value=true
  try{const response=await client.get(`/bureau/cases/${props.caseId}/electronic-deliveries/events/${row.id}/evidence`,{responseType:'blob'});if(!current())return
    const blob=response.data instanceof Blob?response.data:new Blob([response.data]);const digest=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await blob.arrayBuffer()))).map(v=>v.toString(16).padStart(2,'0')).join('');if(!current())return
    if(digest!==row.evidence_sha256){ElMessage.warning('回证摘要不一致，请联系管理员核查');return}const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download=`receipt-${row.id}.bin`;a.click();URL.revokeObjectURL(url)
  }catch{if(current())ElMessage.warning('原始回证下载失败，请刷新核对')}
  finally{if(current())busy.value=false}
}
async function expire(){
  if(!active()||!canWrite.value||busy.value)return
  const current=capture();busy.value=true
  try{await client.post(`/bureau/cases/${props.caseId}/electronic-deliveries/expire`,{});if(!current())return;busy.value=false;await load()}
  catch{ /* API supplies the request error; keep the current evidence visible. */ }
  finally{if(current())busy.value=false}
}
onMounted(()=>{window.addEventListener(SESSION_EXPIRED_EVENT,clear);void load()})
onUnmounted(()=>{disposed=true;clear();window.removeEventListener(SESSION_EXPIRED_EVENT,clear)})
</script>
<style scoped>.toolbar{display:flex;align-items:center;justify-content:space-between}.toolbar h4{margin:0}.gap{margin-top:12px}.hint{font-size:12px;color:#666}</style>
