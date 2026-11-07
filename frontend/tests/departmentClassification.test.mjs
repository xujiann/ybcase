import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
const source=readFileSync(new URL('../src/components/DepartmentClassificationDialog.vue',import.meta.url),'utf8')
const script=source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm,'')
const tick=()=>new Promise(setImmediate)
function fixture(t){
  const props=Vue.reactive({deptId:7}),auth=Vue.reactive({token:'one',user:{id:1,roles:['ADMIN']}})
  const reads=[],writes=[],events=[],teardown=[],listeners=new Map(),sessionExpiry=createSessionExpiry()
  const client={get:(url,config)=>new Promise((resolve,reject)=>reads.push({url,config,resolve:data=>resolve({data:{data}}),reject})),post:(url,body)=>new Promise((resolve,reject)=>writes.push({url,body:JSON.parse(JSON.stringify(body)),resolve,reject}))}
  const scope=Vue.effectScope(),f=scope.run(()=>vm.runInNewContext(stripTypeScriptTypes(script)+';({kind,reason,sourceReference,snapshot,saving,failed,preview,confirm})',{
    ...Vue,defineProps:()=>props,defineEmits:()=>event=>events.push(event),useAuthStore:()=>auth,client,sessionExpiry,SESSION_EXPIRED_EVENT,
    adminOperationAccess:roles=>roles?.includes('ADMIN'),ElMessage:{warning(){},success(){}},onBeforeUnmount:fn=>teardown.push(fn),
    window:{addEventListener:(name,fn)=>listeners.set(name,fn),removeEventListener:name=>listeners.delete(name)},
  }))
  t.after(()=>{teardown.forEach(fn=>fn());scope.stop()})
  return {...f,auth,props,reads,writes,events,expire(){sessionExpiry.expire(sessionExpiry.capture(auth.token),auth.token);listeners.get(SESSION_EXPIRED_EVENT)()}}
}
function data(kind='DISTRICT'){return{department:{id:7,name:'合成旧目录',code:'S7',org_kind:null},accounts:[],unclassifiedAccounts:0,conflicts:[],orgKind:kind,fingerprint:kind+'fingerprint'}}
async function ready(f){f.kind.value='DISTRICT';await tick();f.reads.at(-1).resolve(data());await tick();f.reason.value='局方确认';f.sourceReference.value='核对001'}
test('classification ignores old previews when the selected category changes',async t=>{const f=fixture(t);f.kind.value='DISTRICT';await tick();const old=f.reads[0];f.kind.value='BUREAU';await tick();f.reads[1].resolve(data('BUREAU'));await tick();old.resolve(data());await tick();assert.equal(f.snapshot.value.orgKind,'BUREAU')})
test('classification snapshots the reviewed category and suppresses duplicate confirmation',async t=>{const f=fixture(t);await ready(f);const a=f.confirm(),b=f.confirm();assert.equal(f.writes.length,1);assert.equal(f.writes[0].body.fingerprint,'DISTRICTfingerprint');f.writes[0].resolve({});await Promise.all([a,b]);assert.deepEqual(f.events,['completed','close'])})
test('failed confirmation preserves reason and requires a fresh preview',async t=>{const f=fixture(t);await ready(f);const promise=f.confirm();f.writes[0].reject(new Error('changed directory'));await promise;assert.equal(f.reason.value,'局方确认');assert.equal(f.snapshot.value,undefined);await f.confirm();assert.equal(f.writes.length,1)})
test('identity changes clear preview and expiry blocks writes and late preview responses',async t=>{const f=fixture(t);await ready(f);const old=f.preview(),read=f.reads.at(-1);f.expire();read.resolve(data());await old;assert.equal(f.snapshot.value,undefined);await f.confirm();assert.equal(f.writes.length,0);f.auth.token='two';assert.ok(f.events.includes('close'));assert.equal(f.reason.value,'')})
