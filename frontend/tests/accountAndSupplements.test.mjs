import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import { webcrypto } from 'node:crypto'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
import { parseTaskFocus } from '../src/api/taskFocus.ts'
const tick=()=>new Promise(setImmediate)
const plain=x=>JSON.parse(JSON.stringify(x))
function fixture(t,component,role='HANDLER'){
 const auth=Vue.reactive({token:'one',user:{id:1,username:'handler-1',accountCategory:role==='HANDLER'?'DISTRICT':'BUREAU',roles:[role]}})
 const route=Vue.reactive({query:{}}),props=Vue.reactive({caseId:1,writable:true}),reads=[],writes=[],messages=[],navigation=[],downloaded=[],listeners=new Map(),cleanup=[]
 const client={get:(url,config)=>new Promise((resolve,reject)=>reads.push({url,config,resolve:data=>resolve({data:{data}}),resolveResponse:resolve,reject})),post:(url,body)=>new Promise((resolve,reject)=>writes.push({url,body:body instanceof FormData?body:plain(body),resolve:data=>resolve({data:{data}}),reject}))}
 const text=readFileSync(new URL(`../src/components/${component}.vue`,import.meta.url),'utf8')
 const script=text.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm,'')
 const api=component==='AccountReadinessPanel'?'state,load,loading,failed,navigate,adminPath':'data,loading,failed,busy,downloading,createForm,createVisible,docForm,documentVisible,selectedBundle,selectedDoc,actionForm,actionMode,actionVisible,opinion,opinionVisible,openCreate,create,upload,submitBundle,openOpinion,processOpinion,openDocument,createDocument,sign,openDocumentAction,saveDocumentAction,electronic,downloadFile,exportBundle,load,focusFailed,focusedBundle,bundleRowClass'
 let seq=0;const sessionExpiry=createSessionExpiry(),scope=Vue.effectScope()
 const view=scope.run(()=>vm.runInNewContext(stripTypeScriptTypes(script)+`;({${api}})`,{
  ...Vue,client,useAuthStore:()=>auth,defineProps:()=>props,useRoute:()=>route,parseTaskFocus,useRouter:()=>({push:path=>navigation.push(path)}),
  localDate:()=> '2026-11-08',sessionExpiry,SESSION_EXPIRED_EVENT,FormData,
  crypto:{randomUUID:()=> 'key-'+ ++seq,subtle:webcrypto.subtle},
  window:{addEventListener:(e,fn)=>listeners.set(e,fn),removeEventListener:e=>listeners.delete(e)},
  document:{createElement:()=>({href:'',download:'',click(){downloaded.push(this.download)}})},
  URL:{createObjectURL:()=> 'blob:owned-test',revokeObjectURL:()=>{}},
  onMounted:fn=>fn(),onBeforeUnmount:fn=>cleanup.push(fn),
  ElMessage:{success:x=>messages.push(x),warning:x=>messages.push(x),error:x=>messages.push(x)}
 }))
 t.after(()=>{cleanup.forEach(fn=>fn());scope.stop()})
 return {...view,auth,props,route,reads,writes,messages,navigation,downloaded,expire:()=>{sessionExpiry.expire(sessionExpiry.capture(auth.token),auth.token);listeners.get(SESSION_EXPIRED_EVENT)()}}
}
const readiness=(admin=false)=>({department:'待归类处室',accountCategory:'BUREAU',administrator:admin,issues:[{code:'DEPARTMENT_UNCLASSIFIED',message:'所属机构待核对归类',adminPath:'/system/depts'}],capabilities:{reportReview:false}})
const draft=()=>({id:8,title:'真实后续材料',source_reference:'原件第3页',status:'DRAFT',created_by:'handler-1',files:[]})
const approved=()=>({...draft(),status:'APPROVED'})
const doc=()=>({id:10,bundle_id:8,title:'变更决定',doc_type:'DECISION',content:'已批准正文',decision_version_id:4,signatures:[],deliveries:[],consents:[{receiver:'当事人',channel:'同意地址'}]})
const state=(patch={})=>({bundles:[draft()],documents:[doc()],decisionVersions:[{id:4,decision_no:'变更文号',content:'已批准正文'}],...patch})
async function ready(f,value){f.reads.at(-1).resolve(value);await tick()}

test('readiness explains configuration issues and never grants administrator navigation to ordinary accounts',async t=>{
 const f=fixture(t,'AccountReadinessPanel','LEGAL');await ready(f,readiness())
 assert.equal(f.state.value.issues[0].code,'DEPARTMENT_UNCLASSIFIED');f.navigate('/system/depts');assert.deepEqual(f.navigation,[])
 assert.equal(f.adminPath('https://untrusted.invalid'),false)
})
test('readiness navigation is allowlisted and stale responses cannot restore an expired identity',async t=>{
 const f=fixture(t,'AccountReadinessPanel','ADMIN');await ready(f,readiness(true));f.navigate('/system/depts');assert.deepEqual(f.navigation,['/system/depts'])
 const p=f.load(),response=f.reads.at(-1);f.expire();response.resolve(readiness(true));await p;assert.equal(f.state.value,undefined)
})
test('supplement package creation is single-flight and retry uses the same immutable draft key',async t=>{
 const f=fixture(t,'SupplementBundlesPanel');await ready(f,state());f.openCreate();f.createForm.title='凭证包';f.createForm.sourceReference='银行回执'
 const first=f.create(),duplicate=f.create();assert.equal(f.writes.length,1);const key=f.writes[0].body.requestKey
 f.writes[0].reject(new Error('response lost'));await Promise.all([first,duplicate]);assert.equal(f.createVisible.value,true)
 const retry=f.create();assert.equal(f.writes[1].body.requestKey,key);f.writes[1].reject(new Error('offline'));await retry
 f.createForm.title='不同材料';const changed=f.create();assert.notEqual(f.writes[2].body.requestKey,key);f.writes[2].reject(new Error('offline'));await changed
})
test('supplement mutations and late source reads are discarded after case identity changes',async t=>{
 const f=fixture(t,'SupplementBundlesPanel');await ready(f,state());const bundle=f.data.value.bundles[0]
 f.openCreate();f.createForm.title='包';f.createForm.sourceReference='出处';const save=f.create();f.props.caseId=2;f.writes[0].resolve({id:8});await save
 assert.equal(f.createVisible.value,false);assert.equal(f.messages.length,0);assert.equal(f.data.value,undefined)
 await ready(f,state({bundles:[]}));await f.submitBundle(bundle);assert.equal(f.writes.length,1)
})
test('raw material upload enforces file size and passes a real multipart file once',async t=>{
 const f=fixture(t,'SupplementBundlesPanel');await ready(f,state());const bundle=f.data.value.bundles[0]
 const empty={files:[new File([],'empty.pdf',{type:'application/pdf'})],value:'selected'};await f.upload(bundle,{target:empty});assert.equal(f.writes.length,0)
 const file=new File(['actual receipt bytes'],'receipt.pdf',{type:'application/pdf'}),input={files:[file],value:'selected'}
 const send=f.upload(bundle,{target:input});assert.equal(f.writes.length,1);assert.ok(f.writes[0].body instanceof FormData);assert.equal(f.writes[0].body.get('file').name,'receipt.pdf')
 f.writes[0].reject(new Error('offline'));await send;assert.equal(input.value,'')
})
test('self review is unavailable and peer review posts the exact snapshot source',async t=>{
 const f=fixture(t,'SupplementBundlesPanel','LEADER');await ready(f,state({bundles:[{...draft(),status:'PENDING'}]}))
 f.openOpinion(f.data.value.bundles[0],'approve');assert.equal(f.opinionVisible.value,false)
 f.data.value.bundles[0].created_by='other-applicant';f.openOpinion(f.data.value.bundles[0],'approve');f.opinion.value='逐份核对'
 const one=f.processOpinion(),two=f.processOpinion();assert.equal(f.writes.length,1);assert.equal(f.writes[0].url,'/bureau/cases/1/supplement-bundles/8/review');assert.deepEqual(f.writes[0].body,{opinion:'逐份核对',approve:true})
 f.expire();f.writes[0].resolve({});await Promise.all([one,two]);assert.equal(f.messages.length,0)
})
test('post-archive document delivery is version-specific and electronic creation uses its own route',async t=>{
 const f=fixture(t,'SupplementBundlesPanel');await ready(f,state({bundles:[approved()]}));const document=f.data.value.documents[0]
 f.openDocumentAction(document,'deliver');Object.assign(f.actionForm,{receiver:'真实当事人',deliveredAt:'2026-11-07',receiptNo:'R-1',sourceReference:'纸质回证'})
 const delivery=f.saveDocumentAction();assert.equal(f.writes[0].url,'/bureau/cases/1/supplement-bundles/documents/10/deliver');assert.equal(f.writes[0].body.receiptNo,'R-1');f.writes[0].reject(new Error('offline'));await delivery
 f.actionVisible.value=false;const electronic=f.electronic(document);assert.equal(f.writes[1].url,'/bureau/cases/1/electronic-deliveries/supplements');assert.equal(f.writes[1].body.documentId,10);f.writes[1].reject(new Error('offline'));await electronic
})
test('original downloads verify SHA before saving and discard files after session expiry',async t=>{
 const f=fixture(t,'SupplementBundlesPanel'),blob=new Blob(['actual original bytes']),hash=Array.from(new Uint8Array(await webcrypto.subtle.digest('SHA-256',await blob.arrayBuffer()))).map(v=>v.toString(16).padStart(2,'0')).join('')
 await ready(f,state({bundles:[{...approved(),files:[{id:9,filename:'receipt.pdf',sha256:hash,size_bytes:blob.size}]}]}))
 const file=f.data.value.bundles[0].files[0],download=f.downloadFile(file);f.reads.at(-1).resolveResponse({data:blob,headers:{'x-content-sha256':hash}});await download;assert.deepEqual(f.downloaded,['receipt.pdf'])
 const second=f.downloadFile(file);f.expire();f.reads.at(-1).resolveResponse({data:blob,headers:{'x-content-sha256':hash}});await second;assert.deepEqual(f.downloaded,['receipt.pdf'])
})


test('supplement task query locates only the exact loaded pending bundle and clears old review on query changes',async t=>{
 const f=fixture(t,'SupplementBundlesPanel','LEGAL');f.route.query.supplementBundle='8'
 await ready(f,state({bundles:[{...draft(),created_by:'other-applicant',status:'PENDING'}],timeline:[{event_type:'材料提交',event_time:'2026-11-08',reference:'凭证包',detail:'实际材料',actor:'other-applicant'}]}))
 assert.equal(f.focusedBundle.value.id,8);assert.equal(f.selectedBundle.value.id,8);assert.equal(f.opinionVisible.value,true);assert.equal(f.focusFailed.value,false)
 assert.equal(f.bundleRowClass({row:f.data.value.bundles[0]}),'focused-bundle')
 assert.equal(f.data.value.timeline[0].event_type,'材料提交')
 f.route.query.supplementBundle='9';assert.equal(f.selectedBundle.value,undefined);assert.equal(f.opinionVisible.value,false)
 await ready(f,state({bundles:[{...draft(),created_by:'other-applicant',status:'PENDING'}]}))
 assert.equal(f.focusFailed.value,true);assert.equal(f.opinionVisible.value,false)
 assert.equal(f.reads.filter(r=>/\/supplement-bundles/.test(r.url)).every(r=>r.url==='/bureau/cases/1/supplement-bundles'),true)
})
test('resolved, malformed or own supplemental review tasks show a safe banner and cannot submit review',async t=>{
 const f=fixture(t,'SupplementBundlesPanel','LEGAL');f.route.query.supplementBundle='8'
 await ready(f,state({bundles:[approved()]}));assert.equal(f.focusFailed.value,true);assert.equal(f.opinionVisible.value,false)
 f.route.query.supplementBundle='not-an-id';await ready(f,state());assert.equal(f.focusFailed.value,true)
 f.route.query.supplementBundle='8';await ready(f,state({bundles:[{...draft(),status:'PENDING'}]}));assert.equal(f.focusFailed.value,true)
 assert.equal(f.writes.length,0)
})
test('late pending-bundle focus responses cannot reopen a different task selection',async t=>{
 const f=fixture(t,'SupplementBundlesPanel','LEGAL');f.route.query.supplementBundle='8';const old=f.reads.at(-1)
 f.route.query.supplementBundle='9';await ready(f,state({bundles:[{...draft(),id:9,created_by:'other-applicant',status:'PENDING'}]}))
 old.resolve(state({bundles:[{...draft(),created_by:'other-applicant',status:'PENDING'}]}));await tick()
 assert.equal(f.selectedBundle.value.id,9);assert.equal(f.focusedBundle.value.id,9)
})
