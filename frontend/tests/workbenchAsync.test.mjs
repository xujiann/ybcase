import nodeTest from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'

const test = (name, run) => nodeTest(name, { timeout: 2500 }, run)
const tick = () => new Promise(setImmediate)
const data = marker => ({ myCases: [{ id: marker, case_no: 'WB' + marker }], myClues: [{ id: marker }], myApplications: [], pendingForMe: marker, unread: marker })
function fixture(t) {
  const reads = [], mounts = [], unmounts = [], listeners = new Map()
  const auth = Vue.reactive({ token: 'first-token', user: { id: 1, username: 'first-user', roles: ['HANDLER'] } })
  const sessionExpiry = createSessionExpiry(); sessionExpiry.reset(auth.token)
  const client = { get: url => new Promise((resolve, reject) => reads.push({ url, resolve: value => resolve({ data: { data: value } }), reject })) }
  const source = readFileSync(new URL('../src/views/case/MyWorkbenchView.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script) + ';({ d,loading,failed:typeof failed === "undefined" ? { value:false } : failed,load:typeof load === "undefined" ? undefined : load,expireView:typeof onSessionExpired === "undefined" ? ()=>{} : onSessionExpired })', {
    ...Vue, client, useAuthStore: () => auth, sessionExpiry, SESSION_EXPIRED_EVENT,
    CASE_STATUS: {}, EXECUTION_DEADLINE_STATE: {},
    onMounted: fn => mounts.push(fn), onUnmounted: fn => unmounts.push(fn),
    window: { addEventListener: (event, fn) => listeners.set(event, fn), removeEventListener: event => listeners.delete(event) },
  }))
  let stopped = false
  const f = { ...view, reads, auth, sessionExpiry,
    initialize: () => Promise.all(mounts.map(fn => fn())),
    switchUser() { sessionExpiry.reset('second-token'); auth.token = 'second-token'; auth.user = { id: 2, username: 'second-user', roles: ['LEGAL'] } },
    expire() { sessionExpiry.expire(sessionExpiry.capture(auth.token), auth.token); view.expireView() },
    unmount() { if (!stopped) { stopped = true; unmounts.forEach(fn => fn()); scope.stop() } },
  }
  t.after(f.unmount); return f
}

test('workbench: normal mounted response retains the existing aggregate fields', async t => {
  const f = fixture(t), job = f.initialize(); assert.equal(f.reads[0].url, '/bureau/my/workbench')
  f.reads[0].resolve(data(7)); await job; await tick()
  assert.equal(f.d.value.myCases[0].id, 7); assert.equal(f.d.value.unread, 7); assert.equal(f.loading.value, false)
})
test('workbench: a previous user response cannot display another user case or clue', async t => {
  const f = fixture(t), job = f.initialize(); f.switchUser(); f.reads[0].resolve(data(1)); await job; await tick()
  assert.equal(f.d.value.myCases?.length || 0, 0); assert.equal(f.d.value.myClues?.length || 0, 0)
})
test('workbench: changing accounts immediately clears already displayed private aggregates', async t => {
  const f = fixture(t), job = f.initialize(); f.reads[0].resolve(data(1)); await job; await tick()
  f.switchUser(); await tick(); assert.equal(f.d.value.myCases?.length || 0, 0); assert.equal(f.d.value.pendingForMe || 0, 0)
})
test('workbench: changing the same user role invalidates an in-flight supervisory count', async t => {
  const f = fixture(t); f.auth.user.roles = ['LEADER']; const job = f.initialize()
  f.auth.user.roles = ['HANDLER']; f.reads[0].resolve(data(15)); await job; await tick()
  assert.equal(f.d.value.pendingForMe || 0, 0); assert.equal(f.loading.value, false)
})
test('workbench: replacing only the token rejects the old response', async t => {
  const f = fixture(t), job = f.initialize(); f.auth.token = 'replacement-token'
  f.reads[0].resolve(data(1)); await job; await tick(); assert.equal(f.d.value.myCases?.length || 0, 0)
})
test('workbench: a reset session with the same token rejects the old response', async t => {
  const f = fixture(t), job = f.initialize(); f.sessionExpiry.reset(f.auth.token)
  f.reads[0].resolve(data(1)); await job; await tick(); assert.equal(f.d.value.myCases?.length || 0, 0); assert.equal(f.loading.value, false)
})
test('workbench: expiry clears visible data and rejects its late response', async t => {
  const f = fixture(t), job = f.initialize(); f.expire(); f.reads[0].resolve(data(1)); await job; await tick()
  assert.equal(f.d.value.myCases?.length || 0, 0); assert.equal(f.loading.value, false)
})
test('workbench: unmount rejects a late successful request', async t => {
  const f = fixture(t), job = f.initialize(); f.unmount(); f.reads[0].resolve(data(1)); await job; await tick()
  assert.equal(f.d.value.myCases?.length || 0, 0)
})
test('workbench: an already expired view cannot start a new aggregate request', async t => {
  const f = fixture(t); f.expire(); const job = f.initialize(); const count = f.reads.length
  f.reads.forEach(r => r.resolve(data(1))); await job; assert.equal(count, 0)
})
test('workbench: a failed aggregate load is distinct from zero work items', async t => {
  const f = fixture(t), job = f.initialize(); f.reads[0].reject(new Error('offline')); await job.catch(() => {}); await tick()
  assert.equal(f.failed.value, true); assert.equal(f.loading.value, false); assert.equal(f.d.value.myCases?.length || 0, 0)
})
test('workbench: a later load retains its spinner when an old request fails', async t => {
  const f = fixture(t); assert.equal(typeof f.load, 'function')
  const first = f.load(), second = f.load(); f.reads[0].reject(new Error('old offline')); await first.catch(() => {})
  assert.equal(f.loading.value, true); f.reads[1].resolve(data(2)); await second; assert.equal(f.loading.value, false)
})
test('workbench: only the latest reload can populate the aggregate', async t => {
  const f = fixture(t); assert.equal(typeof f.load, 'function')
  const first = f.load(), second = f.load(); f.reads[1].resolve(data(2)); await second
  f.reads[0].resolve(data(1)); await first; assert.equal(f.d.value.myCases[0].id, 2)
})
