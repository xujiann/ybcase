<template>
  <div>
    <TaskFocusBanner param-key="treasuryItem" label="财政核对条目" :loading="focusLoading" :failed="focusFailed" :count="detail?.focusedItem ? detail.items.length : 0" @change="setFocusedItem" />
    <el-alert title="财政对账只核对已登记缴款，不自动入账。差异须分派核查并由另一人复核；需要续登记的款项按原续登记申请流程办理。" type="info" :closable="false" />
    <el-button class="gap" :loading="loading" :disabled="busy" @click="refresh">刷新集成台账</el-button>
    <el-alert v-if="failed" title="台账加载失败，请刷新重试。" type="warning" :closable="false" />
    <el-card v-if="isAdmin" class="gap">
      <h4>网关就绪检查</h4>
      <p class="hint">这里只显示配置状态。探测使用供应商约定的 health/check 接口，通过不代表真实业务联调验收完成。</p>
      <el-table :data="gatewayRows" border size="small">
        <el-table-column prop="label" label="网关" />
        <el-table-column label="启用"><template #default="{row}">{{ row.enabled ? '已启用' : '未启用' }}</template></el-table-column>
        <el-table-column label="地址"><template #default="{row}">{{ row.addressValid ? '格式有效' : '未配置或无效' }}</template></el-table-column>
        <el-table-column label="凭据"><template #default="{row}">{{ row.credentialConfigured ? '已配置' : '未配置' }}</template></el-table-column>
        <el-table-column label="探测"><template #default="{row}"><el-button link :disabled="busy || !row.enabled || !row.addressValid || !row.credentialConfigured" @click="probe(row.key)">执行探测</el-button></template></el-table-column>
      </el-table>
      <p class="hint">电子回执服务账号：{{ readiness?.deliveryCallbackConfigured ? '已配置（仍须验证专用角色和有效登录）' : '未配置' }}</p>
      <p class="hint">自动回执超时检查：{{ readiness?.deliveryTimeoutScanEnabled ? '已启用' : '未启用（默认关闭，可在案件内人工检查）' }}</p>
      <el-table :data="readiness?.operations || []" size="small" max-height="230">
        <el-table-column prop="created_at" label="时间" min-width="180" /><el-table-column prop="target" label="网关" />
        <el-table-column prop="operation" label="操作" /><el-table-column prop="status" label="结果" />
        <el-table-column prop="error_code" label="错误码" /><el-table-column prop="source_id" label="业务编号" />
      </el-table>
    </el-card>
    <el-card class="gap">
      <h4>选择缴款记录建立核对批次</h4>
      <el-input v-model="query" placeholder="按案号搜索" style="width:240px" :disabled="busy" @keyup.enter="search" />
      <el-button :disabled="busy || loading" @click="search">查询</el-button>
      <el-button v-if="canWrite" type="primary" :loading="busy" :disabled="loading || !selected.length" @click="createBatch">建立批次（{{ selected.length }} 笔）</el-button>
      <el-table :data="sources" row-key="selectionKey" border size="small" class="gap" @selection-change="selectSources">
        <el-table-column v-if="canWrite" type="selection" width="45" :selectable="() => !busy && !loading" />
        <el-table-column prop="case_no" label="案号" min-width="150" /><el-table-column prop="sourceLabel" label="登记来源" />
        <el-table-column label="款项"><template #default="{row}">{{ kindLabels[row.kind] || row.kind }}</template></el-table-column>
        <el-table-column prop="receipt_no" label="票据号" min-width="130" /><el-table-column prop="amount" label="金额" />
        <el-table-column prop="paid_at" label="缴款日" width="115" />
      </el-table>
      <el-pagination class="gap" :current-page="sourcePage" :page-size="20" :total="sourceTotal" :disabled="busy || loading" layout="total, prev, pager, next" @current-change="loadPage" />
    </el-card>
    <el-card class="gap">
      <h4>已建立批次</h4>
      <el-table :data="batches" border size="small">
        <el-table-column prop="id" label="批次" width="80" /><el-table-column prop="created_by" label="建立人" />
        <el-table-column prop="created_at" label="建立时间" min-width="180" /><el-table-column prop="total" label="笔数" />
        <el-table-column prop="pending" label="待核对" /><el-table-column prop="matched" label="一致" /><el-table-column prop="differences" label="差异或失败" />
        <el-table-column label="操作"><template #default="{row}"><el-button link :disabled="busy" @click="openBatch(row.id)">查看</el-button></template></el-table-column>
      </el-table>
      <el-pagination class="gap" :current-page="batchPage" :page-size="20" :total="batchTotal" :disabled="busy || loading" layout="total, prev, pager, next" @current-change="loadBatchPage" />
    </el-card>
    <el-dialog v-model="detailVisible" :title="`财政核对批次 ${detail?.id || ''}`" width="1100px" :close-on-click-modal="!busy" :before-close="closeDetail">
      <el-button v-if="canWrite && !detail?.focusedItem" :loading="busy" @click="runBatch">核对下一组／重试失败项（最多10笔）</el-button>
      <p class="hint">失败项可在修复配置或接口后重试；差异的核查、复核和人工续登记分别留痕。已复核结果不重复处理。</p>
      <el-table :data="detail?.items || []" border size="small" class="gap">
        <el-table-column prop="id" label="条目" width="75" /><el-table-column prop="case_no" label="案号" min-width="135" />
        <el-table-column label="款项"><template #default="{row}">{{ kindLabels[row.kind] || row.kind }}</template></el-table-column>
        <el-table-column prop="receipt_no" label="票据号" min-width="120" /><el-table-column prop="amount" label="金额" />
        <el-table-column label="结果" min-width="180"><template #default="{row}">
          {{ resultLabels[row.result] || row.result }}
          <div v-if="row.external_amount != null" class="hint">财政金额 {{ row.external_amount }}；缴款日 {{ row.external_paid_at }}；{{ row.paid ? '已缴清' : '未缴清' }}</div>
        </template></el-table-column>
        <el-table-column label="核查进度"><template #default="{row}">{{ reviewLabels[row.review_status] || row.review_status }}<br>{{ row.assignee || '' }}</template></el-table-column>
        <el-table-column prop="error_code" label="错误码" />
        <el-table-column label="操作" min-width="160"><template #default="{row}">
          <el-button v-if="canWrite && !['PENDING','MATCH'].includes(row.result) && !['SUBMITTED','RESOLVED'].includes(row.review_status)" link :disabled="busy" @click="retryItem(row)">重新核对</el-button>
          <el-button v-if="canAssign && !['PENDING','MATCH'].includes(row.result) && row.review_status !== 'RESOLVED'" link @click="openAction(row,'assign')">分派</el-button>
          <el-button v-if="canWrite && row.assignee === auth.user?.username && row.review_status === 'ASSIGNED'" link @click="openAction(row,'investigate')">提交核查</el-button>
          <el-button v-if="canReview && row.review_status === 'SUBMITTED' && row.investigated_by !== auth.user?.username" link @click="openAction(row,'review')">复核</el-button>
        </template></el-table-column>
      </el-table>
      <h4>处理历史</h4>
      <el-table :data="detail?.history || []" size="small" max-height="200"><el-table-column prop="item_id" label="条目" /><el-table-column label="操作"><template #default="{row}">{{ actionLabels[row.action] || row.action }}</template></el-table-column><el-table-column prop="actor" label="人员" /><el-table-column prop="note" label="说明" min-width="220" /><el-table-column prop="external_amount" label="当次财政金额" min-width="120" /><el-table-column prop="external_paid_at" label="当次财政缴款日" min-width="130" /><el-table-column prop="source_reference" label="核查依据" min-width="150" /></el-table>
    </el-dialog>
    <el-dialog v-model="actionVisible" :title="actionTitle" width="520px" :close-on-click-modal="!busy" :show-close="!busy">
      <p>条目 {{ activeItem?.id }} · {{ activeItem?.case_no }}</p>
      <el-input v-if="action === 'assign'" v-model="form.username" placeholder="承办人员登录账号（须有本案权限）" maxlength="64" />
      <template v-else>
        <p v-if="action === 'review'">核查说明：{{ activeItem?.investigation_note }}<br>核查依据：{{ activeItem?.source_reference }}</p>
        <el-input v-model="form.note" type="textarea" :rows="4" placeholder="核查／复核说明" maxlength="1000" />
        <el-input v-if="action === 'investigate'" class="gap" v-model="form.reference" placeholder="核查凭据或来源编号" maxlength="255" />
        <el-radio-group v-if="action === 'review'" v-model="form.approve" class="gap"><el-radio :value="true">复核通过</el-radio><el-radio :value="false">退回继续核查</el-radio></el-radio-group>
      </template>
      <template #footer><el-button :disabled="busy" @click="actionVisible=false">取消</el-button><el-button type="primary" :loading="busy" @click="submitAction">提交</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import client from '../api/client'
import { useAuthStore } from '../stores/auth'
import { sessionExpiry, SESSION_EXPIRED_EVENT } from '../api/sessionExpiry'
import TaskFocusBanner from './TaskFocusBanner.vue'
const auth=useAuthStore()
const permitted=computed(()=>!auth.user?.roles.some(r=>['AGENCY','INTEGRATION'].includes(r)) && !!auth.user?.roles.some(r=>['HANDLER','LEGAL','LEADER','ADMIN'].includes(r)))
const isAdmin=computed(()=>!!auth.user?.roles.includes('ADMIN'))
const canWrite=computed(()=>permitted.value && !!auth.user?.roles.some(r=>['HANDLER','LEADER','ADMIN'].includes(r)))
const canAssign=computed(()=>permitted.value && !!auth.user?.roles.some(r=>['LEGAL','LEADER','ADMIN'].includes(r)))
const canReview=computed(()=>permitted.value && !!auth.user?.roles.some(r=>['LEGAL','LEADER'].includes(r)))
const sources=ref<any[]>([]),batches=ref<any[]>([]),selected=ref<any[]>([]),readiness=ref<any>(null),detail=ref<any>(null)
const query=ref(''),lastQuery=ref(''),sourcePage=ref(1),sourceTotal=ref(0),batchPage=ref(1),batchTotal=ref(0),loading=ref(false),failed=ref(false),busy=ref(false)
const detailVisible=ref(false),actionVisible=ref(false),activeItem=ref<any>(null),action=ref<'assign'|'investigate'|'review'>('assign')
const focusedItemId=ref<number>(),focusLoading=ref(false),focusFailed=ref(false)
const form=reactive({username:'',note:'',reference:'',approve:true})
const actionTitle=computed(()=>({assign:'分派差异核查',investigate:'提交差异核查',review:'复核差异处理'})[action.value])
const resultLabels:Record<string,string>={PENDING:'待核对',MATCH:'一致',DIFFERENT:'金额／日期／缴清状态不一致',MISSING_RECEIPT:'缺少票据号',NOT_FOUND:'财政端未找到',UNCONFIGURED:'网关未配置',FAILED:'接口失败',SOURCE_CHANGED:'源记录已变化'}
const reviewLabels:Record<string,string>={OPEN:'待分派',ASSIGNED:'待核查',SUBMITTED:'待另一人复核',RESOLVED:'已复核',NOT_REQUIRED:'无需核查'}
const kindLabels:Record<string,string>={FINE:'罚款',RECOUP:'退回基金',CONFISCATE:'没收违法所得',LATE_FEE:'加处罚款'}
const actionLabels:Record<string,string>={CHECK:'核对',ASSIGN:'分派',SUBMIT:'提交核查',APPROVE:'复核通过',RETURN:'退回核查',RETRY:'申请重新核对'}
let disposed=false,mounted=false,generation=0,detailGeneration=0,refreshGeneration=0,batchGeneration=0,requestKey=''
const identity=()=>JSON.stringify([auth.token,auth.user?.id,auth.user?.accountCategory,auth.user?.roles])
function active(){return !disposed && !!auth.token && permitted.value && !sessionExpiry.isExpired(sessionExpiry.capture(auth.token),auth.token)}
function capture(){const who=identity(),stamp=sessionExpiry.capture(auth.token),gen=generation;return ()=>active() && who===identity() && gen===generation && sessionExpiry.isCurrent(stamp,auth.token)}
function clear(){generation++;detailGeneration++;refreshGeneration++;batchGeneration++;sources.value=[];batches.value=[];selected.value=[];readiness.value=null;detail.value=null;activeItem.value=null;sourceTotal.value=0;sourcePage.value=1;batchTotal.value=0;batchPage.value=1;detailVisible.value=false;actionVisible.value=false;loading.value=false;busy.value=false;failed.value=false;focusLoading.value=false;focusFailed.value=false;requestKey='';Object.assign(form,{username:'',note:'',reference:'',approve:true})}
watch(identity,clear,{flush:'sync'})
function selectSources(rows:any[]){if(busy.value||loading.value)return;selected.value=rows.filter(row=>sources.value.includes(row));requestKey=''}
async function loadPage(page:number){
  if(!active() || busy.value || !Number.isSafeInteger(page) || page<1)return
  const run=++generation,current=capture();loading.value=true;failed.value=false;sources.value=[];selected.value=[];requestKey=''
  try{const response=await client.get('/bureau/integrations/treasury/sources',{params:{page,size:20,q:lastQuery.value}})
    if(!current())return
    const result=response.data.data;if(!Array.isArray(result?.rows)||!Number.isSafeInteger(result.total)||result.total<0)throw new Error('Invalid sources')
    sources.value=result.rows.map((row:any)=>({...row,selectionKey:row.source+':'+row.id,sourceLabel:row.source==='ORIGINAL'?'原登记':'续登记'}));sourceTotal.value=result.total;sourcePage.value=page
  }catch{if(current()){sources.value=[];sourceTotal.value=0;failed.value=true}}finally{if(run===generation)loading.value=false}
}
async function refresh(){
  if(!active()||busy.value)return
  if(focusedItemId.value===0)return
  const run=++refreshGeneration,who=identity(),stamp=sessionExpiry.capture(auth.token)
  await loadPage(1);if(run!==refreshGeneration||who!==identity()||!active()||!sessionExpiry.isCurrent(stamp,auth.token))return
  const current=capture()
  const batchRun=++batchGeneration
  const requests=[client.get('/bureau/integrations/treasury/batches',{params:{page:1,size:20}}),...(isAdmin.value?[client.get('/bureau/integrations/readiness')]:[])]
  const results=await Promise.allSettled(requests);if(!current())return
  if(batchRun===batchGeneration){if(results[0]?.status==='fulfilled'&&Array.isArray(results[0].value.data.data?.rows)){batches.value=results[0].value.data.data.rows;batchTotal.value=results[0].value.data.data.total;batchPage.value=1}else{batches.value=[];batchTotal.value=0;failed.value=true}}
  if(isAdmin.value){if(results[1]?.status==='fulfilled')readiness.value=results[1].value.data.data;else{readiness.value=null;failed.value=true}}
  if(focusedItemId.value && current()) await openItem(focusedItemId.value)
}
function setFocusedItem(value:number|undefined){
  if(value===focusedItemId.value)return
  focusedItemId.value=value;detailGeneration++;detail.value=null;detailVisible.value=false;focusFailed.value=false;focusLoading.value=!!value
  if(mounted&&value)void openItem(value)
  else if(mounted&&value===undefined)void refresh()
}
async function openItem(id:number){
  if(!active()||busy.value||id<1)return
  const current=capture(),run=++detailGeneration;detail.value=null;detailVisible.value=true;focusLoading.value=true;focusFailed.value=false
  try{const response=await client.get(`/bureau/integrations/treasury/items/${id}`);if(current()&&run===detailGeneration&&detailVisible.value)detail.value=response.data.data}
  catch{if(current()&&run===detailGeneration){focusFailed.value=true;detailVisible.value=false}}
  finally{if(run===detailGeneration)focusLoading.value=false}
}
async function loadBatchPage(page:number){
  if(!active()||busy.value||!Number.isSafeInteger(page)||page<1)return
  const current=capture(),run=++batchGeneration
  try{const response=await client.get('/bureau/integrations/treasury/batches',{params:{page,size:20}});if(!current()||run!==batchGeneration)return
    const result=response.data.data;if(!Array.isArray(result?.rows)||!Number.isSafeInteger(result.total)||result.total<0)throw new Error('Invalid batches')
    batches.value=result.rows;batchTotal.value=result.total;batchPage.value=page
  }catch{if(current()&&run===batchGeneration){batches.value=[];batchTotal.value=0;failed.value=true}}
}
function search(){if(busy.value)return;lastQuery.value=query.value;return loadPage(1)}
const gatewayRows=computed(()=>['monitor','signature','treasury','delivery'].map((key,i)=>({key,label:['疑点监控','签章','财政票据','电子送达'][i],...(readiness.value?.[key]||{})})))
async function createBatch(){
  if(!active()||!canWrite.value||busy.value||loading.value||!selected.value.length)return
  const current=capture(),entries=selected.value.map(row=>({source:row.source,id:row.id}));requestKey ||= crypto.randomUUID();busy.value=true
  try{const response=await client.post('/bureau/integrations/treasury/batches',{executions:entries,requestKey});if(!current())return;requestKey='';busy.value=false;await openBatch(response.data.data.id);await refresh()}
  catch{if(current())ElMessage.warning('未取得批次结果，可使用相同选择重试，并核对已有批次。')}
  finally{if(current())busy.value=false}
}
async function openBatch(id:number){
  if(!active()||busy.value)return
  const current=capture(),run=++detailGeneration;detail.value=null;detailVisible.value=true
  try{const response=await client.get(`/bureau/integrations/treasury/batches/${id}`);if(current()&&run===detailGeneration&&detailVisible.value)detail.value=response.data.data}
  catch{if(current()&&run===detailGeneration)ElMessage.warning('批次加载失败，请重试。')}
}
function closeDetail(done:()=>void){if(busy.value)return;detailGeneration++;detail.value=null;actionVisible.value=false;activeItem.value=null;done()}
async function runBatch(){
  if(!active()||!canWrite.value||busy.value||!detail.value||detail.value.focusedItem)return
  const current=capture(),id=detail.value.id;busy.value=true
  try{await client.post(`/bureau/integrations/treasury/batches/${id}/run`,{},{timeout:100000});if(!current())return;busy.value=false;await openBatch(id)}catch{if(current())ElMessage.warning('部分票据结果可能已保存，请刷新批次后继续核对。')}finally{if(current())busy.value=false}
}
async function retryItem(row:any){
  if(!active()||!canWrite.value||busy.value||!detail.value?.items.includes(row)||['PENDING','MATCH'].includes(row.result)||['SUBMITTED','RESOLVED'].includes(row.review_status))return
  const current=capture(),id=detail.value.id;busy.value=true
  const focused=detail.value.focusedItem
  try{await client.post(`/bureau/integrations/treasury/items/${row.id}/retry`,{});if(!current())return;busy.value=false;await (focused?openItem(row.id):openBatch(id))}catch{ /* The API retains the original evidence on rejection. */ }finally{if(current())busy.value=false}
}
function openAction(row:any,kind:'assign'|'investigate'|'review'){
  if(!active()||busy.value||!detail.value?.items.includes(row))return
  if(kind==='assign'?!canAssign.value:kind==='review'?!canReview.value:!canWrite.value)return
  if(kind==='assign'&&(['PENDING','MATCH'].includes(row.result)||row.review_status==='RESOLVED'))return
  if(kind==='investigate'&&(row.assignee!==auth.user?.username||row.review_status!=='ASSIGNED'))return
  if(kind==='review'&&(row.review_status!=='SUBMITTED'||row.investigated_by===auth.user?.username))return
  activeItem.value=row;action.value=kind;Object.assign(form,{username:'',note:'',reference:'',approve:true});actionVisible.value=true
}
async function submitAction(){
  if(!active()||busy.value||!actionVisible.value||!activeItem.value||!detail.value?.items.includes(activeItem.value))return
  const kind=action.value;if(kind==='assign'?!canAssign.value:kind==='review'?!canReview.value:!canWrite.value)return
  if(kind==='assign'?!form.username.trim():!form.note.trim()||(kind==='investigate'&&!form.reference.trim())){ElMessage.warning('请填写必填信息。');return}
  const current=capture(),id=detail.value?.id,item=activeItem.value.id,focused=detail.value.focusedItem;busy.value=true
  const body=kind==='assign'?{username:form.username}:kind==='investigate'?{note:form.note,sourceReference:form.reference}:{approve:form.approve,reason:form.note}
  try{await client.post(`/bureau/integrations/treasury/items/${item}/${kind}`,body);if(!current())return;actionVisible.value=false;activeItem.value=null;busy.value=false;await (focused?openItem(item):openBatch(id))}catch{ /* API reports the rejected mutation; retain the current draft. */ }finally{if(current())busy.value=false}
}
async function probe(target:string){
  if(!active()||!isAdmin.value||busy.value||!['monitor','signature','treasury','delivery'].includes(target))return
  const current=capture();busy.value=true
  try{const response=await client.post(`/bureau/integrations/probe/${target}`,{});if(!current())return;ElMessage.info(response.data.data.status==='READY'?'探测通过，请继续完成真实业务验收。':'探测未通过，请核对配置和网关运行状态。');busy.value=false;await refresh()}catch{ /* API reports the diagnostic failure. */ }finally{if(current())busy.value=false}
}
onMounted(()=>{mounted=true;window.addEventListener(SESSION_EXPIRED_EVENT,clear);void refresh()})
onUnmounted(()=>{disposed=true;clear();window.removeEventListener(SESSION_EXPIRED_EVENT,clear)})
</script>
<style scoped>.gap{margin-top:12px}.hint{color:#666;font-size:12px}h4{margin:0 0 12px}</style>
