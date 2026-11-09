import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
const source=readFileSync(new URL('../src/components/SignaturePolicyBanner.vue',import.meta.url),'utf8')
const script=source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm,'')
const tick=()=>new Promise(setImmediate)
function fixture(t){
  const auth=Vue.reactive({token:'first',user:{username:'handler',roles:['HANDLER']}})
  const reads=[],teardown=[],scope=Vue.effectScope()
  const state=scope.run(()=>vm.runInNewContext(stripTypeScriptTypes(script)+';({notice,formal,failed})',{
    ...Vue,useAuthStore:()=>auth,onBeforeUnmount:fn=>teardown.push(fn),
    client:{get:url=>new Promise((resolve,reject)=>reads.push({url,resolve:data=>resolve({data:{data}}),reject}))},
  }))
  let stopped=false
  const unmount=()=>{if(!stopped){stopped=true;teardown.forEach(fn=>fn());scope.stop()}}
  t.after(unmount)
  return {...state,auth,reads,unmount}
}
test('signature policy explicitly distinguishes simulation and formal runtime',async t=>{
  const f=fixture(t)
  f.reads[0].resolve({mode:'SIMULATION',formal:false,notice:'模拟签章不能替代正式签章'});await tick()
  assert.equal(f.formal.value,false);assert.match(f.notice.value,/模拟/)
  f.auth.token='second'
  f.reads[1].resolve({mode:'FORMAL',formal:true,notice:'正式模式须使用真实CA'});await tick()
  assert.equal(f.formal.value,true);assert.match(f.notice.value,/正式/)
})
test('a stale policy reply cannot refill another signed-in identity',async t=>{
  const f=fixture(t),old=f.reads[0]
  f.auth.user={username:'another',roles:['LEGAL']}
  f.reads[1].resolve({mode:'SIMULATION',formal:false,notice:'当前模拟'});await tick()
  old.resolve({mode:'FORMAL',formal:true,notice:'旧正式'});await tick()
  assert.equal(f.notice.value,'当前模拟');assert.equal(f.formal.value,false)
})
test('invalid or failed policy never claims a formal state',async t=>{
  const f=fixture(t)
  f.reads[0].resolve({mode:'unknown',formal:true,notice:'不可信状态'});await tick()
  assert.equal(f.failed.value,true);assert.equal(f.notice.value,'');assert.equal(f.formal.value,false)
})
test('unmounted policy panel ignores late server results',async t=>{
  const f=fixture(t);f.unmount()
  f.reads[0].resolve({mode:'FORMAL',formal:true,notice:'迟到正式'});await tick()
  assert.equal(f.notice.value,'');assert.equal(f.formal.value,false)
})
