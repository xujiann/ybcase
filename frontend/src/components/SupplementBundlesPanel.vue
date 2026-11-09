<template>
  <el-card v-if="permitted" class="supplements">
    <div class="toolbar"><h4>归档后续材料与补充卷</h4><el-button :loading="loading" :disabled="busy" @click="load()">刷新补充卷</el-button></div>
    <p>后续材料独立保存，原封存案卷保持原样。上传真实凭证、提交另一人复核后，可关联续办申请、制作后续文书及导出补充卷。</p>
    <el-alert v-if="failed" type="warning" :closable="false" title="后续材料未能加载，请核对单案范围或刷新重试。" />
    <el-alert v-if="focusFailed" type="warning" :closable="false" title="目标材料包已处理、已失效或不在当前复核范围，请在本案材料列表核对。" />
    <el-button v-if="writer" type="primary" :disabled="busy || !data" @click="openCreate">建立材料包</el-button>
    <template v-if="data">
      <el-table :data="data.bundles" border size="small" class="gap" row-key="id" :expand-row-keys="focusedBundle ? [focusedBundle.id] : []" :row-class-name="bundleRowClass">
        <el-table-column type="expand"><template #default="{row}">
          <p>材料出处：{{ row.source_reference }}　复核人：{{ row.reviewed_by || '—' }}　意见：{{ row.opinion || '—' }}</p>
          <p v-if="row.manifest_sha256">已封存清单摘要：<code>{{ row.manifest_sha256 }}</code></p>
          <el-table :data="row.files" border size="small"><el-table-column prop="filename" label="原始材料" />
            <el-table-column prop="size_bytes" label="字节数" width="100" /><el-table-column prop="sha256" label="文件摘要" show-overflow-tooltip />
            <el-table-column label="操作" width="90"><template #default="{row:file}"><el-button link :disabled="busy || downloading" @click="downloadFile(file)">下载原件</el-button></template></el-table-column>
          </el-table>
        </template></el-table-column>
        <el-table-column prop="id" label="包编号" width="80" /><el-table-column prop="title" label="名称" min-width="150" />
        <el-table-column label="状态" width="100"><template #default="{row}">{{ statuses[row.status] || row.status }}</template></el-table-column>
        <el-table-column prop="created_by" label="申请人" width="115" />
        <el-table-column label="操作" min-width="340"><template #default="{row}">
          <template v-if="own(row) && row.status === 'DRAFT'">
            <label class="upload"><input type="file" :disabled="busy" @change="upload(row, $event)" />上传凭证</label>
            <el-button link type="primary" :disabled="busy" @click="submitBundle(row)">提交复核</el-button>
          </template>
          <el-button v-if="own(row) && ['DRAFT','PENDING'].includes(row.status)" link :disabled="busy" @click="openOpinion(row,'withdraw')">撤回</el-button>
          <template v-if="canReview(row)"><el-button link type="primary" :disabled="busy" @click="openOpinion(row,'approve')">核对批准</el-button><el-button link :disabled="busy" @click="openOpinion(row,'reject')">退回</el-button></template>
          <template v-if="row.status === 'APPROVED'"><el-button link :disabled="busy || downloading" @click="exportBundle(row)">导出补充卷</el-button><el-button v-if="writer" link type="primary" :disabled="busy" @click="openDocument(row)">制作后续文书</el-button></template>
        </template></el-table-column>
      </el-table>
      <h4>后续版本文书</h4>
      <el-table :data="data.documents" border size="small">
        <el-table-column type="expand"><template #default="{row}"><h4>{{ row.title }}</h4><pre>{{ row.content }}</pre>
          <p>关联决定版本：{{ row.decision_version_id || '—' }}　材料包：{{ row.bundle_id }}</p>
          <el-table :data="row.signatures" border size="small"><el-table-column prop="signer" label="签章人" /><el-table-column label="签章性质"><template #default="{row:sig}"><el-tag :type="sig.provider==='MOCK'?'warning':'success'">{{ sig.provider==='MOCK'?'模拟摘要签章':sig.provider }}</el-tag></template></el-table-column><el-table-column prop="content_hash" label="正文摘要" show-overflow-tooltip /></el-table>
          <h4>本版本送达记录</h4><el-table :data="row.deliveries" border size="small"><el-table-column prop="method" label="方式" /><el-table-column prop="announced_at" label="公告发布日期" /><el-table-column prop="delivered_at" label="送达生效日" /><el-table-column prop="receiver" label="受送达人" /><el-table-column prop="receipt_no" label="回证号" /><el-table-column prop="source_reference" label="回证材料出处" /></el-table>
          <p v-if="row.consents.length">最新同意受送达人：{{ row.consents[0].receiver }}；地址：{{ row.consents[0].channel }}；材料出处：{{ row.consents[0].source_reference }}</p>
        </template></el-table-column>
        <el-table-column prop="id" label="文书编号" width="90" /><el-table-column prop="title" label="后续文书" />
        <el-table-column prop="decision_version_id" label="决定版本" width="90" />
        <el-table-column v-if="writer" label="操作" min-width="330"><template #default="{row}">
          <el-button link :disabled="busy" @click="sign(row)">签章</el-button><el-button link :disabled="busy" @click="openDocumentAction(row,'deliver')">登记实际送达</el-button>
          <el-button link :disabled="busy" @click="openDocumentAction(row,'consent')">核实电子同意地址</el-button>
          <el-button link :disabled="busy || !row.consents.length" @click="electronic(row)">建立电子发送</el-button>
        </template></el-table-column>
      </el-table>
      <p>电子发送建立后，在本案“电子送达与回证”面板刷新，核对网关配置后发送或重试。模拟签章仅用于联调展示，正式效力以已验收的供应商及运行策略为准。</p>
      <template v-if="Array.isArray(data.timeline)">
        <h4>独立后续时间轴</h4><p>以下记录反映后续材料、文书及办理事实，原封存案卷不回写。</p>
        <el-table :data="data.timeline" border size="small"><el-table-column prop="event_time" label="时间" min-width="170" />
          <el-table-column prop="event_type" label="事项" min-width="140" /><el-table-column prop="reference" label="关联材料或文书" min-width="170" />
          <el-table-column prop="detail" label="办理事实" min-width="220" show-overflow-tooltip /><el-table-column prop="actor" label="办理人" width="110" />
        </el-table>
      </template>
    </template>
    <el-dialog v-model="createVisible" title="建立独立后续材料包" width="600px" :show-close="!busy" :close-on-click-modal="!busy">
      <el-form label-width="90px" :disabled="busy"><el-form-item label="材料包名称" required><el-input v-model="createForm.title" maxlength="200" /></el-form-item><el-form-item label="材料出处" required><el-input v-model="createForm.sourceReference" maxlength="1000" type="textarea" /></el-form-item></el-form>
      <p>每包最多20份原件、合计32MB，每份不超过10MB。提交后文件不能更换。</p>
      <template #footer><el-button :disabled="busy" @click="createVisible=false">取消</el-button><el-button :loading="busy" type="primary" @click="create">建立草稿</el-button></template>
    </el-dialog>
    <el-dialog v-model="opinionVisible" title="核对原始材料及办理意见" width="650px" :show-close="!busy" :close-on-click-modal="!busy">
      <template v-if="selectedBundle"><p>材料包 {{ selectedBundle.id }}：{{ selectedBundle.title }}</p><p>出处：{{ selectedBundle.source_reference }}</p>
        <ul><li v-for="file in selectedBundle.files" :key="file.id"><el-button link :disabled="downloading" @click="downloadFile(file)">{{ file.filename }}</el-button> {{ file.sha256 }}</li></ul>
      </template><el-input v-model="opinion" maxlength="2000" type="textarea" :rows="3" :disabled="busy" placeholder="逐项核对意见或撤回理由" />
      <template #footer><el-button :disabled="busy" @click="opinionVisible=false">取消</el-button><el-button :loading="busy" type="primary" @click="processOpinion">确认办理</el-button></template>
    </el-dialog>
    <el-dialog v-model="documentVisible" title="制作独立后续文书" width="720px" :show-close="!busy" :close-on-click-modal="!busy">
      <el-form label-width="100px" :disabled="busy"><el-form-item label="文书类型"><el-select v-model="docForm.docType"><el-option v-for="(label,key) in docTypes" :key="key" :value="key" :label="label" /></el-select></el-form-item>
        <el-form-item label="决定版本"><el-select v-model="docForm.decisionVersionId" clearable @change="selectVersion"><el-option v-for="version in data?.decisionVersions" :key="version.id" :value="version.id" :label="version.decision_no + '（版本'+version.id+'）'" /></el-select></el-form-item>
        <el-form-item label="文书标题" required><el-input v-model="docForm.title" maxlength="200" /></el-form-item><el-form-item label="文书正文" required><el-input v-model="docForm.content" type="textarea" :rows="10" maxlength="16000" :readonly="docForm.docType==='DECISION' && !!docForm.decisionVersionId" /></el-form-item>
      </el-form><p>后续决定必须关联具体已批准结果版本，正文按该版本原文保存。</p>
      <template #footer><el-button :disabled="busy" @click="documentVisible=false">取消</el-button><el-button :loading="busy" type="primary" @click="createDocument">保存后续文书</el-button></template>
    </el-dialog>
    <el-dialog v-model="actionVisible" :title="actionMode==='consent'?'核实本版本电子送达同意地址':'登记本版本实际送达'" width="620px" :show-close="!busy" :close-on-click-modal="!busy">
      <el-form label-width="100px" :disabled="busy"><el-form-item label="受送达人" required><el-input v-model="actionForm.receiver" maxlength="64" /></el-form-item>
        <el-form-item v-if="actionMode==='consent'" label="同意地址" required><el-input v-model="actionForm.channel" maxlength="500" /></el-form-item>
        <template v-else>
          <el-form-item label="实际方式"><el-select v-model="actionForm.method"><el-option v-for="(label,key) in methods" :key="key" :value="key" :label="label" /></el-select></el-form-item>
          <el-form-item :label="actionForm.method==='ANNOUNCE'?'公告发布日期':'实际送达日期'" required><el-date-picker v-model="actionForm.deliveryDate" value-format="YYYY-MM-DD" /></el-form-item>
          <p v-if="actionForm.method==='ANNOUNCE'">平台按公告发布日期和配置期限计算送达生效日。</p>
          <el-form-item label="回证号" required><el-input v-model="actionForm.receiptNo" maxlength="64" /></el-form-item>
        </template>
        <el-form-item label="材料出处" required><el-input v-model="actionForm.sourceReference" maxlength="1000" type="textarea" /></el-form-item>
      </el-form><template #footer><el-button :disabled="busy" @click="actionVisible=false">取消</el-button><el-button :loading="busy" type="primary" @click="saveDocumentAction">保存实际资料</el-button></template>
    </el-dialog>
  </el-card>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'
import { localDate } from '../api/dates'
import { parseTaskFocus } from '../api/taskFocus'
const props=defineProps<{caseId:number;writable?:boolean;refreshKey?:unknown}>(),auth=useAuthStore(),route=useRoute()
interface Material {id:number;filename:string;sha256:string;size_bytes:number}
interface Bundle {id:number;title:string;status:string;source_reference:string;created_by:string;manifest_sha256?:string;files:Material[];reviewed_by?:string;opinion?:string}
interface Doc {id:number;bundle_id:number;decision_version_id?:number;doc_type:string;title:string;content:string;signatures:any[];deliveries:any[];consents:any[]}
interface State {bundles:Bundle[];documents:Doc[];decisionVersions:{id:number;decision_no:string;content?:string}[];timeline?:{event_type:string;event_time:string;reference:string;detail:string;actor:string}[]}
const data=ref<State>(),loading=ref(false),failed=ref(false),busy=ref(false),downloading=ref(false)
const focusFailed=ref(false),focusId=computed(()=>parseTaskFocus(route.query.supplementBundle))
const focusedBundle=computed(()=>data.value?.bundles.find(row=>row.id===focusId.value))
const createVisible=ref(false),documentVisible=ref(false),opinionVisible=ref(false),actionVisible=ref(false)
const selectedBundle=ref<Bundle>(),selectedDoc=ref<Doc>(),opinion=ref(''),operation=ref<'approve'|'reject'|'withdraw'>('approve')
const createForm=reactive({title:'',sourceReference:''}),docForm=reactive({docType:'OTHER',title:'',content:'',decisionVersionId:undefined as number|undefined})
const actionMode=ref<'consent'|'deliver'>('deliver'),actionForm=reactive({receiver:'',channel:'',method:'DIRECT',deliveryDate:localDate(),receiptNo:'',sourceReference:''})
const statuses:Record<string,string>={DRAFT:'草稿',PENDING:'待复核',APPROVED:'已批准',REJECTED:'已退回',WITHDRAWN:'已撤回'}
const docTypes:Record<string,string>={DECISION:'后续决定',NOTICE:'后续告知',URGE_LETTER:'催告文书',COURT_RECEIPT:'法院回执',OTHER:'其他文书'}
const methods:Record<string,string>={DIRECT:'直接送达',POST:'邮寄送达',LEAVE:'留置送达',ANNOUNCE:'公告送达',OTHER:'其他实际方式'}
const permitted=computed(()=>!!auth.user?.roles.some(r=>['HANDLER','LEGAL','LEADER','ADMIN'].includes(r))&&!auth.user.roles.some(r=>['AGENCY','INTEGRATION'].includes(r)))
const writer=computed(()=>permitted.value&&props.writable!==false&&!!auth.user?.roles.some(r=>['HANDLER','LEADER','ADMIN'].includes(r)))
const reviewer=computed(()=>auth.user?.accountCategory==='BUREAU'&&!!auth.user.roles.some(r=>['LEGAL','LEADER'].includes(r)))
const identity=computed(()=>JSON.stringify([props.caseId,props.writable,auth.token,auth.user?.id,auth.user?.roles,auth.user?.accountCategory,route.query.supplementBundle]))
const base=()=>`/bureau/cases/${props.caseId}/supplement-bundles`
let disposed=false,readRun=0,writeRun=0,downloadRun=0,createKey='',documentKey='',electronicKeys=new Map<number,string>()
function context(){return {who:identity.value,stamp:sessionExpiry.capture(auth.token)}}
function current(c:ReturnType<typeof context>){return !disposed&&permitted.value&&c.who===identity.value&&sessionExpiry.isCurrent(c.stamp,auth.token)&&!sessionExpiry.isExpired(c.stamp,auth.token)}
function clear(){readRun++;writeRun++;downloadRun++;data.value=undefined;loading.value=false;failed.value=false;busy.value=false;downloading.value=false;focusFailed.value=false;createVisible.value=false;documentVisible.value=false;opinionVisible.value=false;actionVisible.value=false;selectedBundle.value=undefined;selectedDoc.value=undefined;createKey='';documentKey='';electronicKeys.clear()}
async function load(force=false){
 if(disposed||!permitted.value||!auth.token||!Number.isSafeInteger(props.caseId)||props.caseId<1||busy.value&&!force)return
 const run=++readRun,c=context();loading.value=true;failed.value=false;data.value=undefined
 try{const response=await client.get(base());if(run===readRun&&current(c)){const result=response.data.data;if(!Array.isArray(result?.bundles)||!Array.isArray(result?.documents)||!Array.isArray(result?.decisionVersions))throw new Error('Invalid supplementary bundle list');data.value=result;applyFocus()}}
 catch{if(run===readRun&&current(c)){failed.value=true;data.value=undefined;focusFailed.value=focusId.value!==undefined}}finally{if(run===readRun)loading.value=false}
}
function bundleRowClass({row}:{row:Bundle}){return row.id===focusId.value?'focused-bundle':''}
function applyFocus(){
 focusFailed.value=false
 if(focusId.value===undefined)return
 const row=focusedBundle.value
 if(!row||!canReview(row)){focusFailed.value=true;return}
 selectedBundle.value=row;operation.value='approve';opinion.value='';opinionVisible.value=true
}
function own(row:Bundle){return writer.value&&row.created_by===auth.user?.username}
function canReview(row:Bundle){return reviewer.value&&row.status==='PENDING'&&row.created_by!==auth.user?.username}
function liveBundle(row:Bundle){return !!data.value?.bundles.includes(row)}
function liveDoc(row:Doc){return !!data.value?.documents.includes(row)}
async function mutate(url:string,body:unknown,onSuccess?:(value:any)=>void){
 if(busy.value||disposed||!permitted.value)return
 const c=context(),run=++writeRun;busy.value=true
 try{const response=await client.post(url,body);if(run!==writeRun||!current(c))return;onSuccess?.(response.data.data);await load(true)}catch{/* shared client reports failures; drafts remain available for retry */}finally{if(run===writeRun)busy.value=false}
}
function openCreate(){if(!writer.value||!data.value||busy.value)return;createForm.title='';createForm.sourceReference='';createKey='';createVisible.value=true}
async function create(){if(!writer.value||!createVisible.value||busy.value)return;if(!createForm.title.trim()||!createForm.sourceReference.trim()){ElMessage.warning('请填写材料包名称和出处');return}createKey ||= crypto.randomUUID();await mutate(base(),{...createForm,requestKey:createKey},()=>{createVisible.value=false;createKey='';ElMessage.success('独立材料草稿已建立，请上传实际凭证')})}
async function upload(row:Bundle,event:Event){
 const input=event.target as HTMLInputElement,file=input.files?.[0];if(!file||busy.value||!own(row)||!liveBundle(row)||row.status!=='DRAFT')return
 if(file.size<1||file.size>10*1024*1024){ElMessage.warning('每份材料须为1字节至10MB');input.value='';return}
 const form=new FormData();form.append('file',file)
 try{await mutate(base()+'/'+row.id+'/files',form,()=>ElMessage.success('凭证已保存，提交后按摘要封存'))}finally{input.value=''}
}
async function submitBundle(row:Bundle){if(own(row)&&liveBundle(row)&&row.status==='DRAFT'&&!busy.value){if(!row.files.length){ElMessage.warning('请先上传真实凭证');return}await mutate(base()+'/'+row.id+'/submit',{},()=>ElMessage.success('材料已提交另一人复核'))}}
function openOpinion(row:Bundle,action:'approve'|'reject'|'withdraw'){if(busy.value||!liveBundle(row)||!(action==='withdraw'?own(row)&&['DRAFT','PENDING'].includes(row.status):canReview(row)))return;selectedBundle.value=row;operation.value=action;opinion.value='';opinionVisible.value=true}
async function processOpinion(){const row=selectedBundle.value;if(!row||!opinionVisible.value||busy.value||!liveBundle(row)||!(operation.value==='withdraw'?own(row)&&['DRAFT','PENDING'].includes(row.status):canReview(row)))return;if(!opinion.value.trim()){ElMessage.warning('请填写核对意见');return}const cancel=operation.value==='withdraw';await mutate(base()+'/'+row.id+(cancel?'/withdraw':'/review'),{opinion:opinion.value.trim(),...(cancel?{}:{approve:operation.value==='approve'})},()=>{opinionVisible.value=false;ElMessage.success('材料包已处理，原文件继续保留')})}
function openDocument(row:Bundle){if(!writer.value||busy.value||!liveBundle(row)||row.status!=='APPROVED')return;selectedBundle.value=row;Object.assign(docForm,{docType:'OTHER',title:'',content:'',decisionVersionId:undefined});documentKey='';documentVisible.value=true}
function selectVersion(){if(docForm.docType!=='DECISION')return;const version=data.value?.decisionVersions.find(v=>v.id===docForm.decisionVersionId);docForm.content=version?.content||'';if(version)docForm.title=version.decision_no}
async function createDocument(){const row=selectedBundle.value;if(!row||!writer.value||busy.value||!documentVisible.value||!liveBundle(row)||row.status!=='APPROVED')return;if(!docForm.title.trim()||!docForm.content.trim()||docForm.docType==='DECISION'&&!docForm.decisionVersionId){ElMessage.warning('请填写后续文书，决定文书须选择具体结果版本');return}documentKey ||= crypto.randomUUID();await mutate(base()+'/'+row.id+'/documents',{...docForm,requestKey:documentKey},()=>{documentVisible.value=false;documentKey='';ElMessage.success('后续文书已独立保存')})}
async function sign(row:Doc){if(writer.value&&!busy.value&&liveDoc(row))await mutate(base()+'/documents/'+row.id+'/sign',{},v=>ElMessage.success(v.provider==='MOCK'?'已作模拟摘要签章，不能替代正式CA签章':'已签章：'+v.provider))}
function openDocumentAction(row:Doc,mode:'deliver'|'consent'){if(!writer.value||busy.value||!liveDoc(row))return;selectedDoc.value=row;actionMode.value=mode;Object.assign(actionForm,{receiver:'',channel:'',method:'DIRECT',deliveryDate:localDate(),receiptNo:'',sourceReference:''});actionVisible.value=true}
async function saveDocumentAction(){
 const doc=selectedDoc.value
 if(!doc||!writer.value||busy.value||!actionVisible.value||!liveDoc(doc))return
 const {receiver,channel,method,deliveryDate,receiptNo,sourceReference}=actionForm
 const consent=actionMode.value==='consent'
 if(!receiver.trim()||!sourceReference.trim()||(consent?!channel.trim():!receiptNo.trim()||!deliveryDate||deliveryDate>localDate())){
  ElMessage.warning('请核对实际地址、日期、回证及材料出处');return
 }
 const dateField=method==='ANNOUNCE'?'announcedAt':'deliveredAt'
 const body=consent?{receiver,channel,sourceReference}:{receiver,method,[dateField]:deliveryDate,receiptNo,sourceReference}
 await mutate(base()+'/documents/'+doc.id+'/'+actionMode.value,body,()=>{actionVisible.value=false;ElMessage.success('本版本实际资料已记录')})
}
async function electronic(row:Doc){if(!writer.value||busy.value||!liveDoc(row)||!row.consents.length)return;const key=electronicKeys.get(row.id)||crypto.randomUUID();electronicKeys.set(row.id,key);await mutate(`/bureau/cases/${props.caseId}/electronic-deliveries/supplements`,{documentId:row.id,requestKey:key},value=>ElMessage.success('电子发送记录 #'+value.id+' 已建立，请在电子回证面板刷新并发送'))}
async function download(url:string,filename:string,expected?:string){
 if(disposed||!permitted.value||downloading.value)return
 const c=context(),run=++downloadRun;downloading.value=true
 try{const response=await client.get<Blob>(url,{responseType:'blob',timeout:120000});if(run!==downloadRun||!current(c))return;const checksum=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',await response.data.arrayBuffer()))).map(v=>v.toString(16).padStart(2,'0')).join('');if(run!==downloadRun||!current(c))return;const advertised=expected||String(response.headers['x-content-sha256']||'');if(!/^[0-9a-f]{64}$/.test(advertised)||checksum!==advertised){ElMessage.error('文件摘要不一致，请联系管理员核对');return}const link=document.createElement('a');link.href=URL.createObjectURL(response.data);link.download=filename;try{link.click()}finally{URL.revokeObjectURL(link.href)}}catch{/* API/download failure */}finally{if(run===downloadRun)downloading.value=false}
}
async function downloadFile(file:Material){if(!data.value?.bundles.some(b=>b.files.includes(file)))return;await download(base()+'/files/'+file.id,file.filename,file.sha256)}
async function exportBundle(row:Bundle){if(!liveBundle(row)||row.status!=='APPROVED')return;await download(base()+'/'+row.id+'/export',`supplement-${props.caseId}-${row.id}.zip`)}
watch(identity,()=>{clear();void load()},{immediate:true,flush:'sync'})
watch(()=>props.refreshKey,()=>{if(!busy.value)void load()})
watch(createForm,()=>{createKey=''},{deep:true,flush:'sync'})
watch(docForm,()=>{documentKey=''},{deep:true,flush:'sync'})
watch(()=>docForm.docType,selectVersion)
onMounted(()=>window.addEventListener(SESSION_EXPIRED_EVENT,clear))
onBeforeUnmount(()=>{disposed=true;clear();window.removeEventListener(SESSION_EXPIRED_EVENT,clear)})
</script>
<style scoped>.supplements{margin-top:12px;margin-bottom:12px}.toolbar{display:flex;align-items:center;justify-content:space-between}.toolbar h4{margin:0}.gap{margin-top:12px}.upload{display:inline-flex;color:#409eff;cursor:pointer;margin-right:12px}.upload input{width:110px}pre{white-space:pre-wrap;overflow-wrap:anywhere;max-height:300px;overflow:auto}code{overflow-wrap:anywhere}:deep(.focused-bundle){--el-table-tr-bg-color:#ecf5ff}</style>
