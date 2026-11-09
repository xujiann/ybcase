import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
const source=readFileSync(new URL('../src/components/InterviewChangesPanel.vue',import.meta.url),'utf8')
const script=source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm,'')
const tick=()=>new Promise(setImmediate)
const plain=v=>JSON.parse(JSON.stringify(v))
function fixture(t,category='DISTRICT'){
 const auth=Vue.reactive({token:'one',user:{id:1,deptId:10,accountCategory:category,roles:category==='AGENCY'?['AGENCY']:category==='BUREAU'?['LEGAL']:['HANDLER']}})
 const reads=[],writes=[],messages=[],listeners=new Map(),cleanup=[];let uuid=0
 const client={get:(url,config)=>new Promise((resolve,reject)=>reads.push({url,config,resolve:data=>resolve({data:{data}}),reject})),post:(url,body)=>new Promise((resolve,reject)=>writes.push({url,body:plain(body),resolve,reject}))}
 const sessionExpiry=createSessionExpiry(),scope=Vue.effectScope()
 const view=scope.run(()=>vm.runInNewContext(stripTypeScriptTypes(script)+';({records,pending,history,visible,historyVisible,inspected,saving,form,reason,reference,opinion,open,submit,inspect,decide,load,changePage,failed})',{
  ...Vue,client,useAuthStore:()=>auth,sessionExpiry,SESSION_EXPIRED_EVENT,localDate:()=> '2026-11-08',
  crypto:{randomUUID:()=> 'request-'+ ++uuid},window:{addEventListener:(e,fn)=>listeners.set(e,fn),removeEventListener:e=>listeners.delete(e)},
  onBeforeUnmount:fn=>cleanup.push(fn),ElMessage:{success:x=>messages.push(x),warning:x=>messages.push(x)}
 }))
 t.after(()=>{cleanup.forEach(fn=>fn());scope.stop()})
 return {...view,auth,reads,writes,messages,expire:()=>{sessionExpiry.expire(sessionExpiry.capture(auth.token),auth.token);listeners.get(SESSION_EXPIRED_EVENT)()}}
}
const row=()=>({id:8,department_name:'合成部门',institution_code:'ORG',institution_name:'合成机构',interview_type:'个别约谈',reason:'实际事由',content:'实际记录',participants:'甲乙',interview_date:'2026-11-01',can_request:true,voided:false})
async function ready(f,rows=[row()]){for(const read of f.reads.filter(r=>!r.done)){read.done=true;read.resolve({rows:read.url.endsWith('/records')?rows:[],total:rows.length})}await tick()}
test('late prior-identity reads cannot populate current correction sources',async t=>{
 const f=fixture(t);const old=f.reads[0];f.auth.user.deptId=20;old.resolve({rows:[row()],total:1});await tick();assert.equal(f.records.value.length,0)
 await ready(f,[]);assert.equal(f.records.value.length,0)
})
test('correction submission is single-flight, retries same key and snapshots the draft',async t=>{
 const f=fixture(t);await ready(f);f.open(f.records.value[0],'CORRECT');f.form.content='核对后的新内容';f.reason.value='原登记笔误';f.reference.value='原件第2页'
 const one=f.submit(),two=f.submit();assert.equal(f.writes.length,1);const key=f.writes[0].body.requestKey
 assert.equal(f.writes[0].body.values.content,'核对后的新内容');f.writes[0].reject(new Error('offline'));await Promise.all([one,two]);assert.equal(f.visible.value,true)
 const retry=f.submit();assert.equal(f.writes[1].body.requestKey,key);f.writes[1].reject(new Error('offline'));await retry
 f.form.content='再次核对';const changed=f.submit();assert.notEqual(f.writes[2].body.requestKey,key);f.writes[2].reject(new Error('offline'));await changed
})
test('agency requests stay within agency route and reject stale source objects',async t=>{
 const f=fixture(t,'AGENCY');await ready(f);assert.equal(f.reads[0].url,'/agency/interview-changes/records')
 f.open(row(),'VOID');assert.equal(f.visible.value,false)
 f.open(f.records.value[0],'VOID');f.reason.value='误建';f.reference.value='台账第3页';const p=f.submit();assert.equal(f.writes[0].url,'/agency/interview-changes/8');assert.equal(f.writes[0].body.values,null)
 f.expire();f.writes[0].resolve({});await p;assert.equal(f.messages.length,0);assert.equal(f.visible.value,false)
})
test('review obeys server eligibility and discards a successful response after role loss',async t=>{
 const f=fixture(t,'BUREAU');await ready(f);const entry={id:9,canReview:false,canCancel:false}
 f.pending.value=[entry];f.inspect(f.pending.value[0]);f.opinion.value='核对';await f.decide('approve');assert.equal(f.writes.length,0)
 f.pending.value=[{...entry,canReview:true}];f.inspect(f.pending.value[0]);f.opinion.value='另一人复核通过'
 const first=f.decide('approve'),duplicate=f.decide('approve');assert.equal(f.writes.length,1)
 f.auth.user.roles=[];f.writes[0].resolve({});await Promise.all([first,duplicate]);assert.equal(f.inspected.value,undefined);assert.equal(f.messages.length,0)
})
test('records pagination uses bounded server parameters',async t=>{
 const f=fixture(t);await ready(f);f.changePage(3);assert.deepEqual(plain(f.reads.at(-1).config.params),{page:3,size:20});await ready(f,[])
})
