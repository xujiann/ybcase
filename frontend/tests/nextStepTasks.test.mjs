import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'

function fixture(t, domain = 'bureau') {
  const auth = Vue.reactive({ token: 'one', user: {
    id: 1, accountCategory: domain === 'agency' ? 'AGENCY' : 'DISTRICT',
    roles: domain === 'agency' ? ['AGENCY'] : ['HANDLER'],
  } })
  const sessionExpiry = createSessionExpiry(); sessionExpiry.reset(auth.token)
  const requests = [], routes = [], unmounts = [], listeners = new Map()
  const props = Vue.reactive({ domain, refreshKey: undefined })
  const client = { get: (path, config) => new Promise((resolve, reject) => requests.push({
    path, params: config.params, reject,
    resolve: (rows, total = rows.length) => resolve({ data: { data: { rows, total, page: config.params.page, size: 20, asOf: '2026-11-07' } } }),
  })) }
  const source = readFileSync(new URL('../src/components/NextStepTasks.vue', import.meta.url), 'utf8')
    .match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(source)
    + ';({refresh,changePage,openTask,rows,page,failed,loading})', { ...Vue, client, useAuthStore: () => auth,
    defineProps: () => props, useRouter: () => ({ push: path => routes.push(path) }),
    sessionExpiry, SESSION_EXPIRED_EVENT, onMounted: () => {}, onUnmounted: fn => unmounts.push(fn),
    window: { addEventListener: (key, fn) => listeners.set(key, fn), removeEventListener: key => listeners.delete(key) },
  }))
  t.after(() => { unmounts.forEach(fn => fn()); scope.stop() })
  return { ...view, auth, requests, routes }
}

const task = (id = 1, link = '/case/detail/1') => ({
  taskKey: 'CASE:' + id, sourceType: 'CASE', sourceId: id, cycle: null, sourceNo: '案号' + id,
  title: '核对办理条件', stage: '待处理', dueAt: null, overdueDays: null, actionLabel: '进入办理', link,
})

test('task list keeps agency and bureau reads on separate endpoints', async t => {
  for (const domain of ['agency', 'bureau']) {
    const f = fixture(t, domain)
    const pending = f.refresh()
    assert.equal(f.requests[0].path, '/' + domain + '/my/tasks')
    f.requests[0].resolve([]); await pending
  }
})

test('a newer task refresh wins and an earlier response cannot restore completed work', async t => {
  const f = fixture(t)
  const old = f.refresh(), recent = f.refresh()
  f.requests[1].resolve([]); await recent
  f.requests[0].resolve([task()]); await old
  assert.equal(f.rows.value.length, 0)
})

test('task list clears current data and rejects pending data after role or account changes', async t => {
  const f = fixture(t)
  const first = f.refresh()
  f.requests[0].resolve([task()]); await first
  const pending = f.refresh()
  f.auth.user.roles = ['AGENCY']; f.auth.user.accountCategory = 'AGENCY'
  f.requests[1].resolve([task(2)]); await pending
  assert.equal(f.rows.value.length, 0)
  await f.refresh()
  assert.equal(f.requests.length, 2)
})

test('task navigation requires a current task and a matching domain source path', async t => {
  const f = fixture(t)
  const first = f.refresh()
  f.requests[0].resolve([task()]); await first
  f.openTask(f.rows.value[0])
  assert.deepEqual(f.routes, ['/case/detail/1'])
  f.openTask(task(2))
  assert.equal(f.routes.length, 1)
  const next = f.refresh()
  f.requests[1].resolve([task(3, '/system/users')]); await next
  f.openTask(f.rows.value[0])
  assert.equal(f.routes.length, 1)
})

test('task pagination follows the last available page after other staff complete work', async t => {
  const f = fixture(t)
  const initial = f.refresh()
  f.requests[0].resolve([task()], 41); await initial
  const later = f.changePage(3)
  f.requests[1].resolve([], 1)
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(f.requests[2].params.page, 1)
  f.requests[2].resolve([task()])
  await later
  assert.equal(f.page.value, 1)
  assert.equal(f.rows.value.length, 1)
  assert.equal(f.loading.value, false)
})
test('report and treasury tasks navigate only to their explicit scoped focus routes',async t=>{
  const f=fixture(t),pending=f.refresh()
  f.requests[0].resolve([task(8,'/case/stats?reportBatch=8'),task(9,'/system/integrations?treasuryItem=9')]);await pending
  f.openTask(f.rows.value[0]);f.openTask(f.rows.value[1])
  assert.deepEqual(f.routes,['/case/stats?reportBatch=8','/system/integrations?treasuryItem=9'])
})

test('supplement and interview review tasks navigate without allowing arbitrary case queries', async t => {
  const f = fixture(t)
  const links = [
    '/case/detail/1?supplementBundle=11',
    '/case/interviews',
    '/case/detail/1?supplementBundle=11&other=12',
    '/case/detail/1?supplementBundle=invalid',
    '/case/interviews?focus=11',
  ]
  const pending = f.refresh()
  f.requests[0].resolve(links.map((link, index) => task(index + 1, link)))
  await pending
  f.rows.value.forEach(f.openTask)
  assert.deepEqual(f.routes, links.slice(0, 2))
})

test('workflow tasks open the matching version, court round and source form within their own domain', async t => {
  const links = [
    '/case/detail/1?executionVersion=17&courtRound=23',
    '/case/detail/1?deliveryVersion=17',
    '/case/detail/1#report-supplement',
    '/case/inspections?focus=25',
    '/case/interviews?interviewRequest=13',
    '/agency/agreements?interview=55',
    '/case/detail/1?executionVersion=17&courtRound=23&other=1',
  ]
  for (const domain of ['bureau', 'agency']) {
    const f = fixture(t, domain)
    const pending = f.refresh()
    f.requests[0].resolve(links.map((link, index) => task(index + 1, link)))
    await pending
    f.rows.value.forEach(f.openTask)
    assert.deepEqual(f.routes, domain === 'agency' ? links.slice(5, 6) : links.slice(0, 5))
  }
})
