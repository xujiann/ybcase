import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import vm from 'node:vm'
import {stripTypeScriptTypes} from 'node:module'
import * as Vue from 'vue'
import {createResourceLoader,resourceState} from '../src/api/loadableResource.ts'
import {createBlobPreview} from '../src/api/blobPreview.ts'
import {createSessionExpiry,SESSION_EXPIRED_EVENT} from '../src/api/sessionExpiry.ts'
import {parseTaskFocus} from '../src/api/taskFocus.ts'

// Execute the actual SFC setup and handlers; HTTP completions are controlled so
// stale results, failures and closing panels can be tested without production data.
function view(file,client,exports,overrides={}) {
  const source=readFileSync(new URL('../src/views/'+file,import.meta.url),'utf8')
  const script=source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const unmount=[]
  const ctx={...Vue,client,URL,createResourceLoader,resourceState,createBlobPreview,
    sessionExpiry:createSessionExpiry(),SESSION_EXPIRED_EVENT,window:{addEventListener(){},removeEventListener(){}},
    onMounted(){},onUnmounted(fn){unmount.push(fn)},watch(){},
    useRoute:()=>({query:{}}),useRouter:()=>({push(){}}),
    useAuthStore:()=>({token:'test-session',user:{username:'admin',roles:['ADMIN']}}),
    caseOperationAccess:()=>true,adminOperationAccess:()=>true,canWritePrecase:()=>true,
    emptyJurisdiction:()=>({}),jurisdictionError:()=>'',serializeJurisdiction:v=>v,
    ElMessage:{success(){},warning(){},error(){}},ElMessageBox:{},
    ...overrides,
  }
  const js=stripTypeScriptTypes(script.replace(/^import .+\r?\n/gm,''))
  const output=vm.runInNewContext(js+'\n;({'+exports+'})',ctx)
  output.unmount=()=>unmount.forEach(fn=>fn())
  return output
}

function filingView(t) {
  const route=Vue.reactive({query:{}})
  const auth=Vue.reactive({token:'session',user:{roles:['LEADER']}})
  const reads=[],writes=[],navigations=[]
  const client={
    get:(_url,config)=>new Promise(resolve=>reads.push({id:config.params.focusId,resolve:data=>resolve({data:{data}})})),
    post:(url,body)=>new Promise(resolve=>writes.push({url,body,resolve:data=>resolve({data:{data}})})),
  }
  const scope=Vue.effectScope()
  t.after(()=>scope.stop())
  const instance=scope.run(()=>view('case/CasesView.vue',client,'openCreate,onCreate,createVisible,previousApproval,form',{
    watch:Vue.watch,parseTaskFocus,useRoute:()=>route,useAuthStore:()=>auth,
    useRouter:()=>({push:target=>navigations.push(target)}),
  }))
  return {...instance,route,auth,reads,writes,navigations}
}

test('a rejected historical filing with missing officers opens for correction and never seeds a new filing',async t=>{
  const f=filingView(t)
  f.route.query={fromApproval:'41'}
  f.reads[0].resolve([{id:41,kind:'FILE_CASE',status:'REJECTED',clue_id:12,opinion:'补人员',
    payload:JSON.stringify({partyName:'原当事人',amountInvolved:500,summary:'原摘要',jurisdiction:{type:'DESIGNATED'}})}])
  await new Promise(setImmediate)
  assert.equal(f.createVisible.value,true)
  assert.equal(f.previousApproval.value.id,41)
  assert.equal(f.form.partyName,'原当事人')
  assert.equal(f.form.officers.length,2)
  assert.equal(f.form.clueId,12)
  f.createVisible.value=false
  f.openCreate()
  assert.equal(f.previousApproval.value,null)
  assert.equal(f.form.partyName,'')
  assert.equal(f.form.amountInvolved,0)
  assert.equal(f.form.summary,'')
  assert.equal(f.form.clueId,null)
  f.openCreate({clueId:14,partyName:'线索当事人',partyType:'PERSON'})
  assert.equal(f.form.clueId,14)
  assert.equal(f.form.partyName,'线索当事人')
})

test('changing the approval route or session retires earlier forms and outstanding responses',async t=>{
  const f=filingView(t)
  f.openCreate()
  Object.assign(f.form,{partyName:'当事人甲',partyType:'PERSON',causeId:1,officers:[{name:'甲',certNo:'1'},{name:'乙',certNo:'2'}]})
  const pending=f.onCreate()
  assert.equal(f.writes.length,1)
  f.route.query={fromApproval:'41'}
  assert.equal(f.createVisible.value,false)
  await f.onCreate()
  assert.equal(f.writes.length,1)
  f.route.query={fromApproval:'42'}
  f.reads[1].resolve([{id:42,kind:'FILE_CASE',status:'REJECTED',payload:{partyName:'当事人乙'}}])
  await new Promise(setImmediate)
  f.reads[0].resolve([{id:41,kind:'FILE_CASE',status:'REJECTED',payload:{partyName:'过期申请'}}])
  f.writes[0].resolve({caseNo:'已提交',id:3})
  await pending
  assert.equal(f.createVisible.value,true)
  assert.equal(f.form.partyName,'当事人乙')
  assert.equal(f.navigations.length,0)
  f.auth.token='another-session'
  assert.equal(f.createVisible.value,false)
  assert.equal(f.form.partyName,'')
})
function reads(file,exports) {
  const pending=[]
  const client={get:(url,config)=>new Promise((resolve,reject)=>pending.push({url,config,resolve:value=>resolve({data:{data:value}}),reject}))}
  return {...view(file,client,exports),pending}
}
for (const file of ['case/CasesView.vue','case/CluesView.vue']) {
  const fixture=()=>reads(file,'load,records,total,page,q,loading,failed:typeof failed==="undefined"?undefined:failed,changePage:typeof changePage==="undefined"?undefined:changePage')
  test(file+': old query cannot overwrite newer rows and total',async()=>{
    const f=fixture();f.q.value='old';const old=f.load();f.q.value='new';const current=f.load()
    assert.equal(f.pending[0].config.params.q,'old');assert.equal(f.pending[1].config.params.q,'new')
    f.pending[1].resolve({rows:[{id:2}],total:22});await current
    f.pending[0].resolve({rows:[{id:1}],total:11});await old
    assert.equal(f.records.value[0].id,2);assert.equal(f.total.value,22)
  })
  test(file+': a stale failure does not end the current spinner',async()=>{
    const f=fixture(),old=f.load().catch(()=>false),current=f.load()
    f.pending[0].reject(new Error('old failure'));await old
    assert.equal(f.loading.value,true)
    f.pending[1].resolve({rows:[],total:0});await current
    assert.equal(f.loading.value,false)
  })
  test(file+': latest failure clears stale rows and permits a retry',async()=>{
    const f=fixture(),first=f.load();f.pending[0].resolve({rows:[{id:1}],total:90});await first
    f.page.value=4;const failed=f.load();f.pending[1].reject(new Error('offline'))
    await assert.doesNotReject(failed)
    assert.equal(f.records.value.length,0);assert.equal(f.total.value,0);assert.equal(f.page.value,1);assert.equal(f.failed.value,true)
    await f.changePage(1);assert.equal(f.pending.length,2,'widget normalization must not restart a failed query')
    const retry=f.load();f.pending[2].resolve({rows:[{id:2}],total:1});await retry
    assert.equal(f.failed.value,false);assert.equal(f.records.value[0].id,2)
  })
  test(file+': unmount invalidates late results',async()=>{
    const f=fixture(),pending=f.load();f.unmount();f.pending[0].resolve({rows:[{id:1}],total:1});await pending
    assert.equal(f.records.value.length,0)
    await f.load();assert.equal(f.pending.length,1,'disposed pages cannot issue new reads')
  })
}

test('template history stays paired with the newest document type',async()=>{
  const f=reads('system/TemplatesView.vue','openHistory,historyType,historyVisible,history,clearHistory:typeof clearHistory==="undefined"?undefined:clearHistory')
  const first=f.openHistory({doc_type:'A'}),second=f.openHistory({doc_type:'B'})
  f.pending[1].resolve([{id:2,content_tpl:'B'}]);await second
  f.pending[0].resolve([{id:1,content_tpl:'A'}]);await first
  assert.equal(f.historyType.value,'B');assert.equal((f.history.data??f.history.value)[0].content_tpl,'B')
})
test('closing template history cannot be undone by a late request',async()=>{
  const f=reads('system/TemplatesView.vue','openHistory,historyVisible,history,clearHistory:typeof clearHistory==="undefined"?undefined:clearHistory')
  const pending=f.openHistory({doc_type:'A'});f.historyVisible.value=false;f.clearHistory?.()
  f.pending[0].resolve([{id:1}]);await pending
  assert.equal(f.historyVisible.value,false);assert.equal((f.history.data??f.history.value).length,0)
})
test('template history failure is handled and cannot reuse a different document history',async()=>{
  const f=reads('system/TemplatesView.vue','openHistory,historyVisible,history')
  const first=f.openHistory({doc_type:'A'});f.pending[0].resolve([{id:1}]);await first
  const second=f.openHistory({doc_type:'B'});f.pending[1].reject(new Error('offline'));await assert.doesNotReject(second)
  assert.equal((f.history.data??f.history.value).length,0);assert.equal(f.history.failed,true)
})
test('feedback filtering ignores old results and failures',async()=>{
  const f=reads('system/FeedbackAdminView.vue','load,d,fStatus,loading')
  f.fStatus.value='NEW';const old=f.load().catch(()=>false);f.fStatus.value='RESOLVED';const current=f.load()
  f.pending[0].reject(new Error('stale'));await old;assert.equal(f.loading.value,true)
  f.pending[1].resolve({rows:[{id:2,status:'RESOLVED'}]});await current
  const slow=f.load();f.fStatus.value='PROCESSING';const latest=f.load()
  f.pending[3].resolve({rows:[{id:4,status:'PROCESSING'}]});await latest
  f.pending[2].resolve({rows:[{id:3,status:'RESOLVED'}]});await slow
  assert.equal(f.d.value.rows[0].id,4)
})
test('feedback save snapshots the chosen record and form; repeated saves cannot notify twice',async()=>{
  const writes=[];let finish
  const client={get:async()=>({data:{data:{rows:[]}}}),post:(url,body)=>{writes.push({url,body});return new Promise(resolve=>finish=resolve)}}
  const f=view('system/FeedbackAdminView.vue',client,'openHandle,onHandle,viewContext,handleForm')
  f.openHandle({id:1,title:'A',status:'NEW'});f.handleForm.reply='reply A';const saving=f.onHandle()
  f.handleForm.reply='later text';await f.viewContext({id:2,title:'B',has_screenshot:false})
  f.openHandle({id:2,title:'B',status:'NEW'})
  // Calling again must not create another write; do not wait on its network result.
  const repeat=f.onHandle();assert.equal(writes.length,1)
  assert.equal(writes[0].url,'/bureau/feedback/1/handle');assert.equal(writes[0].body.reply,'reply A')
  finish();await saving;await repeat
})

test('template save cannot duplicate history or serialize a later edited form',async()=>{
  const writes=[];let finish
  const client={get:async()=>({data:{data:[]}}),put:(url,body)=>{writes.push({url,body});return new Promise(resolve=>finish=resolve)}}
  const f=view('system/TemplatesView.vue',client,'openEdit,onSave,form,saving')
  f.openEdit({doc_type:'A',title_tpl:'Title A',content_tpl:'Content A'});const save=f.onSave()
  const repeat=f.onSave();f.form.contentTpl='later text';f.openEdit({doc_type:'B',content_tpl:'B'})
  assert.equal(writes.length,1);assert.equal(writes[0].url,'/bureau/doc-templates/A')
  assert.equal(writes[0].body.contentTpl,'Content A');assert.equal(f.form.docType,'A')
  finish();await save;await repeat;assert.equal(f.saving.value,false)
})

test('failed template or feedback save preserves the draft and releases the submitting guard',async()=>{
  for (const feedback of [false,true]) {
    const client={post:async()=>{throw new Error('save failed')},put:async()=>{throw new Error('save failed')}}
    const f=view(feedback?'system/FeedbackAdminView.vue':'system/TemplatesView.vue',client,feedback?'openHandle,onHandle,handleForm,saving,handleVisible':'openEdit,onSave,form,saving,editVisible')
    if(feedback){f.openHandle({id:1,status:'NEW'});f.handleForm.reply='keep reply'}
    else f.openEdit({doc_type:'A',title_tpl:'keep title',content_tpl:'keep content'})
    await assert.rejects(feedback?f.onHandle():f.onSave(),/save failed/)
    assert.equal(f.saving.value,false)
    assert.equal(feedback?f.handleVisible.value:f.editVisible.value,true)
    assert.equal(feedback?f.handleForm.reply:f.form.contentTpl,feedback?'keep reply':'keep content')
  }
})

test('template save keeps editing locked until refreshed rows arrive; failed reload cannot expose old editable templates',async()=>{
  let finish;const client={put:async()=>{},get:()=>new Promise((resolve,reject)=>finish={resolve,reject})}
  const f=view('system/TemplatesView.vue',client,'load,openEdit,onSave,form,saving,loading,rows,listFailed')
  f.openEdit({doc_type:'A',content_tpl:'old'});const save=f.onSave();await new Promise(setImmediate)
  assert.equal(f.saving.value,true);assert.equal(f.loading.value,true)
  f.openEdit({doc_type:'B',content_tpl:'stale'});assert.equal(f.form.docType,'A')
  finish.reject(new Error('reload failed'));await save
  assert.equal(f.saving.value,false);assert.equal(f.rows.value.length,0);assert.equal(f.listFailed.value,true)
  f.openEdit({doc_type:'B',content_tpl:'stale'});assert.equal(f.form.docType,'A')
})
