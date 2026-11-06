import nodeTest from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
import { adminOperationAccess } from '../src/api/caseWriteAccess.ts'

const tick = () => new Promise(setImmediate)
const test = (name, run) => nodeTest(name, { timeout: 2500 }, run)
const plain = value => JSON.parse(JSON.stringify(value))
const role = (id = 2) => ({ id, code: 'HANDLER', name: '办案人员' + id, menuIds: [1, 11], menuCount: 2 })
const user = (id = 2) => ({ id, username: 'handler' + id, realName: '办案人员' + id, title: null, deptId: 1, phone: null, enabled: true, roleCodes: ['HANDLER'] })
const menus = [{ id: 1, parentId: null, name: '业务' }, { id: 11, parentId: 1, name: '线索' }, { id: 12, parentId: 1, name: '案件' }]

function fixture(t, kind = 'roles', controlledTicks = false) {
  const reads = [], writes = [], successes = [], confirms = [], navigations = [], unmounts = [], mounts = [], ticks = []
  const auth = Vue.reactive({ token: 'one', user: { id: 1, username: 'admin-one', roles: ['ADMIN'] } })
  const sessionExpiry = createSessionExpiry(); sessionExpiry.reset(auth.token)
  const recentErrors = []
  const client = {
    get: (url, config) => new Promise((resolve, reject) => reads.push({ url, config, resolve: data => resolve({ data: { data } }), reject })),
    put: (url, body, config) => new Promise((resolve, reject) => writes.push({ url, body: body == null ? body : plain(body), config, resolve, reject })),
    post: (url, body) => new Promise((resolve, reject) => writes.push({ url, body: plain(body), resolve, reject })),
  }
  let keys = [], half = []
  const tree = { setCheckedKeys(value) { keys = [...value] }, getCheckedKeys: () => [...keys], getHalfCheckedKeys: () => [...half] }
  const file = kind === 'roles' ? 'RolesView.vue' : 'UsersView.vue'
  const source = readFileSync(new URL('../src/views/system/' + file, import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const exposed = kind === 'roles' ? 'load,loadMenus,openGrant,save,current,treeRef,menuTree,records,loading,saving,dialogVisible'
    : 'load,openCreate,openEdit,save,onUnlock,toggleEnabled,form,records,total,pageNo,loading,saving,dialogVisible,depts,roles'
  const scope = Vue.effectScope()
  const listeners = new Map()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script) + ';({' + exposed + ',expireView:typeof onSessionExpired === "function" ? onSessionExpired : ()=>{}})', {
    ...Vue, client, recentErrors, useAuthStore: () => auth, adminOperationAccess, sessionExpiry, SESSION_EXPIRED_EVENT,
    nextTick: controlledTicks ? () => new Promise(resolve => ticks.push(resolve)) : Vue.nextTick,
    useRouter: () => ({ push: route => navigations.push(route) }),
    window: { addEventListener: (event, fn) => listeners.set(event, fn), removeEventListener: event => listeners.delete(event) },
    onMounted: fn => mounts.push(fn), onUnmounted: fn => unmounts.push(fn),
    ElMessage: { success: value => successes.push(value), warning() {} },
    ElMessageBox: { confirm: (...args) => new Promise((resolve, reject) => confirms.push({ args, resolve, reject })) },
  }))
  if (kind === 'roles') view.treeRef.value = tree
  let stopped = false
  const f = { ...view, auth, sessionExpiry, reads, writes, successes, confirms, navigations, ticks, recentErrors,
    keys: () => [...keys], setKeys: value => { keys = [...value] }, setHalf: value => { half = [...value] },
    initialize: () => Promise.all(mounts.map(fn => fn())),
    switchUser() { sessionExpiry.reset('two'); auth.token = 'two'; auth.user = { id: 3, username: 'admin-two', roles: ['ADMIN'] } },
    expire() { sessionExpiry.expire(sessionExpiry.capture(auth.token), auth.token); view.expireView() },
    unmount() { if (!stopped) { stopped = true; unmounts.forEach(fn => fn()); scope.stop() } },
  }
  t.after(f.unmount)
  return f
}
async function readyRoles(f) {
  const rows = f.load(); f.reads.at(-1).resolve([role()]); await rows
  const tree = f.loadMenus(); f.reads.at(-1).resolve(menus); await tree
}
async function readyUsers(f) {
  const job = f.initialize(); f.reads[0].resolve([{ id: 1, name: '科室' }]); f.reads[1].resolve([{ code: 'HANDLER', name: '办案人员' }])
  await tick(); f.reads.at(-1).resolve({ records: [user()], total: 1 }); await job
}
async function completeSave(f, job, records) {
  f.writes.at(-1).resolve({}); await tick()
  f.reads.at(-1).resolve(records); await job
}
async function finishAction(f, job) {
  await tick()
  for (const read of f.reads) read.resolve(read.url === '/system/roles' ? [role()] : read.url === '/system/roles/menus' ? menus : { records: [user()], total: 1 })
  await job
}

test('roles: pending menu catalog cannot open an empty grant or issue a destructive replacement', async t => {
  const f = fixture(t); const list = f.load(); f.reads[0].resolve([role()]); await list
  const catalog = f.loadMenus(); await f.openGrant(role()); const save = f.save()
  assert.equal(f.writes.length, 0); assert.equal(f.dialogVisible.value, false)
  f.reads[1].resolve(menus); await catalog; await save
})
test('roles: failed menu catalog cannot clear an existing role grant', async t => {
  const f = fixture(t); const list = f.load(); f.reads[0].resolve([role()]); await list
  const catalog = f.loadMenus(); f.reads[1].reject(new Error('offline')); await catalog.catch(() => {})
  await f.openGrant(role()); const save = f.save(); assert.equal(f.writes.length, 0); assert.equal(f.dialogVisible.value, false); await save
})
test('roles: a ready catalog restores leaf checks and saves parent half-checks normally', async t => {
  const f = fixture(t); await readyRoles(f); await f.openGrant(role()); assert.deepEqual(f.keys(), [11]); f.setHalf([1])
  const job = f.save(); assert.equal(f.writes.length, 1); assert.deepEqual(f.writes[0].body.menuIds, [11, 1]); await completeSave(f, job, [role()])
  assert.equal(f.successes.length, 1); assert.equal(f.dialogVisible.value, false)
})
test('roles: a deliberately unchecked ready tree still supports the existing empty grant', async t => {
  const f = fixture(t); await readyRoles(f); await f.openGrant(role()); f.setKeys([])
  const job = f.save(); assert.deepEqual(f.writes[0].body.menuIds, []); await completeSave(f, job, [role()])
  assert.equal(f.dialogVisible.value, false); assert.equal(f.successes.length, 1)
})
test('roles: later role selection owns its delayed tree restoration', async t => {
  const f = fixture(t, 'roles', true); await readyRoles(f)
  const first = f.openGrant(role()), second = f.openGrant({ ...role(3), menuIds: [12] })
  f.ticks[1](); await second; f.ticks[0](); await first
  assert.equal(f.current.value.id, 3); assert.deepEqual(f.keys(), [12])
})
test('roles: duplicate save sends one replacement', async t => {
  const f = fixture(t); await readyRoles(f); await f.openGrant(role()); const job = f.save(); const repeat = f.save()
  assert.equal(f.writes.length, 1); await repeat; await completeSave(f, job, [role()])
})
test('roles: a changed checked draft survives the previous save response', async t => {
  const f = fixture(t); await readyRoles(f); await f.openGrant(role()); const job = f.save(); f.setKeys([12])
  f.writes[0].resolve({}); await finishAction(f, job); assert.equal(f.dialogVisible.value, true); assert.deepEqual(f.keys(), [12]); assert.equal(f.successes.length, 0)
})
test('roles: catalog refresh invalidates an open grant rather than saving an empty replacement', async t => {
  const f = fixture(t); await readyRoles(f); await f.openGrant(role()); const refresh = f.loadMenus(); const save = f.save()
  assert.equal(f.writes.length, 0); f.reads.at(-1).resolve(menus); await refresh; await save
})
for (const kind of ['roles', 'users']) {
  const response = value => kind === 'roles' ? [{ id: value }] : { records: [{ id: value }], total: value }
  test(kind + ': latest list response wins after reversed page refreshes', async t => {
    const f = fixture(t, kind), first = f.load(), second = f.load(); f.reads[1].resolve(response(2)); await second
    f.reads[0].resolve(response(1)); await first; assert.equal(f.records.value[0].id, 2)
  })
  test(kind + ': latest list failure clears old actionable records', async t => {
    const f = fixture(t, kind), first = f.load(); f.reads[0].resolve(response(1)); await first
    const bad = f.load(); f.reads[1].reject(new Error('offline')); await assert.doesNotReject(bad)
    assert.equal(f.records.value.length, 0); assert.equal(f.loading.value, false)
  })
  test(kind + ': an old error cannot release the newer loading state', async t => {
    const f = fixture(t, kind), first = f.load(), second = f.load(); f.reads[0].reject(new Error('old')); await first.catch(() => {})
    assert.equal(f.loading.value, true); f.reads[1].resolve(response(2)); await second
  })
  test(kind + ': late list data cannot cross administrator accounts', async t => {
    const f = fixture(t, kind), first = f.load(); f.switchUser(); const next = f.load()
    f.reads[1].resolve(response(2)); await next; f.reads[0].resolve(response(1)); await first
    assert.equal(f.records.value[0].id, 2)
  })
  test(kind + ': same JWT new login generation ignores old lists', async t => {
    const f = fixture(t, kind), job = f.load(); f.sessionExpiry.reset(f.auth.token); f.reads[0].resolve(response(1)); await job
    assert.equal(f.records.value.length, 0); assert.equal(f.loading.value, false)
  })
  test(kind + ': expired or unmounted views reject late lists', async t => {
    for (const disposed of [false, true]) {
      const f = fixture(t, kind), job = f.load(); disposed ? f.unmount() : f.expire(); f.reads[0].resolve(response(1)); await job
      assert.equal(f.records.value.length, 0)
    }
  })
}
test('roles: losing ADMIN clears role catalogs and grant draft', async t => {
  const f = fixture(t); await readyRoles(f); await f.openGrant(role()); f.auth.user.roles = ['HANDLER']
  assert.equal(f.records.value.length, 0); assert.equal(f.menuTree.value.length, 0); assert.equal(f.dialogVisible.value, false)
})
test('users: old directory initialization cannot load or populate a different account', async t => {
  const f = fixture(t, 'users'), job = f.initialize(); f.switchUser(); f.reads[0].resolve([{ id: 99, name: 'private directory' }]); f.reads[1].resolve([{ code: 'OLD', name: 'old' }])
  await tick(); assert.equal(f.reads.length, 2); await job; assert.equal(f.depts.value.length, 0); assert.equal(f.roles.value.length, 0)
})
test('users: save owns a synchronous duplicate submission lock', async t => {
  const f = fixture(t, 'users'); await readyUsers(f); f.openCreate(); Object.assign(f.form, { username: 'new', realName: '新用户', password: 'Initial2026!', accountCategory: 'DISTRICT', deptId: 1 })
  const job = f.save(), repeat = f.save(); assert.equal(f.writes.length, 1); await repeat; await completeSave(f, job, { records: [user()], total: 1 })
  assert.equal(f.form.password, ''); assert.equal(f.successes.length, 1)
})
test('users: old save cannot close the next account form or show success', async t => {
  const f = fixture(t, 'users'); await readyUsers(f); f.openEdit(user()); const job = f.save(); f.switchUser()
  assert.equal(f.form.password, ''); f.writes[0].resolve({}); await finishAction(f, job)
  assert.equal(f.successes.length, 0); assert.equal(f.records.value.length, 0); assert.equal(f.reads.length, 3)
})
test('users: saving a snapshot cannot erase a later edit of the same draft', async t => {
  const f = fixture(t, 'users'); await readyUsers(f); f.openEdit(user()); const job = f.save(); f.form.realName = 'later draft'
  f.writes[0].resolve({}); await finishAction(f, job); assert.equal(f.dialogVisible.value, true); assert.equal(f.form.realName, 'later draft'); assert.equal(f.successes.length, 0)
})
test('users: same JWT relogin makes earlier saves inactive', async t => {
  const f = fixture(t, 'users'); await readyUsers(f); f.openEdit(user()); const job = f.save(); f.sessionExpiry.reset(f.auth.token)
  f.writes[0].resolve({}); await finishAction(f, job); assert.equal(f.successes.length, 0); assert.equal(f.reads.length, 3)
})
test('users: editing role choices does not mutate the unsaved table row', async t => {
  const f = fixture(t, 'users'); await readyUsers(f); const row = user(); f.openEdit(row); f.form.roleCodes.push('LEGAL')
  assert.deepEqual(row.roleCodes, ['HANDLER'])
})
test('users: duplicate enable requests are blocked through the following refresh', async t => {
  const f = fixture(t, 'users'); await readyUsers(f); const row = f.records.value[0], job = f.toggleEnabled(row), repeat = f.toggleEnabled(row)
  assert.equal(f.writes.length, 1); await repeat; f.writes[0].resolve({}); await tick(); const third = f.toggleEnabled(row); assert.equal(f.writes.length, 1); await third
  f.reads.at(-1).resolve({ records: [{ ...row, enabled: false }], total: 1 }); await job
})
test('users: duplicate unlock requests create one operation and no stale-session success', async t => {
  const f = fixture(t, 'users'); await readyUsers(f); const job = f.onUnlock(user()), repeat = f.onUnlock(user()); assert.equal(f.writes.length, 1); await repeat
  f.switchUser(); f.writes[0].resolve({}); await finishAction(f, job); assert.equal(f.successes.length, 0); assert.equal(f.reads.length, 3)
})
test('users: a normal unlock refreshes the current account list and reports success', async t => {
  const f = fixture(t, 'users'); await readyUsers(f); const job = f.onUnlock(user())
  assert.equal(f.writes[0].url, '/system/users/2/unlock'); await completeSave(f, job, { records: [user()], total: 1 })
  assert.equal(f.successes.length, 1); assert.equal(f.records.value[0].username, 'handler2')
})
test('users: a blocked-disable confirmation cannot navigate a newer account', async t => {
  const f = fixture(t, 'users'); await readyUsers(f); const job = f.toggleEnabled(user())
  f.recentErrors.push({ bizCode: 1105, message: '先移交案件', path: '/system/users/2/enabled' }); f.writes[0].reject(Object.assign(new Error('先移交案件'), { bizCode: 1105 }))
  await tick(); assert.equal(f.confirms.length, 1); f.switchUser(); f.confirms[0].resolve(); await job; await tick(); assert.equal(f.navigations.length, 0)
})
test('users: a rejected old account action cannot show its transfer prompt in a new session', async t => {
  const f = fixture(t, 'users'); await readyUsers(f); const job = f.toggleEnabled(user()); f.switchUser()
  f.recentErrors.push({ bizCode: 1105, message: 'old private case', path: '/system/users/2/enabled' }); f.writes[0].reject(Object.assign(new Error('old'), { bizCode: 1105 }))
  await job; assert.equal(f.confirms.length, 0)
})
test('users: the current blocked-disable confirmation keeps its existing owner handoff link', async t => {
  const f = fixture(t, 'users'); await readyUsers(f); const job = f.toggleEnabled(user())
  f.writes[0].reject(Object.assign(new Error('请先移交'), { bizCode: 1105 })); await tick()
  assert.equal(f.confirms.length, 1); f.confirms[0].resolve(); await job
  assert.equal(f.navigations[0].path, '/case/list'); assert.equal(f.navigations[0].query.owner, 'handler2')
})
