import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
import { actualInterviewError, actualInterviewPayload, blankActualInterview } from '../src/api/actualInterview.ts'

const source = readFileSync(new URL('../src/views/agency/AgencyAgreementsView.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
const tick = () => new Promise(setImmediate)
const plain = value => JSON.parse(JSON.stringify(value))
const today = '2026-11-07'
const actual = () => ({ institutionCode: '', interviewType: '个别约谈', reason: '已核实事项', content: '已实际开展约谈',
  participants: '甲、乙', interviewDate: '2026-11-05' })
const row = (patch = {}) => ({ id: 7, caseNo: 'CASE-7', institutionName: '测试机构', action: 'INTERVIEW',
  sentAt: '2026-11-01', cycle: 3, status: 'ACCEPTED', resultType: null, repliedAt: null, interviewId: null, ...patch })

function fixture(t, query = {}) {
  const reads = [], writes = [], successes = [], warnings = [], teardown = [], listeners = new Map()
  const auth = Vue.reactive({ token: 'one', user: { id: 1, accountCategory: 'AGENCY', roles: ['AGENCY'] } })
  const route = Vue.reactive({ query })
  const sessionExpiry = createSessionExpiry()
  const client = {
    get: url => new Promise((resolve, reject) => reads.push({ url, resolve: rows => resolve({ data: { data: rows } }), reject })),
    post: (url, body) => new Promise((resolve, reject) => writes.push({ url, body: plain(body), resolve, reject })),
  }
  const scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script)
    + ';({load,open,submit,perform,rows,loading,saving,draft,actualInterview,dialogVisible,pendingOnly,canRegisterInterview,rowClassName})', {
      ...Vue, client, useAuthStore: () => auth, useRoute: () => route, localDate: () => today,
      sessionExpiry, SESSION_EXPIRED_EVENT, actualInterviewError, actualInterviewPayload, blankActualInterview,
      onMounted: fn => fn(), onBeforeUnmount: fn => teardown.push(fn),
      window: { addEventListener: (event, fn) => listeners.set(event, fn), removeEventListener: event => listeners.delete(event) },
      ElMessage: { success: message => successes.push(message), warning: message => warnings.push(message) },
    }))
  let stopped = false
  const f = { ...view, auth, route, reads, writes, successes, warnings,
    expire: () => { sessionExpiry.expire(sessionExpiry.capture(auth.token), auth.token); listeners.get(SESSION_EXPIRED_EVENT)() },
    unmount: () => { if (!stopped) { stopped = true; teardown.forEach(fn => fn()); scope.stop() } },
  }
  t.after(f.unmount)
  return f
}
async function ready(f, rows) { f.reads.at(-1).resolve(rows); await tick() }

test('completed interview reply requires actual facts and sends one source-cycle-bound request', async t => {
  const f = fixture(t), sourceRow = row()
  await ready(f, [sourceRow]); f.open(sourceRow, 'reply'); f.draft.result = '已完成约谈'
  await f.submit()
  assert.equal(f.writes.length, 0)
  f.actualInterview.value = actual()
  const first = f.submit(), duplicate = f.submit()
  assert.equal(f.writes.length, 1)
  assert.equal(f.writes[0].url, '/agency/agreement-actions/7/reply')
  assert.equal(f.writes[0].body.cycle, 3)
  assert.deepEqual(f.writes[0].body.interview, { interviewType: '个别约谈', reason: '已核实事项',
    content: '已实际开展约谈', participants: '甲、乙', interviewDate: '2026-11-05' })
  f.writes[0].reject(new Error('offline'))
  await Promise.all([first, duplicate])
  assert.equal(f.dialogVisible.value, true)
  assert.equal(f.actualInterview.value.content, '已实际开展约谈')
})

test('legacy completed interviews use the dedicated registration and linked rows lose that action', async t => {
  const f = fixture(t), sourceRow = row({ status: 'COMPLETED', repliedAt: '2026-11-06' })
  await ready(f, [sourceRow])
  assert.equal(f.canRegisterInterview(sourceRow), true)
  assert.equal(f.canRegisterInterview({ ...sourceRow, resultType: 'NOT_APPLICABLE' }), false)
  assert.equal(f.canRegisterInterview({ ...sourceRow, interviewId: 80 }), false)
  f.open(sourceRow, 'interview'); f.actualInterview.value = actual()
  const job = f.submit()
  assert.equal(f.writes[0].url, '/agency/agreement-actions/7/interview')
  assert.equal(f.writes[0].body.cycle, 3)
  f.writes[0].resolve({}); await tick()
  await ready(f, [{ ...sourceRow, interviewId: 80 }]); await job
  assert.equal(f.dialogVisible.value, false)
  assert.equal(f.canRegisterInterview(f.rows.value[0]), false)
})

test('interview registration rejects stale cycles and invalid actual dates before posting', async t => {
  const f = fixture(t), sourceRow = row({ status: 'COMPLETED', repliedAt: '2026-11-06' })
  await ready(f, [sourceRow]); f.open(sourceRow, 'interview')
  for (const date of ['2026-10-31', '2026-11-08', '2026-02-30']) {
    f.actualInterview.value = { ...actual(), interviewDate: date }
    await f.submit()
  }
  assert.equal(f.writes.length, 0)
  f.actualInterview.value = actual(); f.rows.value = [{ ...sourceRow, cycle: 4 }]
  await f.submit()
  assert.equal(f.writes.length, 0)
})

test('focus links identify an exact cycle and never register or reply automatically', async t => {
  const f = fixture(t, { focus: '7', cycle: '3' }), sourceRow = row({ status: 'COMPLETED', repliedAt: '2026-11-06' })
  await ready(f, [sourceRow])
  assert.equal(f.pendingOnly.value, false)
  assert.equal(f.rowClassName({ row: sourceRow }), 'focused-agreement')
  assert.equal(f.dialogVisible.value, false)
  assert.equal(f.writes.length, 0)
  f.route.query.cycle = '4'
  assert.equal(f.rowClassName({ row: sourceRow }), '')
  assert.equal(f.writes.length, 0)
})

test('interview late success cannot close a new focus context or leak an expired inbox', async t => {
  const f = fixture(t), sourceRow = row({ status: 'COMPLETED', repliedAt: '2026-11-06' })
  await ready(f, [sourceRow]); f.open(sourceRow, 'interview'); f.actualInterview.value = actual()
  const job = f.submit()
  f.route.query = { focus: '8', cycle: '1' }
  f.writes[0].resolve({})
  await job
  assert.equal(f.successes.length, 0)
  assert.equal(f.dialogVisible.value, false)
  const read = f.load(); f.expire(); f.reads.at(-1).resolve([sourceRow]); await read
  assert.equal(f.rows.value.length, 0)
  assert.equal(f.loading.value, false)
})
