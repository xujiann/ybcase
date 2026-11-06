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
const holiday = (year = 2026) => ({ day: `${year}-01-01`, kind: 'HOLIDAY', name: `${year}元旦` })
const template = (type = 'A') => ({ doc_type: type, title_tpl: `${type}标题`, content_tpl: `${type}正文` })

function fixture(t, kind) {
  const reads = [], writes = [], successes = [], unmounts = [], mounts = []
  const auth = Vue.reactive({ token: 'one', user: { id: 1, username: 'admin-one', roles: ['ADMIN'] } })
  const sessionExpiry = createSessionExpiry(); sessionExpiry.reset(auth.token)
  const client = {
    get: (url, config) => new Promise((resolve, reject) => reads.push({ url, config,
      resolve(data) { this.done = true; resolve({ data: { data } }) }, reject(error) { this.done = true; reject(error) } })),
    put: (url, body) => new Promise((resolve, reject) => writes.push({ url, body: plain(body), resolve, reject })),
    post: (url, body) => new Promise((resolve, reject) => writes.push({ url, body: plain(body), resolve, reject })),
    delete: url => new Promise((resolve, reject) => writes.push({ url, resolve, reject })),
  }
  const file = kind === 'holidays' ? 'HolidaysView.vue' : 'TemplatesView.vue'
  const source = readFileSync(new URL('../src/views/system/' + file, import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const exposed = kind === 'holidays' ? 'load,onAdd,onDelete,onBatch,rows,loading,year,missingYears,form,batchVisible,batchText,batchError,batchSubmitting'
    : 'load,openEdit,onSave,openHistory,clearHistory,rows,loading,listFailed,form,saving,editVisible,history,historyVisible,historyType'
  const scope = Vue.effectScope(), listeners = new Map()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script) + ';({' + exposed + ',expireView:typeof onSessionExpired === "function" ? onSessionExpired : ()=>{}})', {
    ...Vue, client, useAuthStore: () => auth, adminOperationAccess, sessionExpiry, SESSION_EXPIRED_EVENT, createResourceLoader, resourceState,
    window: { addEventListener: (event, fn) => listeners.set(event, fn), removeEventListener: event => listeners.delete(event) },
    onMounted: fn => mounts.push(fn), onUnmounted: fn => unmounts.push(fn),
    ElMessage: { success: message => successes.push(message), warning() {} },
  }))
  let stopped = false
  const f = { ...view, auth, sessionExpiry, reads, writes, successes,
    initialize: () => Promise.all(mounts.map(fn => fn())),
    switchUser() { sessionExpiry.reset('two'); auth.token = 'two'; auth.user = { id: 3, username: 'admin-two', roles: ['ADMIN'] } },
    expire() { sessionExpiry.expire(sessionExpiry.capture(auth.token), auth.token); view.expireView() },
    unmount() { if (!stopped) { stopped = true; unmounts.forEach(fn => fn()); scope.stop() } },
  }
  t.after(f.unmount)
  return f
}
// Resolve the actual requested year and settle both sequential and parallel readers.
async function finishReads(f, job, allowed = () => true) {
  for (let i = 0; i < 6; i++) {
    for (const read of f.reads.filter(read => !read.done && allowed(read)))
      read.resolve(read.url.endsWith('/coverage') ? { missingYears: [] }
        : read.url === '/bureau/holidays' ? [holiday(read.config.params.year)] : [template()])
    await tick()
  }
  if (job) await job
}
async function ready(f, kind) { const job = f.load(); await finishReads(f, job) }

for (const kind of ['holidays', 'templates']) {
  test(kind + ': newest list wins after reversed responses', async t => {
    const f = fixture(t, kind)
    if (kind === 'holidays') f.year.value = 2026
    const first = f.load()
    if (kind === 'holidays') f.year.value = 2027
    const second = f.load()
    const lists = f.reads.filter(read => !read.url.endsWith('/coverage'))
    lists[1].resolve(kind === 'holidays' ? [holiday(2027)] : [template('B')])
    await finishReads(f, second, read => read !== lists[0])
    lists[0].resolve(kind === 'holidays' ? [holiday(2026)] : [template('A')])
    await finishReads(f, first)
    assert.equal(kind === 'holidays' ? f.rows.value[0].day : f.rows.value[0].doc_type, kind === 'holidays' ? '2027-01-01' : 'B')
  })
  test(kind + ': old failure cannot release a newer list spinner', async t => {
    const f = fixture(t, kind), first = f.load().catch(() => {}), second = f.load()
    f.reads[0].reject(new Error('old offline')); await first
    assert.equal(f.loading.value, true)
    await finishReads(f, second)
  })
  test(kind + ': latest failure removes old actionable rows', async t => {
    const f = fixture(t, kind); await ready(f, kind)
    const failed = f.load(); f.reads.findLast(read => !read.done && !read.url.endsWith('/coverage')).reject(new Error('offline'))
    await finishReads(f, failed.catch(() => {})); assert.equal(f.rows.value.length, 0); assert.equal(f.loading.value, false)
  })
  for (const change of ['account', 'roles', 'token-generation', 'expiry', 'unmount']) {
    test(kind + ': late list cannot survive ' + change, async t => {
      const f = fixture(t, kind), job = f.load()
      if (change === 'account') f.switchUser()
      if (change === 'roles') f.auth.user.roles = ['HANDLER']
      if (change === 'token-generation') f.sessionExpiry.reset(f.auth.token)
      if (change === 'expiry') f.expire()
      if (change === 'unmount') f.unmount()
      await finishReads(f, job); assert.equal(f.rows.value.length, 0)
    })
  }
  test(kind + ': authenticated read-only roles retain list access and cannot mutate', async t => {
    const f = fixture(t, kind); f.auth.user.roles = ['HANDLER']; await ready(f, kind)
    assert.equal(f.rows.value.length, 1)
    if (kind === 'holidays') { Object.assign(f.form, holiday()); await f.onAdd(); await f.onDelete(holiday()); f.batchText.value = '2026-01-01,放假,元旦'; await f.onBatch() }
    else { f.openEdit(template()); await f.onSave() }
    assert.equal(f.writes.length, 0)
  })
}

test('holidays: rows are committed only with successful coverage', async t => {
  const f = fixture(t, 'holidays'); const job = f.load()
  f.reads[0].resolve([holiday()]); await tick()
  assert.equal(f.rows.value.length, 0)
  f.reads.find(read => !read.done && read.url.endsWith('/coverage')).reject(new Error('coverage offline'))
  await job.catch(() => {}); assert.equal(f.rows.value.length, 0); assert.equal(f.loading.value, false)
})
test('holidays: adding is single-flight and snapshots form values', async t => {
  const f = fixture(t, 'holidays'); Object.assign(f.form, holiday())
  const first = f.onAdd(), second = f.onAdd(); assert.equal(f.writes.length, 1)
  f.form.day = '2027-01-01'; assert.equal(f.writes[0].body.day, '2026-01-01')
  f.writes[0].resolve({}); await finishReads(f, Promise.all([first, second]))
  assert.equal(f.successes.length, 0); assert.equal(f.form.day, '2027-01-01')
})
test('holidays: add response cannot move a newly selected year', async t => {
  const f = fixture(t, 'holidays'); Object.assign(f.form, holiday(2026)); f.year.value = 2026
  const job = f.onAdd(); f.year.value = 2027; f.writes[0].resolve({}); await finishReads(f, job)
  assert.equal(f.year.value, 2027)
})
test('holidays: batch submit is single-flight', async t => {
  const f = fixture(t, 'holidays'); f.batchVisible.value = true; f.batchText.value = '2026-01-01,放假,元旦'
  const first = f.onBatch(), second = f.onBatch(); assert.equal(f.writes.length, 1)
  f.writes[0].resolve({ data: { data: { count: 1 } } }); await finishReads(f, Promise.all([first, second])); assert.equal(f.successes.length, 1)
})
test('holidays: late batch result preserves a newer textarea draft', async t => {
  const f = fixture(t, 'holidays'); f.batchVisible.value = true; f.batchText.value = '2026-01-01,放假,元旦'
  const job = f.onBatch(); f.batchText.value = '2027-01-01,放假,下一年'
  f.writes[0].resolve({ data: { data: { count: 1 } } }); await finishReads(f, job)
  assert.equal(f.batchText.value, '2027-01-01,放假,下一年'); assert.equal(f.batchVisible.value, true); assert.equal(f.successes.length, 0)
})
test('holidays: a closed and reopened batch dialog ignores the old completion', async t => {
  const f = fixture(t, 'holidays'); f.batchVisible.value = true; f.batchText.value = '2026-01-01,放假,元旦'
  const job = f.onBatch(); f.batchVisible.value = false; f.batchVisible.value = true
  f.writes[0].resolve({ data: { data: { count: 1 } } }); await finishReads(f, job)
  assert.equal(f.batchVisible.value, true); assert.equal(f.batchText.value, '2026-01-01,放假,元旦'); assert.equal(f.successes.length, 0)
})
test('holidays: duplicate deletion remains locked through refreshed rows', async t => {
  const f = fixture(t, 'holidays'); await ready(f, 'holidays'); const row = f.rows.value[0]
  const first = f.onDelete(row), second = f.onDelete(row); assert.equal(f.writes.length, 1)
  f.writes[0].resolve({}); await tick(); const third = f.onDelete(row); assert.equal(f.writes.length, 1)
  await finishReads(f, Promise.all([first, second, third]))
})
for (const action of ['add', 'batch', 'delete']) {
  test('holidays: late ' + action + ' cannot refresh or toast into another account', async t => {
    const f = fixture(t, 'holidays'); await ready(f, 'holidays'); Object.assign(f.form, holiday()); f.batchVisible.value = true; f.batchText.value = '2026-01-01,放假,元旦'
    const job = action === 'add' ? f.onAdd() : action === 'batch' ? f.onBatch() : f.onDelete(f.rows.value[0])
    f.switchUser(); const before = f.reads.length; f.writes[0].resolve({ data: { data: { count: 1 } } }); await finishReads(f, job)
    assert.equal(f.reads.length, before); assert.equal(f.successes.length, 0)
  })
}
test('holidays: expired session cannot initiate any read or holiday mutation', async t => {
  const f = fixture(t, 'holidays'); Object.assign(f.form, holiday()); f.batchText.value = '2026-01-01,放假,元旦'; f.expire()
  const jobs = [f.load(), f.onAdd(), f.onDelete(holiday()), f.onBatch()]
  assert.equal(f.reads.length, 0); assert.equal(f.writes.length, 0); await Promise.all(jobs)
})

for (const change of ['account', 'roles', 'token-generation', 'expiry', 'unmount']) {
  test('templates: history result cannot survive ' + change, async t => {
    const f = fixture(t, 'templates'), job = f.openHistory(template())
    if (change === 'account') f.switchUser()
    if (change === 'roles') f.auth.user.roles = ['HANDLER']
    if (change === 'token-generation') f.sessionExpiry.reset(f.auth.token)
    if (change === 'expiry') f.expire()
    if (change === 'unmount') f.unmount()
    f.reads[0].resolve([{ id: 8, content_tpl: 'old-account-content' }]); await job; assert.equal(f.history.data.length, 0)
  })
}
test('templates: changed pending draft is not claimed saved or closed', async t => {
  const f = fixture(t, 'templates'); f.openEdit(template()); const job = f.onSave(); f.form.contentTpl = '下一份草稿'
  f.writes[0].resolve({}); await finishReads(f, job)
  assert.equal(f.editVisible.value, true); assert.equal(f.form.contentTpl, '下一份草稿'); assert.equal(f.successes.length, 0)
})
test('templates: closed and reopened editor ignores old completion', async t => {
  const f = fixture(t, 'templates'); f.openEdit(template()); const job = f.onSave()
  f.editVisible.value = false; f.openEdit(template('B')); f.writes[0].resolve({}); await finishReads(f, job)
  assert.equal(f.editVisible.value, true); assert.equal(f.form.docType, 'B'); assert.equal(f.successes.length, 0)
})
test('templates: old save cannot close or refresh the next administrator editor', async t => {
  const f = fixture(t, 'templates'); f.openEdit(template()); const job = f.onSave(); f.switchUser(); f.openEdit(template('B'))
  f.writes[0].resolve({}); await finishReads(f, job)
  assert.equal(f.editVisible.value, true); assert.equal(f.form.docType, 'B'); assert.equal(f.successes.length, 0); assert.equal(f.reads.length, 0)
})
test('templates: expired session cannot reopen history, editor, or save', async t => {
  const f = fixture(t, 'templates'); f.expire(); f.openEdit(template()); const jobs = [f.onSave(), f.openHistory(template()), f.load()]
  assert.equal(f.reads.length, 0); assert.equal(f.writes.length, 0); assert.equal(f.editVisible.value, false); await Promise.all(jobs)
})
