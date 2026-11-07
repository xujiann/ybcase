import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { adminOperationAccess } from '../src/api/caseWriteAccess.ts'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
import { isHistoryPagePayload } from '../src/api/historyRead.ts'

const plain = value => JSON.parse(JSON.stringify(value))
const log = id => ({ id, username: 'operator', path: '/api/bureau/stats/interviews/download' })

function fixture(t) {
  const auth = Vue.reactive({ token: 'one', user: { id: 1, username: 'admin', roles: ['ADMIN'] } })
  const sessionExpiry = createSessionExpiry()
  sessionExpiry.reset(auth.token)
  const requests = [], events = [], mounts = [], unmounts = [], listeners = new Map()
  const client = { get: (url, { params }) => new Promise((resolve, reject) => requests.push({
    url, params: plain(params), reject, resolve: data => resolve({ data: { data } }),
  })) }
  const source = readFileSync(new URL('../src/components/AuditLogDrawer.vue', import.meta.url), 'utf8')
    .match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(source)
    + ';({filters,rows,total,page,searched,loading,error,search,changePage,close})', {
    ...Vue, client, useAuthStore: () => auth, adminOperationAccess, isHistoryPagePayload,
    defineEmits: () => event => events.push(event), sessionExpiry, SESSION_EXPIRED_EVENT,
    onMounted: fn => mounts.push(fn), onUnmounted: fn => unmounts.push(fn),
    window: { addEventListener: (event, fn) => listeners.set(event, fn), removeEventListener: event => listeners.delete(event) },
  }))
  t.after(() => { unmounts.forEach(fn => fn()); scope.stop() })
  function reply(index, id, total = 25) {
    const request = requests[index]
    request.resolve({ rows: [log(id)], total, page: request.params.page, size: 20 })
  }
  return { ...view, auth, requests, events, reply,
    mount: () => mounts.forEach(fn => fn()),
    expire: () => listeners.get(SESSION_EXPIRED_EVENT)(),
  }
}

test('audit query validates calendar dates and rejects a reversed range without a request', async t => {
  const f = fixture(t)
  f.filters.from = '2026-02-29'
  await f.search()
  assert.equal(f.requests.length, 0)
  assert.match(f.error.value, /有效的起止日期/)
  f.filters.from = '2026-11-09'; f.filters.to = '2026-11-08'
  await f.search()
  assert.equal(f.requests.length, 0)
  assert.match(f.error.value, /结束日期不得早于开始日期/)
})

test('audit query pins the first page maxId through all pages and resets it on a fresh search', async t => {
  const f = fixture(t)
  Object.assign(f.filters, { username: ' clerk ', from: '2026-11-01', to: '2026-11-07',
    path: '/stats/', requestId: 'audit-123', reportType: 'ALL_REPORTS' })
  const first = f.search()
  const filters = { username: 'clerk', from: '2026-11-01', to: '2026-11-07',
    path: '/stats/', requestId: 'audit-123', reportType: 'ALL_REPORTS' }
  assert.equal(f.requests[0].url, '/audit/logs')
  assert.deepEqual(f.requests[0].params, { ...filters, page: 1, size: 20 })
  f.reply(0, 100); await first
  const second = f.changePage(2)
  assert.deepEqual(f.requests[1].params, { ...filters, page: 2, size: 20, maxId: 100 })
  f.reply(1, 75); await second
  const back = f.changePage(1)
  assert.deepEqual(f.requests[2].params, { ...filters, page: 1, size: 20, maxId: 100 })
  f.reply(2, 100); await back
  const fresh = f.search()
  assert.deepEqual(f.requests[3].params, { ...filters, page: 1, size: 20 })
  f.reply(3, 104); await fresh
})

test('changing audit filters clears results and rejects in-flight data from the previous search', async t => {
  const f = fixture(t)
  const first = f.search()
  f.reply(0, 100); await first
  const stale = f.changePage(2)
  f.filters.reportType = 'INTERVIEWS'
  assert.equal(f.rows.value.length, 0)
  assert.equal(f.total.value, 0)
  assert.equal(f.searched.value, false)
  await f.changePage(2)
  assert.equal(f.requests.length, 2)
  const current = f.search()
  f.reply(2, 120); await current
  f.reply(1, 75); await stale
  assert.equal(f.page.value, 1)
  assert.equal(f.rows.value[0].id, 120)
  assert.equal(f.requests[2].params.reportType, 'INTERVIEWS')
  assert.equal(f.requests[2].params.maxId, undefined)
})

test('audit close and session expiry clear data and do not expose late responses', async t => {
  for (const dismiss of ['close', 'expire']) {
    const f = fixture(t)
    f.mount()
    f.filters.requestId = 'private-id'
    const pending = f.search()
    assert.equal(f.requests.length, 2)
    if (dismiss === 'close') f.close(false)
    else f.expire()
    f.reply(1, 99); await pending
    f.reply(0, 100)
    await new Promise(setImmediate)
    assert.equal(f.rows.value.length, 0)
    assert.equal(f.filters.requestId, '')
    assert.deepEqual(f.events, ['close'])
  }
})

test('audit drawer refuses queries once administrator access is lost', async t => {
  const f = fixture(t)
  const first = f.search()
  f.auth.user.roles = ['LEGAL']
  f.reply(0, 100); await first
  await f.search()
  assert.equal(f.rows.value.length, 0)
  assert.equal(f.requests.length, 1)
  assert.deepEqual(f.events, ['close'])
})