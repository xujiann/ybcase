import nodeTest from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
import { adminOperationAccess } from '../src/api/caseWriteAccess.ts'
import { createResourceLoader, resourceState } from '../src/api/loadableResource.ts'

const test = (name, run) => nodeTest(name, { timeout: 2500 }, run)
const tick = () => new Promise(setImmediate)
const plain = value => JSON.parse(JSON.stringify(value))
const dept = (id = 2) => ({ id, parentId: 1, name: '科室' + id, code: 'dept' + id, type: 'CLINICAL', sortNo: 0, enabled: true })
const setting = (key = 'clue_days', value = '15') => ({ key, value, remark: '参数' })

function fixture(t, kind) {
  const reads = [], writes = [], successes = [], downloads = [], unmounts = [], mounts = []
  const auth = Vue.reactive({ token: 'one', user: { id: 1, username: 'admin-one', roles: ['ADMIN'] } })
  const sessionExpiry = createSessionExpiry(); sessionExpiry.reset(auth.token)
  const client = {
    get: (url, config) => new Promise((resolve, reject) => reads.push({ url, config,
      resolve: data => resolve({ data: { data } }), resolveRaw: data => resolve({ data }), reject })),
    put: (url, body, config) => new Promise((resolve, reject) => writes.push({ url, body: body == null ? body : plain(body), config, resolve, reject })),
    post: (url, body) => new Promise((resolve, reject) => writes.push({ url, body: plain(body), resolve, reject })),
  }
  const file = kind === 'settings' ? 'SettingsView.vue' : 'DeptsView.vue'
  const source = readFileSync(new URL('../src/views/system/' + file, import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const exposed = kind === 'settings' ? 'load,onSave,onHistory,clearHistory,onAuditExport,rows,loading,history,historyVisible,historyKey,auditMonth'
    : 'load,openCreate,openEdit,save,toggleEnabled,records,loading,saving,dialogVisible,form'
  const scope = Vue.effectScope(), listeners = new Map()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script) + ';({' + exposed + ',expireView:typeof onSessionExpired === "function" ? onSessionExpired : ()=>{}})', {
    ...Vue, client, useAuthStore: () => auth, adminOperationAccess, sessionExpiry, SESSION_EXPIRED_EVENT, createResourceLoader, resourceState,
    window: { addEventListener: (event, fn) => listeners.set(event, fn), removeEventListener: event => listeners.delete(event) },
    onMounted: fn => mounts.push(fn), onUnmounted: fn => unmounts.push(fn),
    ElMessage: { success: message => successes.push(message), warning() {} },
    document: { createElement: () => ({ href: '', download: '', click() { downloads.push({ href: this.href, name: this.download }) } }) },
    URL: { createObjectURL: () => 'blob:download', revokeObjectURL() {} },
  }))
  let stopped = false
  const f = { ...view, auth, sessionExpiry, reads, writes, successes, downloads,
    initialize: () => Promise.all(mounts.map(fn => fn())),
    switchUser() { sessionExpiry.reset('two'); auth.token = 'two'; auth.user = { id: 3, username: 'admin-two', roles: ['ADMIN'] } },
    expire() { sessionExpiry.expire(sessionExpiry.capture(auth.token), auth.token); view.expireView() },
    unmount() { if (!stopped) { stopped = true; unmounts.forEach(fn => fn()); scope.stop() } },
  }
  t.after(f.unmount)
  return f
}
async function ready(f, kind, data) {
  const job = f.load(); f.reads.at(-1).resolve(data || (kind === 'settings' ? [setting()] : [dept()])); await job
}
async function finish(f, job, data = [dept()]) {
  await tick(); for (const read of f.reads) read.resolve(data); await job
}

for (const kind of ['settings', 'depts']) {
  const state = f => kind === 'settings' ? f.rows : f.records
  const row = marker => kind === 'settings' ? setting('key-' + marker) : dept(marker)
  const marker = value => kind === 'settings' ? value.key : value.id
  test(kind + ': latest list wins after reversed responses', async t => {
    const f = fixture(t, kind), first = f.load(), second = f.load()
    f.reads[1].resolve([row(2)]); await second; f.reads[0].resolve([row(1)]); await first
    assert.equal(marker(state(f).value[0]), marker(row(2)))
  })
  test(kind + ': an old failure cannot release the newer spinner', async t => {
    const f = fixture(t, kind), first = f.load(), second = f.load()
    f.reads[0].reject(new Error('old offline')); await first.catch(() => {})
    assert.equal(f.loading.value, true); f.reads[1].resolve([row(2)]); await second
  })
  test(kind + ': latest failure clears old actionable rows', async t => {
    const f = fixture(t, kind); await ready(f, kind)
    const job = f.load(); f.reads.at(-1).reject(new Error('offline')); await job.catch(() => {})
    assert.equal(state(f).value.length, 0); assert.equal(f.loading.value, false)
  })
  test(kind + ': a late list cannot cross administrators', async t => {
    const f = fixture(t, kind), old = f.load(); f.switchUser(); const current = f.load()
    f.reads[1].resolve([row(2)]); await current; f.reads[0].resolve([row(1)]); await old
    assert.equal(marker(state(f).value[0]), marker(row(2)))
  })
  test(kind + ': same JWT new session generation ignores an old list', async t => {
    const f = fixture(t, kind), old = f.load(); f.sessionExpiry.reset(f.auth.token)
    f.reads[0].resolve([row(1)]); await old; assert.equal(state(f).value.length, 0); assert.equal(f.loading.value, false)
  })
  test(kind + ': expiry and unmount reject late list results', async t => {
    for (const dispose of [false, true]) {
      const f = fixture(t, kind), old = f.load(); dispose ? f.unmount() : f.expire()
      f.reads[0].resolve([row(1)]); await old; assert.equal(state(f).value.length, 0)
    }
  })
}

test('settings: one parameter cannot be submitted twice while its write is pending', async t => {
  const f = fixture(t, 'settings'); await ready(f, 'settings'); const row = f.rows.value[0]
  row.value = '20'; const first = f.onSave(row), repeated = f.onSave(row)
  assert.equal(f.writes.length, 1); f.writes[0].resolve({}); await Promise.all([first, repeated])
  assert.equal(f.successes.length, 1)
})
test('settings: independent parameter writes remain supported', async t => {
  const f = fixture(t, 'settings'); await ready(f, 'settings', [setting('first'), setting('second')])
  const first = f.onSave(f.rows.value[0]), second = f.onSave(f.rows.value[1]); assert.equal(f.writes.length, 2)
  f.writes.forEach(w => w.resolve({})); await Promise.all([first, second]); assert.equal(f.successes.length, 2)
})
test('settings: successful response does not claim a newer edited draft was saved', async t => {
  const f = fixture(t, 'settings'); await ready(f, 'settings'); const row = f.rows.value[0]
  row.value = '20'; const job = f.onSave(row); row.value = '25'; f.writes[0].resolve({}); await job
  assert.equal(row.value, '25'); assert.equal(f.successes.length, 0)
})
test('settings: a removed old list row cannot write after refresh', async t => {
  const f = fixture(t, 'settings'); await ready(f, 'settings'); const old = f.rows.value[0]
  await ready(f, 'settings', [setting('clue_days', '30')]); const job = f.onSave(old); assert.equal(f.writes.length, 0); await job
})
test('settings: late save cannot toast into a different administrator session', async t => {
  const f = fixture(t, 'settings'); await ready(f, 'settings'); const job = f.onSave(f.rows.value[0])
  f.switchUser(); f.writes[0].resolve({}); await job; assert.equal(f.successes.length, 0)
})
test('settings: an expired view does not issue a new setting write, history or export', async t => {
  const f = fixture(t, 'settings'); await ready(f, 'settings'); const old = f.rows.value[0]; f.expire()
  const jobs = [f.onSave(old), f.onHistory(old), f.onAuditExport()]
  assert.equal(f.writes.length, 0); assert.equal(f.reads.length, 1); await Promise.all(jobs)
})
test('settings: changing accounts clears an open config history and ignores its late result', async t => {
  const f = fixture(t, 'settings'), job = f.onHistory(setting()); f.switchUser()
  f.reads[0].resolve([{ changed_by: 'old-admin' }]); await job
  assert.equal(f.history.data.length, 0); assert.equal(f.historyVisible.value, false)
})
test('settings: same JWT new session generation ignores old history', async t => {
  const f = fixture(t, 'settings'), job = f.onHistory(setting()); f.sessionExpiry.reset(f.auth.token)
  f.reads[0].resolve([{ changed_by: 'old-admin' }]); await job; assert.equal(f.history.data.length, 0)
})
test('settings: closing history retains the existing late-response invalidation', async t => {
  const f = fixture(t, 'settings'), job = f.onHistory(setting()); f.clearHistory()
  f.reads[0].resolve([{ changed_by: 'old-admin' }]); await job; assert.equal(f.history.data.length, 0)
})
test('settings: history failure is shown and reopening recovers', async t => {
  const f = fixture(t, 'settings'), bad = f.onHistory(setting()); f.reads[0].reject(new Error('offline')); await bad
  assert.equal(f.history.failed, true); const good = f.onHistory(setting('other'))
  f.reads[1].resolve([{ changed_by: 'current-admin' }]); await good; assert.equal(f.history.failed, false); assert.equal(f.history.data.length, 1)
})
test('settings: audit export is single-flight and uses the requested month', async t => {
  const f = fixture(t, 'settings'); f.auditMonth.value = '2026-09'; const first = f.onAuditExport(), second = f.onAuditExport()
  assert.equal(f.reads.length, 1); f.auditMonth.value = '2026-10'; f.reads[0].resolveRaw('csv'); await Promise.all([first, second])
  assert.equal(f.downloads.length, 1); assert.equal(f.downloads[0].name, 'audit-2026-09.csv')
})
test('settings: late audit export cannot download into another session', async t => {
  const f = fixture(t, 'settings'), job = f.onAuditExport(); f.switchUser(); f.reads[0].resolveRaw('sensitive-csv'); await job
  assert.equal(f.downloads.length, 0); assert.equal(f.successes.length, 0)
})

test('depts: duplicate save submits only one creation', async t => {
  const f = fixture(t, 'depts'); await ready(f, 'depts'); f.openCreate(); f.form.name = '新科室'; f.form.code = 'new'
  const first = f.save(), second = f.save(); assert.equal(f.writes.length, 1)
  f.writes[0].resolve({}); await finish(f, Promise.all([first, second])); assert.equal(f.successes.length, 1)
})
test('depts: changed in-flight draft is retained instead of being closed as saved', async t => {
  const f = fixture(t, 'depts'); await ready(f, 'depts'); f.openEdit(dept()); const job = f.save(); f.form.name = '未提交新名称'
  f.writes[0].resolve({}); await finish(f, job)
  assert.equal(f.dialogVisible.value, true); assert.equal(f.form.name, '未提交新名称'); assert.equal(f.successes.length, 0)
})
test('depts: a late closed form save cannot close a newly opened draft', async t => {
  const f = fixture(t, 'depts'); await ready(f, 'depts'); f.openEdit(dept()); const job = f.save()
  f.dialogVisible.value = false; f.openCreate(); f.form.name = '下一份草稿'; f.writes[0].resolve({}); await finish(f, job)
  assert.equal(f.dialogVisible.value, true); assert.equal(f.form.id, null); assert.equal(f.form.name, '下一份草稿'); assert.equal(f.successes.length, 0)
})
test('depts: late save does not close the next administrator form or trigger a refresh', async t => {
  const f = fixture(t, 'depts'); await ready(f, 'depts'); f.openEdit(dept()); const job = f.save(); f.switchUser()
  await ready(f, 'depts'); f.openCreate(); f.form.name = '新会话草稿'; const requests = f.reads.length
  f.writes[0].resolve({}); await finish(f, job)
  assert.equal(f.reads.length, requests); assert.equal(f.dialogVisible.value, true); assert.equal(f.form.name, '新会话草稿'); assert.equal(f.successes.length, 0)
})
test('depts: duplicate enable request remains locked through its refresh', async t => {
  const f = fixture(t, 'depts'); await ready(f, 'depts'); const row = f.records.value[0]
  const first = f.toggleEnabled(row), second = f.toggleEnabled(row); assert.equal(f.writes.length, 1)
  f.writes[0].resolve({}); await tick(); const third = f.toggleEnabled(row); assert.equal(f.writes.length, 1)
  await finish(f, Promise.all([first, second, third]), [{ ...dept(), enabled: false }]); assert.equal(f.records.value[0].enabled, false)
})
test('depts: expired session cannot submit a new form write or enabled change', async t => {
  const f = fixture(t, 'depts'); await ready(f, 'depts'); f.openEdit(dept()); f.expire()
  const first = f.save(), second = f.toggleEnabled(dept()); assert.equal(f.writes.length, 0); await Promise.all([first, second])
})
test('depts: read-only users can still load the existing department list', async t => {
  const f = fixture(t, 'depts'); f.auth.user.roles = ['HANDLER']; await ready(f, 'depts')
  assert.equal(f.records.value.length, 1); f.openEdit(dept()); await f.toggleEnabled(dept()); assert.equal(f.writes.length, 0)
})
