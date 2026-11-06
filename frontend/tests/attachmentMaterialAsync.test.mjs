import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import * as Vue from 'vue'
import { canWritePrecase, canVoidPrecase } from '../src/api/precaseAttachments.ts'
import * as caseAccess from '../src/api/caseWriteAccess.ts'
import * as periods from '../src/api/periodCompletion.ts'
import * as jurisdiction from '../src/api/jurisdiction.ts'
import { createPreflightCheck } from '../src/api/preflight.ts'
import { localDate, createTodayReset } from '../src/api/dates.ts'
import { evidenceCatalogHtml } from '../src/api/evidenceCatalog.ts'

// Run the actual SFC setup/handlers. Deferred HTTP and prompts control adverse
// ordering without real accounts, files, or a database.
const tick = () => new Promise(setImmediate)
function fixture(file, exported, initialProps = {}) {
  const reads = [], writes = [], prompts = [], successes = [], downloads = [], windows = [], messages = []
  const unmounted = [], auth = Vue.reactive({ user: { username: 'admin', realName: '测试员', roles: ['ADMIN'] } })
  const props = Vue.reactive(initialProps), route = Vue.reactive({ params: { id: '1' }, query: {} })
  const client = {
    get(url, config) {
      if (url === '/config/public') return Promise.resolve({ data: { data: { attachment_storage: 'FILE' } } })
      return new Promise((resolve, reject) => reads.push({ url, config, settled: false,
        resolve(data) { this.settled = true; resolve(config?.responseType === 'blob' ? { data } : { data: { data } }) },
        reject(error) { this.settled = true; reject(error) } }))
    },
    post(url, body, config) { return new Promise((resolve, reject) => writes.push({ url, body, config,
      resolve: data => resolve({ data: { data: data ?? {} } }), reject })) },
  }
  const ElMessage = Object.assign(message => { messages.push(message); return { close() {} } }, {
    success: text => successes.push(text), warning() {}, error() {}, info() {},
  })
  const context = { ...Vue, ...caseAccess, ...periods, ...jurisdiction, client, ElMessage,
    canWritePrecase, canVoidPrecase, evidenceCatalogHtml, localDate, todayLocal: localDate, createTodayReset, createPreflightCheck,
    useAuthStore: () => auth, useRoute: () => route, defineProps: () => props, defineEmits: () => () => {},
    onMounted() {}, onUnmounted: fn => unmounted.push(fn), LARGE_TRANSFER: { timeout: 600000 },
    ElMessageBox: { prompt: () => new Promise((resolve, reject) => prompts.push({ resolve, reject })), alert: async () => {} },
    FormData: class { values = []; append(key, value) { this.values.push([key, value]) } },
    URL: { createObjectURL: () => 'blob:controlled', revokeObjectURL() {} },
    document: { createElement: () => ({ href: '', download: '', click() { downloads.push({ href: this.href, filename: this.download }) } }) },
    window: { open: () => { const output = { html: '', printed: false }; windows.push(output)
      return { document: { write: html => output.html += html, close() {} }, print: () => output.printed = true } } },
    executionDateError: () => '', lateFeeTimingError: () => '', DOC_TYPE: {}, fmtDate: String,
  }
  const source = readFileSync(new URL('../src/' + file, import.meta.url), 'utf8')
  const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
    .replace(/^import[\s\S]*?from ['"][^'"]+['"];?\r?\n/gm, '')
  const scope = Vue.effectScope()
  const output = scope.run(() => vm.runInNewContext(stripTypeScriptTypes(script) + '\n;({' + exported + '})', context))
  return { ...output, props, route, auth, reads, writes, prompts, successes, downloads, windows, messages,
    unmount() { unmounted.forEach(fn => fn()); scope.stop() } }
}
function precase(initialProps = { visible: true, source: { type: 'clues', id: 1, status: 'PENDING', owner: 'admin' } }) {
  return fixture('components/PrecaseAttachments.vue', 'load,rows,loading,ready,writable,uploading,voiding,openUpload,upload,download,voidFile,category,fileInput',
    initialProps)
}
function catalog() {
  return fixture('components/EvidenceCatalog.vue', 'load,items,loading,ready,download,printIndex', { visible: true, caseId: '1' })
}
function caseView() {
  return fixture('views/case/CaseDetailView.vue', 'load,detail,loading,caseReady,attachments,canWriteCase,dlg,documentForm,submitting,submit,docView,docReady,docReading,viewDoc,printDoc,onCatalog,catalog,catalogReady,catalogReading,onRenderTpl,onRenderTplConfirm,tplOptions,tplPickType,openAttachmentUpload,onUpload,uploadCategory,uploadingAttachment,fileInput,onDownload,onVoidAttachment,voidingAttachment,onSignDoc,onVerifyDoc,onPrintArchive,onDeliverDoc,onDocDeliverSubmit,docDeliverForm,deliveringDocument')
}
function seedCase(f, name = 'A') {
  f.detail.value = { caseFile: { id: Number(f.route.params.id), name, status: 'INVESTIGATING' }, documents: [{ id: 11, title: name }] }
  f.attachments.value = [{ id: 7, filename: name + '.pdf', uploaded_by: 'admin' }]
  f.caseReady.value = true
}
function resolveCaseReads(f, name = 'B') {
  for (const read of f.reads.filter(r => !r.settled)) {
    if (/\/cases\/\d+$/.test(read.url)) read.resolve({ caseFile: { id: Number(f.route.params.id), name, status: 'INVESTIGATING' }, documents: [] })
    else read.resolve([])
  }
}
async function seedPrecase(f) {
  f.reads[0].resolve([{ id: 7, filename: 'A.pdf', uploaded_by: 'admin' }]); await tick()
}
function pickedFile() { return { value: 'selected', files: [{ name: 'A.pdf', size: 30 }] } }
function picker(ref) { ref.value = { value: '', click() {} } }

test('precase refresh failure or malformed data clears old actionable materials', async () => {
  for (const malformed of [false, true]) {
    const f = precase(); await seedPrecase(f); const refresh = f.load()
    assert.equal(f.rows.value.length, 0); assert.equal(f.writable.value, false)
    if (malformed) f.reads[1].resolve({ records: [] }); else f.reads[1].reject(new Error('offline'))
    await assert.doesNotReject(refresh)
    assert.equal(f.rows.value.length, 0); assert.equal(f.ready.value, false); f.unmount()
  }
})
test('precase stale failure cannot clear newer materials or stop their spinner', async () => {
  const f = precase(); const newest = f.load(); f.reads[0].reject(new Error('old'))
  await tick(); assert.equal(f.loading.value, true)
  f.reads[1].resolve([{ id: 9 }]); await newest; assert.equal(f.rows.value[0].id, 9); f.unmount()
})
test('closing, changing source or unmounting discards precase results and later loads', async () => {
  for (const action of ['close', 'source', 'unmount']) {
    const f = precase()
    if (action === 'close') f.props.visible = false
    else if (action === 'source') f.props.source.id = 2
    else f.unmount()
    f.reads[0].resolve([{ id: 7, filename: 'old' }]); await tick()
    assert.equal(f.rows.value.length, 0)
    if (action !== 'unmount') f.unmount()
    const count = f.reads.length; await f.load(); assert.equal(f.reads.length, count)
  }
})
test('precase file picker is bound to the source and dialog generation that opened it', async () => {
  for (const action of ['source', 'reopen']) {
    const f = precase(); await seedPrecase(f); picker(f.fileInput); f.openUpload()
    if (action === 'source') f.props.source.id = 2
    else { f.props.visible = false; f.props.visible = true }
    await Vue.nextTick()
    f.reads.at(-1).resolve([]); await tick()
    const input = pickedFile(); await f.upload({ target: input })
    assert.equal(f.writes.length, 0); assert.equal(input.value, ''); f.unmount()
  }
})
test('precase upload snapshots category, rejects duplicates and cannot refresh another source', async () => {
  const f = precase(); await seedPrecase(f); picker(f.fileInput); f.category.value = 'OTHER'; f.openUpload()
  const pending = f.upload({ target: pickedFile() }); f.category.value = 'DOC_SCAN'; f.openUpload()
  await f.upload({ target: pickedFile() }); assert.equal(f.writes.length, 1)
  assert.equal(f.writes[0].url, '/bureau/clues/1/attachments')
  assert.equal(f.writes[0].body.values.find(([key]) => key === 'category')[1], 'OTHER')
  f.props.source.id = 2; await Vue.nextTick(); const reads = f.reads.length; f.writes[0].resolve(); await pending
  assert.equal(f.reads.length, reads); assert.equal(f.successes.length, 0); f.unmount()
})
test('precase failed upload is consumed and never retried automatically', async () => {
  const f = precase(); await seedPrecase(f); picker(f.fileInput); f.openUpload()
  const input = pickedFile(), pending = f.upload({ target: input }); f.writes[0].reject(new Error('denied'))
  await assert.doesNotReject(pending); assert.equal(f.uploading.value, false); assert.equal(input.value, '')
  assert.equal(f.writes.length, 1); assert.equal(f.reads.length, 1); f.unmount()
})
test('precase void prompt has one lock, consumes cancellation and rechecks role/context', async () => {
  for (const action of ['cancel', 'role', 'source']) {
    const f = precase(); await seedPrecase(f); const row = f.rows.value[0], pending = f.voidFile(row)
    await f.voidFile(row); assert.equal(f.prompts.length, 1)
    if (action === 'cancel') f.prompts[0].reject('cancel')
    else { if (action === 'role') f.auth.user.roles = ['LEGAL']; else f.props.source.id = 2; f.prompts[0].resolve({ value: '误传' }) }
    await assert.doesNotReject(pending); assert.equal(f.writes.length, 0); assert.equal(f.voiding.value, false); f.unmount()
  }
})
test('precase downloads cannot duplicate, save error bytes or complete in a departed context', async () => {
  for (const action of ['failure', 'source', 'unmount']) {
    const f = precase(); await seedPrecase(f); const row = f.rows.value[0], pending = f.download(row)
    await f.download(row); assert.equal(f.reads.length, 2)
    if (action === 'failure') f.reads[1].reject(new Error('403'))
    else { if (action === 'source') f.props.source.id = 2; else f.unmount(); f.reads[1].resolve('blob') }
    await assert.doesNotReject(pending); assert.equal(f.downloads.length, 0); if (action !== 'unmount') f.unmount()
  }
})
test('evidence catalog refresh failure clears printable old items', async () => {
  const f = catalog(); f.reads[0].resolve({ items: [{ id: 1, kind: 'ATTACHMENT', number: 'F1' }] }); await tick()
  const refresh = f.load(); f.printIndex(); assert.equal(f.windows.length, 0)
  f.reads[1].reject(new Error('offline')); await assert.doesNotReject(refresh)
  f.printIndex(); assert.equal(f.items.value.length, 0); assert.equal(f.ready.value, false); assert.equal(f.windows.length, 0); f.unmount()
})
test('evidence catalog stale results and failures cannot replace or finish the latest request', async () => {
  const f = catalog(), newest = f.load(); f.reads[0].reject(new Error('old')); await tick(); assert.equal(f.loading.value, true)
  f.reads[1].resolve({ items: [{ id: 2, kind: 'ATTACHMENT', number: 'F2' }] }); await newest
  assert.equal(f.items.value[0].id, 2); f.unmount()
})
test('closed, changed identity and unmounted evidence catalogs discard old contents', async () => {
  for (const action of ['close', 'identity', 'unmount']) {
    const f = catalog()
    if (action === 'close') f.props.visible = false
    else if (action === 'identity') f.auth.user.username = 'other'
    else f.unmount()
    f.reads[0].resolve({ items: [{ id: 1 }] }); await tick()
    assert.equal(f.items.value.length, 0); if (action !== 'unmount') f.unmount()
  }
})
test('catalog download snapshots its filename but never saves an obsolete or rejected blob', async () => {
  for (const action of ['valid', 'case', 'failure']) {
    const f = catalog(), row = { id: 5, kind: 'ATTACHMENT', number: 'F5', filename: 'original.pdf' }
    f.reads[0].resolve({ items: [row] }); await tick(); const pending = f.download(row)
    row.filename = 'edited.pdf'; await f.download(row); assert.equal(f.reads.length, 2)
    if (action === 'case') f.props.caseId = '2'
    if (action === 'failure') f.reads[1].reject(new Error('403')); else f.reads[1].resolve('file')
    await assert.doesNotReject(pending); assert.equal(f.downloads.length, action === 'valid' ? 1 : 0)
    if (action === 'valid') assert.equal(f.downloads[0].filename, 'original.pdf'); f.unmount()
  }
})

test('case upload picker cannot submit an old selection into the next case', async () => {
  const f = caseView(); seedCase(f); picker(f.fileInput); f.openAttachmentUpload(); f.route.params.id = '2'
  resolveCaseReads(f); await tick(); const input = pickedFile(); await f.onUpload({ target: input })
  assert.equal(f.writes.length, 0); assert.equal(input.value, ''); f.unmount()
})
test('case upload uses one immutable original target and suppresses late completion after navigation', async () => {
  const f = caseView(); seedCase(f); picker(f.fileInput); f.uploadCategory.value = 'OTHER'; f.openAttachmentUpload()
  const pending = f.onUpload({ target: pickedFile() }); f.openAttachmentUpload(); await f.onUpload({ target: pickedFile() })
  assert.equal(f.writes.length, 1); assert.equal(f.writes[0].url, '/bureau/cases/1/attachments')
  assert.equal(f.writes[0].body.values.find(([key]) => key === 'category')[1], 'OTHER')
  f.route.params.id = '2'; resolveCaseReads(f); await tick(); const reads = f.reads.length
  f.writes[0].resolve(); await pending; assert.equal(f.successes.length, 0); assert.equal(f.reads.length, reads)
  assert.equal(f.detail.value.caseFile.name, 'B'); assert.equal(f.uploadingAttachment.value, false); f.unmount()
})
test('failed case upload resets the input and lock without refreshing or retrying', async () => {
  const f = caseView(); seedCase(f); picker(f.fileInput); f.openAttachmentUpload()
  const input = pickedFile(), pending = f.onUpload({ target: input }); f.writes[0].reject(new Error('sealed'))
  await assert.doesNotReject(pending); assert.equal(input.value, ''); assert.equal(f.uploadingAttachment.value, false)
  assert.equal(f.writes.length, 1); assert.equal(f.reads.length, 0); f.unmount()
})
test('case attachment void cannot reuse a prompt after leaving and returning to the same case ID', async () => {
  const f = caseView(); seedCase(f); const pending = f.onVoidAttachment(f.attachments.value[0])
  await f.onVoidAttachment(f.attachments.value[0]); assert.equal(f.prompts.length, 1)
  f.route.params.id = '2'; f.route.params.id = '1'; resolveCaseReads(f); await tick()
  f.prompts[0].resolve({ value: '误传' }); await pending
  assert.equal(f.writes.length, 0); assert.equal(f.voidingAttachment.value, false); f.unmount()
})
test('signature confirmation is cancelled on role loss, case switch, cancellation or unmount', async () => {
  for (const action of ['role', 'case', 'cancel', 'unmount']) {
    const f = caseView(); seedCase(f); const pending = f.onSignDoc({ id: 11 })
    await f.onSignDoc({ id: 11 }); assert.equal(f.prompts.length, 1)
    if (action === 'role') f.auth.user.roles = ['LEGAL']
    else if (action === 'case') f.route.params.id = '2'
    else if (action === 'unmount') f.unmount()
    if (action === 'cancel') f.prompts[0].reject('cancel'); else f.prompts[0].resolve({ value: '签章人' })
    await assert.doesNotReject(pending); assert.equal(f.writes.length, 0); if (action !== 'unmount') f.unmount()
  }
})
test('late template options cannot open a draft for a different case', async () => {
  const f = caseView(); seedCase(f); const pending = f.onRenderTpl(); f.route.params.id = '2'
  f.reads[0].resolve([{ doc_type: 'NOTICE' }]); await pending
  assert.equal(f.dlg.tplPick, false); assert.equal(f.tplOptions.value.length, 0); f.unmount()
})
test('closing and reopening template selection invalidates an old rendered document', async () => {
  const f = caseView(); seedCase(f); f.dlg.tplPick = true; f.tplPickType.value = 'NOTICE'
  const pending = f.onRenderTplConfirm(); f.dlg.tplPick = false; f.dlg.tplPick = true
  f.reads[0].resolve({ docType: 'NOTICE', title: 'old', content: 'old' }); await pending
  assert.equal(f.dlg.document, false); assert.equal(f.documentForm.title, ''); f.unmount()
})
test('rendering a template cannot overwrite a manually opened document draft', async () => {
  const f = caseView(); seedCase(f); f.dlg.tplPick = true; f.tplPickType.value = 'NOTICE'
  const pending = f.onRenderTplConfirm(); f.dlg.document = true; f.documentForm.title = 'manual'
  f.reads[0].resolve({ docType: 'NOTICE', title: 'old', content: 'old' }); await pending
  assert.equal(f.documentForm.title, 'manual'); f.unmount()
})
test('template rendering takes one snapshot and resets stale signature/maker/deadline fields', async () => {
  const f = caseView(); seedCase(f); f.dlg.tplPick = true; f.tplPickType.value = 'NOTICE'
  Object.assign(f.documentForm, { signed: true, maker: 'old', dueAt: '2030-01-01' })
  const pending = f.onRenderTplConfirm(); await f.onRenderTplConfirm(); assert.equal(f.reads.length, 1)
  f.reads[0].resolve({ docType: 'NOTICE', title: 'new', content: 'body' }); await pending
  assert.equal(f.dlg.document, true); assert.equal(f.documentForm.signed, false); assert.equal(f.documentForm.maker, '')
  assert.equal(f.documentForm.dueAt, null); f.unmount()
})
test('document views stay paired with the newest row and cannot reopen after close', async () => {
  const f = caseView(); seedCase(f); const first = f.viewDoc({ id: 11 }), second = f.viewDoc({ id: 12 })
  f.reads[1].resolve({ id: 12, title: 'new' }); await second
  f.reads[0].resolve({ id: 11, title: 'old' }); await first; assert.equal(f.docView.value.id, 12)
  const closed = f.viewDoc({ id: 13 }); f.dlg.doc = false; f.reads[2].resolve({ id: 13 }); await closed
  assert.equal(f.dlg.doc, false); assert.equal(f.docReady.value, false); assert.equal(Object.keys(f.docView.value).length, 0); f.unmount()
})
test('failed document read clears old content, disables printing and consumes rejection', async () => {
  const f = caseView(); seedCase(f); const first = f.viewDoc({ id: 11 }); f.reads[0].resolve({ id: 11, title: 'old' }); await first
  const failing = f.viewDoc({ id: 12 }); f.printDoc(); assert.equal(f.windows.length, 0)
  f.reads[1].reject(new Error('403')); await assert.doesNotReject(failing); f.printDoc()
  assert.equal(f.windows.length, 0); assert.equal(f.docReady.value, false); assert.equal(Object.keys(f.docView.value).length, 0); f.unmount()
})
test('unmount invalidates pending document content and cannot start more reads', async () => {
  const f = caseView(); seedCase(f); const pending = f.viewDoc({ id: 11 }); f.unmount()
  f.reads[0].resolve({ id: 11, title: 'late' }); await pending; await f.viewDoc({ id: 12 })
  assert.equal(f.reads.length, 1); assert.equal(Object.keys(f.docView.value).length, 0)
})
test('archive catalog cannot reopen on a late result and failures hide old completeness claims', async () => {
  const f = caseView(); seedCase(f); const pending = f.onCatalog(); f.dlg.catalog = false
  f.reads[0].resolve({ catalog: [{ title: 'old' }], missing: [] }); await pending
  assert.equal(f.dlg.catalog, false); assert.equal(f.catalogReady.value, false)
  const failure = f.onCatalog(); f.reads[1].reject(new Error('403')); await assert.doesNotReject(failure)
  assert.equal(f.catalogReady.value, false); assert.equal(Object.keys(f.catalog.value).length, 0); f.unmount()
})
test('document save snapshots payload, rejects duplicate clicks and preserves a reopened draft', async () => {
  const f = caseView(); seedCase(f); f.dlg.document = true; f.documentForm.title = 'original'; f.documentForm.content = 'body'
  const pending = f.submit('documents', f.documentForm, 'document'); f.documentForm.title = 'edited'
  await f.submit('documents', f.documentForm, 'document'); assert.equal(f.writes.length, 1)
  assert.equal(f.writes[0].body.title, 'original'); f.dlg.document = false; f.dlg.document = true
  f.writes[0].resolve(); await pending
  assert.equal(f.documentForm.title, 'edited'); assert.equal(f.dlg.document, true); assert.equal(f.successes.length, 0)
  assert.equal(f.reads.length, 0); f.unmount()
})
test('material save failures retain the draft and do not retry or announce success', async () => {
  const f = caseView(); seedCase(f); f.dlg.evidence = true
  const body = Vue.reactive({ name: '证据草稿' }), pending = f.submit('evidences', body, 'evidence')
  f.writes[0].reject(new Error('invalid')); await assert.doesNotReject(pending)
  assert.equal(body.name, '证据草稿'); assert.equal(f.dlg.evidence, true); assert.equal(f.submitting.value, false)
  assert.equal(f.writes.length, 1); assert.equal(f.successes.length, 0); f.unmount()
})
test('opening document delivery resets prior recipient and receipt; its post uses a snapshot', async () => {
  const f = caseView(); seedCase(f); Object.assign(f.docDeliverForm, { receiver: 'old', receiptNo: 'old', method: 'POSTAL' })
  f.onDeliverDoc({ id: 11, title: 'A', doc_type: 'NOTICE' })
  assert.equal(f.docDeliverForm.receiver, ''); assert.equal(f.docDeliverForm.receiptNo, ''); assert.equal(f.docDeliverForm.method, 'DIRECT')
  f.docDeliverForm.receiver = 'new'; const pending = f.onDocDeliverSubmit(); f.docDeliverForm.receiver = 'edited'
  await f.onDocDeliverSubmit(); assert.equal(f.writes.length, 1); assert.equal(f.writes[0].body.receiver, 'new')
  f.route.params.id = '2'; const count = f.reads.length; f.writes[0].resolve(); await pending
  assert.equal(f.successes.length, 0); assert.equal(f.reads.length, count); f.unmount()
})
test('departed or rejected whole-archive reads cannot open print windows', async () => {
  for (const action of ['case', 'unmount', 'failure']) {
    const f = caseView(); seedCase(f); const pending = f.onPrintArchive()
    if (action === 'case') f.route.params.id = '2'
    else if (action === 'unmount') f.unmount()
    if (action === 'failure') f.reads[0].reject(new Error('403')); else f.reads[0].resolve({ documents: [], signatures: [] })
    await assert.doesNotReject(pending); assert.equal(f.windows.length, 0); if (action !== 'unmount') f.unmount()
  }
})
test('case download snapshots filename and does not save after role loss or failure', async () => {
  for (const action of ['valid', 'role', 'failure']) {
    const f = caseView(); seedCase(f); const row = f.attachments.value[0], pending = f.onDownload(row)
    row.filename = 'edited.pdf'; await f.onDownload(row); assert.equal(f.reads.length, 1)
    if (action === 'role') f.auth.user.roles = ['LEGAL']
    if (action === 'failure') f.reads[0].reject(new Error('403')); else f.reads[0].resolve('file')
    await assert.doesNotReject(pending); assert.equal(f.downloads.length, action === 'valid' ? 1 : 0)
    if (action === 'valid') assert.equal(f.downloads[0].filename, 'A.pdf'); f.unmount()
  }
})
test('changing cases immediately hides old writable data and logout discards pending case reads', async () => {
  const f = caseView(); seedCase(f); f.route.params.id = '2'
  assert.equal(f.canWriteCase.value, false); assert.equal(f.attachments.value.length, 0)
  assert.equal(Object.keys(f.detail.value).length, 0); f.auth.user = null
  resolveCaseReads(f); await tick()
  assert.equal(f.caseReady.value, false); assert.equal(Object.keys(f.detail.value).length, 0); f.unmount()
})
test('successful precase upload refreshes only its original visible source', async () => {
  const f = precase(); await seedPrecase(f); picker(f.fileInput); f.openUpload()
  const pending = f.upload({ target: pickedFile() }); f.writes[0].resolve(); await tick()
  assert.equal(f.reads[1].url, '/bureau/clues/1/attachments'); f.reads[1].resolve([{ id: 8, filename: 'new.pdf' }]); await pending
  assert.equal(f.rows.value[0].id, 8); assert.equal(f.uploading.value, false); assert.equal(f.successes.length, 1); f.unmount()
})
test('successful material save resets its own form and refreshes the original case', async () => {
  const f = caseView(); seedCase(f); f.dlg.document = true; f.documentForm.title = 'new'; f.documentForm.content = 'body'
  const pending = f.submit('documents', f.documentForm, 'document'); f.writes[0].resolve(); await tick()
  assert.equal(f.reads.length, 4); assert.equal(f.dlg.document, false); assert.equal(f.documentForm.title, '')
  resolveCaseReads(f, 'A refreshed'); await pending
  assert.equal(f.detail.value.caseFile.name, 'A refreshed'); assert.equal(f.caseReady.value, true); assert.equal(f.successes.length, 1); f.unmount()
})
test('successful document preview remains printable and escapes user content', async () => {
  const f = caseView(); seedCase(f); const pending = f.viewDoc({ id: 11 })
  f.reads[0].resolve({ id: 11, title: '<img onerror=x>', content: '<script>alert(1)</script>' }); await pending
  f.printDoc(); assert.equal(f.windows.length, 1); assert.equal(f.windows[0].printed, true)
  assert.ok(f.windows[0].html.includes('&lt;script&gt;')); assert.ok(!f.windows[0].html.includes('<script>')); f.unmount()
})
test('a failed case refresh also clears open cached document and catalog contents', async () => {
  const f = caseView(); seedCase(f); const preview = f.viewDoc({ id: 11 })
  f.reads[0].resolve({ id: 11, title: 'old', content: 'old' }); await preview
  const refresh = f.load(); assert.equal(f.dlg.doc, false); assert.equal(f.docReady.value, false)
  f.reads[1].reject(new Error('scope changed')); await assert.doesNotReject(refresh)
  f.printDoc(); assert.equal(f.windows.length, 0); assert.equal(f.canWriteCase.value, false); f.unmount()
})
test('opening a hidden precase dialog batches visible and source props into one final-source request', async () => {
  for (const type of ['clues', 'inspections']) {
    for (const visibleFirst of [true, false]) {
      const f = precase({ visible: false, source: { type: 'clues', id: 0 } })
      assert.equal(f.reads.length, 0)
      if (visibleFirst) f.props.visible = true
      f.props.source = { type, id: 4, status: 'PENDING', owner: 'admin' }
      if (!visibleFirst) f.props.visible = true
      assert.equal(f.reads.length, 0, 'prop patching must not synchronously issue an HTTP request')
      await Vue.nextTick()
      assert.equal(f.reads.length, 1); assert.equal(f.reads[0].url, `/bureau/${type}/4/attachments`)
      f.reads[0].resolve([{ id: 7 }]); await tick(); assert.equal(f.rows.value[0].id, 7); f.unmount()
    }
  }
})
test('precase initial or explicit loads never request zero, negative, fractional or invalid source IDs', async () => {
  for (const id of [0, -1, 1.5, NaN, Infinity, '4']) {
    const f = precase({ visible: true, source: { type: 'clues', id, status: 'PENDING', owner: 'admin' } })
    await f.load(); assert.equal(f.reads.length, 0); assert.equal(f.ready.value, false)
    f.props.source.id = 4; await Vue.nextTick(); assert.equal(f.reads.length, 1)
    assert.equal(f.reads[0].url, '/bureau/clues/4/attachments'); f.unmount()
  }
})
test('identity changes suppress upload completion even before the batched props watcher executes', async () => {
  const f = precase(); await seedPrecase(f); picker(f.fileInput); f.openUpload()
  const pending = f.upload({ target: pickedFile() })
  // Queue the HTTP continuation first, then the post-flush watcher. Its captured
  // context must still reject success effects for the newly signed-in identity.
  f.writes[0].resolve(); f.auth.user.username = 'other'
  await pending; assert.equal(f.successes.length, 0); assert.equal(f.uploading.value, false); f.unmount()
})
