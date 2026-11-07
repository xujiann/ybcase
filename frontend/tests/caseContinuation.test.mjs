import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { webcrypto } from 'node:crypto'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'

const source = readFileSync(new URL('../src/components/CaseContinuationPanel.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
const tick = () => new Promise(setImmediate)
const initial = marker => ({ eligible: true, canRequest: true, canRegisterRemedy: true, marker,
  originalCaseStatus: 'CLOSED', effectiveDecision: {}, balances: {}, executions: [], courtRounds: [], remedies: [], requests: [] })
function fixture(t) {
  const props = Vue.reactive({ caseId: 101 })
  const auth = Vue.reactive({ token: 'token-one', user: { id: 1, roles: ['HANDLER'], accountCategory: 'DISTRICT' } })
  const reads = [], writes = [], warnings = [], success = [], teardown = [], listeners = new Map()
  const sessionExpiry = createSessionExpiry()
  const client = { get: (url, config) => new Promise((resolve, reject) => reads.push({ url, config,
    resolve: value => resolve({ data: { data: value } }), reject })),
  post: (url, body) => new Promise((resolve, reject) => writes.push({ url, body: JSON.parse(JSON.stringify(body)), resolve, reject })) }
  const scope = Vue.effectScope()
  const result = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script) + ';({state,loading,saving,failed,cutoff,formVisible,reviewVisible,draft,reason,sourceReference,opinion,requestKey,open,submit,openReview,submitReview,load})', {
    ...Vue, defineProps: () => props, useAuthStore: () => auth, client, sessionExpiry, SESSION_EXPIRED_EVENT,
    crypto: webcrypto, ElMessage: { warning: message => warnings.push(message), success: message => success.push(message) },
    onMounted: fn => fn(), onBeforeUnmount: fn => teardown.push(fn),
    window: { addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name) },
  }))
  t.after(() => { teardown.forEach(fn => fn()); scope.stop() })
  return { ...result, props, auth, reads, writes, warnings, success, expire: () => {
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
