import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'

function fixture(t, component='IntegrationWorkbench',role='LEADER') {
  const auth=Vue.reactive({token:'one',user:{id:1,username:'actor',accountCategory:'BUREAU',roles:[role]}})
  const props=Vue.reactive({caseId:1,writable:true,refreshKey:undefined})
  const sessionExpiry=createSessionExpiry();sessionExpiry.reset(auth.token)
  const requests=[],notices=[],unmounts=[],listeners=new Map()
  const request=(method,path,body,config)=>new Promise((resolve,reject)=>requests.push({method,path,body,config,resolve:data=>resolve({data:{data}}),reject}))
  const client={get:(path,config)=>request('GET',path,null,config),post:(path,body,config)=>request('POST',path,body,config)}
  const source=readFileSync(new URL(`../src/components/${component}.vue`,import.meta.url),'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm,'')
  const scope=Vue.effectScope()
  const exports=component==='IntegrationWorkbench'?'refresh,loadPage,selectSources,createBatch,openBatch,openItem,openAction,submitAction,runBatch,sources,selected,detail,detailVisible,actionVisible,activeItem,form,busy,failed':'load,create,send,expire,data,documentId,busy,failed,hasAddress,canWrite,canSupplement,openReplace,replaceRound,replaceVisible,replacement,replacementRow'
  const view=scope.run(()=>vm.runInNewContext(stripTypeScriptTypes(source)+`;({${exports}})`,{...Vue,client,useAuthStore:()=>auth,defineProps:()=>props,sessionExpiry,SESSION_EXPIRED_EVENT,crypto:{randomUUID:()=>`request-${requests.length}`},ElMessage:{info:m=>notices.push(m),warning:m=>notices.push(m)},onMounted:()=>{},onUnmounted:fn=>unmounts.push(fn),window:{addEventListener:(k,fn)=>listeners.set(k,fn),removeEventListener:k=>listeners.delete(k)}}))
  t.after(()=>{unmounts.forEach(fn=>fn());scope.stop()})
  return {...view,auth,props,requests,notices}
}
const sourceRow=()=>({source:'ORIGINAL',id:5,case_id:1,case_no:'CASE-1',receipt_no:'TEST-1',amount:'23.45',paid_at:'2026-11-07'})
const deliveryData=(extra={})=>({status:'DECIDED',archive_no:null,e_delivery_consent:true,e_delivery_receiver:'合成当事人',e_delivery_channel:'fixture@example.invalid',gatewayConfigured:true,documents:[{id:10,title:'决定书'}],deliveries:[{id:20,status:'DRAFT',attempts:0}],events:[],...extra})
async function listSources(f){const p=f.loadPage(1);f.requests.at(-1).resolve({rows:[sourceRow()],total:1});await p}

test('treasury lists reject stale identity data and clear sensitive batch dialog',async t=>{
  const f=fixture(t),pending=f.loadPage(1)
  f.detail.value={id:8,items:[{id:1}]};f.detailVisible.value=true
  f.auth.user.id=2;f.requests[0].resolve({rows:[sourceRow()],total:1});await pending
  assert.equal(f.sources.value.length,0);assert.equal(f.detail.value,null);assert.equal(f.detailVisible.value,false)
})
test('new treasury query wins and clears old selections while loading',async t=>{
  const f=fixture(t);await listSources(f);f.selectSources(f.sources.value);assert.equal(f.selected.value.length,1)
  const old=f.loadPage(1),recent=f.loadPage(2)
  assert.equal(f.selected.value.length,0);f.requests[2].resolve({rows:[],total:0});await recent
  f.requests[1].resolve({rows:[sourceRow()],total:1});await old
  assert.equal(f.sources.value.length,0)
})
test('lost treasury create result keeps request key and blocks duplicate submission',async t=>{
  const f=fixture(t);await listSources(f);f.selectSources(f.sources.value)
  const first=f.createBatch();await f.createBatch();assert.equal(f.requests.length,2)
  const key=f.requests[1].body.requestKey;f.requests[1].reject(new Error('timeout'));await first
  const retry=f.createBatch();assert.equal(f.requests[2].body.requestKey,key);f.requests[2].reject(new Error('timeout'));await retry
  assert.equal(f.busy.value,false)
})
test('treasury details cannot reopen after identity change and old result',async t=>{
  const f=fixture(t),pending=f.openBatch(1);f.auth.user.roles=['AGENCY'];f.requests[0].resolve({id:1,items:[],history:[]});await pending
  assert.equal(f.detail.value,null);assert.equal(f.detailVisible.value,false)
  await f.openBatch(1);assert.equal(f.requests.length,1)
})
test('treasury task opens only the authorized item and cannot run a mixed full batch',async t=>{
  const f=fixture(t),pending=f.openItem(77)
  assert.equal(f.requests[0].path,'/bureau/integrations/treasury/items/77')
  f.requests[0].resolve({id:5,focusedItem:true,items:[{id:77}],history:[]});await pending
  assert.equal(f.detail.value.items.length,1);assert.equal(f.detail.value.focusedItem,true)
  await f.runBatch();assert.equal(f.requests.length,1)
})
test('treasury review action requires current source row and mandatory explanation',async t=>{
  const f=fixture(t,'IntegrationWorkbench','LEGAL'),row={id:9,review_status:'SUBMITTED',investigated_by:'another'}
  f.detail.value={id:1,items:[row]};f.openAction({id:9},'review');assert.equal(f.actionVisible.value,false)
  f.openAction(f.detail.value.items[0],'review');assert.equal(f.actionVisible.value,true)
  await f.submitAction();assert.equal(f.requests.length,0)
  f.form.note='核查凭证完整';const pending=f.submitAction();assert.equal(f.requests[0].path,'/bureau/integrations/treasury/items/9/review')
  f.auth.user.id=3;f.requests[0].resolve({});await pending;assert.equal(f.detail.value,null);assert.equal(f.actionVisible.value,false)
})
test('delivery list discards previous case response and cannot leak recipient',async t=>{
  const f=fixture(t,'DeliveryReceiptsPanel'),pending=f.load();f.props.caseId=2
  // Case change starts its own read. Complete the new read before the old one.
  f.requests[1].resolve(deliveryData({e_delivery_receiver:'新案当事人'}));await new Promise(resolve=>setImmediate(resolve))
  f.requests[0].resolve(deliveryData());await pending
  assert.equal(f.data.value.e_delivery_receiver,'新案当事人')
})
test('delivery requires structured consent address and rejects sealed source writes',async t=>{
  const f=fixture(t,'DeliveryReceiptsPanel');f.data.value=deliveryData({e_delivery_channel:null});f.documentId.value=10
  assert.equal(f.hasAddress.value,false);await f.create();assert.equal(f.requests.length,0)
  f.data.value=deliveryData({archive_no:'SEALED'});assert.equal(f.canWrite.value,false)
  await f.send(f.data.value.deliveries[0]);await f.expire();assert.equal(f.requests.length,0)
})
test('delivery write is single flight and a late reply cannot reload another identity',async t=>{
  const f=fixture(t,'DeliveryReceiptsPanel');f.data.value=deliveryData();f.documentId.value=10
  const pending=f.create();await f.create();assert.equal(f.requests.length,1)
  f.auth.user.id=2;assert.equal(f.data.value,null)
  f.requests[0].resolve({id:20});await pending
  // The identity watcher may read the newly authorized case, but the old write never reloads it.
  assert.equal(f.requests.filter(r=>r.method==='POST').length,1)
  assert.equal(f.data.value,null)
})
test('delivery sending retains supplier idempotency route and blocks duplicate clicks',async t=>{
  const f=fixture(t,'DeliveryReceiptsPanel');f.data.value=deliveryData();const row=f.data.value.deliveries[0]
  const pending=f.send(row);await f.send(row);assert.equal(f.requests.length,1);assert.equal(f.requests[0].path,'/bureau/cases/1/electronic-deliveries/20/send')
  f.requests[0].reject(new Error('network'));await pending
  assert.equal(f.busy.value,false);assert.equal(f.data.value.deliveries[0].id,20)
})

test('replacing a failed delivery keeps the replacement request on uncertain result and rejects duplicate clicks',async t=>{
  const f=fixture(t,'DeliveryReceiptsPanel');f.data.value=deliveryData();const row=f.data.value.deliveries[0]
  f.openReplace(row);f.replacement.value.reason='核实旧供应商结果和新地址';f.replacement.value.providerChecked=true
  const first=f.replaceRound();await f.replaceRound();assert.equal(f.requests.length,1)
  const key=f.requests[0].body.requestKey;f.requests[0].reject(new Error('timeout'));await first
  const retry=f.replaceRound();assert.equal(f.requests[1].body.requestKey,key);f.requests[1].reject(new Error('timeout'));await retry
  assert.equal(f.replaceVisible.value,true);assert.equal(f.busy.value,false)
})

test('sealed original cannot send while independent supplement retains its own send and replacement route',async t=>{
  const f=fixture(t,'DeliveryReceiptsPanel')
  f.data.value=deliveryData({status:'CLOSED',archive_no:'SEALED',deliveries:[{id:21,status:'DRAFT'},{id:22,status:'DRAFT',supplement_document_id:7}]})
  assert.equal(f.canWrite.value,false);assert.equal(f.canSupplement.value,true)
  await f.send(f.data.value.deliveries[0]);assert.equal(f.requests.length,0)
  const p=f.send(f.data.value.deliveries[1]);assert.equal(f.requests[0].path,'/bureau/cases/1/electronic-deliveries/22/send');f.requests[0].reject(new Error('timeout'));await p
  f.openReplace(f.data.value.deliveries[1]);assert.equal(f.replaceVisible.value,true)
})

test('in-flight and superseded deliveries cannot start replacement and old identity cannot reopen replacement after response',async t=>{
  const f=fixture(t,'DeliveryReceiptsPanel');f.data.value=deliveryData({deliveries:[{id:20,status:'SENT'},{id:21,status:'SUPERSEDED',superseded_at:'2026-11-08'},{id:22,status:'FAILED'}]})
  f.openReplace(f.data.value.deliveries[0]);assert.equal(f.replaceVisible.value,false)
  f.openReplace(f.data.value.deliveries[1]);assert.equal(f.replaceVisible.value,false)
  f.openReplace(f.data.value.deliveries[2]);f.replacement.value.reason='已核对';f.replacement.value.providerChecked=true
  const p=f.replaceRound();f.auth.user.id=4;f.requests[0].resolve({id:23});await p
  assert.equal(f.replaceVisible.value,false);assert.equal(f.replacementRow.value,null);assert.equal(f.data.value,null)
})
