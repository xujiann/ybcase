import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {stripTypeScriptTypes} from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import {createSessionExpiry,SESSION_EXPIRED_EVENT} from '../src/api/sessionExpiry.ts'
const source=readFileSync(new URL('../src/components/FactRevisionPanel.vue',import.meta.url),'utf8')
const script=source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm,'')
const tick=()=>new Promise(setImmediate)
const initial=marker=>({marker,canCorrectInstitution:true,canReturnInvestigation:true,originalFacts:{institutionCode:'001',institutionOwnership:'PUBLIC',institutionGrade:'SECONDARY',institutionKind:'MEDICAL'},effectiveFacts:{},history:[]})
function fixture(t){
 const props=Vue.reactive({caseId:1}),auth=Vue.reactive({token:'one',user:{id:1,roles:['HANDLER'],accountCategory:'DISTRICT'}})
 const reads=[],writes=[],messages=[],emits=[],cleanup=[],listeners=new Map(),sessionExpiry=createSessionExpiry()
 const client={get:(url,config)=>new Promise((resolve,reject)=>reads.push({url,config,resolve:value=>resolve({data:{data:value}}),reject})),post:(url,body)=>new Promise((resolve,reject)=>writes.push({url,body:JSON.parse(JSON.stringify(body)),resolve,reject})),put:(url,body)=>new Promise((resolve,reject)=>writes.push({url,body:JSON.parse(JSON.stringify(body)),resolve,reject}))}
 const scope=Vue.effectScope()
 const result=scope.run(()=>vm.runInNewContext(stripTypeScriptTypes(script)+';({state,visible,mode,reason,sourceReference,institution,loading,saving,failed,open,submit,load})',{
  ...Vue,defineProps:()=>props,defineEmits:()=>(...args)=>emits.push(args),useAuthStore:()=>auth,client,sessionExpiry,SESSION_EXPIRED_EVENT,AbortController,
  ElMessage:{warning:v=>messages.push(v),success:v=>messages.push(v)},onMounted:f=>f(),onBeforeUnmount:f=>cleanup.push(f),
  window:{addEventListener:(key,fn)=>listeners.set(key,fn),removeEventListener:key=>listeners.delete(key)}
 }))
 t.after(()=>{cleanup.forEach(f=>f());scope.stop()})
 return {...result,props,auth,reads,writes,messages,emits,expire:()=>{sessionExpiry.expire(sessionExpiry.capture(auth.token),auth.token);listeners.get(SESSION_EXPIRED_EVENT)()}}
}
async function ready(f,marker='current'){f.reads.at(-1).resolve(initial(marker));await tick()}
test('institution correction captures required evidence and blocks duplicate writes',async t=>{
 const f=fixture(t);await ready(f);f.open('institution');assert.equal(f.institution.code,'001')
 f.institution.code='002';f.reason.value='核对证照更正';f.sourceReference.value='证照第2页'
 const one=f.submit(),two=f.submit();assert.equal(f.writes.length,1);assert.equal(f.writes[0].body.institution.code,'002')
 f.institution.code='003';assert.equal(f.writes[0].body.institution.code,'002')
 f.writes[0].reject(new Error('network'));await Promise.all([one,two]);assert.equal(f.visible.value,true)
})
test('return investigation requires a current server-approved action and material reference',async t=>{
 const f=fixture(t);await ready(f);f.state.value.canReturnInvestigation=false;f.open('return');assert.equal(f.visible.value,false)
 f.state.value.canReturnInvestigation=true;f.open('return');f.reason.value='补查理由';await f.submit();assert.equal(f.writes.length,0)
 f.sourceReference.value='法审意见第1页';const request=f.submit();assert.equal(f.writes[0].url,'/bureau/cases/1/fact-versions/return-investigation')
 f.writes[0].reject(new Error('network'));await request
})
test('new case wins stale reads and identity expiry clears sensitive dialogs',async t=>{
 const f=fixture(t);const old=f.reads[0];f.props.caseId=2;await ready(f,'case-two');old.resolve(initial('old'));await tick();assert.equal(f.state.value.marker,'case-two')
 f.open('institution');f.reason.value='private';f.expire();assert.equal(f.visible.value,false);assert.equal(f.reason.value,'');assert.equal(f.state.value,undefined)
})
test('late correction success cannot emit refresh or clear a newly navigated case',async t=>{
 const f=fixture(t);await ready(f);f.open('institution');f.reason.value='证照更正';f.sourceReference.value='材料页';const request=f.submit()
 f.props.caseId=2;await ready(f,'two');const messages=f.messages.length;f.writes[0].resolve({data:{data:{}}});await request
 assert.equal(f.state.value.marker,'two');assert.equal(f.emits.length,0);assert.equal(f.messages.length,messages)
})
