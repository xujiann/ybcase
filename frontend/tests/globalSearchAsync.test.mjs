import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
import { businessReadAccess } from '../src/api/caseWriteAccess.ts'

// Execute the actual layout handlers and Vue watchers. Deferred responses control
// ordering without changing browser tokens, reaching a server, or copying handlers.
function layout() {
  const token = 'header.' + Buffer.from(JSON.stringify({ exp: 1999999999 })).toString('base64url') + '.first'
  const storage = new Map([['bureau_token', token]])
  const localStorage = { getItem: key => storage.get(key) ?? null, removeItem: key => storage.delete(key) }
  const sessionExpiry = createSessionExpiry(); sessionExpiry.reset(token)
  const auth = Vue.reactive({ token, user: { username: 'same-account', roles: ['ADMIN'], mustChangePassword: false },
    fetchMe: async () => {}, logout() { auth.user = null; auth.token = ''; storage.clear(); sessionExpiry.reset() } })
  const reads = [], unmounts = []
  const client = { get(url, config) {
    assert.equal(url, '/bureau/search')
    return new Promise((resolve, reject) => reads.push({ config, resolve: data => resolve({ data: { data } }), reject }))
  } }
  const window = { innerWidth: 1280, clearInterval() {}, clearTimeout() {}, addEventListener() {}, removeEventListener() {},
    setInterval() { return 1 }, setTimeout() { return 2 } }
  const context = { ...Vue, client, recentErrors: [], useRoute: () => ({ path: '/dashboard' }), useRouter: () => ({ push() {} }),
    useAuthStore: () => auth, businessReadAccess, sessionExpiry, SESSION_EXPIRED_EVENT, localStorage, window,
    onMounted() {}, onUnmounted: fn => unmounts.push(fn), ElMessage: { warning() {}, success() {} },
    atob: value => Buffer.from(value, 'base64').toString('binary') }
  const script = readFileSync(new URL('../src/views/MainLayout.vue', import.meta.url), 'utf8')
    .match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script) +
    ';({onSearch,searchQ,searchVisible,searchResult,onSessionExpired,pwdForm,fb})', context))
  return { ...view, auth, storage, token, sessionExpiry, reads, client,
    unmount() { unmounts.forEach(fn => fn()); scope.stop() } }
}
const rows = id => ({ cases: [{ id, name: 'case-' + id }], clues: [{ id: id + 100 }] })
const empty = f => { assert.equal(f.searchVisible.value, false); assert.deepEqual(JSON.parse(JSON.stringify(f.searchResult.value)), {}) }

test('global search only displays the newest submitted query despite reversed responses', async () => {
  const f = layout()
  f.searchQ.value = 'old query'; const old = f.onSearch()
  f.searchQ.value = 'new query'; const latest = f.onSearch()
  assert.equal(f.reads[0].config.params.q, 'old query'); assert.equal(f.reads[1].config.params.q, 'new query')
  f.reads[1].resolve(rows(2)); await latest
  assert.equal(f.searchVisible.value, true); assert.equal(f.searchResult.value.cases[0].id, 2)
  f.reads[0].resolve(rows(1)); await old
  assert.equal(f.searchResult.value.cases[0].id, 2); f.unmount()
})

test('editing a query invalidates pending data even when the input is changed back without submitting', async () => {
  const f = layout(); f.searchQ.value = 'original'; const job = f.onSearch()
  f.searchQ.value = 'changed'; f.searchQ.value = 'original'
  f.reads[0].resolve(rows(1)); await job; empty(f)
  f.searchQ.value = 'x'; await f.onSearch(); assert.equal(f.reads.length, 1); f.unmount()
})

test('a failed new search clears old rows and can recover with another current request', async () => {
  const f = layout(); f.searchQ.value = 'first'
  const first = f.onSearch(); f.reads[0].resolve(rows(1)); await first
  assert.equal(f.searchVisible.value, true)
  f.searchQ.value = 'second'; const failure = f.onSearch(); empty(f)
  f.reads[1].reject(new Error('temporary failure')); await failure; empty(f)
  const recovery = f.onSearch(); f.reads[2].resolve(rows(3)); await recovery
  assert.equal(f.searchResult.value.cases[0].id, 3); assert.equal(f.searchVisible.value, true); f.unmount()
})

test('closing the search dialog clears its rows and late older data cannot reopen it', async () => {
  const f = layout(); f.searchQ.value = 'first'; const old = f.onSearch()
  f.searchQ.value = 'second'; const latest = f.onSearch()
  f.reads[1].resolve(rows(2)); await latest
  f.searchVisible.value = false; empty(f)
  f.reads[0].resolve(rows(1)); await old; empty(f); f.unmount()
})

test('unmounted layouts clear search state and ignore pending or newly attempted searches', async () => {
  const f = layout(); f.searchQ.value = 'pending'; const job = f.onSearch(); f.unmount()
  f.reads[0].resolve(rows(1)); await job; empty(f)
  await f.onSearch(); assert.equal(f.reads.length, 1)
})

test('a new token for the same account invalidates the previous search without blocking the new session', async () => {
  const f = layout(); f.searchQ.value = 'same query'; const old = f.onSearch()
  const next = f.token + '-next'; f.sessionExpiry.reset(next); f.storage.set('bureau_token', next); f.auth.token = next
  f.reads[0].resolve(rows(1)); await old; empty(f)
  const current = f.onSearch(); f.reads[1].resolve(rows(2)); await current
  assert.equal(f.searchResult.value.cases[0].id, 2); f.unmount()
})

test('an identical JWT after a fresh sign-in still rejects data from the earlier session generation', async () => {
  const f = layout(); f.searchQ.value = 'same query'; const old = f.onSearch()
  f.sessionExpiry.reset(f.token)
  f.reads[0].resolve(rows(1)); await old; empty(f)
  const current = f.onSearch(); f.reads[1].resolve(rows(2)); await current
  assert.equal(f.searchResult.value.cases[0].id, 2); f.unmount()
})

test('business-role changes discard privileged results and loss of bureau access blocks further searching', async () => {
  const f = layout(); f.searchQ.value = 'scope query'; const old = f.onSearch()
  f.auth.user.roles.splice(0, 1, 'HANDLER')
  f.reads[0].resolve(rows(1)); await old; empty(f)
  const own = f.onSearch(); f.reads[1].resolve(rows(2)); await own
  assert.equal(f.searchVisible.value, true)
  f.auth.user.roles.length = 0; empty(f)
  await f.onSearch(); assert.equal(f.reads.length, 2); f.unmount()
})

test('session expiry clears search results but preserves unrelated drafts and suppresses late data', async () => {
  const f = layout(); f.searchQ.value = 'first'; const first = f.onSearch()
  f.reads[0].resolve(rows(1)); await first
  f.pwdForm.newPassword = 'unsaved draft'; f.fb.content = '尚未提交的反馈'
  const pending = f.onSearch(), stamp = f.sessionExpiry.capture(f.token)
  f.sessionExpiry.expire(stamp, f.token); f.onSessionExpired(); empty(f)
  f.reads[1].resolve(rows(2)); await pending; empty(f)
  assert.equal(f.pwdForm.newPassword, 'unsaved draft'); assert.equal(f.fb.content, '尚未提交的反馈'); f.unmount()
})

test('an explicit search after background expiry still delegates login handling to the request client', async () => {
  const f = layout(); f.searchQ.value = 'intentional search'
  f.sessionExpiry.expire(f.sessionExpiry.capture(f.token), f.token)
  let attempts = 0
  f.client.get = async url => {
    assert.equal(url, '/bureau/search'); attempts++
    throw Object.assign(new Error('Login handling belongs to the request client'), { code: 'ERR_SESSION_EXPIRED' })
  }
  await f.onSearch(); assert.equal(attempts, 1); empty(f); f.unmount()
})
