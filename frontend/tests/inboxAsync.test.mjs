import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
import { businessReadAccess } from '../src/api/caseWriteAccess.ts'

// Exercise the real SFC, Vue identity watchers, and deferred server responses.
function layout(t) {
  const token = 'header.' + Buffer.from(JSON.stringify({ exp: 1999999999 })).toString('base64url') + '.first'
  const storage = new Map([['bureau_token', token]])
  const localStorage = { getItem: key => storage.get(key) ?? null, removeItem: key => storage.delete(key) }
  const sessionExpiry = createSessionExpiry(); sessionExpiry.reset(token)
  const auth = Vue.reactive({ token, user: { id: 1, username: 'first', roles: ['ADMIN'], mustChangePassword: false }, fetchMe: async () => {} })
  const reads = [], writes = [], routes = [], unmounts = []
  const deferred = (collection, url, config) => new Promise((resolve, reject) => collection.push({ url, config, resolve: data => resolve({ data: { data } }), reject }))
  const client = { get: (url, config) => deferred(reads, url, config), post: (url, config) => deferred(writes, url, config) }
  const window = { innerWidth: 1280, clearInterval() {}, clearTimeout() {}, addEventListener() {}, removeEventListener() {}, setInterval() { return 1 }, setTimeout() { return 2 } }
  const context = { ...Vue, client, recentErrors: [], useRoute: () => ({ path: '/dashboard' }), useRouter: () => ({ push: path => routes.push(path) }),
    useAuthStore: () => auth, businessReadAccess, sessionExpiry, SESSION_EXPIRED_EVENT, localStorage, window,
    onMounted() {}, onUnmounted: fn => unmounts.push(fn), ElMessage: { warning() {}, success() {} }, atob: value => Buffer.from(value, 'base64').toString('binary') }
  const source = readFileSync(new URL('../src/views/MainLayout.vue', import.meta.url), 'utf8').match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(source) + ';({reloadMessages,openMessages,onOpenMessage,onReadAll,refreshUnread,messages,msgVisible,unread,onSessionExpired,pwdForm,fb})', context))
  let disposed = false
  const f = { ...view, auth, token, storage, sessionExpiry, client, reads, writes, routes,
    switchSession(username = 'first', next = token + '-next') { sessionExpiry.reset(next); storage.set('bureau_token', next); auth.token = next; auth.user = { id: username === 'first' ? 1 : 2, username, roles: ['HANDLER'], mustChangePassword: false } },
    unmount() { if (!disposed) { disposed = true; unmounts.forEach(fn => fn()); scope.stop() } } }
  t.after(() => f.unmount())
  return f
}
const row = (id, read_at = null) => ({ id, title: 'message-' + id, link: '/case/detail/' + id, read_at })
async function open(f, data = [row(1)]) { const job = f.openMessages(); f.reads.at(-1).resolve(data); await job; return f.messages.value[0] }
const empty = f => { assert.equal(f.messages.value.length, 0); assert.equal(f.msgVisible.value, false); assert.equal(f.unread.value, 0) }
const tick = () => new Promise(setImmediate)

test('inbox only opens the newest requested list when responses arrive backwards', async t => {
  const f = layout(t), old = f.openMessages(), latest = f.openMessages()
  f.reads[1].resolve([row(2)]); await latest; f.reads[0].resolve([row(1)]); await old
  assert.equal(f.msgVisible.value, true); assert.equal(f.messages.value[0].id, 2)
})

test('business-role changes immediately clear the visible inbox and unread badge', async t => {
  const f = layout(t); await open(f); f.unread.value = 7
  f.auth.user.roles.splice(0, 1, 'HANDLER'); empty(f)
  f.auth.user.roles.length = 0; await f.openMessages(); await f.onReadAll()
  assert.equal(f.reads.length, 1); assert.equal(f.writes.length, 0)
})

test('a same-account new sign-in cannot receive the older inbox response', async t => {
  const f = layout(t), old = f.openMessages(); f.switchSession()
  f.reads[0].resolve([row(1)]); await old; empty(f)
  await open(f, [row(2)]); assert.equal(f.messages.value[0].id, 2)
})

test('an identical JWT after a fresh sign-in still rejects the older inbox response', async t => {
  const f = layout(t), old = f.openMessages(); f.sessionExpiry.reset(f.token)
  f.reads[0].resolve([row(1)]); await old; empty(f)
  await open(f, [row(2)]); assert.equal(f.messages.value[0].id, 2)
})

test('session expiry hides inbox data, preserves unrelated drafts and ignores late list responses', async t => {
  const f = layout(t); await open(f); f.unread.value = 4
  f.pwdForm.newPassword = 'unsaved password'; f.fb.content = '尚未提交的反馈'
  const pending = f.openMessages(); f.sessionExpiry.expire(f.sessionExpiry.capture(f.token), f.token); f.onSessionExpired(); empty(f)
  f.reads[1].resolve([row(2)]); await pending; empty(f)
  assert.equal(f.pwdForm.newPassword, 'unsaved password'); assert.equal(f.fb.content, '尚未提交的反馈')
})

test('unmounted layouts clear inbox data and reject pending or new loads', async t => {
  const f = layout(t); await open(f); const pending = f.openMessages(); f.unmount()
  f.reads[1].resolve([row(2)]); await pending; empty(f)
  await f.openMessages(); assert.equal(f.reads.length, 2)
})

test('a prior account mark-read completion cannot mutate or navigate the new account', async t => {
  const f = layout(t), m = await open(f), job = f.onOpenMessage(m); f.switchSession('second')
  f.writes[0].resolve(); await job; empty(f)
  assert.equal(m.read_at, null); assert.equal(f.routes.length, 0); assert.equal(f.reads.length, 1)
})

test('a fresh identical-token session invalidates a pending mark-read callback', async t => {
  const f = layout(t), m = await open(f), job = f.onOpenMessage(m); f.sessionExpiry.reset(f.token)
  f.writes[0].resolve(); await job
  assert.equal(m.read_at, null); assert.equal(f.routes.length, 0); assert.equal(f.reads.length, 1)
})

test('only the latest selected message can navigate when mark-read responses reverse', async t => {
  const f = layout(t); await open(f, [row(1), row(2)])
  const first = f.onOpenMessage(f.messages.value[0]), second = f.onOpenMessage(f.messages.value[1])
  f.writes[1].resolve(); await second; f.writes[0].resolve(); await first
  assert.deepEqual(f.routes, ['/case/detail/2'])
})

test('closing the inbox while mark-read waits cancels its later navigation', async t => {
  const f = layout(t), m = await open(f), job = f.onOpenMessage(m); f.msgVisible.value = false
  f.writes[0].resolve(); await job
  assert.equal(f.routes.length, 0); assert.equal(m.read_at, null)
})

test('mark-all-read from the prior session cannot request data under the next account', async t => {
  const f = layout(t); await open(f); const job = f.onReadAll(); f.switchSession('second')
  f.writes[0].resolve(); await tick()
  assert.equal(f.reads.length, 1); await job; empty(f)
})

test('current mark-all-read reloads authorized rows and count; a failed list never retains stale rows', async t => {
  const f = layout(t); await open(f); const job = f.onReadAll(); f.writes[0].resolve(); await tick()
  const list = f.reads.findLast(r => r.url === '/bureau/messages'), count = f.reads.find(r => r.url.endsWith('unread-count'))
  assert.ok(count); list.reject(new Error('temporary failure')); count.resolve(2); await job
  assert.equal(f.messages.value.length, 0); assert.equal(f.unread.value, 2)
  await open(f, [row(3)]); assert.equal(f.messages.value[0].id, 3)
})

test('a late privileged unread count cannot overwrite the badge after a role change', async t => {
  const f = layout(t); f.unread.value = 5; const job = f.refreshUnread()
  f.auth.user.roles.splice(0, 1, 'HANDLER'); assert.equal(f.unread.value, 0)
  f.reads[0].resolve(99); await job; assert.equal(f.unread.value, 0)
})

test('an intentional inbox load after expiry still delegates login handling to the request client', async t => {
  const f = layout(t); f.sessionExpiry.expire(f.sessionExpiry.capture(f.token), f.token); f.onSessionExpired()
  let attempts = 0; f.client.get = async url => { assert.equal(url, '/bureau/messages'); attempts++; throw Object.assign(new Error('expired'), { code: 'ERR_SESSION_EXPIRED' }) }
  await f.openMessages(); assert.equal(attempts, 1); empty(f)
})
