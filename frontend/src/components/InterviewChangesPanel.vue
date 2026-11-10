<template>
  <el-card v-if="allowed" class="changes">
    <h4>实际约谈更正、作废及替代</h4>
    <p>原约谈和协议来源保留。更正、误建作废和作废后的替代须提供依据，另一名局机关法制或负责人复核后进入新报表。</p>
    <TaskFocusBanner param-key="interview" label="实际约谈" :loading="loading" :failed="!!failed" :count="records.length" @change="focusRecord" />
    <TaskFocusBanner v-if="reviewer" param-key="interviewRequest" label="约谈复核申请" :loading="loading" :failed="!!failed" :count="pending.length" @change="focusReview" />
    <el-button :disabled="saving" :loading="loading" @click="load">刷新约谈及申请</el-button>
    <el-alert v-if="failed" type="warning" :closable="false" :title="failed" />
    <el-table v-if="reviewFocus === undefined" :data="records" border size="small" v-loading="loading">
      <el-table-column prop="id" label="编号" width="70" /><el-table-column prop="department_name" label="部门" />
      <el-table-column prop="institution_name" label="机构" /><el-table-column prop="interview_date" label="实际日期" />
      <el-table-column label="状态"><template #default="{ row }">{{ row.voided ? '已作废' : '有效' }}</template></el-table-column>
      <el-table-column label="操作" width="220"><template #default="{ row }">
        <el-button link :disabled="saving" @click="showHistory(row)">申请历史</el-button>
        <el-button v-if="row.can_request && !row.voided" link type="primary" :disabled="saving" @click="open(row, 'CORRECT')">申请更正</el-button>
        <el-button v-if="row.can_request && !row.voided" link type="danger" :disabled="saving" @click="open(row, 'VOID')">误建作废</el-button>
        <el-button v-if="row.can_replace" link type="primary" :disabled="saving" @click="open(row, 'REPLACE')">申请替代约谈</el-button>
      </template></el-table-column>
    </el-table>
    <el-pagination v-if="recordFocus === undefined && reviewFocus === undefined" :current-page="page" :page-size="20" :total="total" :disabled="loading || saving"
      layout="total, prev, pager, next" @current-change="changePage" />
    <template v-if="reviewer && recordFocus === undefined">
      <h4>待复核申请</h4>
      <el-table :data="pending" border size="small">
        <el-table-column prop="id" label="申请" width="75" /><el-table-column prop="interview_id" label="约谈编号" />
        <el-table-column label="类型"><template #default="{row}">{{ kindLabel(row.request_kind) }}</template></el-table-column>
        <el-table-column prop="requested_username" label="申请人" /><el-table-column prop="reason" label="理由" />
        <el-table-column label="操作"><template #default="{ row }"><el-button link :disabled="saving" @click="inspect(row)">查看快照及复核</el-button></template></el-table-column>
      </el-table>
      <el-pagination v-if="reviewFocus === undefined" :current-page="pendingPage" :page-size="20" :total="pendingTotal" :disabled="loading || saving"
        layout="total, prev, pager, next" @current-change="changePendingPage" />
    </template>
    <el-dialog v-model="visible" :title="'申请' + kindLabel(kind)" width="650px"
      :close-on-click-modal="!saving" :close-on-press-escape="!saving" :show-close="!saving">
      <el-form label-width="100px" :disabled="saving">
        <template v-if="kind !== 'VOID'">
          <el-form-item v-for="field in fields" :key="field.key" :label="field.label" required>
            <el-input v-model="form[field.key]" :maxlength="field.max" :type="field.max > 512 ? 'textarea' : 'text'" :readonly="kind === 'REPLACE' && field.key === 'institutionName'" />
          </el-form-item>
          <el-form-item label="约谈日期" required><el-date-picker v-model="form.interviewDate" value-format="YYYY-MM-DD" /></el-form-item>
        </template>
        <el-form-item label="申请理由" required><el-input v-model="reason" type="textarea" maxlength="2000" /></el-form-item>
        <el-form-item label="材料出处" required><el-input v-model="reference" maxlength="1000" /></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="visible = false">取消</el-button><el-button type="primary" :loading="saving" @click="submit">提交另一人复核</el-button></template>
    </el-dialog>
    <el-dialog v-model="historyVisible" title="约谈申请与原始快照" width="85%" :show-close="!saving" :close-on-click-modal="!saving">
      <el-table :data="history" border size="small"><el-table-column prop="id" label="申请" width="75" />
        <el-table-column label="类型"><template #default="{row}">{{ kindLabel(row.request_kind) }}</template></el-table-column><el-table-column prop="status" label="状态" />
        <el-table-column prop="requested_username" label="申请人" /><el-table-column prop="reason" label="理由" />
        <el-table-column prop="source_reference" label="出处" /><el-table-column prop="reviewed_username" label="复核人" />
        <el-table-column prop="opinion" label="复核意见" />
        <el-table-column label="操作"><template #default="{ row }"><el-button link :disabled="saving" @click="inspect(row)">查看内容</el-button></template></el-table-column>
      </el-table>
      <template v-if="inspected">
        <h4>申请 {{ inspected.id }} 原始、当前及拟变更内容</h4>
        <el-table :data="[...fields, {key:'interviewDate',label:'约谈日期'}]" border size="small">
          <el-table-column prop="label" label="字段" width="100" />
          <el-table-column label="原登记值"><template #default="{row}">{{ snapshotValue('original',row.key) }}</template></el-table-column>
          <el-table-column label="申请时有效值"><template #default="{row}">{{ snapshotValue('effective',row.key) }}</template></el-table-column>
          <el-table-column label="拟变更值"><template #default="{row}">{{ inspected.request_kind === 'VOID' ? '整条约谈作废' : proposedValue(row.key) }}</template></el-table-column>
        </el-table>
        <el-input v-if="inspected.canReview || inspected.canCancel" v-model="opinion" maxlength="2000" type="textarea" placeholder="核对意见或撤回理由" :disabled="saving" />
      </template>
      <template #footer><el-button :disabled="saving" @click="historyVisible = false">关闭</el-button>
        <el-button v-if="inspected?.canCancel" :loading="saving" @click="decide('cancel')">撤回申请</el-button>
        <el-button v-if="inspected?.canReview" :loading="saving" @click="decide('reject')">驳回</el-button>
        <el-button v-if="inspected?.canReview" type="primary" :loading="saving" @click="decide('approve')">批准</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'
import { localDate } from '../api/dates'
import { SESSION_EXPIRED_EVENT, sessionExpiry } from '../api/sessionExpiry'
import TaskFocusBanner from './TaskFocusBanner.vue'
const auth = useAuthStore()
const identity = computed(() => JSON.stringify([auth.token, auth.user?.id, (auth.user as (typeof auth.user & { deptId?: number }))?.deptId, auth.user?.roles, auth.user?.accountCategory]))
const allowed = computed(() => !!auth.user?.accountCategory && auth.user.roles.some(r => ['HANDLER','LEADER','LEGAL','ADMIN','AGENCY'].includes(r)))
const reviewer = computed(() => auth.user?.accountCategory === 'BUREAU' && auth.user.roles.some(r => ['LEGAL','LEADER'].includes(r)))
const base = computed(() => auth.user?.accountCategory === 'AGENCY' ? '/agency/interview-changes' : '/bureau/interview-changes')
interface RecordRow { id: number; department_name: string; institution_name: string; institution_code: string; interview_type: string; reason: string; content: string; participants: string; interview_date: string; voided: boolean; can_request: boolean; can_replace: boolean; replacement_institution_name?: string }
interface Change { id: number; interview_id: number; status: string; request_kind: string; source_snapshot: unknown; proposed_values: unknown; canReview: boolean; canCancel: boolean }
type Field = 'institutionCode'|'institutionName'|'interviewType'|'reason'|'content'|'participants'
type ChangeKind = 'CORRECT'|'VOID'|'REPLACE'
const fields: { key: Field; label: string; max: number }[] = [{key:'institutionCode',label:'机构编码',max:64},{key:'institutionName',label:'机构名称',max:128},{key:'interviewType',label:'约谈类型',max:64},{key:'reason',label:'约谈事由',max:1000},{key:'content',label:'约谈内容',max:12000},{key:'participants',label:'约谈人员',max:512}]
const form = reactive({ institutionCode:'',institutionName:'',interviewType:'',reason:'',content:'',participants:'',interviewDate:'' })
const records=ref<RecordRow[]>([]),pending=ref<Change[]>([]),history=ref<Change[]>([]),selected=ref<RecordRow>(),inspected=ref<Change>()
const total=ref(0),page=ref(1),pendingTotal=ref(0),pendingPage=ref(1)
const recordFocus=ref<number>(),reviewFocus=ref<number>()
const loading=ref(false),saving=ref(false),failed=ref(''),visible=ref(false),historyVisible=ref(false)
const kind=ref<ChangeKind>('CORRECT'),reason=ref(''),reference=ref(''),opinion=ref('')
let disposed=false,readRun=0,historyRun=0,saveRun=0,requestKey: string|undefined
function kindLabel(type:string){return type==='VOID'?'误建作废':type==='REPLACE'?'替代约谈':'实际约谈更正'}
function snapshotValue(part:string,key:string){
  const keys:Record<string,string>={institutionCode:'institution_code',institutionName:'institution_name',interviewType:'interview_type',interviewDate:'interview_date'}
  const data=inspected.value?.source_snapshot as Record<string,Record<string,unknown>>|undefined
  return String(data?.[part]?.[keys[key]||key]??'—')
}
function proposedValue(key:string){return String((inspected.value?.proposed_values as Record<string,unknown>|undefined)?.[key]??'—')}
function capture(){return {who:identity.value,stamp:sessionExpiry.capture(auth.token)}}
function current(c:ReturnType<typeof capture>){return !disposed&&allowed.value&&c.who===identity.value&&sessionExpiry.isCurrent(c.stamp,auth.token)&&!sessionExpiry.isExpired(c.stamp,auth.token)}
function clear(){readRun++;historyRun++;saveRun++;records.value=[];pending.value=[];history.value=[];selected.value=undefined;inspected.value=undefined;visible.value=false;historyVisible.value=false;loading.value=false;saving.value=false;failed.value='';total.value=0;pendingTotal.value=0;requestKey=undefined}
async function load(){
  if(!allowed.value||disposed)return
  const run=++readRun,c=capture(),url=base.value;loading.value=true;failed.value=''
  try{
    const [a,b]=await Promise.all([
      reviewFocus.value===undefined && recordFocus.value!==0 ? client.get(url+'/records',{params:{page:page.value,size:20,focus:recordFocus.value}}) : Promise.resolve(undefined),
      reviewer.value && recordFocus.value===undefined && reviewFocus.value!==0 ? client.get(url+'/pending',{params:{page:pendingPage.value,size:20,focus:reviewFocus.value}}) : Promise.resolve(undefined)
    ])
    if(run!==readRun||!current(c))return
    records.value=a?.data.data.rows||[];total.value=a?.data.data.total||0;pending.value=b?.data.data.rows||[];pendingTotal.value=b?.data.data.total||0
  }catch(error){if(run===readRun&&current(c)){records.value=[];pending.value=[];failed.value=(error as Error)?.message||'约谈申请加载失败，请核对机构类别和岗位'}}
  finally{if(run===readRun)loading.value=false}
}
function changePage(n:number){page.value=n;void load()}
function changePendingPage(n:number){pendingPage.value=n;void load()}
function focusRecord(value:number|undefined){if(recordFocus.value===value)return;recordFocus.value=value;page.value=1;void load()}
function focusReview(value:number|undefined){if(reviewFocus.value===value)return;reviewFocus.value=value;pendingPage.value=1;void load()}
function open(row:RecordRow,type:ChangeKind){
  if(saving.value||!records.value.includes(row)||(type==='REPLACE'?!row.can_replace:!row.can_request||row.voided))return
  selected.value=row;kind.value=type
  const replacing=type==='REPLACE'
  Object.assign(form,{institutionCode:row.institution_code,institutionName:replacing?row.replacement_institution_name:row.institution_name,
    interviewType:row.interview_type,reason:replacing?'':row.reason,content:replacing?'':row.content,participants:replacing?'':row.participants,interviewDate:replacing?'':row.interview_date})
  reason.value='';reference.value='';requestKey=undefined;visible.value=true
}
async function showHistory(row:RecordRow){
  if(saving.value||!records.value.includes(row))return
  const run=++historyRun,c=capture();history.value=[];inspected.value=undefined;historyVisible.value=true
  try{const {data}=await client.get(base.value+'/'+row.id);if(run===historyRun&&current(c)&&historyVisible.value)history.value=data.data}catch{if(run===historyRun&&current(c))historyVisible.value=false}
}
function inspect(row:Change){if(saving.value||!pending.value.includes(row)&&!history.value.includes(row))return;inspected.value=row;opinion.value='';historyVisible.value=true}
async function submit(){
  const row=selected.value;if(!row||!visible.value||saving.value||!allowed.value)return
  if(!reason.value.trim()||!reference.value.trim()||kind.value!=='VOID'&&(Object.values(form).some(x=>!x?.trim())||form.interviewDate>localDate())){ElMessage.warning('请填写有效约谈资料、理由和材料出处');return}
  requestKey ||= crypto.randomUUID();const body={requestKey,kind:kind.value,values:kind.value==='VOID'?null:{...form},reason:reason.value.trim(),sourceReference:reference.value.trim()}
  const c=capture(),run=++saveRun;saving.value=true
  try{await client.post(base.value+'/'+row.id,body);if(run!==saveRun||!current(c))return;visible.value=false;ElMessage.success('约谈申请已提交，待另一人复核');await load()}catch{/* preserve original request key for retry */}finally{if(run===saveRun)saving.value=false}
}
async function decide(action:'approve'|'reject'|'cancel'){
  const r=inspected.value;if(!r||saving.value||!historyVisible.value||!allowed.value||!(action==='cancel'?r.canCancel:r.canReview))return
  if(!opinion.value.trim()){ElMessage.warning('请填写核对意见');return}
  const c=capture(),run=++saveRun,url=base.value+'/requests/'+r.id+(action==='cancel'?'/cancel':'/review'),body=action==='cancel'?{opinion:opinion.value.trim()}:{approve:action==='approve',opinion:opinion.value.trim()}
  saving.value=true
  try{await client.post(url,body);if(run!==saveRun||!current(c))return;historyVisible.value=false;inspected.value=undefined;ElMessage.success('约谈申请已处理，原记录保留');await load()}catch{/* preserve opinion */}finally{if(run===saveRun)saving.value=false}
}
watch([form,reason,reference,kind],()=>{requestKey=undefined},{deep:true,flush:'sync'})
watch(identity,()=>{clear();page.value=1;pendingPage.value=1;if(!reviewer.value)reviewFocus.value=undefined;void load()},{immediate:true,flush:'sync'})
watch(historyVisible,v=>{if(!v){historyRun++;history.value=[];inspected.value=undefined}},{flush:'sync'})
window.addEventListener(SESSION_EXPIRED_EVENT,clear)
onBeforeUnmount(()=>{disposed=true;clear();window.removeEventListener(SESSION_EXPIRED_EVENT,clear)})
</script>
<style scoped>.changes{margin-top:12px;margin-bottom:12px}.el-pagination{margin-top:10px}pre{white-space:pre-wrap;max-height:260px;overflow:auto;overflow-wrap:anywhere}</style>
