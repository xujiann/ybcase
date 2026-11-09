import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { parse } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-dom'
import { renderToString } from '@vue/server-renderer'
import { createResourceLoader, resourceState } from '../src/api/loadableResource.ts'

const source=readFileSync(new URL('../src/views/case/ApprovalsView.vue',import.meta.url),'utf8')
const tick=()=>new Promise(setImmediate)
function fixture() {
  const reads=[],writes=[],prompts=[],successes=[],unmount=[]
  const client={
    get:url=>new Promise((resolve,reject)=>reads.push({url,resolve:data=>resolve({data:{data}}),reject})),
    post:(url,body)=>new Promise((resolve,reject)=>writes.push({url,body,resolve,reject})),
  }
  const auth=Vue.reactive({user:{roles:['ADMIN']}}),route=Vue.reactive({query:{}})
  const context={...Vue,client,resourceState,createResourceLoader,useAuthStore:()=>auth,
    useRoute:()=>route,useRouter:()=>({push(){}}),caseOperationAccess:()=>true,
    onMounted(){},onUnmounted:fn=>unmount.push(fn),
    ElMessage:{success:msg=>successes.push(msg)},
    ElMessageBox:{prompt:()=>new Promise((resolve,reject)=>prompts.push({resolve,reject}))}}
  const script=source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  const output=vm.runInNewContext(stripTypeScriptTypes(script.replace(/^import .+\r?\n/gm,''))+
    '\n;({load,rows,loading,approvalState,deciding,onDecide,parsePayload,pl,officerNames})',context)
  return {...output,auth,route,reads,writes,prompts,successes,unmount:()=>unmount.forEach(fn=>fn())}
}
async function seed(f,rows=[{id:1,kind:'EXTEND',payload:'{"days":3}'}]) {
  const pending=f.load();f.reads.at(-1).resolve(rows);await pending
}
test('personal application history loads its own endpoint and offers no decision action',async()=>{
  const f=fixture()
  f.route.query.mine='1'
  await seed(f,[{id:1,kind:'EXTEND',status:'REJECTED',opinion:'补材料',payload:'{}'}])
  assert.equal(f.reads[0].url,'/bureau/approvals/mine')
  await f.onDecide(f.rows.value[0],true)
  assert.equal(f.prompts.length,0)
  assert.equal(f.writes.length,0)
})
test('a rejected filing with usable historical JSON can be corrected even if personnel are missing',()=>{
  const f=fixture()
  const historical=f.parsePayload('{"partyName":"原当事人"}','FILE_CASE')
  assert.equal(historical.__invalid,true)
  assert.equal(historical.__resumable,true)
  assert.equal(f.parsePayload('[]','FILE_CASE').__resumable,undefined)
})
test('approval refresh ignores older responses and does not restore decided rows',async()=>{
  const f=fixture(),first=f.load(),second=f.load()
  f.reads[1].resolve([]);await second;f.reads[0].resolve([{id:1}]);await first
  assert.equal(f.rows.value.length,0);assert.equal(f.approvalState.ready,true)
})
test('stale approval failure cannot finish the latest spinner',async()=>{
  const f=fixture(),first=f.load(),second=f.load()
  f.reads[0].reject(new Error('stale'));await first;assert.equal(f.loading.value,true)
  f.reads[1].resolve([{id:2}]);await second
  assert.equal(f.rows.value[0].id,2);assert.equal(f.approvalState.failed,false)
})
test('latest failed approval refresh clears actionable rows and can be retried',async()=>{
  const f=fixture();await seed(f);const failed=f.load()
  assert.equal(f.rows.value.length,0);f.reads[1].reject(new Error('offline'));await assert.doesNotReject(failed)
  assert.equal(f.rows.value.length,0);assert.equal(f.approvalState.failed,true)
  const retry=f.load();f.reads[2].resolve([{id:2}]);await retry;assert.equal(f.rows.value[0].id,2)
})
test('unmounted approvals ignore late responses and cannot start new loads',async()=>{
  const f=fixture(),first=f.load();f.unmount();f.reads[0].resolve([{id:1}]);await first
  assert.equal(f.rows.value.length,0);await f.load();assert.equal(f.reads.length,1)
})
test('approval prompt, post and refresh share one operation lock',async()=>{
  const f=fixture();await seed(f);const row=f.rows.value[0]
  const first=f.onDecide(row,true);await f.onDecide(row,false);assert.equal(f.prompts.length,1)
  f.prompts[0].resolve({value:'同意'});await tick();assert.equal(f.writes.length,1)
  await f.onDecide(row,true);assert.equal(f.writes.length,1)
  f.writes[0].resolve({});await tick();assert.equal(f.deciding.value,1);assert.equal(f.reads.length,2)
  f.reads[1].resolve([]);await first
  assert.equal(f.deciding.value,null);assert.equal(f.rows.value.length,0);assert.equal(f.successes.length,1)
})
test('cancelled approval prompt is consumed without a write or reload',async()=>{
  const f=fixture();await seed(f);const pending=f.onDecide(f.rows.value[0],true)
  f.prompts[0].reject('cancel');await assert.doesNotReject(pending)
  assert.equal(f.writes.length,0);assert.equal(f.reads.length,1);assert.equal(f.deciding.value,null)
})
test('refreshing the list invalidates an older approval confirmation',async()=>{
  const f=fixture();await seed(f);const pending=f.onDecide(f.rows.value[0],true)
  const refresh=f.load();f.reads[1].resolve([{id:2}]);await refresh
  f.prompts[0].resolve({value:'同意'});await pending
  assert.equal(f.writes.length,0);assert.equal(f.rows.value[0].id,2)
})
test('role loss or unmount during approval confirmation prevents the post',async()=>{
  for(const unmount of [false,true]) {
    const f=fixture();await seed(f);const pending=f.onDecide(f.rows.value[0],true)
    if(unmount)f.unmount();else f.auth.user.roles=[]
    f.prompts[0].resolve({value:'同意'});await pending;assert.equal(f.writes.length,0)
  }
})
test('failed approval post is consumed and refreshes stale pending rows',async()=>{
  const f=fixture();await seed(f);const pending=f.onDecide(f.rows.value[0],true)
  f.prompts[0].resolve({value:'同意'});await tick();f.writes[0].reject(new Error('already decided'))
  await tick();f.reads[1].resolve([]);await assert.doesNotReject(pending)
  assert.equal(f.successes.length,0);assert.equal(f.rows.value.length,0);assert.equal(f.deciding.value,null)
})
test('successful approval followed by failed refresh never leaves an actionable old row',async()=>{
  const f=fixture();await seed(f);const pending=f.onDecide(f.rows.value[0],true)
  f.prompts[0].resolve({value:'同意'});await tick();f.writes[0].resolve({})
  await tick();f.reads[1].reject(new Error('refresh offline'));await assert.doesNotReject(pending)
  assert.equal(f.rows.value.length,0);assert.equal(f.approvalState.failed,true);assert.equal(f.deciding.value,null)
})
test('late post completion after leaving approvals cannot reload or announce success',async()=>{
  const f=fixture();await seed(f);const pending=f.onDecide(f.rows.value[0],true)
  f.prompts[0].resolve({value:'同意'});await tick();f.unmount();f.writes[0].resolve({});await pending
  assert.equal(f.reads.length,1);assert.equal(f.successes.length,0)
})
test('malformed historical filing materials block approval but remain rejectable',async()=>{
  for(const payload of ['{',JSON.stringify([]),JSON.stringify({officers:{}}),JSON.stringify({officers:[null]})]) {
    const f=fixture();await seed(f,[{id:7,kind:'FILE_CASE',payload}]);const row=f.rows.value[0]
    assert.equal(f.pl(row).__invalid,true)
    await f.onDecide(row,true);assert.equal(f.prompts.length,0)
    const rejecting=f.onDecide(row,false);f.prompts[0].resolve({value:'材料异常'});await tick()
    assert.equal(f.writes[0].body.approve,false);f.writes[0].resolve({});await tick()
    f.reads[1].resolve([]);await rejecting
  }
})
test('actual filing expansion renders malformed officers safely and normal officers by name',async()=>{
  const f=fixture()
  const template=parse(source).descriptor.template.content
  const start=template.indexOf('<el-table-column type="expand">')
  const cell=template.slice(start,template.indexOf('</el-table-column>',start)+'</el-table-column>'.length)
  const render=new Function('Vue',compile(cell,{mode:'function'}).code)(Vue)
  for(const [raw,invalid] of [[{officers:{}},true],[{officers:'bad'},true],[{officers:[null]},true],[{officers:[{name:'甲',certNo:'证1',duty:'LEAD'}]},false]]) {
    const row={kind:'FILE_CASE',__pl:f.parsePayload(raw,'FILE_CASE')}
    const app=Vue.createSSRApp({render,setup:()=>({pl:f.pl,officerNames:f.officerNames,causeName:()=>'',PARTY_TYPE:{}})})
    app.component('el-table-column',{setup:(_,{slots})=>()=>Vue.h('div',slots.default?.({row}))})
    for(const name of ['el-descriptions','el-descriptions-item'])app.component(name,{setup:(_,{slots})=>()=>Vue.h('div',slots.default?.())})
    app.component('el-alert',{props:['title'],setup:props=>()=>Vue.h('p',props.title)})
    app.component('JurisdictionSummary',{setup:()=>()=>Vue.h('div')})
    app.config.warnHandler=()=>{}
    const html=await renderToString(app)
    assert.equal(html.includes('申请材料格式异常'),invalid)
    if(!invalid)assert.ok(html.includes('甲（证1 主办）'))
  }
})
