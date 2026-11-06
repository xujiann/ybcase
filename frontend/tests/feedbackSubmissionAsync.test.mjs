import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
import { businessReadAccess } from '../src/api/caseWriteAccess.ts'

// Actual SFC handlers/watchers, with deferred HTTP and FileReader completions.
function layout(t) {
  const token = 'header.' + Buffer.from(JSON.stringify({ exp: 1999999999 })).toString('base64url') + '.first'
  const storage = new Map([['bureau_token', token]])
  const localStorage = { getItem: key => storage.get(key) ?? null, removeItem: key => storage.delete(key) }
  const sessionExpiry = createSessionExpiry(); sessionExpiry.reset(token)
  const auth = Vue.reactive({ token, user: { id: 1, username: 'first', roles: ['ADMIN'], mustChangePassword: false }, fetchMe: async () => {} })
  const writes = [], reads = [], readers = [], successes = [], warnings = [], unmounts = []
  const route = Vue.reactive({ path: '/case/detail/123' })
  const client = {
    get: url => new Promise(resolve => reads.push({ url, resolve: data => resolve({ data: { data } }) })),
    post: (url, payload) => new Promise((resolve, reject) => writes.push({ url, payload: JSON.parse(JSON.stringify(payload)), resolve: () => resolve({ data: { code: 0 } }), reject })),
  }
  class FileReader {
    constructor() { readers.push(this) }
    readAsDataURL(file) { assert.ok(file); this.file = file }
    finish(result) { this.result = result; this.onload?.() }
  }
  const window = { innerWidth: 1280, clearInterval() {}, clearTimeout() {}, addEventListener() {}, removeEventListener() {}, setInterval() { return 1 }, setTimeout() { return 2 } }
  const recentErrors = [{ requestId: 'original-trace', message: 'original diagnostic' }]
  const context = { ...Vue, client, recentErrors, useRoute: () => route, useRouter: () => ({ push() {} }), useAuthStore: () => auth,
    businessReadAccess, sessionExpiry, SESSION_EXPIRED_EVENT, localStorage, window, FileReader, navigator: { userAgent: 'u'.repeat(300) },
    onMounted() {}, onUnmounted: fn => unmounts.push(fn), ElMessage: { warning: value => warnings.push(value), success: value => successes.push(value) },
    atob: value => Buffer.from(value, 'base64').toString('binary') }
  const source = readFileSync(new URL('../src/views/MainLayout.vue', import.meta.url), 'utf8')
    .match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(source) +
    ';({openFeedback,onPasteShot,onSubmitFeedback,fbVisible,fb,on500,onSessionExpired,pwdForm,fbSaving:typeof fbSaving!=="undefined"?fbSaving:{value:false}})', context))
  let disposed = false
  const f = { ...view, auth, token, storage, sessionExpiry, client, route, recentErrors, writes, reads, readers, successes, warnings,
    switchSession(username = 'first', next = token + '-next') { sessionExpiry.reset(next); storage.set('bureau_token', next); auth.token = next; auth.user = { id: username === 'first' ? 1 : 2, username, roles: ['HANDLER'], mustChangePassword: false } },
    unmount() { if (!disposed) { disposed = true; unmounts.forEach(fn => fn()); scope.stop() } } }
  t.after(() => f.unmount())
  return f
}
function draft(f, suffix = '') { f.openFeedback(); f.fb.title = '标题' + suffix; f.fb.content = '正文' + suffix }
const paste = (file = { name: 'test.png' }) => ({ clipboardData: { items: [{ type: 'image/png', getAsFile: () => file }] } })

test('feedback double submit creates exactly one request and releases its pending lock after success', async t => {
  const f = layout(t); draft(f); const first = f.onSubmitFeedback(), second = f.onSubmitFeedback()
  assert.equal(f.writes.length, 1); assert.equal(f.fbSaving.value, true)
  f.writes[0].resolve(); await Promise.all([first, second])
  assert.equal(f.fbSaving.value, false); assert.equal(f.fbVisible.value, false)
  assert.equal(f.fb.title, ''); assert.equal(f.fb.content, ''); assert.equal(f.successes.length, 1)
})

test('a successful older feedback submission cannot erase edits made to the captured draft', async t => {
  const f = layout(t); draft(f); const job = f.onSubmitFeedback(); f.fb.content = '更晚的正文'
  f.writes[0].resolve(); await job
  assert.equal(f.fb.content, '更晚的正文'); assert.equal(f.fbVisible.value, true); assert.equal(f.successes.length, 0)
})

test('closing and reopening feedback invalidates the older successful completion', async t => {
  const f = layout(t); draft(f); const job = f.onSubmitFeedback(); f.fbVisible.value = false; draft(f, '-新草稿')
  f.writes[0].resolve(); await job
  assert.equal(f.fb.title, '标题-新草稿'); assert.equal(f.fbVisible.value, true); assert.equal(f.successes.length, 0)
})

test('an old completion cannot unlock or clear a newer draft submission', async t => {
  const f = layout(t); draft(f); const first = f.onSubmitFeedback(); f.fbVisible.value = false; draft(f, '-第二次')
  const second = f.onSubmitFeedback(); assert.equal(f.writes.length, 2)
  f.writes[0].resolve(); await first; assert.equal(f.fbSaving.value, true); assert.equal(f.fb.content, '正文-第二次')
  f.writes[1].resolve(); await second; assert.equal(f.fbSaving.value, false); assert.equal(f.successes.length, 1)
})

test('another account never receives old submission UI effects or inherited feedback draft', async t => {
  const f = layout(t); draft(f); f.fb.screenshotBase64 = 'data:image/png;base64,old'; const job = f.onSubmitFeedback()
  f.switchSession('second'); f.openFeedback()
  assert.equal(f.fb.title, ''); assert.equal(f.fb.content, ''); assert.equal(f.fb.screenshotBase64, '')
  f.fb.title = '新账号标题'; f.fb.content = '新账号正文'; f.writes[0].resolve(); await job
  assert.equal(f.fb.title, '新账号标题'); assert.equal(f.fbVisible.value, true); assert.equal(f.successes.length, 0)
})

test('same-JWT new sign-in generation still rejects the older submission completion', async t => {
  const f = layout(t); draft(f); const job = f.onSubmitFeedback(); f.sessionExpiry.reset(f.token)
  f.writes[0].resolve(); await job
  assert.equal(f.fb.title, '标题'); assert.equal(f.fb.content, '正文'); assert.equal(f.successes.length, 0)
})

test('session expiry preserves unsaved feedback and password drafts but prevents late success', async t => {
  const f = layout(t); draft(f); f.pwdForm.newPassword = 'unsaved password'; const job = f.onSubmitFeedback()
  f.sessionExpiry.expire(f.sessionExpiry.capture(f.token), f.token); f.onSessionExpired(); f.writes[0].resolve(); await job
  assert.equal(f.fb.title, '标题'); assert.equal(f.fb.content, '正文'); assert.equal(f.pwdForm.newPassword, 'unsaved password'); assert.equal(f.successes.length, 0)
})

test('unmount suppresses feedback success, draft changes and subsequent submits', async t => {
  const f = layout(t); draft(f); const job = f.onSubmitFeedback(); f.unmount(); f.writes[0].resolve(); await job; await f.onSubmitFeedback()
  assert.equal(f.writes.length, 1); assert.equal(f.fb.title, '标题'); assert.equal(f.successes.length, 0)
})

test('role downgrade invalidates a captured submission and cannot create feedback without a business role', async t => {
  const f = layout(t); draft(f); const job = f.onSubmitFeedback(); f.auth.user.roles.length = 0
  f.writes[0].resolve(); await job; await f.onSubmitFeedback()
  assert.equal(f.writes.length, 1); assert.equal(f.fb.content, '正文'); assert.equal(f.successes.length, 0)
})

test('feedback payload snapshots the existing route, diagnostic and screenshot fields', async t => {
  const f = layout(t); draft(f); f.fb.kind = 'QUESTION'; f.fb.screenshotBase64 = 'data:image/png;base64,AQID'
  const job = f.onSubmitFeedback(); const payload = f.writes[0].payload
  f.route.path = '/my/feedback'; f.recentErrors[0].requestId = 'later'
  assert.equal(payload.pageRoute, '/case/detail/123'); assert.equal(payload.caseRef, '123')
  assert.equal(payload.requestId, 'original-trace'); assert.equal(JSON.parse(payload.recentErrors)[0].requestId, 'original-trace')
  assert.equal(payload.userAgent.length, 250); assert.equal(payload.kind, 'QUESTION'); assert.equal(payload.screenshotBase64, 'data:image/png;base64,AQID')
  f.writes[0].resolve(); await job
})

test('missing feedback fields do not send a request or discard the draft', async t => {
  const f = layout(t); f.openFeedback(); f.fb.title = '  '; f.fb.content = '未丢失正文'; await f.onSubmitFeedback()
  assert.equal(f.writes.length, 0); assert.equal(f.warnings.length, 1); assert.equal(f.fb.content, '未丢失正文')
})

test('only the latest pasted screenshot can complete when FileReader results reverse', t => {
  const f = layout(t); draft(f); f.onPasteShot(paste()); f.onPasteShot(paste())
  f.readers[1].finish('second-image'); f.readers[0].finish('first-image')
  assert.equal(f.fb.screenshotBase64, 'second-image')
})

test('a FileReader belonging to a closed feedback dialog cannot attach to the reopened draft', t => {
  const f = layout(t); draft(f); f.onPasteShot(paste()); f.fbVisible.value = false; draft(f, '-新草稿'); f.readers[0].finish('old-image')
  assert.equal(f.fb.screenshotBase64, ''); assert.equal(f.fb.title, '标题-新草稿')
})

test('a prior account FileReader cannot leak its screenshot into the new account', t => {
  const f = layout(t); draft(f); f.onPasteShot(paste()); f.switchSession('second'); draft(f, '-新账号'); f.readers[0].finish('old-private-image')
  assert.equal(f.fb.screenshotBase64, ''); assert.equal(f.fb.title, '标题-新账号')
})

test('session expiration alone rejects an already running screenshot read', t => {
  const f = layout(t); draft(f); f.onPasteShot(paste()); f.sessionExpiry.expire(f.sessionExpiry.capture(f.token), f.token); f.readers[0].finish('expired-image')
  assert.equal(f.fb.screenshotBase64, '')
})

test('unmounted layouts neither apply nor start clipboard reads', t => {
  const f = layout(t); draft(f); f.onPasteShot(paste()); f.unmount(); f.readers[0].finish('old-image'); f.onPasteShot(paste())
  assert.equal(f.fb.screenshotBase64, ''); assert.equal(f.readers.length, 1)
})

test('submitting invalidates a still-running screenshot read without changing the sent payload', async t => {
  const f = layout(t); draft(f); f.onPasteShot(paste()); const job = f.onSubmitFeedback(); f.readers[0].finish('late-image')
  assert.equal(f.writes[0].payload.screenshotBase64, null); assert.equal(f.fb.screenshotBase64, '')
  f.writes[0].resolve(); await job
})

test('missing clipboard file or closed dialog does not create a reader', t => {
  const f = layout(t); f.onPasteShot(paste()); f.openFeedback(); f.onPasteShot(paste(null))
  assert.equal(f.readers.length, 0)
})

test('failed feedback submissions retain every field and allow one deliberate retry', async t => {
  const f = layout(t); draft(f); f.fb.kind = 'FEATURE'; f.fb.screenshotBase64 = 'kept-image'; const job = f.onSubmitFeedback()
  f.writes[0].reject(new Error('server failure')); await assert.rejects(job, /server failure/)
  assert.equal(f.fbSaving.value, false); assert.equal(f.fbVisible.value, true); assert.equal(f.fb.kind, 'FEATURE'); assert.equal(f.fb.screenshotBase64, 'kept-image')
  const retry = f.onSubmitFeedback(); assert.equal(f.writes.length, 2); f.writes[1].resolve(); await retry
})

test('the global 500 event does not overwrite a pending submitted feedback kind', async t => {
  const f = layout(t); draft(f); f.fb.kind = 'FEATURE'; const job = f.onSubmitFeedback(); f.on500()
  assert.equal(f.fb.kind, 'FEATURE'); f.writes[0].resolve(); await job
})

test('a deliberate feedback submit after expiry still reaches the request client without applying late success', async t => {
  const f = layout(t); draft(f); f.sessionExpiry.expire(f.sessionExpiry.capture(f.token), f.token)
  const job = f.onSubmitFeedback(); assert.equal(f.writes.length, 1)
  f.writes[0].resolve(); await job
  assert.equal(f.fb.title, '标题'); assert.equal(f.successes.length, 0); assert.equal(f.fbSaving.value, false)
})
