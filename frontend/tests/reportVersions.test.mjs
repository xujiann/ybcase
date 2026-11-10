import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'

const source = readFileSync(new URL('../src/components/ReportingVersionsPanel.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
const plain = value => JSON.parse(JSON.stringify(value))
const tick = () => new Promise(setImmediate)
const batch = (id, extra = {}) => ({ id, report_type: 'PENALTIES', status: 'GENERATED', canConfirm: true,
  period_start: '2026-01-01', period_end: '2026-10-31', business_cutoff: '2026-10-31',
  file_sha256: 'unused', criteria: { year: 2026 }, columns: ['序号', '对象'], ...extra })

function fixture(t, props = {}) {
  props = Vue.reactive(props)
  const auth = Vue.reactive({ token: 'one', user: { id: 1, roles: ['LEGAL'], accountCategory: 'BUREAU' } })
  const reads = [], writes = [], messages = [], navigation = [], listeners = new Map(), teardown = []
  let uuid = 0
  const client = {
    get: (url, config) => new Promise((resolve, reject) => reads.push({ url, config,
      resolve: data => resolve({ data: { data } }), reject })),
    post: (url, body) => new Promise((resolve, reject) => writes.push({ url, body: plain(body),
      resolve: data => resolve({ data: { data } }), reject })),
  }
  const sessionExpiry = createSessionExpiry(), scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script) +
    ';({form,batches,selected,previewRows,previewVisible,opinion,saving,loadList,generate,openBatch,confirm,openSource,changes,focusFailed,transition,statusLabel,recordReceipt,receiptForm,reissue})', {
    ...Vue, client, useAuthStore: () => auth, defineProps: () => props,
    useRouter: () => ({ push: path => navigation.push(path) }), sessionExpiry, SESSION_EXPIRED_EVENT,
    crypto: { randomUUID: () => 'request-' + ++uuid }, localDate: () => '2026-11-07',
    onMounted: fn => fn(), onBeforeUnmount: fn => teardown.push(fn),
    window: { addEventListener: (event, fn) => listeners.set(event, fn), removeEventListener: event => listeners.delete(event) },
    ElMessage: { success: message => messages.push(message), warning: message => messages.push(message) },
  }))
  let stopped = false
  const f = { ...view, props, auth, reads, writes, messages, navigation,
    expire: () => { sessionExpiry.expire(sessionExpiry.capture(auth.token), auth.token); listeners.get(SESSION_EXPIRED_EVENT)() },
    unmount: () => { if (!stopped) { stopped = true; teardown.forEach(fn => fn()); scope.stop() } },
  }
  t.after(f.unmount)
  return f
}
async function listReady(f, rows = []) { f.reads.at(-1).resolve({ rows, total: rows.length }); await tick() }
async function openReady(f, row) {
  const pending = f.openBatch(row)
  f.reads.at(-1).resolve(row); await tick()
  f.reads.at(-1).resolve({ rows: [], total: 0 }); await pending
}

test('report version generation retries with same identity key and suppresses duplicate writes', async t => {
  const f = fixture(t); await listReady(f)
  const first = f.generate(), duplicate = f.generate()
  assert.equal(f.writes.length, 1)
  const key = f.writes[0].body.requestKey
  f.writes[0].reject(new Error('response lost')); await Promise.all([first, duplicate])
  const retry = f.generate()
  assert.equal(f.writes[1].body.requestKey, key)
  f.writes[1].reject(new Error('offline')); await retry
  f.form.end = '2026-10-31'
  const changed = f.generate()
  assert.notEqual(f.writes[2].body.requestKey, key)
  f.writes[2].reject(new Error('offline')); await changed
})

test('monthly correction retains exact calendar criteria and sends receipt only with server eligibility', async t => {
  const f = fixture(t)
  const old = batch(81, { report_type: 'MONTHLY_SUBMISSION', status: 'VOIDED', canReissue: true,
    period_start: '2024-02-01', period_end: '2024-02-29', business_cutoff: '2024-03-04' })
  await listReady(f, [old]); f.reissue(f.batches.value[0])
  const generate = f.generate()
  assert.deepEqual(f.writes[0].body, { type: 'MONTHLY_SUBMISSION', year: 2024, start: '2024-02-01',
    end: '2024-02-29', cutoff: '2024-03-04', parentBatchId: 81, requestKey: 'request-1' })
  f.writes[0].reject(new Error('offline')); await generate
  await openReady(f, old); await f.recordReceipt(); assert.equal(f.writes.length, 1)
  await openReady(f, batch(82, { report_type: 'MONTHLY_SUBMISSION', status: 'CONFIRMED', canRecordReceipt: true }))
  Object.assign(f.receiptForm, { submittedAt: '2026-11-07', recipient: ' 接收单位 ', receiptNo: ' 回执82 ' })
  const pending = f.recordReceipt(), duplicate = f.recordReceipt()
  assert.equal(f.writes[1].url, '/bureau/report-versions/batches/82/receipt')
  assert.equal(f.writes[1].body.receiptNo, '回执82'); assert.equal(f.writes.length, 2)
  f.auth.user.accountCategory = 'AGENCY'; f.writes[1].resolve({}); await Promise.all([pending, duplicate])
  assert.equal(f.selected.value, undefined); assert.equal(f.saving.value, false)
})

test('report version confirmation obeys server eligibility and discards success after role loss', async t => {
  const f = fixture(t); await listReady(f)
  await openReady(f, batch(1, { canConfirm: false }))
  f.opinion.value = '核对通过'; await f.confirm()
  assert.equal(f.writes.length, 0)
  await openReady(f, batch(2))
  f.opinion.value = '逐行核对通过'
  const first = f.confirm(), duplicate = f.confirm()
  assert.equal(f.writes.length, 1)
  assert.equal(f.writes[0].url, '/bureau/report-versions/batches/2/confirm')
  f.auth.user.accountCategory = 'AGENCY'
  f.writes[0].resolve(batch(2, { status: 'CONFIRMED' }))
  await Promise.all([first, duplicate])
  assert.equal(f.selected.value, undefined)
  assert.equal(f.previewVisible.value, false)
  assert.equal(f.messages.length, 0)
})

test('old batch source responses cannot refill a new selection or bypass source-link allowlist', async t => {
  const f = fixture(t); await listReady(f)
  const old = f.openBatch(batch(1)), oldResponse = f.reads.at(-1)
  await openReady(f, batch(2))
  oldResponse.resolve(batch(1)); await old
  assert.equal(f.selected.value.id, 2)
  const row = { link: '/case/detail/22', cells: [] }
  f.previewRows.value = [row]
  f.openSource(f.previewRows.value[0])
  assert.deepEqual(f.navigation, ['/case/detail/22'])
  f.openSource({ link: '/case/detail/23', cells: [] })
  f.previewRows.value = [{ link: 'https://untrusted.invalid', cells: [] }]
  f.openSource(f.previewRows.value[0])
  assert.equal(f.navigation.length, 1)
})

test('expired or unmounted sessions cannot restore report metadata from late reads', async t => {
  for (const expire of [true, false]) {
    const f = fixture(t), pending = f.reads[0]
    expire ? f.expire() : f.unmount()
    pending.resolve({ rows: [batch(9)], total: 1 }); await tick()
    assert.equal(f.batches.value.length, 0)
    assert.equal(f.selected.value, undefined)
  }
})

test('task focus fetches the exact batch directly and reports inaccessible targets', async t => {
  const f = fixture(t, { focusBatchId: 401 })
  assert.equal(f.reads[0].url, '/bureau/report-versions/batches')
  assert.equal(f.reads[1].url, '/bureau/report-versions/batches/401')
  f.reads[0].resolve({ rows: [], total: 0 })
  f.reads[1].resolve(batch(401)); await tick()
  assert.equal(f.reads[2].url, '/bureau/report-versions/batches/401/rows')
  f.reads[2].resolve({ rows: [], total: 0 }); await tick()
  assert.equal(f.selected.value.id, 401)
  f.props.focusBatchId = 402
  assert.equal(f.reads.at(-1).url, '/bureau/report-versions/batches/402')
  f.reads.at(-1).reject(new Error('forbidden')); await tick()
  assert.equal(f.previewVisible.value, false)
  assert.equal(f.selected.value, undefined)
  assert.equal(f.focusFailed.value, true)
})


test('frozen terminal actions use eligibility, preserve reason for retry and stop after identity loss', async t => {
  const f = fixture(t); await listReady(f)
  await openReady(f, batch(40, { canReturn: false }))
  f.opinion.value = '核对后退回'; await f.transition('RETURN'); assert.equal(f.writes.length, 0)
  await openReady(f, batch(41, { canReturn: true }))
  f.opinion.value = '范围选择错误'
  const first=f.transition('RETURN'), duplicate=f.transition('RETURN')
  assert.equal(f.writes.length,1); assert.deepEqual(f.writes[0].body,{action:'RETURN',opinion:'范围选择错误'})
  f.writes[0].reject(new Error('response lost')); await Promise.all([first,duplicate])
  assert.equal(f.opinion.value,'范围选择错误')
  const retry=f.transition('RETURN'); f.expire(); f.writes[1].resolve(batch(41,{status:'REJECTED'})); await retry
  assert.equal(f.selected.value,undefined); assert.equal(f.messages.length,0)
  assert.equal(f.statusLabel('WITHDRAWN'),'已撤回'); assert.equal(f.statusLabel('VOIDED'),'已作废')
})
