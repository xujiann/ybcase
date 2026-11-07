import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import test from 'node:test'
import vm from 'node:vm'
import * as Vue from 'vue'
import { createSessionExpiry, SESSION_EXPIRED_EVENT } from '../src/api/sessionExpiry.ts'

const source = readFileSync(new URL('../src/views/case/PenaltyMissingFields.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .+\r?\n/gm, '')
const plain = value => JSON.parse(JSON.stringify(value))

function fixture(t) {
  const reads = [], teardown = []
  const props = Vue.reactive({ params: { year: '2026', start: '2026-01-01', end: '2026-05-31', cutoff: '2026-06-30' } })
  const auth = Vue.reactive({ token: 'one', user: { id: 1, accountCategory: 'BUREAU', roles: ['LEGAL'] } })
  const sessionExpiry = createSessionExpiry()
  const client = { get: (url, config) => new Promise((resolve, reject) => reads.push({
    url, config, resolve: result => resolve({ data: { data: result } }), reject,
  })) }
  const scope = Vue.effectScope()
  const listeners = new Map()
  const view = scope.run(() => vm.runInNewContext(
    stripTypeScriptTypes(script) + ';({open,close,load,visible,result,page,loading,failed})', {
      ...Vue, client, defineProps: () => props, useAuthStore: () => auth, sessionExpiry, SESSION_EXPIRED_EVENT,
      onMounted: mount => mount(), onBeforeUnmount: callback => teardown.push(callback),
      window: { addEventListener: (event, callback) => listeners.set(event, callback),
        removeEventListener: event => listeners.delete(event) },
    }))
  t.after(() => { teardown.forEach(callback => callback()); scope.stop() })
  return { ...view, props, auth, reads, expire: () => listeners.get(SESSION_EXPIRED_EVENT)() }
}

function response(caseNo, total = 60) {
  return { rows: [{ caseNo }], total, start: '2026-01-01', end: '2026-05-31', cutoff: '2026-06-30' }
}

test('penalty check uses the annual export filters and requests a server page', async t => {
  const f = fixture(t)
  const first = f.open()
  assert.equal(f.reads[0].url, '/bureau/stats/penalties/missing-fields')
  assert.deepEqual(plain(f.reads[0].config.params), { ...f.props.params, page: 1, size: 30 })
  f.reads[0].resolve(response('case-1'))
  await first
  const second = f.load(2)
  assert.equal(f.reads[1].config.params.page, 2)
  f.reads[1].resolve(response('case-2'))
  await second
  assert.equal(f.page.value, 2)
  assert.equal(f.result.value.rows[0].caseNo, 'case-2')
})

test('penalty check discards results after closing or changing report dates', async t => {
  const f = fixture(t)
  const first = f.open()
  f.props.params = { year: '2025' }
  assert.equal(f.visible.value, false)
  const next = f.open()
  f.reads[1].resolve(response('new'))
  await next
  f.reads[0].resolve(response('old'))
  await first
  assert.equal(f.result.value.rows[0].caseNo, 'new')
  f.close()
  assert.equal(f.result.value, undefined)
})

test('penalty check clears sensitive data on role changes and session expiry', async t => {
  const f = fixture(t)
  const first = f.open()
  f.auth.user.roles = ['HANDLER']
  assert.equal(f.visible.value, false)
  f.reads[0].resolve(response('previous-role'))
  await first
  assert.equal(f.result.value, undefined)
  f.auth.user.roles = ['LEGAL']
  const next = f.open()
  f.expire()
  f.reads[1].resolve(response('expired'))
  await next
  assert.equal(f.result.value, undefined)
  assert.equal(f.loading.value, false)
})
