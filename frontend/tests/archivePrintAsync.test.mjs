import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import vm from 'node:vm'
import { evidenceCatalogHtml } from '../src/api/evidenceCatalog.ts'

const source = readFileSync(new URL('../src/views/case/CaseDetailView.vue', import.meta.url), 'utf8')
function handler(name) {
  const start = source.indexOf(`function ${name}(`)
  assert.ok(start >= 0)
  return source.slice(source.slice(0, start).endsWith('async ') ? start - 6 : start, source.indexOf('\n}', start) + 2)
}
function fixture() {
  const reads = [], windows = []
  const state = { caseId: '1', generation: 0, popupBlocked: false }
  const context = { materialDisposed: false, caseReady: { value: true }, loading: { value: false }, printingArchive: { value: false },
    captureMaterialContext: () => ({ caseId: state.caseId, generation: state.generation }),
    materialContextCurrent: captured => !context.materialDisposed && captured.caseId === state.caseId && captured.generation === state.generation,
    client: { get: url => new Promise((resolve, reject) => reads.push({ url, resolve: data => resolve({ data: { data } }), reject })) },
    window: { open: () => {
      if (state.popupBlocked) return null
      const output = { html: '', printed: false }; windows.push(output)
      return { document: { write: html => output.html += html, close() {} }, print: () => output.printed = true }
    } }, evidenceCatalogHtml, fmtDate: value => value,
  }
  const print = vm.runInNewContext(stripTypeScriptTypes(handler('esc') + '\n' + handler('onPrintArchive')) + ';onPrintArchive', context)
  return { print, reads, windows, state, context }
}
function archive(name = '案卷') {
  return { caseFile: { name, case_no: '医保案1', archive_no: '卷1' }, orgName: '测试局', signatures: [], documents: [] }
}

test('repeated whole-archive clicks share one request and one printed snapshot', async () => {
  const f = fixture(), pending = f.print()
  await f.print(); await f.print()
  assert.equal(f.reads.length, 1); assert.equal(f.reads[0].url, '/bureau/cases/1/archive-full')
  assert.equal(f.context.printingArchive.value, true); assert.equal(f.windows.length, 0)
  f.reads[0].resolve(archive('唯一快照')); await pending
  assert.equal(f.windows.length, 1); assert.equal(f.windows[0].printed, true)
  assert.ok(f.windows[0].html.includes('唯一快照')); assert.equal(f.context.printingArchive.value, false)
})
test('a rejected whole-archive read releases the lock and permits a deliberate retry', async () => {
  const f = fixture(), pending = f.print(); f.reads[0].reject(new Error('offline'))
  await assert.doesNotReject(pending); assert.equal(f.context.printingArchive.value, false); assert.equal(f.windows.length, 0)
  const retry = f.print(); assert.equal(f.reads.length, 2)
  f.reads[1].resolve(archive('重试快照')); await retry
  assert.equal(f.windows.length, 1); assert.ok(f.windows[0].html.includes('重试快照'))
})
test('leaving or invalidating the captured case cannot print and still releases the lock', async () => {
  for (const changed of ['case', 'identity', 'unmount']) {
    const f = fixture(), pending = f.print()
    if (changed === 'case') f.state.caseId = '2'
    else if (changed === 'identity') f.state.generation++
    else f.context.materialDisposed = true
    f.reads[0].resolve(archive('旧快照')); await pending
    assert.equal(f.windows.length, 0); assert.equal(f.context.printingArchive.value, false)
  }
})
test('a browser-blocked print popup releases the lock without retrying the read', async () => {
  const f = fixture(); f.state.popupBlocked = true
  const pending = f.print(); f.reads[0].resolve(archive()); await pending
  assert.equal(f.reads.length, 1); assert.equal(f.windows.length, 0); assert.equal(f.context.printingArchive.value, false)
})
test('successful whole-archive printing keeps server document order and escapes supplied text', async () => {
  const f = fixture(), pending = f.print(), data = archive('<script>case()</script>')
  data.documents = [
    { id: 20, title: '先装订', content: '<img onerror=bad()>', made_at: '2026-10-27', source: 'DOCUMENT' },
    { id: 3, title: '后装订', content: '正文', made_at: '2026-10-27', source: 'DOCUMENT' },
  ]
  data.signatures = [{ document_id: 20, signer: '<script>signer()</script>', signed_at: '2026-10-27', provider: '<mock>' }]
  f.reads[0].resolve(data); await pending
  const html = f.windows[0].html
  assert.ok(html.indexOf('先装订') < html.indexOf('后装订'))
  assert.ok(html.includes('&lt;script&gt;case()&lt;/script&gt;'))
  assert.ok(html.includes('&lt;img onerror=bad()&gt;')); assert.ok(html.includes('&lt;mock&gt;'))
  assert.equal(html.includes('<script>'), false); assert.equal(html.includes('<img '), false)
})
test('unready or disposed case handlers cannot request a whole-archive snapshot', async () => {
  for (const guard of ['unready', 'loading', 'disposed']) {
    const f = fixture()
    if (guard === 'unready') f.context.caseReady.value = false
    else if (guard === 'loading') f.context.loading.value = true
    else f.context.materialDisposed = true
    await f.print(); assert.equal(f.reads.length, 0); assert.equal(f.context.printingArchive.value, false)
  }
})
