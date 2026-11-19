import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { compile } from '@vue/compiler-dom'
import { renderToString } from '@vue/server-renderer'
import { ElForm, ElFormItem, ElSwitch, ID_INJECTION_KEY } from 'element-plus'
import * as caseAccess from '../src/api/caseWriteAccess.ts'
import * as periods from '../src/api/periodCompletion.ts'
import * as jurisdiction from '../src/api/jurisdiction.ts'
import * as dates from '../src/api/dates.ts'
import * as reviewMaterials from '../src/api/reviewMaterials.ts'
import { createPreflightCheck } from '../src/api/preflight.ts'
import { createApprovalRequestKey } from '../src/api/approvalRequestKey.ts'

const source = readFileSync(new URL('../src/views/case/CaseDetailView.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
  .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\r?\n/gm, '')
const tick = () => new Promise(setImmediate)
const modes = {
  notice: { form: 'noticeForm', flag: 'notifying', handler: 'onNotify', field: 'content', label: '事实理由依据', initial: '本次告知原文', status: 'REPORTED' },
  statement: { form: 'statementForm', flag: 'savingStatement', handler: 'onStatement', field: 'statement', label: '陈述申辩内容', initial: '本次申辩原文', status: 'NOTIFIED' },
  deliver: { form: 'deliveryForm', flag: 'deliveringCase', handler: 'onDeliver', field: 'receiver', label: '受送达人', initial: '合成受送达人', status: 'DECIDED' },
}
function caseData(id, kind) {
  return { caseFile: { id: Number(id), status: modes[kind].status, decidedAt: dates.localDate() },
    notices: kind === 'notice' ? [] : [{ id: 42, deliveryRequired: true, content: '原告知',
      statement: '原陈述', statementReview: '原复核', hearingRequested: false, statementWaived: false,
      proposedFine: 0, proposedRecoup: 0, proposedConfiscate: 0 }],
    deliveries: [{ id: 8, notice_id: 42, delivered_at: dates.localDate() }] }
}

async function fixture(t, kind) {
  const reads = [], writes = [], successes = [], unmounts = [], scope = Vue.effectScope()
  const auth = Vue.reactive({ token: 'first-session', user: { id: 1, username: 'handler', roles: ['HANDLER'] } })
  const route = Vue.reactive({ params: { id: '1' }, query: {}, hash: '' })
  const client = {
    get(url) {
      if (url === '/config/public') return Promise.resolve({ data: { data: {} } })
      return new Promise((resolve, reject) => reads.push({ url, settled: false,
        resolve(data) { this.settled = true; resolve({ data: { data } }) }, reject }))
    },
    post(url, body) { return new Promise((resolve, reject) => writes.push({ url, body, resolve, reject })) },
  }
  const output = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script)
    + ';({load,detail,c,canWriteCase,caseReady,loading,dlg,noticeForm,notifying,openNotice,onNotify,statementForm,'
    + 'get statementSnapshot(){return statementSnapshot},openStatement,onStatement,deliveryForm,deliveringCase,onDeliver,'
    + 'savingStatement:typeof savingStatement === "undefined" ? undefined : savingStatement,noticeAmountLimit,DELIVERY_METHOD})', {
    ...Vue, ...caseAccess, ...periods, ...jurisdiction, ...dates, ...reviewMaterials,
    client, createPreflightCheck, createApprovalRequestKey, todayLocal: dates.localDate,
    DOC_TYPE: {}, fmtDate: String, DELIVERY_METHOD: { DIRECT: '直接送达' },
    executionDateError: () => '', lateFeeTimingError: () => '', useRoute: () => route, useAuthStore: () => auth,
    onMounted() {}, onUnmounted: fn => unmounts.push(fn),
    ElMessage: { success: text => successes.push(text), warning() {}, info() {} },
    document: { getElementById: () => null },
  }))
  let disposed = false
  const unmount = () => { if (!disposed) { disposed = true; unmounts.forEach(fn => fn()); scope.stop() } }
  const f = Object.assign(output, { kind, reads, writes, successes, auth, route, unmount })
  t.after(async () => { unmount(); writes.forEach(write => write.resolve({})); resolveReads(f); await tick() })
  f.detail.value = caseData(1, kind)
  f.caseReady.value = true
  await openDialog(f)
  const ui = await dialogUI(f)
  ui.edit(modes[kind].initial)
  if (kind === 'statement') f.statementForm.statementReceivedAt = dates.localDate()
  if (kind === 'deliver') f.deliveryForm.receiptNo = 'SYNTHETIC-RECEIPT'
  return f
}

function resolveReads(f) {
  for (const read of f.reads.filter(read => !read.settled)) {
    const match = read.url.match(/^\/bureau\/cases\/(\d+)$/)
    read.resolve(match ? caseData(match[1], f.kind) : [])
  }
}
function submit(f) { const pending = f[modes[f.kind].handler](); pending.catch(() => {}); return pending }

async function openDialog(f) {
  if (f.kind === 'notice') f.openNotice()
  else if (f.kind === 'statement') f.openStatement()
  else {
    // Execute the real visible toolbar button, including its disabled prop and inline v-model event.
    const template = source.match(/<el-button v-if="canWriteCase && !detail.executionDeadline\?\.decisionVersionId"[^>]*>登记送达<\/el-button>/)[0]
    let button
    const render = new Function('Vue', compile(template, { mode: 'function', prefixIdentifiers: true }).code)(Vue)
    const app = Vue.createSSRApp({ render, setup: () => f })
    app.component('ElButton', { props: ['disabled'], setup: (props, { attrs }) => () => {
      button = () => { if (!props.disabled) attrs.onClick() }
      return null
    } })
    await renderToString(app)
    button()
  }
}

async function dialogUI(f) {
  const template = source.match(new RegExp('<el-dialog v-model="dlg\\.' + f.kind + '"[\\s\\S]*?</el-dialog>'))[0]
  const render = new Function('Vue', compile(template, { mode: 'function', prefixIdentifiers: true }).code)(Vue)
  const ui = {}
  const app = Vue.createSSRApp({ render, setup: () => f })
  app.component('ElDialog', { props: ['closeOnClickModal', 'closeOnPressEscape', 'showClose'],
    setup: (props, { slots }) => () => { ui.dialog = { ...props }; return Vue.h('section', [slots.default?.(), slots.footer?.()]) } })
  app.component('ElForm', { props: ['disabled'], setup: (props, { slots }) => {
    Vue.provide('formDisabled', Vue.computed(() => props.disabled === true))
    return () => { ui.formDisabled = props.disabled === true; return Vue.h('form', slots.default?.()) }
  } })
  app.component('ElFormItem', { props: ['label'], setup: (props, { slots }) => {
    Vue.provide('fieldLabel', props.label)
    return () => Vue.h('div', slots.default?.())
  } })
  app.component('ElInput', { setup: (_, { attrs }) => {
    const disabled = Vue.inject('formDisabled'), label = Vue.inject('fieldLabel')
    return () => {
      if (label === modes[f.kind].label) ui.edit = value => { if (!disabled.value) attrs['onUpdate:modelValue'](value) }
      return Vue.h('input')
    }
  } })
  app.component('ElButton', { props: ['disabled', 'loading'], setup: (props, { attrs, slots }) => () => {
    const nodes = slots.default?.(), text = nodes?.map(value => value.children).join('')
    if (text === '取消') ui.cancel = () => { if (!props.disabled) attrs.onClick() }
    else ui.submit = { disabled: props.disabled, loading: props.loading }
    return Vue.h('button', nodes)
  } })
  for (const name of ['ElInputNumber', 'ElSelect', 'ElOption', 'ElSwitch', 'ElDatePicker']) {
    app.component(name, { setup: (_, { slots }) => () => Vue.h('div', slots.default?.()) })
  }
  app.component('ElAlert', { setup: () => () => null })
  await renderToString(app)
  return ui
}

for (const kind of Object.keys(modes)) {
  test(`${kind}: actual pending dialog cannot edit or close, and exposed controls cannot reopen it`, async t => {
    const f = await fixture(t, kind), pending = submit(f), ui = await dialogUI(f), mode = modes[kind]
    ui.edit('提交后新增但尚未保存的修改')
    const editedWhilePending = f[mode.form][mode.field] !== mode.initial
    ui.cancel()
    const closedWhilePending = !f.dlg[kind]
    let reopenedWhilePending = false
    if (closedWhilePending) {
      await openDialog(f)
      reopenedWhilePending = f.dlg[kind]
      if (reopenedWhilePending) (await dialogUI(f)).edit('重新打开后的新草稿')
    }
    f.writes[0].resolve({})
    await tick()
    const oldCallbackClosedDialog = !f.dlg[kind]
    resolveReads(f)
    await pending
    t.diagnostic(JSON.stringify({ kind, editedWhilePending, closedWhilePending, reopenedWhilePending, oldCallbackClosedDialog }))
    assert.equal(editedWhilePending, false)
    assert.equal(closedWhilePending, false)
    assert.equal(ui.formDisabled, true)
    assert.equal(ui.dialog.closeOnClickModal, false)
    assert.equal(ui.dialog.closeOnPressEscape, false)
    assert.equal(ui.dialog.showClose, false)
    assert.equal(ui.submit.disabled, true)
    assert.equal(ui.submit.loading, true)
  })

  test(`${kind}: double-click posts one immutable payload`, async t => {
    const f = await fixture(t, kind), pending = submit(f), duplicate = submit(f), mode = modes[kind]
    assert.equal(f.writes.length, 1)
    assert.equal(f.writes[0].body[mode.field], mode.initial)
    f.writes[0].resolve({})
    await tick()
    resolveReads(f)
    await Promise.all([pending, duplicate])
    assert.equal(f.successes.length, 1)
  })

  test(`${kind}: failure keeps the entered draft and permits an explicit retry`, async t => {
    const f = await fixture(t, kind), pending = submit(f), mode = modes[kind]
    f.writes[0].reject(new Error('offline'))
    await assert.doesNotReject(pending)
    assert.equal(f[mode.flag].value, false)
    assert.equal(f.dlg[kind], true)
    assert.equal(f[mode.form][mode.field], mode.initial)
    assert.equal(f.reads.length, 0)
    assert.equal(f.successes.length, 0)
    const retry = submit(f)
    assert.equal(f.writes.length, 2)
    f.writes[1].resolve({})
    await tick()
    resolveReads(f)
    await retry
  })

  test(`${kind}: the submit lock remains held until the refreshed case is ready`, async t => {
    const f = await fixture(t, kind), pending = submit(f), mode = modes[kind]
    f.writes[0].resolve({})
    await tick()
    assert.equal(f[mode.flag]?.value, true)
    assert.equal(f.loading.value, true)
    resolveReads(f)
    await pending
    assert.equal(f[mode.flag].value, false)
    assert.equal(f.caseReady.value, true)
  })

  for (const transition of ['route-return', 'session', 'session-return', 'unmount']) {
    test(`${kind}: late completion after ${transition} cannot announce success or refresh another context`, async t => {
      const f = await fixture(t, kind), pending = submit(f)
      if (transition === 'route-return') {
        f.route.params.id = '2'; resolveReads(f); await tick()
        f.route.params.id = '1'; resolveReads(f); await tick()
        await openDialog(f)
        if (f.dlg[kind]) (await dialogUI(f)).edit('回到原案后新填写的内容')
      } else if (transition === 'session') f.auth.token = 'replacement-session'
      else if (transition === 'session-return') { f.auth.token = 'replacement-session'; f.auth.token = 'first-session' }
      else f.unmount()
      const readCount = f.reads.length
      f.writes[0].resolve({})
      await tick()
      resolveReads(f)
      await pending
      assert.equal(f.successes.length, 0)
      assert.equal(f.reads.length, readCount)
    })
  }
}

test('actual Element Plus waiver switch cannot override its pending statement form lock', async t => {
  const f = await fixture(t, 'statement')
  submit(f)
  const item = source.match(/<el-form-item label="明确放弃">[\s\S]*?<\/el-form-item>/)[0]
  const render = new Function('Vue', compile('<el-form :disabled="savingStatement">' + item + '</el-form>',
    { mode: 'function', prefixIdentifiers: true }).code)(Vue)
  const app = Vue.createSSRApp({ render, setup: () => f })
  app.provide(ID_INJECTION_KEY, { prefix: 19, current: 0 })
  app.component('ElForm', ElForm)
  app.component('ElFormItem', ElFormItem)
  app.component('ElSwitch', ElSwitch)
  const html = await renderToString(app)
  const checkbox = html.match(/<input[^>]*type="checkbox"[^>]*>/)[0]
  t.diagnostic(checkbox)
  assert.match(checkbox, /\sdisabled(?:[ =>])/)
})
