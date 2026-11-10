import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { webcrypto } from 'node:crypto'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
import { parseTaskFocus } from '../src/api/taskFocus.ts'

const source = readFileSync(new URL('../src/components/CaseContinuationPanel.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
const tick = () => new Promise(setImmediate)
const initial = marker => ({ eligible: true, canRequest: true, canRegisterRemedy: true, marker,
  originalCaseStatus: 'CLOSED', effectiveDecision: {}, balances: {}, executions: [], courtRounds: [], remedies: [], requests: [] })
function fixture(t) {
  const props = Vue.reactive({ caseId: 101 })
  const route = Vue.reactive({ query: {} })
  const auth = Vue.reactive({ token: 'token-one', user: { id: 1, roles: ['HANDLER'], accountCategory: 'DISTRICT' } })
  const reads = [], writes = [], warnings = [], success = [], teardown = [], listeners = new Map()
  const sessionExpiry = createSessionExpiry()
  const client = { get: (url, config) => url.endsWith('/supplement-bundles') ? Promise.resolve({data:{data:{bundles:[]}}}) : new Promise((resolve, reject) => reads.push({ url, config,
    resolve: value => resolve({ data: { data: value } }), reject })),
  post: (url, body) => new Promise((resolve, reject) => writes.push({ url, body: JSON.parse(JSON.stringify(body)), resolve, reject })) }
  const scope = Vue.effectScope()
  const result = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script) + ';({state,loading,saving,failed,cutoff,formVisible,reviewVisible,draft,reason,sourceReference,opinion,requestKey,withFinding,withInstitution,materialBundleId,formFields,open,submit,openReview,submitReview,load,focusedExecution,focusUnavailable,courtRowClass})', {
    ...Vue, defineProps: () => props, useAuthStore: () => auth, useRoute: () => route, parseTaskFocus, client, sessionExpiry, SESSION_EXPIRED_EVENT,
    crypto: webcrypto, ElMessage: { warning: message => warnings.push(message), success: message => success.push(message) },
    onMounted: fn => fn(), onBeforeUnmount: fn => teardown.push(fn),
    window: { addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name) },
  }))
  t.after(() => { teardown.forEach(fn => fn()); scope.stop() })
  return { ...result, props, route, auth, reads, writes, warnings, success, expire: () => {
    sessionExpiry.expire(sessionExpiry.capture(auth.token), auth.token); listeners.get(SESSION_EXPIRED_EVENT)()
  } }
}
async function ready(f, marker = 'current') { f.reads.at(-1).resolve(initial(marker)); await tick() }
function payment(f) {
  f.open('EXECUTION'); Object.assign(f.draft, { kind: 'FINE', amount: '40.25', paidAt: '2026-11-01', receiptNo: 'receipt-001', method: 'BANK' })
  f.reason.value = '核实原始凭证'; f.sourceReference.value = '凭证第1页'
}

test('continuation suppresses duplicate submit and preserves request key for an uncertain-response retry', async t => {
  const f = fixture(t); await ready(f); payment(f)
  const one = f.submit(), two = f.submit(); assert.equal(f.writes.length, 1)
  assert.equal(f.writes[0].body.payload.amount, 40.25)
  assert.equal(f.writes[0].url, '/bureau/cases/101/continuations/requests')
  const key = f.writes[0].body.requestKey
  f.writes[0].reject(new Error('uncertain response')); await Promise.all([one, two])
  assert.equal(f.formVisible.value, true); assert.equal(f.draft.amount, '40.25')
  const retry = f.submit(); assert.equal(f.writes[1].body.requestKey, key)
  f.writes[1].reject(new Error('still disconnected')); await retry
})

test('execution task highlights only the exact active decision and court round without opening a form', async t => {
  const f=fixture(t);await ready(f)
  f.state.value.executionDeadline={decisionVersionId:12,courtRoundId:9}
  f.state.value.courtRounds=[{id:8},{id:9}]
  f.route.query={executionVersion:'12',courtRound:'9'}
  assert.equal(f.focusUnavailable.value,false)
  assert.equal(f.focusedExecution.value,true)
  assert.equal(f.courtRowClass({row:{id:9}}),'task-highlight')
  assert.equal(f.courtRowClass({row:{id:8}}),'')
  f.route.query={executionVersion:'11',courtRound:'9'}
  assert.equal(f.focusUnavailable.value,true)
  assert.equal(f.courtRowClass({row:{id:9}}),'')
  f.route.query={executionVersion:'12',courtRound:'404'}
  assert.equal(f.focusUnavailable.value,true)
  f.state.value.executionDeadline={decisionVersionId:null}
  f.route.query={executionVersion:'0'}
  assert.equal(f.focusUnavailable.value,false)
  f.route.query={executionVersion:['0']}
  assert.equal(f.focusUnavailable.value,true)
  assert.equal(f.formVisible.value,false)
  assert.equal(f.writes.length,0)
})

test('actual remedy intake can be recorded while an ordinary continuation is pending', async t => {
  const f=fixture(t);await ready(f)
  f.state.value.canRequest=false
  f.open('EXECUTION')
  assert.equal(f.formVisible.value,false)
  f.open('REGISTER_REMEDY')
  Object.assign(f.draft,{remedyType:'LITIGATION',acceptedAt:'2026-11-01',reference:'法院收件文号'})
  f.reason.value='实际收到';f.sourceReference.value='受理通知书'
  const action=f.submit()
  assert.equal(f.writes[0].url,'/bureau/cases/101/continuations/remedies')
  f.writes[0].reject(new Error('uncertain response'));await action
})

test('continuation ignores stale case reads and clears sensitive drafts on navigation', async t => {
  const f = fixture(t); await ready(f); payment(f)
  const old = f.load(); const oldRead = f.reads.at(-1)
  f.props.caseId = 202; assert.equal(f.formVisible.value, false); assert.equal(f.reason.value, '')
  await ready(f, 'new-case'); oldRead.resolve(initial('old-case')); await old
  assert.equal(f.state.value.marker, 'new-case')
  assert.equal(f.reads.at(-1).url, '/bureau/cases/202/continuations')
})

test('continuation requires authoritative review action and rejects blank opinions', async t => {
  const f = fixture(t); await ready(f)
  const row = { id: 8, canReview: false, canCancel: false, payload: {} }
  f.openReview(row, 'approve'); assert.equal(f.reviewVisible.value, false)
  row.canReview = true; f.openReview(row, 'approve'); await f.submitReview()
  assert.equal(f.writes.length, 0); assert.ok(f.warnings.length)
  f.opinion.value = '另行核对材料'; const action = f.submitReview()
  assert.deepEqual(f.writes[0].body, { approve: true, opinion: '另行核对材料' })
  assert.equal(f.writes[0].url, '/bureau/cases/101/continuations/requests/8/review')
  f.writes[0].reject(new Error('failure')); await action
  assert.equal(f.opinion.value, '另行核对材料')
})

test('continuation clears state on session expiry and ignores a late successful read', async t => {
  const f = fixture(t); const old = f.reads[0]
  f.expire(); old.resolve(initial('expired')); await tick()
  assert.equal(f.state.value, undefined); assert.equal(f.formVisible.value, false)
})

test('continuation does not accept overprecise financial input or submit unrequested fields', async t => {
  const f = fixture(t); await ready(f); payment(f)
  f.draft.amount = '40.251'; await f.submit(); assert.equal(f.writes.length, 0)
  f.draft.amount = '40'; f.draft.unrelated = 'do not send'
  const request = f.submit(); assert.equal('unrelated' in f.writes[0].body.payload, false)
  f.writes[0].reject(new Error('failure')); await request
})

test('continuation partial refund links its original source and submits only confirmed refund fields', async t => {
  const f = fixture(t); await ready(f)
  f.state.value.executions = [{ source: 'ORIGINAL', id: 15, kind: 'FINE', amount: 100 }]
  f.open('REFUND'); Object.assign(f.draft, { executionSource: 'ORIGINAL:15', amount: '20', paidAt: '2026-11-02', receiptNo: 'refund-1' })
  f.reason.value = '按实际退付凭证登记'; f.sourceReference.value = '退款单第1页'
  const request = f.submit(); assert.equal(f.writes[0].body.type, 'REFUND')
  assert.deepEqual(f.writes[0].body.payload, { source: 'ORIGINAL', sourceId: 15, amount: 20, paidAt: '2026-11-02', receiptNo: 'refund-1' })
  f.writes[0].reject(new Error('failure')); await request
})

test('continuation late-fee submission requires a manual assessed total and assessment reference', async t => {
  const f = fixture(t); await ready(f); payment(f); f.draft.kind = 'LATE_FEE'
  await f.submit(); assert.equal(f.writes.length, 0)
  f.draft.assessedLateFeeAmount = '60'; f.draft.assessmentReference = '核定文号及按日核算材料'
  const request = f.submit(); assert.equal(f.writes[0].body.payload.assessedLateFeeAmount, 60)
  assert.equal(f.writes[0].body.payload.assessmentReference, '核定文号及按日核算材料')
  f.writes[0].reject(new Error('failure')); await request
})

test('continuation late mutation success does not clear or refresh a newly navigated case', async t => {
  const f = fixture(t); await ready(f); payment(f); const request = f.submit()
  f.props.caseId = 202; await ready(f, 'new-case')
  const count = f.reads.length; f.writes[0].resolve({ data: { data: { id: 1 } } }); await request
  assert.equal(f.state.value.marker, 'new-case'); assert.equal(f.reads.length, count); assert.equal(f.success.length, 0)
})

test('independent fact correction submits complete selected facts and an explicit approved material bundle', async t => {
  const f=fixture(t);await ready(f)
  f.state.value.effectiveFacts={confirmed_violation_start:'2026-10-01',confirmed_violation_end:'2026-10-02',confirmed_violation_facts:'原认定事实',confirmed_violation_amount:10000}
  f.open('FACT_CORRECTION')
  assert.equal(f.draft.confirmedViolationFacts,'原认定事实')
  Object.assign(f.draft,{effectiveAt:'2026-11-01',confirmedViolationFacts:'按结果核定新事实',confirmedViolationAmount:'6000'})
  f.reason.value='核对原始结果材料';f.sourceReference.value='复议结果第2页';f.materialBundleId.value=7
  const request=f.submit();assert.equal(f.writes.length,1)
  assert.equal(f.writes[0].body.type,'FACT_CORRECTION');assert.equal(f.writes[0].body.materialBundleId,7)
  assert.equal(f.writes[0].body.payload.confirmedViolationAmount,6000)
  assert.equal('institutionCode' in f.writes[0].body.payload,false)
  f.writes[0].reject(new Error('uncertain response'));await request
  assert.equal(f.formVisible.value,true)
})

test('fact correction cannot submit an empty selection and remedy maintenance does not leak hidden fact fields', async t=>{
  const f=fixture(t);await ready(f);f.open('FACT_CORRECTION');f.withFinding.value=false;f.withInstitution.value=false
  f.draft.effectiveAt='2026-11-01';f.reason.value='核对';f.sourceReference.value='材料'
  await f.submit();assert.equal(f.writes.length,0)
  f.state.value.remedies=[{id:2,status:'OPEN',remedy_type:'RECONSIDERATION',reference:'受理'}]
  f.open('REMEDY_RESULT');f.withFinding.value=true
  Object.assign(f.draft,{remedyId:'2',outcome:'MAINTAIN',effectiveAt:'2026-11-01',resultReference:'结果文号',resultContent:'维持原决定',confirmedViolationAmount:'900'})
  f.reason.value='核实维持结果';f.sourceReference.value='结果材料'
  const request=f.submit();assert.equal(f.writes.length,1);assert.equal('confirmedViolationAmount' in f.writes[0].body.payload,false)
  f.writes[0].reject(new Error('failure'));await request
})
