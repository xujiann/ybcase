import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { adminOperationAccess } from '../src/api/caseWriteAccess.ts'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'

const source = readFileSync(new URL('../src/views/system/DeptsView.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
function fixture() {
  const writes = []
  const auth = Vue.reactive({ token: 'dept-parent-test', user: { id: 1, username: 'admin', roles: ['ADMIN'] } })
  const sessionExpiry = createSessionExpiry(); sessionExpiry.reset(auth.token)
  const client = {
    get: async () => ({ data: { data: [] } }),
    put: async (url, body) => { writes.push({ method: 'PUT', url, body: JSON.parse(JSON.stringify(body)) }) },
    post: async (url, body) => { writes.push({ method: 'POST', url, body: JSON.parse(JSON.stringify(body)) }) },
  }
  const scope = Vue.effectScope()
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script) + ';({form,openEdit,openCreate,save,dialogVisible})', {
    ...Vue, client, adminOperationAccess, sessionExpiry, SESSION_EXPIRED_EVENT, useAuthStore: () => auth,
    onMounted() {}, onUnmounted() {}, ElMessage: { success() {} },
  }))
  return { ...view, writes, stop: () => scope.stop() }
}
const child = { id: 8, parentId: 5, name: '原有子科室', code: 'CHILD', type: 'ADMIN', sortNo: 2, enabled: true }

test('department editing keeps its existing parent in the real PUT payload', async t => {
  const f = fixture(); t.after(f.stop); f.openEdit(child); f.form.name = '子科室名称更正'
  assert.equal(f.form.parentId, 5); await f.save()
  assert.equal(f.writes.length, 1); assert.equal(f.writes[0].method, 'PUT'); assert.equal(f.writes[0].url, '/system/depts/8')
  assert.equal(f.writes[0].body.parentId, 5); assert.equal(f.writes[0].body.name, '子科室名称更正')
})
test('creating after editing a child explicitly clears its hidden parent in the real POST payload', async t => {
  const f = fixture(); t.after(f.stop); f.openEdit(child); f.openCreate()
  Object.assign(f.form, { name: '新根科室', code: 'NEWROOT', type: 'ADMIN', orgKind: 'BUREAU' })
  assert.equal(f.form.id, null); assert.equal(f.form.parentId, null); await f.save()
  assert.equal(f.writes.length, 1); assert.equal(f.writes[0].method, 'POST'); assert.equal(f.writes[0].url, '/system/depts')
  assert.equal(f.writes[0].body.parentId, null); assert.equal(f.writes[0].body.name, '新根科室')
})
test('fresh department creation serializes an explicit null parent', async t => {
  const f = fixture(); t.after(f.stop); assert.equal(f.form.parentId, null); f.openCreate()
  Object.assign(f.form, { name: '新建科室', code: 'FRESH', orgKind: 'DISTRICT' }); await f.save()
  assert.equal(f.writes[0].method, 'POST'); assert.equal(f.writes[0].body.parentId, null)
})
test('editing a root department after a child keeps the root null parent', async t => {
  const f = fixture(); t.after(f.stop); f.openEdit(child); f.openEdit({ ...child, id: 9, parentId: null, name: '原有根科室' })
  await f.save(); assert.equal(f.writes[0].method, 'PUT'); assert.equal(f.writes[0].url, '/system/depts/9')
  assert.equal(f.writes[0].body.parentId, null)
})
