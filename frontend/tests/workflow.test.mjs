import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import { parse } from '@vue/compiler-sfc'
import { compile } from '@vue/compiler-dom'
import * as Vue from 'vue'
import { renderToString } from '@vue/server-renderer'
import { canWritePrecase } from '../src/api/precaseAttachments.ts'
import { periodEndError } from '../src/api/periodCompletion.ts'
import { emptyJurisdiction, jurisdictionError, serializeJurisdiction } from '../src/api/jurisdiction.ts'

// Execute the real component handlers with controlled state and HTTP, without a browser or database.
function handler(file, name, context) {
  const source = readFileSync(new URL(`../src/views/${file}`, import.meta.url), 'utf8')
  const start = source.indexOf(`function ${name}(`)
  assert.ok(start >= 0)
  const end = source.indexOf('\n}', start) + 2
  const code = source.slice(source.slice(0, start).endsWith('async ') ? start - 6 : start, end)
  return vm.runInNewContext(stripTypeScriptTypes(code) + `; ${name}`, context)
}
function state(extra={}) {
  const calls=[]
  return {calls, canUseBureau:{value:true}, canOperate:{value:true}, canManageClue:()=>true, isAdmin:{value:true}, canWriteCase:{value:true}, canEditActive:{value:true}, isLeader:{value:true}, client:{post:async (...args)=>calls.push(args)}, ElMessage:{success(){}, warning(){}, info(){}}, load(){}, id:{value:'9'}, dlg:{}, ...extra}
}
test('statement save preserves changed booleans and cannot fabricate hearing date', async () => {
  const ctx=state({statementForm:{statement:'申辩', statementReview:'', hearingRequested:false, hearingHeldAt:'2026-01-01', statementWaived:false},statementSnapshot:{statement:'', statementReview:'', hearingRequested:true, hearingHeldAt:null, statementWaived:false}})
  await handler('case/CaseDetailView.vue','onStatement',ctx)()
  assert.equal(ctx.calls.length,1)
  const body=ctx.calls[0][1]
  assert.equal(body.hearingRequested,false)
  assert.equal(body.statement,'申辩')
  assert.equal(Object.hasOwn(body,'hearingHeldAt'),false)
})
test('review submit omits user-controlled reviewer identity', async () => {
  const ctx=state({canReviewSelected:{value:true},reviewForm:{reviewId:5,reviewer:'冒用姓名',opinionType:'AGREE',opinion:'同意'}})
  await handler('case/CaseDetailView.vue','onDoReview',ctx)()
  assert.equal(Object.hasOwn(ctx.calls[0][1],'reviewer'),false)
  assert.equal(ctx.calls[0][1].opinion,'同意')
})
test('clue extension requests approval rather than granting itself an extension', async () => {
  const ctx=state({ElMessageBox:{prompt:async()=>({value:'核查情况复杂'})}})
  await handler('case/CluesView.vue','onExtend',ctx)({id:12})
  assert.equal(ctx.calls[0][0],'/bureau/approvals')
  assert.equal(ctx.calls[0][1].kind,'CLUE_EXTEND')
  assert.equal(ctx.calls[0][1].clueId,12)
})
test('opening relief dialog refreshes every field from current case', () => {
  const ctx=state({c:{value:{reliefDeadline:'2026-10-01', reliefPending:false, reliefBasis:'期限依据'}},reliefForm:{deadline:'old',pending:true,basis:'old',deferUntil:'old'}})
  handler('case/CaseDetailView.vue','openRelief',ctx)()
  assert.equal(ctx.reliefForm.deadline,'2026-10-01')
  assert.equal(ctx.reliefForm.pending,false)
  assert.equal(ctx.reliefForm.deferUntil,null)
})

test('forced password change does not trigger background business requests', async () => {
  const ctx=state({auth:{user:{mustChangePassword:true}},unread:{value:0}})
  await handler('MainLayout.vue','refreshUnread',ctx)()
  assert.equal(ctx.calls.length,0)
})

test('decision submission stops when checklist is incomplete and keeps form open', async () => {
  const ctx=state({deciding:{value:false}, decisionCheck:{checking:false}, decisionGuard:{check:async()=>false}, decisionBody:()=>({content:'保留决定'}),dlg:{decide:true}})
  await handler('case/CaseDetailView.vue','onDecide',ctx)()
  assert.equal(ctx.calls.length,0)
  assert.equal(ctx.dlg.decide,true)
  assert.equal(ctx.deciding.value,false)
})
test('close submission stops when checklist fails and preserves the report', async () => {
  const ctx=state({closing:{value:false}, closeCheck:{checking:false}, closeGuard:{check:async()=>false}, closeForm:{closeReport:'保留结案报告'},dlg:{close:true}})
  await handler('case/CaseDetailView.vue','submitClose',ctx)()
  assert.equal(ctx.calls.length,0)
  assert.equal(ctx.closeForm.closeReport,'保留结案报告')
  assert.equal(ctx.dlg.close,true)
  assert.equal(ctx.closing.value,false)
})
test('decision submission always rechecks before posting the final request', async () => {
  const sequence=[]
  const ctx=state({deciding:{value:false}, decisionCheck:{checking:false}, decisionGuard:{check:async()=>{sequence.push('check');return true}}, decisionBody:()=>({content:'决定',fineAmount:0}),client:{post:async(path,body)=>sequence.push([path,body])},dlg:{decide:true}})
  await handler('case/CaseDetailView.vue','onDecide',ctx)()
  assert.equal(sequence[0],'check')
  assert.equal(sequence[1][0],'/bureau/cases/9/decide')
  assert.equal(ctx.dlg.decide,false)
})

test('network failure during preflight never writes a decision or closes its form', async () => {
  const ctx=state({deciding:{value:false},decisionCheck:{checking:false},decisionGuard:{check:async()=>{throw new Error('offline')}},dlg:{decide:true}})
  await assert.rejects(handler('case/CaseDetailView.vue','onDecide',ctx)(),/offline/)
  assert.equal(ctx.calls.length,0)
  assert.equal(ctx.dlg.decide,true)
  assert.equal(ctx.deciding.value,false)
})
test('repeated decision clicks perform one preflight and one mutation', async () => {
  let release,checks=0
  const ctx=state({deciding:{value:false},decisionCheck:{checking:false},decisionGuard:{check:()=>{checks++;return new Promise(resolve=>release=resolve)}},decisionBody:()=>({content:'决定'}),dlg:{decide:true}})
  const submit=handler('case/CaseDetailView.vue','onDecide',ctx)
  const first=submit()
  await submit()
  assert.equal(checks,1)
  release(true)
  await first
  assert.equal(ctx.calls.length,1)
})
test('final guard rejection preserves close report for correction and retry', async () => {
  const ctx=state({closing:{value:false},closeCheck:{checking:false},closeGuard:{check:async()=>true},closeForm:{closeReport:'报告内容'},client:{post:async()=>{throw new Error('并发变更')}},dlg:{close:true}})
  await assert.rejects(handler('case/CaseDetailView.vue','submitClose',ctx)(),/并发变更/)
  assert.equal(ctx.dlg.close,true)
  assert.equal(ctx.closeForm.closeReport,'报告内容')
  assert.equal(ctx.closing.value,false)
})

test('read-only checklist users cannot mutate decision or closure even by invoking handlers', async () => {
  for (const name of ['onDecide','submitClose']) {
    let checked=false
    const ctx=state({isLeader:{value:false},deciding:{value:false},closing:{value:false},decisionCheck:{checking:false},closeCheck:{checking:false},decisionGuard:{check:async()=>{checked=true;return true}},closeGuard:{check:async()=>{checked=true;return true}}})
    await handler('case/CaseDetailView.vue',name,ctx)()
    assert.equal(checked,false)
    assert.equal(ctx.calls.length,0)
  }
})

test('filing and filing approval both carry the same jurisdiction snapshot and clue association', async () => {
  for (const leader of [true,false]) {
    const form={partyName:'测试单位',causeId:1,clueId:12,officers:[{name:'甲',certNo:'证1'},{name:'乙',certNo:'证2'}],jurisdiction:{...emptyJurisdiction(),type:'DESIGNATED',basis:' 批复依据 ',designatedNo:' 指定1 '}}
    const ctx=state({isLeader:{value:leader},submitting:{value:false},form,jurisdictionError,serializeJurisdiction,createVisible:{value:true},previousApproval:{value:null},filingRequestKey:()=> 'example-filing-key',auth:{token:'session'},filingDraftGeneration:1,disposed:false,router:{push(){}},client:{post:async(...args)=>{ctx.calls.push(args);return {data:{data:{caseNo:'测试案',id:3}}}}}})
    await handler('case/CasesView.vue','onCreate',ctx)()
    assert.equal(ctx.calls.length,1)
    const payload=leader?ctx.calls[0][1]:ctx.calls[0][1].payload
    assert.equal(payload.clueId,12)
    assert.equal(payload.jurisdiction.basis,'批复依据')
    assert.equal(payload.jurisdiction.designatedNo,'指定1')
    assert.notEqual(payload.jurisdiction,form.jurisdiction)
    if (!leader) {
      assert.equal(ctx.calls[0][1].clueId,12)
      assert.equal(ctx.calls[0][1].requestKey,'example-filing-key')
    }
  }
})
test('opening jurisdiction correction loads current case facts and clears prior reason', () => {
  const ctx=state({canUpdateJurisdiction:{value:true},detail:{value:{jurisdiction:{...emptyJurisdiction(),type:'LOCAL',basis:'当前依据',violationPlace:'当前地点'}}},jurisdictionForm:{jurisdiction:{type:'DESIGNATED'},reason:'旧理由'},serializeJurisdiction})
  handler('case/CaseDetailView.vue','openJurisdiction',ctx)()
  assert.equal(ctx.jurisdictionForm.jurisdiction.type,'LOCAL')
  assert.equal(ctx.jurisdictionForm.jurisdiction.violationPlace,'当前地点')
  assert.equal(ctx.jurisdictionForm.reason,'')
})
test('jurisdiction writes require role, reason and valid facts; transport failure preserves draft', async () => {
  const ctx=state({canUpdateJurisdiction:{value:false},savingJurisdiction:{value:false},jurisdictionForm:{jurisdiction:emptyJurisdiction(),reason:'留痕理由'},jurisdictionError,serializeJurisdiction,client:{put:async(...args)=>ctx.calls.push(args)}})
  const save=handler('case/CaseDetailView.vue','saveJurisdiction',ctx)
  await save()
  assert.equal(ctx.calls.length,0)
  ctx.canUpdateJurisdiction.value=true
  ctx.jurisdictionForm.reason=' '
  await save()
  assert.equal(ctx.calls.length,0)
  ctx.jurisdictionForm.reason='保留理由'
  ctx.client.put=async()=>{throw new Error('失败')}
  await assert.rejects(save(),/失败/)
  assert.equal(ctx.jurisdictionForm.reason,'保留理由')
  assert.equal(ctx.savingJurisdiction.value,false)
})
test('clue filing navigation keeps clue and suspect fields for the common filing form', () => {
  const calls=[]
  handler('case/CluesView.vue','toFile',{canManageClue:()=>true,router:{push:v=>calls.push(v)}})({id:7,suspectName:'单位',suspectType:'PROVIDER'})
  assert.equal(calls[0].path,'/case/list')
  assert.equal(calls[0].query.clueId,7)
  assert.equal(calls[0].query.suspectName,'单位')
})

test('precase upload uses selected source and category and clears input for same-file retry', async () => {
  const file=new File(['材料'],'现场.json',{type:'application/json'}), input={files:[file],value:'picked'}
  const ctx=state({FormData,props:{visible:true},sourceKey:()=> 'clues/12',picker:{key:'clues/12',generation:1},current:(_run,key)=>key==='clues/12',voiding:{value:false},uploading:{value:false},writable:{value:true},category:{value:'DOC_SCAN'},LARGE_TRANSFER:{timeout:600000},load:async()=>{}})
  await handler('../components/PrecaseAttachments.vue','upload',ctx)({target:input})
  assert.equal(ctx.calls[0][0],'/bureau/clues/12/attachments')
  assert.equal(ctx.calls[0][1].get('category'),'DOC_SCAN')
  assert.equal(ctx.calls[0][1].get('file').name,'现场.json')
  assert.equal(input.value,'')
  assert.equal(ctx.uploading.value,false)
})
test('switching material dialogs during upload does not reload the next source', async () => {
  let release,reloaded=false,key='inspections/2'
  const input={files:[new File(['x'],'x.txt')],value:'picked'}
  const ctx=state({FormData,props:{visible:true},sourceKey:()=>key,picker:{key,generation:1},current:(_run,selectedKey)=>selectedKey===key,voiding:{value:false},uploading:{value:false},writable:{value:true},category:{value:'OTHER'},LARGE_TRANSFER:{timeout:600000},client:{post:()=>new Promise(resolve=>release=resolve)},load:async()=>{reloaded=true}})
  const pending=handler('../components/PrecaseAttachments.vue','upload',ctx)({target:input})
  key='clues/3';release({})
  await pending
  assert.equal(reloaded,false)
})

test('clue material dialog retains creator and handler identities separately', () => {
  const ctx={materialsSource:{value:{}},materialsVisible:{value:false}}
  handler('case/CluesView.vue','openMaterials',ctx)({id:5,status:'PENDING',createdBy:'creator',handler:'handler1、handler2'})
  assert.equal(ctx.materialsSource.value.owner,'creator')
  assert.equal(ctx.materialsSource.value.handler,'handler1、handler2')
})

test('period completion posts explicit dates to the correct endpoints, including expert opinions', async () => {
  for (const expert of [false,true]) {
    const ctx=state({canCompletePeriods:{value:true},endingPeriod:{value:false},periodEndForm:{kind:expert?'EXPERT':'EXCLUSION',rowId:6,startAt:'2026-10-01',endAt:'2026-10-10',opinion:' 专家意见 '},c:{value:{status:'TERMINATED',terminatedAt:'2026-10-12'}},todayLocal:()=> '2026-10-21',periodEndError})
    await handler('case/CaseDetailView.vue','savePeriodEnd',ctx)()
    assert.equal(ctx.calls[0][0],`/bureau/cases/9/${expert?'expert-reviews':'exclusions'}/6/end`)
    assert.equal(ctx.calls[0][1][expert?'endedAt':'endAt'],'2026-10-10')
    if (expert) assert.equal(ctx.calls[0][1].opinion,'专家意见')
  }
})
test('invalid historical end dates preserve form and do not mutate the case', async () => {
  const ctx=state({canCompletePeriods:{value:true},endingPeriod:{value:false},periodEndForm:{kind:'EXCLUSION',rowId:6,startAt:'2026-10-01',endAt:'2026-10-21',opinion:''},c:{value:{status:'TERMINATED',terminatedAt:'2026-10-12'}},todayLocal:()=> '2026-10-21',periodEndError,dlg:{periodEnd:true}})
  await handler('case/CaseDetailView.vue','savePeriodEnd',ctx)()
  assert.equal(ctx.calls.length,0)
  assert.equal(ctx.dlg.periodEnd,true)
  assert.equal(ctx.periodEndForm.endAt,'2026-10-21')
})
test('period completion role guard prevents writes even if the handler is invoked directly', async () => {
  const ctx=state({canCompletePeriods:{value:false},endingPeriod:{value:false}})
  await handler('case/CaseDetailView.vue','savePeriodEnd',ctx)()
  assert.equal(ctx.calls.length,0)
})

test('expired or superseded material review cannot be submitted through its handler', async () => {
  const ctx=state({canReviewSelected:{value:false},reviewForm:{reviewId:5,opinion:'意见',opinionType:'AGREE'},dlg:{review:true}})
  await handler('case/CaseDetailView.vue','onDoReview',ctx)()
  assert.equal(ctx.calls.length,0)
  assert.equal(ctx.dlg.review,true)
  assert.equal(ctx.reviewForm.opinion,'意见')
})

test('read-only case users cannot write core materials or attachment bytes through direct handler calls', async () => {
  for (const name of ['onReport','onNotify','onStatement','onDeliver','onDocDeliverSubmit','onSignDoc','onUpload','onCrossExam','onSubmitReview','onSignMeeting','onScheduleHearing','onRenderTplConfirm']) {
    const ctx=state({canWriteCase:{value:false},canEditActive:{value:false}})
    await handler('case/CaseDetailView.vue',name,ctx)({})
    assert.equal(ctx.calls.length,0,name)
  }
})
test('attachment void handler rejects unauthorized uploader before requesting a reason or writing', async () => {
  const ctx=state({canVoidCaseAttachment:()=>false})
  await handler('case/CaseDetailView.vue','onVoidAttachment',ctx)({id:1})
  assert.equal(ctx.calls.length,0)
})

test('sealed case external publication remains available to authorized operators', async () => {
  const ctx=state({canWriteCase:{value:false},canOperateCase:{value:true}})
  await handler('case/CaseDetailView.vue','onPublish',ctx)()
  assert.equal(ctx.calls[0][0],'/bureau/cases/9/publish')
})
test('government filing trims a single permitted record number', async () => {
  const ctx=state({canGovRecord:{value:true},ElMessageBox:{prompt:async()=>({value:' 文号123 '})}})
  await handler('case/CaseDetailView.vue','onGovRecord',ctx)()
  assert.equal(ctx.calls[0][1].recordNo,'文号123')
})
test('existing government filing does not allow replacement or open an edit prompt', async () => {
  const ctx=state({canGovRecord:{value:false}})
  await handler('case/CaseDetailView.vue','onGovRecord',ctx)()
  assert.equal(ctx.calls.length,0)
})

test('read-only business roles cannot invoke source, inspection, transfer, reward or filing mutations', async () => {
  const pages={
    'case/CluesView.vue':['onCreate','onExclusion','onExtend','onReject','onImport','toFile'],
    'case/InspectionsView.vue':['onCreate','onComplete','submitResult','onToClue'],
    'case/TransfersView.vue':['onCreate','onConfirm','onReceipt'],
    'case/RewardsView.vue':['onCreate','onApprove','onPay'],
    'case/CasesView.vue':['openCreate','onCreate','openTransfer','onTransfer'],
    'case/DelegatesView.vue':['onCreate'],
    'case/ApprovalsView.vue':['onDecide'],
    'system/TemplatesView.vue':['openEdit','onSave'],
    'system/EnforcersView.vue':['openCreate','onSave'],
  }
  for (const [page,names] of Object.entries(pages)) for (const name of names) {
    const ctx=state({canOperate:{value:false},isLeader:{value:false},isAdmin:{value:false},canManageClue:()=>false,showMine:{value:false}})
    await handler(page,name,ctx)({id:1})
    assert.equal(ctx.calls.length,0,`${page}:${name}`)
  }
})
test('clue action scope accepts creator or exact assigned usernames, not another shared-list reader', () => {
  const row={id:1,status:'PENDING',createdBy:'creator',handler:'first、second'}
  for (const [username,allowed] of [['creator',true],['second',true],['sec',false],['other',false]]) {
    const ctx={auth:{user:{roles:['HANDLER'],username}},canWritePrecase}
    assert.equal(handler('case/CluesView.vue','canManageClue',ctx)(row),allowed)
  }
})
test('ordinary handlers cannot invoke leader-only case workflow changes', async () => {
  for (const name of ['onSuspend','onResume','onTerminate','onExtendCase','onApproveDefer','saveCourtResult','onCourtEnforce','onTransferOwner']) {
    const ctx=state({isLeader:{value:false},canWriteCase:{value:true}})
    await handler('case/CaseDetailView.vue',name,ctx)()
    assert.equal(ctx.calls.length,0,name)
  }
})

test('filing navigation requires the real pending clue ownership predicate, not just a HANDLER role', () => {
  const row={id:7,status:'PENDING',createdBy:'creator',handler:'assignee1、assignee2',suspectName:'单位'}
  for (const [username,allowed] of [['creator',true],['assignee2',true],['unrelated',false],['assignee',false]]) {
    const pushes=[]
    const scope=handler('case/CluesView.vue','canManageClue',{auth:{user:{roles:['HANDLER'],username}},canWritePrecase})
    const ctx={canManageClue:scope,router:{push:v=>pushes.push(v)}}
    handler('case/CluesView.vue','toFile',ctx)(row)
    assert.equal(pushes.length,allowed?1:0,username)
  }
})
test('actual pending clue operation cell hides filing for a foreign handler and shows it to creator or assignee', async () => {
  const source=readFileSync(new URL('../src/views/case/CluesView.vue',import.meta.url),'utf8')
  const template=parse(source).descriptor.template.content
  const start=template.indexOf('<el-table-column label="操作"')
  assert.ok(start>=0)
  const cell=template.slice(start,template.indexOf('</el-table-column>',start)+'</el-table-column>'.length)
  const render=new Function('Vue',compile(cell,{mode:'function'}).code)(Vue)
  const row={id:7,status:'PENDING',createdBy:'creator',handler:'assignee',extended:false}
  for (const [username,canFile] of [['creator',true],['assignee',true],['foreign',false]]) {
    const canManageClue=handler('case/CluesView.vue','canManageClue',{auth:{user:{roles:['HANDLER'],username}},canWritePrecase})
    const app=Vue.createSSRApp({render,setup:()=>({canOperate:true,canManageClue,toFile(){},openMaterials(){},onExtend(){},onExclusion(){},onReject(){}})})
    app.component('el-table-column',{setup:(_, {slots})=>()=>Vue.h('div',slots.default?.({row}))})
    app.component('el-button',{setup:(_, {slots})=>()=>Vue.h('button',slots.default?.())})
    app.config.warnHandler=()=>{}
    const html=await renderToString(app)
    assert.equal(html.includes('>立案</button>'),canFile,username)
    assert.ok(html.includes('>材料</button>')) // Shared-list material viewing remains available.
  }
})

test('history-page creation handlers return to page one, while edits refresh the current page', async () => {
  for (const [page,form] of [['InspectionsView',{objectName:'检查对象',officers:'甲、乙'}],['RewardsView',{clueId:1,reporterName:'举报人'}],['TransfersView',{clueId:1}]]) {
    const refreshes=[]
    const ctx=state({form,paging:{firstPage:async()=>refreshes.push('first')},dlg:{value:true},load:()=>refreshes.push('current')})
    await handler(`case/${page}.vue`,'onCreate',ctx)()
    assert.deepEqual(refreshes,['first'],page)
  }
  for (const [page,name] of [['RewardsView','onPay'],['TransfersView','onConfirm']]) {
    const refreshes=[]
    const ctx=state({paging:{firstPage:async()=>refreshes.push('first')},load:()=>refreshes.push('current'),ElMessageBox:{prompt:async()=>({value:'结果'})}})
    await handler(`case/${page}.vue`,name,ctx)({id:1})
    assert.deepEqual(refreshes,['current'],page)
  }
})

test('inspection completion uses the explicit finding and refreshes the current page', async () => {
  for (const violationFound of [false, true]) {
    const refreshes = []
    const ctx = state({
      auth: { token: 'token', user: { id: 1 } }, resultVisible: { value: true }, resultSaving: { value: false },
      resultDisposed: false, resultForm: { id: 27, result: '违法行为未发现', violationFound },
      sessionExpiry: { capture: () => 'stamp', isExpired: () => false, isCurrent: () => true },
      load: () => refreshes.push('current'),
    })
    await handler('case/InspectionsView.vue', 'submitResult', ctx)()
    assert.equal(ctx.calls[0][0], '/bureau/inspections/27/complete')
    assert.equal(ctx.calls[0][1].violationFound, violationFound)
    assert.equal(ctx.calls[0][1].result, '违法行为未发现')
    assert.deepEqual(refreshes, ['current'])
  }
})

test('non-admin direct navigation or handler invocation does not read or modify protected system data', async () => {
 const pages={
  'UsersView':['load','openCreate','openEdit','save','onUnlock','toggleEnabled'],
  'RolesView':['load','loadMenus','openGrant','save'],
  'DeptsView':['openCreate','openEdit','save','toggleEnabled'],
  'SettingsView':['load','onSave','onAuditExport','onHistory'],
  'HolidaysView':['onAdd','onBatch','onDelete'],
  'FeedbackAdminView':['load','openHandle','onHandle','viewContext','onExport'],
 }
 for(const [page,names] of Object.entries(pages))for(const name of names){
  const ctx=state({isAdmin:{value:false}})
  await handler(`system/${page}.vue`,name,ctx)({id:1})
  assert.equal(ctx.calls.length,0,`${page}:${name}`)
 }
})
test('closed feedback cannot be reopened by its admin handler and unresolved feedback cannot be confirmed closed', async () => {
 const ctx=state({isAdmin:{value:true},disposed:false,handleVisible:{value:true},handling:{value:{id:1,status:'CLOSED'}},cur:{value:{id:2,status:'NEW'}},saving:{value:false}})
 await handler('system/FeedbackAdminView.vue','onHandle',ctx)()
 await handler('case/MyFeedbackView.vue','onClose',ctx)({id:1,status:'NEW'})
 assert.equal(ctx.calls.length,0)
})
test('statistic export handlers produce no downloads before load or after failure', () => {
 for(const ready of [false]){
  const downloads=[],ctx={reportResource:{ready,failed:true},publishedResource:{ready,failed:true},download:(...args)=>downloads.push(args)}
  handler('case/StatsView.vue','onReportExport',ctx)()
  handler('case/StatsView.vue','onExport',ctx)()
  assert.equal(downloads.length,0)
 }
})
test('a successfully loaded empty annual report can be exported with its visible data scope', async () => {
 const downloads=[],ctx={reportResource:{ready:true},report:{value:{scopeLabel:'当前可见业务统计（线索为共享台账）',monthly:[],totals:{filed_total:0,closed_total:0,clue_total:0,hearing_total:0,transfer_total:0,inspection_total:0,reward_paid:0}}},reportYear:2026,Blob,download:blob=>downloads.push(blob)}
 ctx.q=handler('case/StatsView.vue','q',{})
 handler('case/StatsView.vue','onReportExport',ctx)()
 assert.equal(downloads.length,1)
 const csv=await downloads[0].text()
 assert.ok(csv.includes('数据范围,当前可见业务统计（线索为共享台账）'))
 assert.ok(csv.includes('合计,0,0'))
 assert.equal(csv.includes('undefined'),false)
})
test('accounts without a business role do not poll or invoke bureau shell features', async () => {
 const ctx=state({canUseBureau:{value:false},auth:{user:{mustChangePassword:false}}})
 for(const name of ['refreshUnread','openMessages','onOpenMessage','onReadAll','onSearch','openFeedback','onSubmitFeedback']) await handler('MainLayout.vue',name,ctx)({id:1})
 assert.equal(ctx.calls.length,0)
})

test('mark-all-read reloads the server-authorized message list instead of retaining an old scoped list', async () => {
 const events=[],ctx=state({messageContext:()=>()=>true,messageActionSeq:0,reloadMessages:async()=>events.push('list'),refreshUnread:async()=>events.push('count'),messages:{value:[{id:1,read_at:null}]}})
 await handler('MainLayout.vue','onReadAll',ctx)()
 assert.equal(ctx.calls[0][0],'/bureau/messages/read-all')
 assert.deepEqual(events,['list','count'])
 assert.equal(ctx.messages.value[0].read_at,null) // No optimistic rewrite of records no longer returned by server.
})


// Execute the real application draft state and handler together so closing,
// reopening and unmount hooks exercise their actual generation invalidation.
function applicationFixture(extra={}) {
  const source=readFileSync(new URL('../src/views/case/CaseDetailView.vue',import.meta.url),'utf8')
  const start=source.indexOf('const applyForm =')
  const block=source.slice(start,source.indexOf('const docDeliverForm =',start))
  const fnStart=source.indexOf('async function onApply()')
  const fn=source.slice(fnStart,source.indexOf('\n}',fnStart)+2)
  const events=[],unmount=[],stops=[]
  const ctx=state({dlg:Vue.reactive({apply:true}),id:Vue.ref('9'),
    reactive:Vue.reactive,ref:Vue.ref,onUnmounted:cb=>unmount.push(cb),
    watch:(...args)=>{const stop=Vue.watch(...args);stops.push(stop);return stop},
    load:async()=>events.push('load'),ElMessage:{success:()=>events.push('success'),warning:()=>events.push('warning')},...extra})
  const output=vm.runInNewContext(stripTypeScriptTypes(block+'\n'+fn)+';({onApply,applyForm,applying,invalidateApplyContext})',ctx)
  output.applyForm.reason='保留申请理由'
  return {...output,ctx,events,unmount:()=>{unmount.forEach(fn=>fn());stops.forEach(stop=>stop())}}
}
test('approval application snapshots its case and payload and blocks repeated posts',async()=>{
  let finish;const calls=[]
  const f=applicationFixture({client:{post:(url,body)=>{calls.push({url,body});return new Promise(resolve=>finish=resolve)}}})
  const pending=f.onApply();f.applyForm.kind='TERMINATE';f.applyForm.days=90;f.applyForm.reason='之后修改'
  await f.onApply();assert.equal(calls.length,1)
  assert.equal(calls[0].body.kind,'EXTEND');assert.equal(calls[0].body.payload.days,30)
  assert.equal(calls[0].body.reason,'保留申请理由');assert.equal(calls[0].body.caseId,9)
  finish();await pending;assert.equal(f.applying.value,false);f.unmount()
})
test('failed approval application preserves its draft and releases the operation lock',async()=>{
  const f=applicationFixture({client:{post:async()=>{throw new Error('denied')}}})
  await assert.doesNotReject(f.onApply())
  assert.equal(f.ctx.dlg.apply,true);assert.equal(f.applyForm.reason,'保留申请理由')
  assert.equal(f.applying.value,false);assert.equal(f.events.length,0);f.unmount()
})
test('application completion cannot close a draft reopened on the same case',async()=>{
  let finish;const f=applicationFixture({client:{post:()=>new Promise(resolve=>finish=resolve)}})
  const pending=f.onApply();f.ctx.dlg.apply=false;f.ctx.dlg.apply=true;f.applyForm.reason='重新打开的新草稿'
  finish();await pending
  assert.equal(f.ctx.dlg.apply,true);assert.equal(f.applyForm.reason,'重新打开的新草稿');assert.equal(f.events.length,0);f.unmount()
})
test('application completion cannot mutate a different case or a returned case context',async()=>{
  for(const returnToOriginal of [false,true]) {
    let finish;const f=applicationFixture({client:{post:()=>new Promise(resolve=>finish=resolve)}})
    const pending=f.onApply();f.ctx.id.value='10';f.invalidateApplyContext()
    if(returnToOriginal){f.ctx.id.value='9';f.invalidateApplyContext()}
    finish();await pending;assert.equal(f.ctx.dlg.apply,true);assert.equal(f.events.length,0);f.unmount()
  }
})
test('unmounted application completion cannot reload or announce success',async()=>{
  let finish;const f=applicationFixture({client:{post:()=>new Promise(resolve=>finish=resolve)}})
  const pending=f.onApply();f.unmount();finish();await pending
  assert.equal(f.events.length,0);assert.equal(f.applying.value,false)
})
test('approval application remains locked until its successful refresh completes',async()=>{
  let finish;const f=applicationFixture({load:()=>new Promise(resolve=>finish=resolve)})
  const pending=f.onApply();await new Promise(setImmediate);assert.equal(f.applying.value,true)
  finish();await pending;assert.equal(f.applying.value,false);assert.equal(f.ctx.dlg.apply,false);f.unmount()
})
test('closed application dialogs and lost write permission cannot post',async()=>{
  for(const forbidden of [false,true]) {
    const f=applicationFixture(forbidden?{canWriteCase:{value:false}}:{})
    if(!forbidden)f.ctx.dlg.apply=false
    await f.onApply();assert.equal(f.ctx.calls.length,0);f.unmount()
  }
})
