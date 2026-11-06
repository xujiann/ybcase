import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
import { createBlobPreview } from '../src/api/blobPreview.ts'
import { adminOperationAccess } from '../src/api/caseWriteAccess.ts'

const tick = () => new Promise(setImmediate)
function fixture(t, admin = true) {
  const reads = [], writes = [], successes = [], warnings = [], downloads = [], blobs = [], revoked = [], unmounts = []
  const auth = Vue.reactive({ token: 'session-one', user: { id: 1, username: 'first', roles: [admin ? 'ADMIN' : 'HANDLER'] } })
  const sessionExpiry = createSessionExpiry(); sessionExpiry.reset(auth.token)
  const client = {
    get: (url, config) => new Promise((resolve, reject) => reads.push({ url, config, resolve: data => resolve({ data: url.endsWith('/screenshot') || url.endsWith('/export') ? data : { data } }), reject })),
    post: (url, body) => new Promise((resolve, reject) => writes.push({ url, body, resolve, reject })),
  }
  const URL = { createObjectURL: blob => { blobs.push(blob); return 'blob:' + blobs.length }, revokeObjectURL: url => revoked.push(url) }
  const document = { createElement: () => ({ click() { downloads.push({ href: this.href, download: this.download }) } }) }
  const listeners = new Map()
  const window = { addEventListener: (name, fn) => listeners.set(name, fn), removeEventListener: name => listeners.delete(name) }
  const file = admin ? 'system/FeedbackAdminView.vue' : 'case/MyFeedbackView.vue'
  const source = readFileSync(new URLConstructor('../src/views/' + file, import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const exposed = admin ? 'load,d,fStatus,loading,failed,openHandle,onHandle,handling,handleForm,handleVisible,saving,onExport,exporting,viewContext,cur,ctxVisible,shot,onSessionExpired'
    : 'load,rows,loading,failed,onClose,closing,invalidate'
  const scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script) + ';({' + exposed + '})', {
    ...Vue, client, useAuthStore: () => auth, sessionExpiry, SESSION_EXPIRED_EVENT, adminOperationAccess, createBlobPreview: state => createBlobPreview(state, URL),
    window, document, URL, fmtTime: value => value, onMounted() {}, onUnmounted: fn => unmounts.push(fn),
    ElMessage: { success: v => successes.push(v), warning: v => warnings.push(v) },
  }))
  let disposed = false
  const f = { ...view, auth, sessionExpiry, reads, writes, successes, warnings, downloads, blobs, revoked,
    switchUser() { sessionExpiry.reset('session-two'); auth.token = 'session-two'; auth.user = { id: 2, username: 'second', roles: [admin ? 'ADMIN' : 'HANDLER'] } },
    expire() { sessionExpiry.expire(sessionExpiry.capture(auth.token), auth.token); admin ? f.onSessionExpired() : f.invalidate() },
    unmount() { if (!disposed) { disposed = true; unmounts.forEach(fn => fn()); scope.stop() } },
  }
  t.after(() => f.unmount())
  return f
}
const URLConstructor = URL
async function seed(f, admin = true) {
  const job = f.load(); f.reads.at(-1).resolve(admin ? { rows: [{ id: 1, status: 'NEW', title: 'first' }] } : [{ id: 1, status: 'RESOLVED', title: 'first' }]); await job
}
function handle(f) { f.openHandle({ id: 1, status: 'NEW', title: 'first' }); f.handleForm.status = 'RESOLVED'; f.handleForm.reply = '原回复'; return f.onHandle() }

for (const admin of [true, false]) {
  const name = admin ? 'administrator feedback' : 'my feedback'
  const rows = f => admin ? f.d.value.rows : f.rows.value
  const result = (value, id) => admin ? { rows: [{ id, title: value }] } : [{ id, title: value }]
  test(name + ': responses cannot cross accounts', async t => {
    const f = fixture(t, admin), old = f.load(); f.switchUser(); const next = f.load()
    f.reads[1].resolve(result('second', 2)); await next; f.reads[0].resolve(result('private first', 1)); await old
    assert.equal(rows(f)[0].id, 2)
  })
  test(name + ': same JWT new login generation rejects old data', async t => {
    const f = fixture(t, admin), pending = f.load(); f.sessionExpiry.reset(f.auth.token)
    f.reads[0].resolve(result('old', 1)); await pending; assert.equal(rows(f).length, 0); assert.equal(f.loading.value, false)
  })
  test(name + ': expiry invalidates reads and clears prior visible data', async t => {
    const f = fixture(t, admin); await seed(f, admin); const pending = f.load(); f.expire()
    f.reads[1].resolve(result('expired', 1)); await pending
    assert.equal(rows(f).length, 0); assert.equal(f.loading.value, false); assert.equal(f.failed.value, false)
  })
  test(name + ': latest failure removes old actionable rows and permits retry', async t => {
    const f = fixture(t, admin); await seed(f, admin); const pending = f.load(); f.reads[1].reject(new Error('offline'))
    await assert.doesNotReject(pending); assert.equal(rows(f).length, 0); assert.equal(f.failed.value, true)
    const retry = f.load(); f.reads[2].resolve(result('retry', 3)); await retry; assert.equal(rows(f)[0].id, 3)
  })
  test(name + ': older failure cannot release latest loading state', async t => {
    const f = fixture(t, admin), first = f.load(), second = f.load()
    f.reads[0].reject(new Error('old')); await first; assert.equal(f.loading.value, true)
    f.reads[1].resolve(result('new', 2)); await second; assert.equal(f.failed.value, false)
  })
  test(name + ': unmount prevents late data and further reads', async t => {
    const f = fixture(t, admin), pending = f.load(); f.unmount(); f.reads[0].resolve(result('late', 1)); await pending
    assert.equal(rows(f).length, 0); await f.load(); assert.equal(f.reads.length, 1)
  })
}
test('my feedback: reversed refreshes cannot restore resolved state over a closed row', async t => {
  const f = fixture(t, false), first = f.load(), second = f.load()
  f.reads[1].resolve([{ id: 1, status: 'CLOSED' }]); await second
  f.reads[0].resolve([{ id: 1, status: 'RESOLVED' }]); await first; assert.equal(f.rows.value[0].status, 'CLOSED')
})
test('my feedback: close owns its pending lock through the following refresh', async t => {
  const f = fixture(t, false); await seed(f, false); const row = f.rows.value[0], job = f.onClose(row)
  await f.onClose(row); assert.equal(f.writes.length, 1); assert.equal(f.closing.value, 1)
  f.writes[0].resolve({}); await tick(); assert.equal(f.closing.value, 1); assert.equal(f.rows.value.length, 0)
  f.reads[1].resolve([{ id: 1, status: 'CLOSED' }]); await job
  assert.equal(f.closing.value, null); assert.equal(f.rows.value[0].status, 'CLOSED'); assert.equal(f.successes.length, 1)
})
test('my feedback: failed close refreshes current state before allowing another action', async t => {
  const f = fixture(t, false); await seed(f, false); const job = f.onClose(f.rows.value[0])
  f.writes[0].reject(new Error('already closed')); await tick(); assert.equal(f.closing.value, 1)
  f.reads[1].resolve([{ id: 1, status: 'CLOSED' }]); await assert.doesNotReject(job)
  assert.equal(f.successes.length, 0); assert.equal(f.rows.value[0].status, 'CLOSED')
})
test('my feedback: old close completion cannot unlock a new account close', async t => {
  const f = fixture(t, false); await seed(f, false); const first = f.onClose(f.rows.value[0]); f.switchUser()
  const second = f.onClose({ id: 2, status: 'RESOLVED' }); f.writes[0].resolve({}); await first
  assert.equal(f.closing.value, 2); assert.equal(f.reads.length, 1); assert.equal(f.successes.length, 0)
  f.writes[1].resolve({}); await tick(); f.reads[1].resolve([{ id: 2, status: 'CLOSED' }]); await second
})
test('administrator: old handling response cannot close the next account dialog or notify it', async t => {
  const f = fixture(t), pending = handle(f); f.switchUser(); f.openHandle({ id: 2, status: 'NEW', title: 'second' }); f.handleForm.reply = 'new draft'
  f.writes[0].resolve({}); await pending
  assert.equal(f.handleVisible.value, true); assert.equal(f.handling.value.id, 2); assert.equal(f.handleForm.reply, 'new draft')
  assert.equal(f.reads.length, 0); assert.equal(f.successes.length, 0)
})
test('administrator: changing the pending draft prevents older success from erasing it', async t => {
  const f = fixture(t), pending = handle(f); f.handleForm.reply = 'later draft'; f.writes[0].resolve({}); await pending
  assert.equal(f.handleVisible.value, true); assert.equal(f.handleForm.reply, 'later draft'); assert.equal(f.successes.length, 0); assert.equal(f.saving.value, false)
})
test('administrator: unmount and same JWT new login each suppress older handling completion', async t => {
  for (const dispose of [true, false]) {
    const f = fixture(t), pending = handle(f); dispose ? f.unmount() : f.sessionExpiry.reset(f.auth.token)
    f.writes[0].resolve({}); await pending; assert.equal(f.successes.length, 0); assert.equal(f.reads.length, 0)
  }
})
test('administrator: rejected or resolved handling requires a reply without sending a request', async t => {
  const f = fixture(t); f.openHandle({ id: 1, status: 'NEW' }); f.handleForm.status = 'RESOLVED'; f.handleForm.reply = ' '
  await f.onHandle(); assert.equal(f.writes.length, 0); assert.equal(f.warnings.length, 1); assert.equal(f.handleVisible.value, true)
})
test('administrator: repeated exports create one request and one download', async t => {
  const f = fixture(t), first = f.onExport(); await f.onExport(); assert.equal(f.reads.length, 1)
  f.reads[0].resolve(new Blob(['csv'])); await first; assert.equal(f.downloads.length, 1); assert.equal(f.exporting.value, false); assert.equal(f.revoked.length, 1)
})
test('administrator: export never downloads data after a new account or unmount', async t => {
  for (const dispose of [true, false]) {
    const f = fixture(t), pending = f.onExport(); dispose ? f.unmount() : f.switchUser()
    f.reads[0].resolve(new Blob(['private csv'])); await pending; assert.equal(f.downloads.length, 0); assert.equal(f.blobs.length, 0)
  }
})
test('administrator: losing ADMIN clears visible rows, handling and screenshot data immediately', async t => {
  const f = fixture(t); await seed(f); f.openHandle({ id: 1, status: 'NEW' })
  const shot = f.viewContext({ id: 1, has_screenshot: true }); f.reads[1].resolve(new Blob(['image'])); await shot
  assert.equal(f.shot.url, 'blob:1'); f.auth.user.roles = ['HANDLER']
  assert.equal(f.d.value.rows.length, 0); assert.equal(f.ctxVisible.value, false); assert.equal(f.handleVisible.value, false); assert.equal(f.shot.url, '')
})
test('administrator: screenshot load is rejected after same JWT login generation changes', async t => {
  const f = fixture(t), pending = f.viewContext({ id: 1, has_screenshot: true }); f.sessionExpiry.reset(f.auth.token)
  f.reads[0].resolve(new Blob(['private screenshot'])); await pending; assert.equal(f.shot.url, ''); assert.equal(f.blobs.length, 0); assert.equal(f.shot.failed, false)
})
