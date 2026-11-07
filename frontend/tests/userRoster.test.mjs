import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { ROSTER_HEADER, ROSTER_MAX_BYTES, parseUserRoster } from '../src/api/userRoster.ts'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'
import { adminOperationAccess } from '../src/api/caseWriteAccess.ts'

const csv = name => ROSTER_HEADER + '\nCREATE,' + name + ',张三,BUREAU-01,BUREAU,LEGAL'
const ready = username => [{ row: 1, username, status: 'READY', message: '通过' }]
const applied = username => [{ row: 1, username, initialPassword: 'transient-example' }]

test('roster CSV supports Excel BOM, CRLF, escaped quotes and multiple roles', () => {
  const text = '\ufeff' + ROSTER_HEADER + '\r\nCREATE,a,"张""三,处长",BUREAU-01,BUREAU,LEADER|LEGAL\r\n'
    + 'CLASSIFY,b,李四,DISTRICT-01,DISTRICT,\r\n'
  assert.deepEqual(parseUserRoster(text), [
    { action: 'CREATE', username: 'a', realName: '张"三,处长', departmentCode: 'BUREAU-01', accountCategory: 'BUREAU', roleCodes: ['LEADER', 'LEGAL'] },
    { action: 'CLASSIFY', username: 'b', realName: '李四', departmentCode: 'DISTRICT-01', accountCategory: 'DISTRICT' },
  ])
})

test('roster CSV refuses ambiguous delimiters, malformed rows and missing required values', () => {
  assert.throws(() => parseUserRoster(csv('a').replace('登录账号', '账号')), /表头/)
  assert.throws(() => parseUserRoster(csv('a').replace('张三', '"张三')), /引号未闭合/)
  assert.throws(() => parseUserRoster(csv('a').replace('张三', '"张三"附')), /引号格式错误/)
  assert.throws(() => parseUserRoster(csv('a') + '\n\nCLASSIFY,b,李四,DISTRICT-01,DISTRICT,'), /第 2 条/)
  assert.throws(() => parseUserRoster(ROSTER_HEADER + '\nCLASSIFY,b,,DISTRICT-01,DISTRICT,'), /姓名/)
  assert.throws(() => parseUserRoster(ROSTER_HEADER + '\nCLASSIFY,b,李四,DISTRICT-01,DISTRICT,LEGAL'), /角色编码须留空/)
  assert.throws(() => parseUserRoster(csv('a') + '|'), /角色编码/)
  assert.throws(() => parseUserRoster(ROSTER_HEADER + '\n' + 'CREATE,a,张三,BUREAU-01,BUREAU,LEGAL\n'.repeat(101)), /100 条/)
})

function fixture(t) {
  const auth = Vue.reactive({ token: 'first', user: { id: 1, username: 'admin', roles: ['ADMIN'] } })
  const sessionExpiry = createSessionExpiry()
  sessionExpiry.reset(auth.token)
  const requests = [], events = [], unmounts = [], listeners = new Map()
  const client = { post: (url, payload, config) => new Promise((resolve, reject) => requests.push({
    url, payload, config, reject, resolve: rows => resolve({ data: { data: { rows } } }),
  })) }
  const source = readFileSync(new URL('../src/components/UserRosterDialog.vue', import.meta.url), 'utf8')
    .match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
  const scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(source)
    + ';({chooseFile,previewRoster,applyRoster,close,preview,result,canApply,applyError,fileName})', {
    ...Vue, client, TextDecoder, useAuthStore: () => auth, adminOperationAccess,
    defineEmits: () => event => events.push(event),
    sessionExpiry, SESSION_EXPIRED_EVENT, parseUserRoster, ROSTER_MAX_BYTES,
    onMounted: fn => fn(), onUnmounted: fn => unmounts.push(fn),
    window: { addEventListener: (event, fn) => listeners.set(event, fn), removeEventListener: event => listeners.delete(event) },
  }))
  t.after(() => { unmounts.forEach(fn => fn()); scope.stop() })
  async function choose(username) {
    const bytes = new TextEncoder().encode(csv(username))
    const input = { value: 'roster.csv', files: [{ name: 'roster.csv', size: bytes.length, arrayBuffer: async () => bytes.buffer }] }
    await view.chooseFile({ target: input })
    assert.equal(input.value, '')
    assert.equal(view.fileName.value, 'roster.csv')
  }
  async function preview(username) {
    const job = view.previewRoster()
    assert.match(requests.at(-1).url, /\/preview$/)
    requests.at(-1).resolve(ready(username))
    await job
  }
  return { ...view, auth, requests, events, choose, preview, expire: () => listeners.get(SESSION_EXPIRED_EVENT)() }
}

test('new CSV selection drops a pending preview and requires a fresh one', async t => {
  const f = fixture(t)
  await f.choose('first')
  await f.applyRoster()
  assert.equal(f.requests.length, 0)
  const stale = f.previewRoster()
  await f.choose('second')
  f.requests[0].resolve(ready('first'))
  await stale
  assert.equal(f.canApply.value, false)
  await f.preview('second')
  assert.equal(f.canApply.value, true)
})

test('roster apply submits once, refreshes the list, and clears passwords on close', async t => {
  const f = fixture(t)
  await f.choose('first')
  await f.preview('first')
  const applying = f.applyRoster()
  await f.applyRoster()
  assert.equal(f.requests.length, 2)
  assert.match(f.requests[1].url, /\/apply$/)
  assert.equal(f.requests[1].config.timeout, 90000)
  f.requests[1].resolve(applied('first'))
  await applying
  assert.deepEqual(f.events, ['applied'])
  assert.equal(f.result.value[0].initialPassword, 'transient-example')
  f.close(false)
  assert.equal(f.result.value.length, 0)
  assert.deepEqual(f.events, ['applied', 'close'])
})

test('session expiry clears sensitive state and prevents late apply results crossing sessions', async t => {
  const f = fixture(t)
  await f.choose('first')
  await f.preview('first')
  const applying = f.applyRoster()
  f.expire()
  f.requests.at(-1).resolve(applied('first'))
  await applying
  assert.equal(f.result.value.length, 0)
  assert.deepEqual(f.events, ['close'])
})

test('apply timeout clears preview and advises checking list or resetting a lost password', async t => {
  const f = fixture(t)
  await f.choose('first')
  await f.preview('first')
  const applying = f.applyRoster()
  f.requests.at(-1).reject(new Error('timeout'))
  await applying
  assert.equal(f.canApply.value, false)
  assert.match(f.applyError.value, /核对用户列表.*重置/)
  await f.previewRoster()
  assert.equal(f.requests.length, 2)
  assert.deepEqual(f.events, ['applied'])
})