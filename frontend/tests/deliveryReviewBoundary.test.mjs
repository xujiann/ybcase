import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'

const source = readFileSync(new URL('../src/views/case/CaseDetailView.vue', import.meta.url), 'utf8')
const tick = () => new Promise(setImmediate)
function fixture(name) {
  const writes = [], prompts = [], success = [], warnings = []
  const context = {
    canWriteCase: { value: true }, submittingReview: { value: false }, deliveringCase: { value: false },
    auth: { token: 'first' }, id: { value: '1' }, dlg: { deliver: true },
    deliveryForm: { method: 'DIRECT', deliveredAt: '2026-11-11', receiptSignedAt: null, receiver: '当事人', receiptNo: 'R-1' },
    c: { value: { decidedAt: '2026-11-10' } }, detail: { value: {} }, todayLocal: () => '2026-11-11',
    captureMaterialContext: () => ({ caseId: context.id.value }),
    materialContextCurrent: value => value.caseId === context.id.value,
    client: { post: (url, body) => new Promise((resolve, reject) => writes.push({ url, body, resolve, reject })) },
    ElMessageBox: { prompt: () => new Promise((resolve, reject) => prompts.push({ resolve, reject })) },
    ElMessage: { success: value => success.push(value), warning: value => warnings.push(value) }, load: async () => {},
  }
  const start = source.indexOf(`async function ${name}(`)
  const end = source.indexOf('\n}', start) + 2
  const run = vm.runInNewContext(stripTypeScriptTypes(source.slice(start, end)) + `;${name}`, context)
  return { context, writes, prompts, success, warnings, run }
}

test('delivery requires real receipt fields and an actual date within the decision interval', async () => {
  for (const invalid of [{ receiver: '' }, { receiptNo: ' ' }, { deliveredAt: '' },
    { deliveredAt: '2026-11-09' }, { receiptSignedAt: '2026-11-12' }]) {
    const f = fixture('onDeliver')
    Object.assign(f.context.deliveryForm, invalid)
    await f.run()
    assert.equal(f.writes.length, 0)
    assert.equal(f.warnings.length, 1)
    assert.equal(f.context.dlg.deliver, true)
  }
})

test('delivery posts one immutable receipt snapshot and permits explicit retry after failure', async () => {
  const f = fixture('onDeliver'), pending = f.run()
  await f.run()
  f.context.deliveryForm.receiptNo = 'later edit'
  assert.equal(f.writes.length, 1)
  assert.equal(f.writes[0].body.receiptNo, 'R-1')
  f.writes[0].reject(new Error('offline'))
  await pending
  assert.equal(f.context.deliveringCase.value, false)
  assert.equal(f.context.dlg.deliver, true)
  const retry = f.run()
  f.writes[1].resolve({})
  await retry
  assert.equal(f.context.dlg.deliver, false)
  assert.equal(f.success.length, 1)
})

test('delivery responses cannot announce success after account or case changes', async () => {
  for (const change of [f => { f.context.auth.token = 'second' }, f => { f.context.id.value = '2' }]) {
    const f = fixture('onDeliver'), pending = f.run()
    change(f)
    f.writes[0].resolve({})
    await pending
    assert.equal(f.success.length, 0)
    assert.equal(f.context.dlg.deliver, true)
  }
})

test('review prompt and submission share one lock and keep the original case', async () => {
  const f = fixture('onSubmitReview'), pending = f.run()
  await f.run()
  assert.equal(f.prompts.length, 1)
  f.prompts[0].resolve({ value: '疑难复杂' })
  await tick()
  await f.run()
  assert.equal(f.writes.length, 1)
  assert.equal(f.writes[0].url, '/bureau/cases/1/reviews')
  f.writes[0].resolve({})
  await pending
  assert.equal(f.success.length, 1)
  assert.equal(f.context.submittingReview.value, false)
})

test('cancelled or stale review prompts cannot create an application', async () => {
  for (const transition of ['cancel', 'case', 'account', 'permission']) {
    const f = fixture('onSubmitReview'), pending = f.run()
    if (transition === 'cancel') f.prompts[0].reject(new Error('cancel'))
    else {
      if (transition === 'case') f.context.id.value = '2'
      if (transition === 'account') f.context.auth.token = 'second'
      if (transition === 'permission') f.context.canWriteCase.value = false
      f.prompts[0].resolve({ value: '疑难复杂' })
    }
    await pending
    assert.equal(f.writes.length, 0)
    assert.equal(f.context.submittingReview.value, false)
  }
})
