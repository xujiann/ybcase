import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {stripTypeScriptTypes} from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import {createHistoryPagination,historyPageState} from '../src/api/historyPagination.ts'
import {createSessionExpiry} from '../src/api/sessionExpiry.ts'
import {createApprovalRequestKey} from '../src/api/approvalRequestKey.ts'
import {caseOperationAccess} from '../src/api/caseWriteAccess.ts'
import {canWritePrecase} from '../src/api/precaseAttachments.ts'

const tick=()=>new Promise(setImmediate)
function fixture(t,inspection=false) {
  const auth=Vue.reactive({token:'first-session',user:{id:1,username:'owner',roles:['HANDLER'],accountCategory:'DISTRICT'}})
  const posts=[],reads=[],prompts=[],messages=[],unmounts=[]
  const sessionExpiry=createSessionExpiry(),scope=Vue.effectScope()
  const client={
    get:(path,config)=>{reads.push({path,config});return Promise.resolve({data:{data:{rows:[],total:0,page:1,size:20}}})},
    post:(path,body)=>new Promise((resolve,reject)=>posts.push({path,body,resolve:value=>resolve({data:{data:value??{}}}),reject})),
  }
  const file=inspection?'InspectionsView':'CluesView'
  const source=readFileSync(new URL('../src/views/case/'+file+'.vue',import.meta.url),'utf8')
    .match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm,'')
  const exported=inspection?'onCreate,form,dlg':'onCreate,form,createVisible,onImport,onExclusion,onReject'
  const view=scope.run(()=>vm.runInNewContext(stripTypeScriptTypes(source)+';({'+exported+'})',{
    ...Vue,client,sessionExpiry,createHistoryPagination,historyPageState,createApprovalRequestKey,
    caseOperationAccess,canWritePrecase,useAuthStore:()=>auth,useRouter:()=>({push(){}}),
    onMounted(){},onUnmounted:fn=>unmounts.push(fn),
    ElMessage:{success:message=>messages.push(message),warning:message=>messages.push(message),error:message=>messages.push(message)},
    ElMessageBox:{prompt:(text,title,options)=>new Promise((resolve,reject)=>prompts.push({text,title,options,resolve,reject}))},
  }))
  let unmounted=false
  const unmount=()=>{if(!unmounted){unmounted=true;unmounts.forEach(fn=>fn());scope.stop()}}
  t.after(unmount)
  const visible=inspection?view.dlg:view.createVisible
  visible.value=true
  Object.assign(view.form,inspection?{objectName:'检查对象甲',officers:'甲、乙'}:{suspectName:'线索对象甲',content:'线索草稿甲'})
  return {...view,auth,posts,reads,prompts,messages,visible,sessionExpiry,unmount}
}
const row=()=>Vue.reactive({id:12,status:'PENDING',createdBy:'owner',handler:'owner'})

for(const inspection of [false,true]) {
  const name=inspection?'inspection':'clue'
  test(name+' creation snapshots its form and posts once while pending',async t=>{
    const f=fixture(t,inspection),pending=f.onCreate()
    Object.assign(f.form,inspection?{objectName:'之后修改',officers:'丙、丁'}:{suspectName:'之后修改',content:'之后修改'})
    const repeated=f.onCreate()
    assert.equal(f.posts.length,1)
    assert.equal(inspection?f.posts[0].body.objectName:f.posts[0].body.suspectName,inspection?'检查对象甲':'线索对象甲')
    f.posts[0].resolve()
    await Promise.all([pending,repeated])
    assert.equal(f.visible.value,false)
    assert.equal(f.reads.length,1)
  })
  test(name+' creation completion cannot close a reopened draft',async t=>{
    const f=fixture(t,inspection),pending=f.onCreate()
    f.visible.value=false
    f.visible.value=true
    Object.assign(f.form,inspection?{objectName:'新检查草稿'}:{suspectName:'新线索草稿',content:'新内容'})
    f.posts[0].resolve()
    await pending
    assert.equal(f.visible.value,true)
    assert.equal(inspection?f.form.objectName:f.form.suspectName,inspection?'新检查草稿':'新线索草稿')
    assert.equal(f.messages.length,0)
    assert.equal(f.reads.length,0)
  })
  test(name+' creation ignores completion after a session changes and returns',async t=>{
    const f=fixture(t,inspection),pending=f.onCreate()
    f.auth.token='other-session'
    f.auth.token='first-session'
    f.visible.value=true
    f.posts[0].resolve()
    await pending
    assert.equal(f.visible.value,true)
    assert.equal(f.messages.length,0)
    assert.equal(f.reads.length,0)
  })
  test(name+' creation stops after unmount and handles a rejected save without losing the draft',async t=>{
    const f=fixture(t,inspection),pending=f.onCreate()
    f.posts[0].reject(new Error('request failed'))
    await assert.doesNotReject(pending)
    assert.equal(f.visible.value,true)
    assert.equal(inspection?f.form.objectName:f.form.suspectName,inspection?'检查对象甲':'线索对象甲')
    const retry=f.onCreate()
    f.unmount()
    f.posts[1].resolve()
    await retry
    await f.onCreate()
    assert.equal(f.posts.length,2)
    assert.equal(f.messages.length,0)
    assert.equal(f.reads.length,0)
  })
  test(name+' creation cannot post from a closed dialog or an expired session',async t=>{
    const f=fixture(t,inspection)
    f.visible.value=false
    const closed=f.onCreate()
    assert.equal(f.posts.length,0)
    await closed
    f.visible.value=true
    f.sessionExpiry.expire(f.sessionExpiry.capture(f.auth.token),f.auth.token)
    const expired=f.onCreate()
    assert.equal(f.posts.length,0)
    await expired
  })
  test(name+' account changes discard the previous account draft and restore current defaults',t=>{
    const f=fixture(t,inspection),date=inspection?f.form.plannedAt:f.form.receivedAt
    Object.assign(f.form,inspection
      ?{itemId:9,taskLevel:'MUNICIPAL',objectType:'PERSON',plannedAt:'1900-01-01'}
      :{source:'TRANSFER',suspectType:'PERSON',receivedAt:'1900-01-01',handler:'previous-owner'})
    const sameAccountDraft=JSON.stringify(f.form)
    f.visible.value=false
    f.visible.value=true
    assert.equal(JSON.stringify(f.form),sameAccountDraft)
    f.auth.token='new-account-session'
    f.auth.user.id=2
    assert.equal(f.visible.value,false)
    if(inspection){
      assert.equal(f.form.objectName,'')
      assert.equal(f.form.officers,'')
      assert.equal(f.form.itemId,null)
      assert.equal(f.form.taskLevel,null)
      assert.equal(f.form.objectType,'PROVIDER')
      assert.equal(f.form.plannedAt,date)
    }else{
      assert.equal(f.form.suspectName,'')
      assert.equal(f.form.content,'')
      assert.equal(f.form.handler,'')
      assert.equal(f.form.source,'COMPLAINT')
      assert.equal(f.form.suspectType,'PROVIDER')
      assert.equal(f.form.receivedAt,date)
    }
    f.visible.value=true
    assert.equal(inspection?f.form.objectName:f.form.suspectName,'')
  })
}

test('clue import owns one prompt, rejects stale confirmation and consumes cancellation',async t=>{
  const f=fixture(t),pending=f.onImport(),repeated=f.onImport()
  assert.equal(f.prompts.length,1)
  f.auth.token='other-session'
  f.prompts[0].resolve({value:'[{"content":"线索","suspectName":"机构"}]'})
  await Promise.all([pending,repeated])
  assert.equal(f.posts.length,0)
  const cancelled=f.onImport()
  f.prompts[1].reject('cancel')
  await assert.doesNotReject(cancelled)
  assert.equal(f.messages.length,0)
})
test('clue import failure retains confirmed JSON for a retry and suppresses late unmounted success',async t=>{
  const f=fixture(t),json='[{"content":"线索","suspectName":"机构"}]',pending=f.onImport()
  f.prompts[0].resolve({value:json})
  await tick()
  f.posts[0].reject(new Error('offline'))
  await assert.doesNotReject(pending)
  const retry=f.onImport()
  assert.equal(f.prompts[1].options.inputValue,json)
  f.prompts[1].resolve({value:json})
  await tick()
  f.unmount()
  f.posts[1].resolve({imported:1})
  await retry
  assert.equal(f.messages.length,0)
  assert.equal(f.reads.length,0)
})
for(const action of ['onExclusion','onReject']) {
  test(action+' keeps its normal payload and refreshes only after success',async t=>{
    const f=fixture(t),source=row(),pending=f[action](source)
    f.prompts[0].resolve({value:action==='onExclusion'?'2':'核查结果'})
    if(action==='onExclusion'){await tick();f.prompts[1].resolve({value:'鉴定事由'})}
    await tick()
    assert.equal(f.posts.length,1)
    assert.equal(f.reads.length,0)
    if(action==='onExclusion'){
      assert.equal(f.posts[0].body.days,2)
      assert.equal(f.posts[0].body.reason,'鉴定事由')
    }else assert.equal(f.posts[0].body.verifyResult,'核查结果')
    f.posts[0].resolve()
    await pending
    assert.equal(f.reads.length,1)
    assert.equal(f.messages.length,1)
  })
  test(action+' blocks repeated prompts and stops when the session changes',async t=>{
    const f=fixture(t),source=row(),pending=f[action](source),repeated=f[action](source)
    assert.equal(f.prompts.length,1)
    f.auth.token='other-session'
    f.prompts[0].resolve({value:action==='onExclusion'?'2':'核查结果'})
    await Promise.all([pending,repeated])
    assert.equal(f.posts.length,0)
    assert.equal(f.prompts.length,1)
  })
  test(action+' consumes cancellation and preserves the current page after a failed post',async t=>{
    const f=fixture(t),source=row(),cancelled=f[action](source)
    f.prompts[0].reject('cancel')
    await assert.doesNotReject(cancelled)
    const pending=f[action](source)
    f.prompts[1].resolve({value:action==='onExclusion'?'2':'核查结果'})
    if(action==='onExclusion'){await tick();f.prompts[2].resolve({value:'鉴定事由'})}
    await tick()
    f.posts[0].reject(new Error('denied'))
    await assert.doesNotReject(pending)
    assert.equal(f.reads.length,0)
    assert.equal(f.messages.length,0)
  })
  test(action+' pins the chosen source and ignores a late response after unmount',async t=>{
    const f=fixture(t),source=row(),pending=f[action](source)
    f.prompts[0].resolve({value:action==='onExclusion'?'2':'核查结果'})
    if(action==='onExclusion'){await tick();f.prompts[1].resolve({value:'鉴定事由'})}
    await tick()
    assert.equal(f.posts.length,1)
    assert.match(f.posts[0].path,/clues\/12\//)
    f.unmount()
    f.posts[0].resolve()
    await pending
    assert.equal(f.messages.length,0)
    assert.equal(f.reads.length,0)
  })
  test(action+' cannot submit a source whose identity changed during confirmation',async t=>{
    const f=fixture(t),source=row(),pending=f[action](source)
    source.id=99
    f.prompts[0].resolve({value:action==='onExclusion'?'2':'核查结果'})
    await pending
    assert.equal(f.posts.length,0)
    assert.equal(f.prompts.length,1)
  })
}
test('clue import preserves its JSON validation message and imports a corrected draft once',async t=>{
  const f=fixture(t),invalid=f.onImport()
  f.prompts[0].resolve({value:'['})
  await invalid
  assert.equal(f.messages[0],'JSON 格式有误，请检查括号与逗号后重试')
  const valid=f.onImport()
  assert.equal(f.prompts[1].options.inputValue,'[')
  f.prompts[1].resolve({value:'[{"content":"线索","suspectName":"机构"}]'})
  await tick()
  assert.equal(f.posts[0].body[0].content,'线索')
  f.posts[0].resolve({imported:1})
  await valid
  assert.equal(f.messages.at(-1),'已导入 1 条线索')
  assert.equal(f.reads.length,1)
})
test('expired clue operations never open a confirmation or submit',async t=>{
  const f=fixture(t)
  f.sessionExpiry.expire(f.sessionExpiry.capture(f.auth.token),f.auth.token)
  await f.onImport()
  await f.onExclusion(row())
  await f.onReject(row())
  assert.equal(f.prompts.length,0)
  assert.equal(f.posts.length,0)
})
