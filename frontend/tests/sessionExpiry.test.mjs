import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import axios from 'axios'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
import { businessReadAccess } from '../src/api/caseWriteAccess.ts'

const tick = () => new Promise(setImmediate)
const jwt = label => 'header.' + Buffer.from(JSON.stringify({ sub: label, exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url') + '.signature'

function fixture(token = jwt('first')) {
  const storage = new Map([['bureau_token', token]])
  const localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
  const warnings = [], errors = [], routes = [], events = [], requests = [], intervals = new Map(), timeouts = new Map(), listeners = new Map()
  let timerId = 0
  const window = {
    innerWidth: 1280,
    setInterval: fn => { const id = timerId++; intervals.set(id, fn); return id },
    clearInterval: id => intervals.delete(id),
    setTimeout: fn => { const id = timerId++; timeouts.set(id, fn); return id },
    clearTimeout: id => timeouts.delete(id),
    addEventListener: (name, fn) => { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn) },
    removeEventListener: (name, fn) => listeners.get(name)?.delete(fn),
    dispatchEvent: event => { events.push(event.type); for (const fn of listeners.get(event.type) ?? []) fn(event) },
  }
  const ElMessage = Object.assign(() => {}, { warning: message => warnings.push(message), error: message => errors.push(message), success() {} })
  const router = { currentRoute: { value: { path: '/case/detail/7', fullPath: '/case/detail/7?tab=evidence' } }, push: target => routes.push(target) }
  const sessionExpiry = createSessionExpiry()
  sessionExpiry.reset(token)
  const context = { axios, ElMessage, router, localStorage, window, sessionExpiry, SESSION_EXPIRED_EVENT,
    CustomEvent: class { constructor(type) { this.type = type } }, performance, Date,
    downloadError() {}, isManagedHistoryRead: () => false, historyReadDiagnostic: () => null,
    isHistoryPagePayload: () => true, isTransientHistoryReadFailure: () => false, safeHistoryId: () => null }
  const source = readFileSync(new URL('../src/api/client.ts', import.meta.url), 'utf8')
    .replace(/^import .+\r?\n/gm, '').replace(/^export default client.*$/gm, '').replace(/^export /gm, '')
  const client = vm.runInNewContext(stripTypeScriptTypes(source) + ';client', context)
  client.defaults.adapter = config => new Promise((resolve, reject) => requests.push({
    config,
    ok: data => resolve({ data: { code: 0, data }, status: 200, statusText: 'OK', config, headers: {} }),
    fail: (status = 401) => reject(new axios.AxiosError('request failed', 'ERR_BAD_REQUEST', config, null,
      { status, data: { message: 'request failed' }, headers: {}, config })),
  }))
  return { token, localStorage, storage, sessionExpiry, warnings, errors, routes, events, requests, window, router, client,
    ElMessage, intervals, timeouts, listeners }
}

function layout(f, { initialUser = { username: 'first', roles: ['ADMIN'], mustChangePassword: false } } = {}) {
  const mount = [], unmount = [], identity = []
  const auth = Vue.reactive({ token: f.token, user: initialUser,
    fetchMe: () => new Promise(resolve => identity.push(user => { auth.user = user; resolve() })),
    logout() { f.sessionExpiry.reset(); auth.token = ''; auth.user = null; f.localStorage.removeItem('bureau_token') } })
  const context = { ...Vue, client: f.client, recentErrors: [], useRoute: () => ({ path: '/case/detail/7' }), useRouter: () => f.router,
    useAuthStore: () => auth, businessReadAccess, sessionExpiry: f.sessionExpiry, SESSION_EXPIRED_EVENT,
    onMounted: fn => mount.push(fn), onUnmounted: fn => unmount.push(fn), window: f.window, localStorage: f.localStorage,
    ElMessage: f.ElMessage, atob: value => Buffer.from(value, 'base64').toString('binary'), Date }
  const source = readFileSync(new URL('../src/views/MainLayout.vue', import.meta.url), 'utf8')
    .match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(source) +
    ';({refreshUnread,unread,pwdForm,fb,stopSessionTimers,startSessionTimers})', context))
  return { ...view, auth, identity, mount: () => mount.forEach(fn => fn()), unmount: () => { unmount.forEach(fn => fn()); scope.stop() } }
}

test('concurrent background 401s warn once, keep the form session and block later background requests', async () => {
  const f = fixture()
  const first = f.client.get('/bureau/messages/unread-count', { headers: { 'X-Background': '1' } }).catch(e => e)
  const second = f.client.get('/bureau/messages/unread-count', { headers: { 'x-background': '1' } }).catch(e => e)
  await tick(); assert.equal(f.requests.length, 2)
  f.requests[0].fail(); f.requests[1].fail(); await Promise.all([first, second])
  assert.equal(f.warnings.length, 1); assert.equal(f.events.filter(e => e === SESSION_EXPIRED_EVENT).length, 1)
  assert.equal(f.localStorage.getItem('bureau_token'), f.token); assert.equal(f.routes.length, 0)
  await assert.rejects(f.client.get('/bureau/messages/unread-count', { headers: { 'X-Background': '1' } }), e => e.code === 'ERR_SESSION_EXPIRED')
  assert.equal(f.requests.length, 2); assert.equal(f.warnings.length, 1); assert.equal(f.errors.length, 0)
})

test('a deliberate action after background expiration redirects once without resending the stale token', async () => {
  const f = fixture(), background = f.client.get('/count', { headers: { 'X-Background': '1' } }).catch(e => e)
  await tick(); f.requests[0].fail(); await background
  await assert.rejects(f.client.post('/bureau/cases/7/party-info', { partyName: 'draft' }), e => e.code === 'ERR_SESSION_EXPIRED')
  assert.equal(f.requests.length, 1); assert.equal(f.localStorage.getItem('bureau_token'), null)
  assert.equal(f.routes.length, 1); assert.deepEqual(JSON.parse(JSON.stringify(f.routes[0])), { path: '/login', query: { redirect: '/case/detail/7?tab=evidence' } })
  assert.equal(f.warnings.length, 1)
})

test('simultaneous foreground 401s clear the token and navigate only once', async () => {
  const f = fixture(), jobs = [f.client.get('/first').catch(e => e), f.client.get('/second').catch(e => e)]
  await tick(); f.requests.forEach(r => r.fail()); await Promise.all(jobs)
  assert.equal(f.routes.length, 1); assert.equal(f.warnings.length, 1); assert.equal(f.localStorage.getItem('bureau_token'), null)
})

test('late 401s from the prior account cannot clear or warn the new account', async () => {
  for (const background of [false, true]) {
    const f = fixture(), old = f.client.get('/old', background ? { headers: { 'X-Background': '1' } } : {}).catch(e => e)
    await tick(); const next = jwt('next'); f.sessionExpiry.reset(next); f.localStorage.setItem('bureau_token', next)
    f.requests[0].fail(); await old
    assert.equal(f.localStorage.getItem('bureau_token'), next); assert.equal(f.routes.length, 0)
    assert.equal(f.warnings.length, 0); assert.equal(f.events.length, 0)
  }
})

test('signing in again with an identical JWT still invalidates old request generations', async () => {
  const f = fixture(), old = f.client.get('/old').catch(e => e)
  await tick(); f.sessionExpiry.reset(); f.sessionExpiry.reset(f.token)
  f.requests[0].fail(); await old
  assert.equal(f.localStorage.getItem('bureau_token'), f.token); assert.equal(f.warnings.length, 0); assert.equal(f.routes.length, 0)
  const current = f.client.get('/current').catch(e => e); await tick(); f.requests[1].fail(); await current
  assert.equal(f.warnings.length, 1); assert.equal(f.routes.length, 1)
})

test('anonymous authentication failures and non-401 failures do not expire an authenticated session', async () => {
  const anonymous = fixture(''), attempt = anonymous.client.post('/auth/login', {}).catch(e => e)
  await tick(); anonymous.requests[0].fail(); await attempt
  assert.equal(anonymous.warnings.length, 0); assert.equal(anonymous.routes.length, 0)
  const f = fixture(), denied = f.client.get('/forbidden').catch(e => e)
  await tick(); f.requests[0].fail(403); await denied
  assert.equal(f.sessionExpiry.isExpired(f.sessionExpiry.capture(f.token), f.token), false)
  assert.equal(f.localStorage.getItem('bureau_token'), f.token); assert.equal(f.events.length, 0)
})

test('background expiration stops real layout timers, preserves draft content and cannot poll again', async () => {
  const f = fixture(), view = layout(f)
  view.pwdForm.newPassword = 'local unsaved value'; view.fb.content = '尚未提交的长文本'
  view.mount(); await tick(); const queuedPoll = [...f.intervals.values()][0]
  assert.equal(f.requests.length, 1); assert.equal(f.intervals.size, 1); assert.equal(f.timeouts.size, 1)
  f.requests[0].fail(); await tick()
  assert.equal(f.intervals.size, 0); assert.equal(f.timeouts.size, 0); assert.equal(f.warnings.length, 1)
  assert.equal(view.fb.content, '尚未提交的长文本'); assert.equal(view.pwdForm.newPassword, 'local unsaved value')
  assert.equal(view.auth.user.username, 'first'); assert.equal(f.routes.length, 0)
  await queuedPoll(); await view.refreshUnread(); await tick(); assert.equal(f.requests.length, 1)
  view.unmount()
})

test('layout prevents overlapping unread requests and applies only one current response', async () => {
  const f = fixture(), view = layout(f); view.mount(); await tick()
  await view.refreshUnread(); await [...f.intervals.values()][0](); await tick()
  assert.equal(f.requests.length, 1)
  f.requests[0].ok(7); await tick(); assert.equal(view.unread.value, 7)
  const next = view.refreshUnread(); await tick(); assert.equal(f.requests.length, 2)
  f.requests[1].ok(8); await next; assert.equal(view.unread.value, 8); view.unmount()
})

test('unmount clears both timer types and ignores queued warnings and unread responses', async () => {
  const f = fixture(), view = layout(f); view.mount(); await tick()
  const lateWarning = [...f.timeouts.values()][0], latePoll = [...f.intervals.values()][0]
  view.unmount(); lateWarning(); await latePoll(); f.requests[0].ok(99); await tick()
  assert.equal(f.intervals.size, 0); assert.equal(f.timeouts.size, 0); assert.equal(f.requests.length, 1)
  assert.equal(view.unread.value, 0); assert.equal(f.warnings.length, 0)
})

test('old unread responses and expiry callbacks cannot overwrite a newly signed in account', async () => {
  const f = fixture(), view = layout(f); view.mount(); await tick()
  const oldWarning = [...f.timeouts.values()][0]
  view.auth.user = null; const token = jwt('second'); f.sessionExpiry.reset(token); f.localStorage.setItem('bureau_token', token)
  view.auth.token = token; view.auth.user = { username: 'second', roles: ['HANDLER'], mustChangePassword: false }; await tick()
  assert.equal(f.requests.length, 2); oldWarning(); f.requests[1].ok(2); await tick(); f.requests[0].ok(99); await tick()
  assert.equal(view.unread.value, 2); assert.equal(f.warnings.length, 0); assert.equal(f.intervals.size, 1); view.unmount()
})

test('initial identity loading does not poll before identity and starts once when ready', async () => {
  const f = fixture(), view = layout(f, { initialUser: null }); view.mount(); await tick()
  assert.equal(f.requests.length, 0); assert.equal(f.intervals.size, 0)
  view.identity[0]({ username: 'first', roles: ['HANDLER'], mustChangePassword: false }); await tick()
  assert.equal(f.requests.length, 1); assert.equal(f.intervals.size, 1); view.unmount()
})

test('forced password changes and accounts without business roles never start the business poll', async () => {
  for (const user of [{ username: 'first', roles: ['ADMIN'], mustChangePassword: true }, { username: 'first', roles: [], mustChangePassword: false }]) {
    const f = fixture(), view = layout(f, { initialUser: user }); view.mount(); await tick()
    assert.equal(f.requests.length, 0); assert.equal(f.intervals.size, 0); view.unmount()
  }
})

test('a transient unread failure keeps the poll available for a later successful recovery', async () => {
  const f = fixture(), view = layout(f); view.mount(); await tick(); f.requests[0].fail(503); await tick()
  assert.equal(f.intervals.size, 1); assert.equal(f.events.length, 0)
  const retry = view.refreshUnread(); await tick(); f.requests[1].ok(4); await retry
  assert.equal(view.unread.value, 4); view.unmount()
})

test('a fresh sign-in after expiration restarts polling with exactly one pair of timers', async () => {
  const f = fixture(), view = layout(f); view.mount(); await tick(); f.requests[0].fail(); await tick()
  view.auth.user = null; f.sessionExpiry.reset(f.token); view.auth.user = { username: 'first', roles: ['ADMIN'], mustChangePassword: false }; await tick()
  assert.equal(f.requests.length, 2); assert.equal(f.intervals.size, 1); assert.equal(f.timeouts.size, 1)
  f.requests[1].ok(3); await tick(); assert.equal(view.unread.value, 3); view.unmount()
})

test('a late identity response cannot restore the prior account after logout or a fresh sign-in', async () => {
  for (const nextToken of ['', jwt('next-account')]) {
    const f = fixture(), pending = []
    const source = readFileSync(new URL('../src/stores/auth.ts', import.meta.url), 'utf8')
      .replace(/^import .+\r?\n/gm, '').replace(/^export /gm, '')
    const options = vm.runInNewContext(stripTypeScriptTypes(source) + ';useAuthStore', {
      defineStore: (name, options) => options, sessionExpiry: f.sessionExpiry, localStorage: f.localStorage,
      client: { get: () => new Promise(resolve => pending.push(resolve)) },
    })
    const state = { token: f.token, user: { username: 'old' } }
    const job = options.actions.fetchMe.call(state)
    f.sessionExpiry.reset(nextToken); state.token = nextToken
    if (nextToken) f.localStorage.setItem('bureau_token', nextToken)
    else f.localStorage.removeItem('bureau_token')
    state.user = nextToken ? { username: 'next-account' } : null
    pending[0]({ data: { data: { username: 'old', roles: ['ADMIN'] } } }); await job
    assert.equal(state.user?.username ?? null, nextToken ? 'next-account' : null)
  }
})
