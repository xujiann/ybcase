import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {stripTypeScriptTypes} from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import {parseTaskFocus,withoutTaskFocus} from '../src/api/taskFocus.ts'
import {createResourceLoader,resourceState} from '../src/api/loadableResource.ts'
import {createHistoryPagination,historyPageState} from '../src/api/historyPagination.ts'

test('task focus only accepts one canonical positive safe integer and clears only its own query',()=>{
  assert.equal(parseTaskFocus(undefined),undefined)
  for(const value of ['', '0','-1','1.5','01','1e2','9007199254740992',['1','2'],1])assert.equal(parseTaskFocus(value),0)
  assert.equal(parseTaskFocus('250001'),250001)
  assert.deepEqual(withoutTaskFocus({focus:'3',q:'留存',reportBatch:'9'}),{q:'留存',reportBatch:'9'})
})
test('focus banner reacts to route changes and supports the independent report query key',t=>{
  const route=Vue.reactive({query:{reportBatch:'12',q:'留存'}}),props=Vue.reactive({paramKey:'reportBatch',loading:false,failed:false,count:1,label:'报表'})
  const events=[],replacements=[],scope=Vue.effectScope()
  const code=readFileSync(new URL('../src/components/TaskFocusBanner.vue',import.meta.url),'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm,'')
  const view=scope.run(()=>vm.runInNewContext(stripTypeScriptTypes(code)+';({clearFocus,title})',{...Vue,defineProps:()=>props,withDefaults:value=>value,defineEmits:()=> (...args)=>events.push(args),useRoute:()=>route,useRouter:()=>({replace:value=>replacements.push(value)}),parseTaskFocus,withoutTaskFocus}))
  t.after(()=>scope.stop())
  assert.equal(events[0][1],12);route.query.reportBatch='broken';assert.equal(events.at(-1)[1],0);assert.match(view.title.value,/无效/)
  view.clearFocus();assert.equal(replacements[0].query.q,'留存');assert.equal('reportBatch' in replacements[0].query,false)
})
function fixture(t,file,exports){
  const auth=Vue.reactive({token:'one',user:{id:1,username:'actor',roles:['LEADER'],accountCategory:'BUREAU'}}),requests=[],posts=[]
  const client={
    get:(path,config)=>new Promise((resolve,reject)=>requests.push({path,config,resolve:data=>resolve({data:{data}}),reject})),
    post:(path,body)=>new Promise(resolve=>posts.push({path,body,resolve})),
  }
  const code=readFileSync(new URL('../src/views/case/'+file+'.vue',import.meta.url),'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm,'')
  const unmounts=[],scope=Vue.effectScope()
  const view=scope.run(()=>vm.runInNewContext(stripTypeScriptTypes(code)+`;({${exports}})`,{...Vue,client,AbortController,resourceState,createResourceLoader,createHistoryPagination,historyPageState,useAuthStore:()=>auth,useRoute:()=>({query:{}}),useRouter:()=>({push(){}}),onMounted(){},onUnmounted:fn=>unmounts.push(fn),caseOperationAccess:()=>true,canWritePrecase:()=>true,ElMessage:{warning(){},success(){}},ElMessageBox:{},CLUE_STATUS:{},PARTY_TYPE:{},SOURCE:{},INSTITUTION_GRADE:{},fmtTime:value=>value}))
  t.after(()=>{unmounts.forEach(fn=>fn());scope.stop()})
  return {...view,auth,requests,posts}
}
test('clue focus ignores list filters and malformed IDs never load the first page',async t=>{
  const f=fixture(t,'CluesView','setTaskFocus,load,records,q,statusFilter,range')
  f.q.value='别的线索';f.statusFilter.value='REJECTED';f.range.value=['2000-01-01','2000-01-02'];f.setTaskFocus(250001)
  const read=f.load();assert.equal(f.requests[0].config.params.focusId,250001);assert.equal('q' in f.requests[0].config.params,false)
  f.requests[0].resolve({rows:[{id:250001}],total:1});await read;assert.equal(f.records.value[0].id,250001)
  f.setTaskFocus(0);await f.load();assert.equal(f.requests.length,1);assert.equal(f.records.value.length,0)
})
test('approval focus changes invalidate an earlier target and refuse an invalid focus',async t=>{
  const f=fixture(t,'ApprovalsView','setTaskFocus,load,rows')
  f.setTaskFocus(1);const old=f.load();f.setTaskFocus(2);const next=f.load()
  assert.equal(f.requests[1].config.params.focusId,2);f.requests[1].resolve([{id:2,kind:'EXTEND',payload:'{}'}]);await next
  f.requests[0].resolve([{id:1,kind:'EXTEND',payload:'{}'}]);await old;assert.equal(f.rows.value[0].id,2)
  f.setTaskFocus(0);await f.load();assert.equal(f.requests.length,2);assert.equal(f.rows.value.length,0)
})
test('handoff focus makes one scoped request and discards a previous identity reply',async t=>{
  const f=fixture(t,'DepartmentHandoffsView','setTaskFocus,paging,pagination,status')
  f.status.value='REJECTED';f.setTaskFocus(999);const pending=f.paging.load()
  assert.equal(f.requests[0].config.params.focusId,999);assert.equal('status' in f.requests[0].config.params,false)
  f.auth.user.id=2;f.requests[0].resolve({rows:[{id:999}],total:1,page:1,size:1});await pending
  assert.equal(f.pagination.rows.length,0)
})

test('inspection assignment discards a previous handoff lookup and submits against the displayed assignee',async t=>{
  const f=fixture(t,'DepartmentHandoffsView','openAssignment,assignment,assignees,submitAssignment')
  const first=f.openAssignment({id:1,source_no:'检查一',can_assign_inspection:true})
  const second=f.openAssignment({id:2,source_no:'检查二',can_assign_inspection:true})
  assert.equal(f.requests[0].config.signal.aborted,true)
  f.requests[0].resolve({candidates:[{id:3,username:'stale'}],assignedUserId:3})
  await first
  assert.equal(f.assignees.value.length,0)
  f.requests[1].resolve({candidates:[{id:4,username:'current'},{id:5,username:'replacement'}],assignedUserId:4})
  await second
  assert.equal(f.assignment.username,'current')
  f.assignment.username='replacement'
  f.assignment.reason='接续办理'
  const submitted=f.submitAssignment()
  assert.equal(f.posts[0].path,'/bureau/department-handoffs/2/inspection-assignment')
  assert.equal(f.posts[0].body.username,'replacement')
  assert.equal(f.posts[0].body.expectedAssignedUserId,4)
  f.auth.user.id=2
  f.posts[0].resolve()
  await submitted
  assert.equal(f.requests.length,2)
})
