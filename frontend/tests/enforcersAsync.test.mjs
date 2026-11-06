import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createResourceLoader, resourceState } from '../src/api/loadableResource.ts'
import { adminOperationAccess } from '../src/api/caseWriteAccess.ts'

const source = readFileSync(new URL('../src/views/system/EnforcersView.vue', import.meta.url), 'utf8')
const tick = () => new Promise(setImmediate)
function fixture(roles = ['ADMIN']) {
  const reads = [], writes = [], successes = [], warnings = [], mounted = [], unmounted = []
  const client = {
    get: (url, config) => new Promise((resolve, reject) => reads.push({ url, config, resolve: data => resolve({ data: { data } }), reject })),
    post: (url, body) => new Promise((resolve, reject) => writes.push({ url, body, resolve, reject })),
  }
  const auth = Vue.reactive({ user: { username: 'admin', roles } })
  const context = { ...Vue, client, resourceState, createResourceLoader, adminOperationAccess,
    useAuthStore: () => auth, onMounted: fn => mounted.push(fn), onUnmounted: fn => unmounted.push(fn),
    ElMessage: { success: text => successes.push(text), warning: text => warnings.push(text) } }
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const scope = Vue.effectScope()
  const output = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script) +
    ';({rows,ledger,loading,load,openCreate,onSave,form,dlg,saving,mappingTouched,users})', context))
  return { ...output, auth, reads, writes, successes, warnings,
    mount: () => Promise.all(mounted.map(fn => fn())), unmount: () => { unmounted.forEach(fn => fn()); scope.stop() } }
}
function draft(f) {
  f.openCreate()
  Object.assign(f.form, { name: '测试人员', certNo: 'CERT-TEST', dept: '测试科', certExpireAt: '2030-01-01' })
}
async function finish(f, job, rows = []) {
  f.writes.at(-1).resolve({}); await tick(); f.reads.at(-1).resolve(rows); await job
}

test('an untouched blank account mapping is omitted rather than silently clearing an existing mapping', async () => {
  const f = fixture(); draft(f); const save = f.onSave()
  assert.equal(Object.hasOwn(f.writes[0].body, 'userId'), false)
  assert.equal(f.writes[0].body.certNo, 'CERT-TEST')
  await finish(f, save); f.unmount()
})

test('an explicitly cleared mapping is serialized as null, including select empty values', async () => {
  for (const empty of [null, undefined, '']) {
    const f = fixture(); draft(f); f.form.userId = empty; f.mappingTouched.value = true
    const save = f.onSave(); assert.equal(f.writes[0].body.userId, null)
    assert.equal(Object.hasOwn(f.writes[0].body, 'userId'), true)
    await finish(f, save); f.unmount()
  }
})

test('a selected account is sent and a newly opened form resets prior clearing intent', async () => {
  const f = fixture(); draft(f); f.mappingTouched.value = true; f.dlg.value = false; draft(f)
  assert.equal(f.mappingTouched.value, false); f.form.userId = 42
  const save = f.onSave(); assert.equal(f.writes[0].body.userId, 42)
  await finish(f, save); f.unmount()
})

test('save uses one immutable snapshot and cannot submit twice or reset the form while pending', async () => {
  const f = fixture(); draft(f); f.form.userId = 42
  const save = f.onSave(); f.form.name = '后来编辑'; f.form.userId = null
  await f.onSave(); f.openCreate()
  assert.equal(f.writes.length, 1); assert.equal(f.writes[0].body.name, '测试人员'); assert.equal(f.writes[0].body.userId, 42)
  assert.equal(f.form.name, '后来编辑'); assert.equal(f.saving.value, true)
  f.writes[0].resolve({}); await tick(); assert.equal(f.saving.value, true)
  f.reads[0].resolve([{ cert_no: 'CERT-TEST' }]); await save
  assert.equal(f.saving.value, false); assert.equal(f.dlg.value, false); assert.equal(f.successes.length, 1); f.unmount()
})

test('a rejected save preserves the entered draft and permits correction', async () => {
  const f = fixture(); draft(f); const save = f.onSave(); f.writes[0].reject(new Error('mapping rejected'))
  await assert.doesNotReject(save)
  assert.equal(f.dlg.value, true); assert.equal(f.form.certNo, 'CERT-TEST'); assert.equal(f.saving.value, false)
  assert.equal(f.successes.length, 0); assert.equal(f.reads.length, 0); f.unmount()
})

test('an old completion cannot close a reopened draft or announce success in a changed context', async () => {
  const f = fixture(); draft(f); const save = f.onSave()
  f.dlg.value = false; f.dlg.value = true; f.form.certNo = 'NEW-DRAFT'; f.writes[0].resolve({}); await save
  assert.equal(f.dlg.value, true); assert.equal(f.form.certNo, 'NEW-DRAFT'); assert.equal(f.successes.length, 0)
  assert.equal(f.reads.length, 0); f.unmount()
})

test('role loss and unmount invalidate save callbacks and prevent further writes', async () => {
  for (const unmount of [false, true]) {
    const f = fixture(); draft(f); f.users.value = [{ id: 1 }]; const save = f.onSave()
    if (unmount) f.unmount(); else f.auth.user.roles = ['HANDLER']
    f.writes[0].resolve({}); await save; await f.onSave()
    assert.equal(f.successes.length, 0); assert.equal(f.reads.length, 0); assert.equal(f.writes.length, 1)
    assert.equal(f.users.value.length, 0); if (!unmount) f.unmount()
  }
})

test('overlapping ledger reads retain only the newest rows', async () => {
  const f = fixture(), first = f.load(), second = f.load()
  f.reads[1].resolve([{ cert_no: 'new' }]); await second
  f.reads[0].resolve([{ cert_no: 'old' }]); await first
  assert.equal(f.rows.value[0].cert_no, 'new'); f.unmount()
})

test('a stale failure cannot clear newer ledger rows or end their spinner', async () => {
  const f = fixture(), old = f.load(), current = f.load()
  f.reads[0].reject(new Error('old failure')); await old; assert.equal(f.loading.value, true)
  f.reads[1].resolve([{ cert_no: 'current' }]); await current
  assert.equal(f.rows.value[0].cert_no, 'current'); assert.equal(f.ledger.failed, false); f.unmount()
})

test('the latest failed or malformed ledger read hides stale rows and allows a retry', async () => {
  for (const malformed of [false, true]) {
    const f = fixture(), initial = f.load(); f.reads[0].resolve([{ cert_no: 'old' }]); await initial
    const failure = f.load()
    assert.equal(f.rows.value.length, 0)
    if (malformed) f.reads[1].resolve({ records: [] }); else f.reads[1].reject(new Error('offline'))
    await failure; assert.equal(f.rows.value.length, 0); assert.equal(f.ledger.failed, true)
    const retry = f.load(); f.reads[2].resolve([]); await retry; assert.equal(f.ledger.ready, true); assert.equal(f.ledger.failed, false)
    f.unmount()
  }
})

test('a successful write followed by a failed refresh does not retain the old ledger', async () => {
  const f = fixture(), initial = f.load(); f.reads[0].resolve([{ cert_no: 'old' }]); await initial
  draft(f); const save = f.onSave(); f.writes[0].resolve({}); await tick(); f.reads[1].reject(new Error('offline')); await save
  assert.equal(f.successes.length, 1); assert.equal(f.rows.value.length, 0); assert.equal(f.ledger.failed, true)
  assert.equal(f.saving.value, false); f.unmount()
})

test('unmounted ledger requests cannot repopulate the table or start later reads', async () => {
  const f = fixture(), read = f.load(); f.unmount(); f.reads[0].resolve([{ cert_no: 'late' }]); await read
  await f.load(); assert.equal(f.rows.value.length, 0); assert.equal(f.reads.length, 1)
})

test('the user directory is requested only by admins and late role loss does not publish it', async () => {
  const readonly = fixture(['HANDLER']), ready = readonly.mount(); readonly.reads[0].resolve([]); await ready
  assert.equal(readonly.reads.length, 1); readonly.unmount()
  const f = fixture(), mounting = f.mount(); f.reads[0].resolve([]); await tick()
  assert.equal(f.reads[1].url, '/system/users'); f.auth.user.roles = ['HANDLER']
  f.reads[1].resolve({ records: [{ username: 'private-user' }] }); await mounting
  assert.equal(f.users.value.length, 0); f.unmount()
})

test('blank required fields do not post or dismiss a ledger draft', async () => {
  const f = fixture(); draft(f); f.form.name = '   '; await f.onSave()
  assert.equal(f.writes.length, 0); assert.equal(f.dlg.value, true); assert.equal(f.warnings.length, 1); f.unmount()
})
