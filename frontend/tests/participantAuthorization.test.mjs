import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {stripTypeScriptTypes} from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import {createSessionExpiry,SESSION_EXPIRED_EVENT} from '../src/api/sessionExpiry.ts'
const source=readFileSync(new URL('../src/components/ParticipantAuthorizationPanel.vue',import.meta.url),'utf8')
const script=source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm,'')
const tick=()=>new Promise(setImmediate)
function fixture(t){
 const auth=Vue.reactive({token:'admin-one',user:{id:1,username:'administrator',roles:['ADMIN']}})
 const reads=[],writes=[],warnings=[],success=[],events=[],cleanup=[],listeners=new Map(),sessionExpiry=createSessionExpiry()
 const client={get:(url,config)=>url==='/system/participant-access'?Promise.resolve({data:{data:[]}}):new Promise((resolve,reject)=>reads.push({url,config,resolve:value=>resolve({data:{data:value}}),reject})),post:(url,body)=>new Promise((resolve,reject)=>writes.push({url,body:JSON.parse(JSON.stringify(body)),resolve,reject}))}
 const scope=Vue.effectScope()
 const result=scope.run(()=>vm.runInNewContext(stripTypeScriptTypes(script)+';({input,preview,reason,sourceReference,loading,saving,failed,applied,history,selectedGrant,revokeVisible,revokeReason,revokeReference,openRevoke,revoke,inspect,approve,close})',{
  ...Vue,defineEmits:()=>(...args)=>events.push(args),useAuthStore:()=>auth,client,sessionExpiry,SESSION_EXPIRED_EVENT,AbortController,
  ElMessage:{warning:value=>warnings.push(value),success:value=>success.push(value)},onMounted:fn=>fn(),onBeforeUnmount:fn=>cleanup.push(fn),
  window:{addEventListener:(key,fn)=>listeners.set(key,fn),removeEventListener:key=>listeners.delete(key)}
 }))
 t.after(()=>{cleanup.forEach(fn=>fn());scope.stop()})
 Object.assign(result.input,{caseId:'10',officerId:'20',username:'real_handler'})
 return {...result,auth,reads,writes,warnings,success,events,expire:()=>{sessionExpiry.expire(sessionExpiry.capture(auth.token),auth.token);listeners.get(SESSION_EXPIRED_EVENT)()}}
}
async function ready(f){const request=f.inspect();f.reads.at(-1).resolve({eligible:true,reason:'证号与机构已核对',source:{case_no:'2026-001',cert_no:'CERT-1',username:'real_handler'},fingerprint:'snapshot-one'});await request}
test('historical participant approval binds current preview and material evidence, blocks double submit',async t=>{
 const f=fixture(t);await ready(f);f.reason.value='核实历史参办';f.sourceReference.value='原卷人员页和当前证件'
 const one=f.approve(),two=f.approve();assert.equal(f.writes.length,1);assert.equal(f.writes[0].url,'/system/participant-access/10/20')
 assert.deepEqual(f.writes[0].body,{username:'real_handler',fingerprint:'snapshot-one',reason:'核实历史参办',sourceReference:'原卷人员页和当前证件'})
 f.writes[0].resolve({data:{data:{}}});await Promise.all([one,two]);assert.equal(f.applied.value,true);assert.equal(f.preview.value,undefined)
})
test('changed source input invalidates preview and late eligibility results',async t=>{
 const f=fixture(t);const pending=f.inspect();const read=f.reads[0];f.input.officerId='21'
 read.resolve({eligible:true,source:{},fingerprint:'old'});await pending;assert.equal(f.preview.value,undefined)
 await ready(f);f.input.username='another_handler';f.reason.value='核实';f.sourceReference.value='原卷';await f.approve();assert.equal(f.writes.length,0)
})
test('non-admin, own-account and invalid source identifiers never request approval',async t=>{
 const f=fixture(t);f.input.username='administrator';await f.inspect();assert.equal(f.reads.length,0)
 f.input.username='real_handler';f.input.caseId='1.2';await f.inspect();assert.equal(f.reads.length,0)
 f.input.caseId='10';f.auth.user.roles=['HANDLER'];await f.inspect();assert.equal(f.reads.length,0)
})
test('session expiry clears evidence and obsolete approval success cannot update the new session',async t=>{
 const f=fixture(t);await ready(f);f.reason.value='隐私依据';f.sourceReference.value='原卷人员';const request=f.approve()
 f.expire();assert.equal(f.preview.value,undefined);assert.equal(f.reason.value,'')
 f.writes[0].resolve({data:{data:{}}});await request;assert.equal(f.success.length,0);assert.equal(f.applied.value,false)
})

test('single-case authorization revocation is explicit, keeps original grant identity and blocks double submit',async t=>{
 const f=fixture(t);const grant={id:4,case_id:10,officer_id:20,username:'real_handler',revoked_at:null};f.history.value=[grant]
 f.openRevoke(grant);assert.equal(f.revokeVisible.value,true);await f.revoke();assert.equal(f.writes.length,0)
 f.revokeReason.value='核对发现该次授权需收回';f.revokeReference.value='原卷核对材料第4页'
 const one=f.revoke(),two=f.revoke();assert.equal(f.writes.length,1);assert.equal(f.writes[0].url,'/system/participant-access/4/revoke')
 assert.equal(f.writes[0].body.sourceReference,'原卷核对材料第4页');assert.equal('username' in f.writes[0].body,false)
 f.writes[0].resolve({data:{data:{}}});await Promise.all([one,two]);assert.equal(f.revokeVisible.value,false);assert.equal(f.selectedGrant.value,undefined)
})

test('stale-case and already-revoked grant records cannot be revoked from the current dialog',async t=>{
 const f=fixture(t);const old={id:4,case_id:10,revoked_at:null};f.history.value=[old];f.openRevoke(old);f.revokeReason.value='核实';f.revokeReference.value='原卷'
 f.input.caseId='11';assert.equal(f.history.value.length,0);assert.equal(f.revokeVisible.value,false);await f.revoke();assert.equal(f.writes.length,0)
 const revoked={id:5,case_id:11,revoked_at:'2026-11-08'};f.history.value=[revoked];f.openRevoke(revoked);assert.equal(f.revokeVisible.value,false)
})
