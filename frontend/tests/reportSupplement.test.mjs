import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'

const source = readFileSync(new URL('../src/views/case/HistoricalReportSupplement.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
const plain = value => JSON.parse(JSON.stringify(value))
const state = (marker = 'old') => ({ eligible: true, canRequest: true, canReview: false,
  missingFields: ['institutionCode', 'confirmedViolationAmount'], original: {}, effective: {},
  history: [{ id: 1, values: {}, marker, status: 'PENDING', canReview: false, canCancel: true }] })

function fixture(t, ...caseIds) {
  const caseId = caseIds.length ? caseIds[0] : 101
  const props = Vue.reactive({ caseId, correction: false })
  const auth = Vue.reactive({ token: 'one', user: { id: 1, accountCategory: 'BUREAU', roles: ['LEGAL'] } })
  const reads = [], writes = [], successes = [], warnings = [], teardown = [], listeners = new Map()
  const sessionExpiry = createSessionExpiry()
  const client = {
    get: (url, config) => new Promise((resolve, reject) => reads.push({
      url, config, resolve: data => resolve({ data: { data } }), reject,
    })),
    post: (url, body) => new Promise((resolve, reject) => writes.push({ url, body: plain(body), resolve, reject })),
  }
  const scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script)
    + ';({load,state,rows,requestVisible,decisionVisible,loading,saving,openRequest,submitRequest,selectedFields,draft,reason,sourceReference,openDecision,submitDecision,opinion})', {
      ...Vue, client, defineProps: () => props, useAuthStore: () => auth, sessionExpiry, SESSION_EXPIRED_EVENT,
      onMounted: fn => fn(), onBeforeUnmount: fn => teardown.push(fn),
      window: { addEventListener: (event, fn) => listeners.set(event, fn), removeEventListener: event => listeners.delete(event) },
      ElMessage: { success: text => successes.push(text), warning: text => warnings.push(text) },
    }))
  let stopped = false
  const f = { ...view, props, auth, reads, writes, successes, warnings, sessionExpiry,
    expire: () => { sessionExpiry.expire(sessionExpiry.capture(auth.token), auth.token); listeners.get(SESSION_EXPIRED_EVENT)() },
    unmount: () => { if (!stopped) { stopped = true; teardown.forEach(fn => fn()); scope.stop() } },
  }
  t.after(f.unmount)
  return f
}
const tick = () => new Promise(setImmediate)
async function ready(f, data = state()) {
  f.reads.at(-1).resolve(data)
  await tick()
}

test('report supplement posts only selected missing fields and retains numeric zero', async t => {
  const f = fixture(t); await ready(f)
  f.openRequest()
  f.selectedFields.value = ['confirmedViolationAmount']
  f.draft.confirmedViolationAmount = '0'
  f.draft.institutionCode = 'unused'
  f.reason.value = '依据卷内记录'
  f.sourceReference.value = '第5页'
  const first = f.submitRequest(), second = f.submitRequest()
  assert.equal(f.writes.length, 1)
  assert.deepEqual(f.writes[0].body, { reason: '依据卷内记录', sourceReference: '第5页', values: { confirmedViolationAmount: 0 } })
  f.writes[0].reject(new Error('connection failed'))
  await Promise.all([first, second])
  assert.equal(f.requestVisible.value, true)
  assert.equal(f.draft.confirmedViolationAmount, '0')
  assert.equal(f.saving.value, false)
})

test('report supplement discards an old case response after navigation', async t => {
  const f = fixture(t)
  f.props.caseId = 202
  assert.equal(f.reads.length, 2)
  await ready(f, state('new'))
  f.reads[0].resolve(state('old'))
  await tick()
  assert.equal(f.state.value.history[0].marker, 'new')
  assert.equal(f.reads[1].url, '/bureau/cases/202/report-supplements')
})

test('report supplement review respects server eligibility and ignores late success after role loss', async t => {
  const f = fixture(t, undefined)
  const own = { id: 1, caseId: 10, values: {}, canReview: false, canCancel: false }
  const other = { id: 2, caseId: 20, values: { institutionCode: '001' }, canReview: true, canCancel: false }
  await ready(f, { rows: [own, other], total: 2, page: 1, size: 20 })
  f.openDecision(own, 'approve')
  assert.equal(f.decisionVisible.value, false)
  f.openDecision(other, 'approve')
  f.opinion.value = '核验材料通过'
  const first = f.submitDecision(), second = f.submitDecision()
  assert.equal(f.writes.length, 1)
  assert.equal(f.writes[0].url, '/bureau/cases/20/report-supplements/2/review')
  assert.deepEqual(f.writes[0].body, { approve: true, opinion: '核验材料通过' })
  f.auth.user.roles = ['AGENCY']
  assert.equal(f.rows.value.length, 0)
  f.writes[0].resolve({})
  await Promise.all([first, second])
  assert.equal(f.successes.length, 0)
  assert.equal(f.decisionVisible.value, false)
})

test('report supplement expiry and unmount clear sensitive history and prevent late refill', async t => {
  for (const expired of [true, false]) {
    const f = fixture(t)
    expired ? f.expire() : f.unmount()
    f.reads[0].resolve(state())
    await tick()
    assert.equal(f.state.value, undefined)
    assert.equal(f.rows.value.length, 0)
  }
})

test('correction mode uses separate queue and submits only approved supplemental fields', async t => {
  const f = fixture(t)
  f.props.correction = true
  await ready(f, { ...state(), canRequest: false, canCorrect: true,
    correctableFields: ['institutionCode'], effective: { institutionCode: 'OLD', confirmedViolationAmount: 0 } })
  f.openRequest()
  f.selectedFields.value = ['institutionCode']
  f.draft.institutionCode = 'NEW'
  f.reason.value = '已批准补录值有误'
  f.sourceReference.value = '原始机构名册第8行'
  const pending = f.submitRequest()
  assert.equal(f.writes.length, 1)
  assert.equal(f.writes[0].url, '/bureau/cases/101/report-supplement-corrections')
  assert.deepEqual(f.writes[0].body.values, { institutionCode: 'NEW' })
  f.writes[0].reject(new Error('offline'))
  await pending
  assert.equal(f.draft.institutionCode, 'NEW')
})
