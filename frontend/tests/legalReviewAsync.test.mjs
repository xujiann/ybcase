import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { compile } from '@vue/compiler-dom'
import { renderToString } from '@vue/server-renderer'
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
const caseData = id => ({ caseFile: { id: Number(id), status: 'REPORTED' },
  reviews: [{ id: 5, materialFingerprint: 'current-fingerprint', materialSnapshot: { caseFacts: { party_name: '送审材料' } } }],
  reviewMaterialStatus: { reviewId: 5, bound: true, current: true } })

function fixture(t) {
  const reads = [], writes = [], successes = [], unmounts = [], scope = Vue.effectScope()
  const auth = Vue.reactive({ token: 'first-session', user: { id: 2, username: 'legal', realName: '法制员', roles: ['LEGAL'] } })
  const route = Vue.reactive({ params: { id: '1' }, query: {}, hash: '' })
  const client = {
    get(url) {
      if (url === '/config/public') return Promise.resolve({ data: { data: {} } })
      return new Promise((resolve, reject) => reads.push({ url, settled: false,
        resolve(data) { this.settled = true; resolve({ data: { data } }) }, reject }))
    },
    post(url, body) {
      return new Promise((resolve, reject) => writes.push({ url, body, resolve, reject }))
    },
  }
  const output = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script)
    + ';({load,detail,caseReady,loading,dlg,reviewForm,selectedReview,canReviewSelected,openReviewDialog,onDoReview,'
    + 'reviewing:typeof reviewing === "undefined" ? undefined : reviewing,auth,REVIEW_OPINION})', {
    ...Vue, ...caseAccess, ...periods, ...jurisdiction, ...dates, ...reviewMaterials,
    REVIEW_OPINION: { AGREE: '同意' }, DOC_TYPE: {}, fmtDate: String,
    client, createPreflightCheck, createApprovalRequestKey, todayLocal: dates.localDate,
    executionDateError: () => '', lateFeeTimingError: () => '', useRoute: () => route, useAuthStore: () => auth,
    onMounted() {}, onUnmounted: fn => unmounts.push(fn),
    ElMessage: { success: text => successes.push(text), warning() {} },
    document: { getElementById: () => null },
  }))
  let disposed = false
  const unmount = () => { if (!disposed) { disposed = true; unmounts.forEach(fn => fn()); scope.stop() } }
  t.after(async () => {
    unmount()
    writes.forEach(write => write.resolve({}))
    resolveReads({ reads })
    await tick()
  })
  output.detail.value = caseData(1)
  output.caseReady.value = true
  output.openReviewDialog(output.detail.value.reviews[0])
  output.reviewForm.opinion = '同意原送审材料'
  return { ...output, reads, writes, successes, auth, route, unmount }
}

function resolveReads(f) {
  for (const read of f.reads.filter(read => !read.settled)) {
    const match = read.url.match(/^\/bureau\/cases\/(\d+)$/)
    read.resolve(match ? caseData(match[1]) : [])
  }
}
function submit(f) { const pending = f.onDoReview(); pending.catch(() => {}); return pending }

async function dialogUI(f) {
  const template = source.match(/<el-dialog v-model="dlg.review"[\s\S]*?<\/el-dialog>/)[0]
  const render = new Function('Vue', compile(template, { mode: 'function', prefixIdentifiers: true }).code)(Vue)
  const ui = { formDisabled: false }
  const app = Vue.createSSRApp({ render, setup: () => f })
  app.component('ElDialog', {
    props: ['closeOnClickModal', 'closeOnPressEscape', 'showClose'],
    setup: (props, { slots }) => () => {
      ui.dialog = { ...props }
      return Vue.h('section', slots.default?.().concat(slots.footer?.() || []))
    },
  })
  app.component('ElForm', {
    props: ['disabled'], setup: (props, { slots }) => () => {
      ui.formDisabled = props.disabled === true
      return Vue.h('form', slots.default?.())
    },
  })
  app.component('ElInput', {
    props: ['type'], setup: (props, { attrs }) => () => {
      if (props.type === 'textarea') ui.editOpinion = value => {
        if (!ui.formDisabled) attrs['onUpdate:modelValue'](value)
      }
      return Vue.h('textarea')
    },
  })
  app.component('ElButton', {
    props: ['disabled', 'loading'], setup: (props, { attrs, slots }) => () => {
      const children = slots.default?.()
      const text = children?.map(value => value.children).join('')
      if (text === '取消') ui.cancel = () => { if (!props.disabled) attrs.onClick() }
      if (text === '提交审核') ui.submit = { disabled: props.disabled, loading: props.loading }
      return Vue.h('button', children)
    },
  })
  for (const name of ['ElFormItem', 'ElSelect', 'ElOption']) {
    app.component(name, { setup: (_, { slots }) => () => Vue.h('div', slots.default?.()) })
  }
  for (const name of ['ReviewMaterialSnapshot', 'ElAlert']) app.component(name, { setup: () => () => null })
  await renderToString(app)
  return ui
}

test('legal review double-click submits exactly one immutable decision payload', async t => {
  const f = fixture(t), pending = submit(f), duplicate = submit(f)
  assert.equal(f.writes.length, 1)
  assert.deepEqual(JSON.parse(JSON.stringify(f.writes[0].body)), { opinionType: 'AGREE', opinion: '同意原送审材料' })
  f.writes[0].resolve({})
  await tick()
  resolveReads(f)
  await Promise.all([pending, duplicate])
  assert.equal(f.successes.length, 1)
})

test('normal review dialog controls cannot close, reopen or edit a pending decision', async t => {
  const f = fixture(t), pending = submit(f), ui = await dialogUI(f)
  ui.cancel()
  const closedWhilePending = !f.dlg.review
  if (closedWhilePending) f.openReviewDialog(f.detail.value.reviews[0])
  const reopened = await dialogUI(f)
  reopened.editOpinion('重新打开后的新意见')
  const editedWhilePending = f.reviewForm.opinion !== '同意原送审材料'
  f.writes[0].resolve({})
  await tick()
  const oldCompletionClosedDialog = !f.dlg.review
  resolveReads(f)
  await pending
  t.diagnostic(JSON.stringify({ closedWhilePending, editedWhilePending, oldCompletionClosedDialog }))
  assert.equal(closedWhilePending, false)
  assert.equal(editedWhilePending, false)
  assert.equal(ui.formDisabled, true)
  assert.equal(ui.dialog.closeOnClickModal, false)
  assert.equal(ui.dialog.closeOnPressEscape, false)
  assert.equal(ui.dialog.showClose, false)
  assert.equal(ui.submit.loading, true)
  assert.equal(ui.submit.disabled, true)
})

test('failed legal review preserves the opinion and permits one explicit retry without an unhandled rejection', async t => {
  const f = fixture(t), pending = submit(f)
  f.writes[0].reject(new Error('offline'))
  await assert.doesNotReject(pending)
  assert.equal(f.reviewForm.opinion, '同意原送审材料')
  assert.equal(f.dlg.review, true)
  assert.equal(f.reviewing.value, false)
  assert.equal(f.successes.length, 0)
  assert.equal(f.reads.length, 0)
  const retry = submit(f)
  assert.equal(f.writes.length, 2)
  f.writes[1].resolve({})
  await tick()
  resolveReads(f)
  await retry
  assert.equal(f.dlg.review, false)
})

test('a successful legal decision remains locked until its refreshed case is ready', async t => {
  const f = fixture(t), pending = submit(f)
  f.writes[0].resolve({})
  await tick()
  assert.equal(f.reviewing?.value, true)
  assert.equal(f.loading.value, true)
  await f.onDoReview()
  assert.equal(f.writes.length, 1)
  resolveReads(f)
  await pending
  assert.equal(f.reviewing.value, false)
  assert.equal(f.caseReady.value, true)
})

test('late review completion cannot affect a reopened same-ID case after real route navigation', async t => {
  const f = fixture(t), pending = submit(f)
  f.route.params.id = '2'
  resolveReads(f)
  await tick()
  f.route.params.id = '1'
  resolveReads(f)
  await tick()
  f.openReviewDialog(f.detail.value.reviews[0])
  const readsBeforeCompletion = f.reads.length
  f.writes[0].resolve({})
  await tick()
  resolveReads(f)
  await pending
  assert.equal(f.successes.length, 0)
  assert.equal(f.reads.length, readsBeforeCompletion)
})

for (const transition of ['session', 'role', 'unmount']) {
  test(`late legal-review completion after ${transition} change cannot announce success or reload`, async t => {
    const f = fixture(t), pending = submit(f)
    if (transition === 'session') f.auth.token = 'replacement-session'
    if (transition === 'role') { f.auth.user.roles = ['HANDLER']; resolveReads(f); await tick() }
    if (transition === 'unmount') f.unmount()
    const count = f.reads.length
    f.writes[0].resolve({})
    await tick()
    resolveReads(f)
    await pending
    assert.equal(f.successes.length, 0)
    assert.equal(f.reads.length, count)
  })
}

test('blank or obsolete review opinions are rejected before a request is made', async t => {
  const f = fixture(t)
  f.reviewForm.opinion = ' \n\t '
  await f.onDoReview()
  assert.equal(f.writes.length, 0)
  f.reviewForm.opinion = '有效意见'
  f.detail.value.reviewMaterialStatus.current = false
  await f.onDoReview()
  assert.equal(f.writes.length, 0)
})
