import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { compile } from '@vue/compiler-dom'
import { renderToString } from '@vue/server-renderer'
import { createHistoryPagination, historyPageState } from '../src/api/historyPagination.ts'

const source = readFileSync(new URL('../src/views/case/DepartmentHandoffsView.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  .replace(/^import .+\r?\n/gm, '')
const department = (id, name, orgKind = 'DISTRICT', enabled = true) => ({ id, name, orgKind, enabled })

function fixture(t) {
  const auth = Vue.reactive({ token: 'first-session', user: { id: 1, roles: ['LEADER'], accountCategory: 'BUREAU' } })
  const reads = [], writes = [], unmounts = [], scope = Vue.effectScope()
  const client = {
    get: (path, config) => new Promise((resolve, reject) => reads.push({
      path, config, resolve: data => resolve({ data: { data } }), reject,
    })),
    post: (path, body) => new Promise(resolve => writes.push({ path, body, resolve })),
  }
  const view = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script)
    + ';({openRequest,requestVisible,departments,deptLoading,sourceLoading,sources,request,decision,saving,'
    + 'decisionVisible,submitRequest,submitDecision,openDecision,SOURCES,ACTIONS,searchSources,changeSourceType})', {
    ...Vue, client, AbortController, createHistoryPagination, historyPageState,
    useAuthStore: () => auth, onMounted() {}, onUnmounted: fn => unmounts.push(fn),
    ElMessage: { success() {}, warning() {} },
  }))
  let stopped = false
  const unmount = () => {
    if (stopped) return
    stopped = true
    unmounts.forEach(fn => fn())
    scope.stop()
  }
  t.after(unmount)
  return { ...view, auth, reads, writes, unmount }
}

async function renderDialog(f, model) {
  const template = source.match(new RegExp('<el-dialog v-model="' + model + '"[\\s\\S]*?</el-dialog>'))[0]
  const render = new Function('Vue', compile(template, { mode: 'function', prefixIdentifiers: true }).code)(Vue)
  const app = Vue.createSSRApp({ render, setup: () => f })
  app.component('ElDialog', {
    props: ['closeOnClickModal', 'closeOnPressEscape', 'showClose'],
    setup: (props, { slots }) => () => Vue.h('section', {
      'data-modal-close': String(props.closeOnClickModal), 'data-escape-close': String(props.closeOnPressEscape),
      'data-show-close': String(props.showClose),
    }, slots.default?.()),
  })
  app.component('ElForm', {
    props: ['disabled'], setup: (props, { slots }) => () => Vue.h('form', { 'data-disabled': String(props.disabled) }, slots.default?.()),
  })
  app.component('ElInput', {
    props: ['disabled'], setup: props => () => Vue.h('textarea', { disabled: props.disabled }),
  })
  for (const component of ['ElFormItem', 'ElSelect', 'ElOption', 'ElButton']) {
    app.component(component, { setup: (_, { slots }) => () => Vue.h('div', slots.default?.()) })
  }
  return renderToString(app)
}

test('a reopened handoff request ignores the old department response while its new lookup is pending', async t => {
  const f = fixture(t)
  const old = f.openRequest()
  f.requestVisible.value = false
  const current = f.openRequest()
  f.reads[0].resolve([department(1, '旧机构')])
  await old
  assert.equal(f.departments.value.length, 0)
  assert.equal(f.deptLoading.value, true)
  f.reads[1].resolve([department(2, '新机构')])
  await current
  assert.equal(f.departments.value[0].name, '新机构')
  assert.equal(f.deptLoading.value, false)
})

test('a late department lookup cannot replace a newer handoff dialog directory', async t => {
  const f = fixture(t)
  const old = f.openRequest()
  f.requestVisible.value = false
  const current = f.openRequest()
  f.reads[1].resolve([department(2, '当前机构')])
  await current
  f.reads[0].resolve([department(1, '旧机构')])
  await old
  assert.equal(f.departments.value[0].name, '当前机构')
})

test('an older failed department request cannot stop the newer request loading state', async t => {
  const f = fixture(t)
  const old = f.openRequest()
  f.requestVisible.value = false
  const current = f.openRequest()
  f.reads[0].reject(new Error('old failure'))
  await old
  assert.equal(f.deptLoading.value, true)
  f.reads[1].resolve([department(2, '当前机构')])
  await current
  assert.equal(f.deptLoading.value, false)
})

test('closing the handoff request aborts the directory lookup and releases its loading state', async t => {
  const f = fixture(t)
  const pending = f.openRequest()
  f.requestVisible.value = false
  await Vue.nextTick()
  assert.equal(f.reads[0].config?.signal?.aborted, true)
  assert.equal(f.deptLoading.value, false)
  f.reads[0].resolve([department(1, '已关闭机构')])
  await pending
  assert.equal(f.departments.value.length, 0)
})

test('switching identity and returning does not revive the former department response', async t => {
  const f = fixture(t)
  const old = f.openRequest()
  f.auth.token = 'another-session'
  f.auth.token = 'first-session'
  const current = f.openRequest()
  f.reads[0].resolve([department(1, '旧身份机构')])
  await old
  assert.equal(f.departments.value.length, 0)
  assert.equal(f.deptLoading.value, true)
  f.reads[1].resolve([department(2, '当前身份机构')])
  await current
  assert.equal(f.departments.value[0].name, '当前身份机构')
})

test('repeatedly opening a handoff request keeps only the latest department lookup', async t => {
  const f = fixture(t)
  const old = f.openRequest()
  const current = f.openRequest()
  f.reads[0].resolve([department(1, '先前机构')])
  await old
  assert.equal(f.departments.value.length, 0)
  assert.equal(f.deptLoading.value, true)
  f.reads[1].resolve([department(2, '当前机构')])
  await current
  assert.equal(f.departments.value[0].name, '当前机构')
})

test('unmount cancels department loading without applying its eventual response', async t => {
  const f = fixture(t)
  const pending = f.openRequest()
  f.unmount()
  assert.equal(f.reads[0].config?.signal?.aborted, true)
  f.reads[0].resolve([department(1, '卸载后的机构')])
  await pending
  assert.equal(f.departments.value.length, 0)
})

test('a current handoff lookup retains enabled bureau and district choices and recovers after failure', async t => {
  const f = fixture(t)
  const failed = f.openRequest()
  f.reads[0].reject(new Error('current failure'))
  await failed
  assert.equal(f.deptLoading.value, false)
  assert.equal(f.departments.value.length, 0)
  const recovered = f.openRequest()
  f.reads[1].resolve([department(1, '局机关', 'BUREAU'), department(2, '区县'),
    department(3, '停用机构', 'DISTRICT', false), department(4, '其他类别', 'AGENCY')])
  await recovered
  assert.deepEqual(Array.from(f.departments.value, value => value.name), ['局机关', '区县'])
  assert.equal(f.deptLoading.value, false)
})

test('a pending handoff submission locks form editing and every dialog close control', async t => {
  const f = fixture(t)
  const opened = f.openRequest()
  f.reads[0].resolve([department(2, '接收机构')])
  await opened
  Object.assign(f.request, { sourceId: 1, targetDeptId: 2, reason: '当前交接原因' })
  const pending = f.submitRequest()
  assert.equal(f.writes.length, 1)
  const html = await renderDialog(f, 'requestVisible')
  assert.match(html, /data-modal-close="false"/)
  assert.match(html, /data-escape-close="false"/)
  assert.match(html, /data-show-close="false"/)
  assert.match(html, /<form[^>]+data-disabled="true"/)
  f.unmount()
  f.writes[0].resolve()
  await pending
})

test('a pending handoff decision locks its opinion and every dialog close control', async t => {
  const f = fixture(t)
  f.openDecision({ id: 1, source_no: 'CLUE-1' }, 'accept')
  f.decision.opinion = '同意接收'
  const pending = f.submitDecision()
  assert.equal(f.writes.length, 1)
  const html = await renderDialog(f, 'decisionVisible')
  assert.match(html, /data-modal-close="false"/)
  assert.match(html, /data-escape-close="false"/)
  assert.match(html, /data-show-close="false"/)
  assert.match(html, /<textarea[^>]+disabled/)
  f.unmount()
  f.writes[0].resolve()
  await pending
})
