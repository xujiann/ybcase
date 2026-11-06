import test from 'node:test'
import assert from 'node:assert/strict'
import { catalogAssociation, evidenceCatalogHtml } from '../src/api/evidenceCatalog.ts'
test('catalog uses server material numbers and shows every attachment association', () => {
  const item={number:'FJ-00000001',kind:'ATTACHMENT',id:1,evidenceId:9,evidenceTitle:'笔录',documentId:8,documentTitle:'文书',sourceClueId:7,sourceInspectionId:6,filename:'材料.pdf'}
  assert.equal(catalogAssociation(item),'证据#9（笔录）；文书#8（文书）；线索#7；检查#6')
  const html=evidenceCatalogHtml([item])
  assert.ok(html.includes('FJ-00000001'))
  assert.ok(html.includes('不包含附件正文'))
  assert.equal((html.match(/材料.pdf/g)||[]).length,1)
})
test('malicious filenames, titles, source labels and void reasons are escaped in printed indices', () => {
  const html=evidenceCatalogHtml([{id:1,kind:'ATTACHMENT',number:'FJ-<script>',filename:'<img src=x onerror=alert(1)>',evidenceId:1,evidenceTitle:'<script>steal()</script>',voided:true,voidReason:'<iframe src=bad>'}])
  assert.equal(html.includes('<script>'),false)
  assert.equal(html.includes('<img '),false)
  assert.equal(html.includes('<iframe '),false)
  assert.ok(html.includes('&lt;img'))
  assert.ok(html.includes('已作废'))
  assert.ok(html.includes('非有效卷'))
})
test('normal catalog remains an effective text index with no fabricated pagination or embedded binary', () => {
  const html=evidenceCatalogHtml([{id:42,kind:'EVIDENCE',number:'ZJ-00000042',title:'书证'}])
  assert.ok(html.includes('证据卷文字索引'))
  assert.equal(html.includes('非有效卷'),false)
  assert.equal(html.includes('<object'),false)
  assert.equal(html.includes('data:'),false)
})

test('tracing mode remains clearly labeled even when it contains no voided rows', () => {
  assert.ok(evidenceCatalogHtml([],true).includes('非有效卷'))
})
