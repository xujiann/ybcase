import test from 'node:test'
import assert from 'node:assert/strict'
import { downloadError } from '../src/api/download.ts'

test('JSON attachment containing an error-shaped object remains downloadable', async () => {
  const blob = new Blob([JSON.stringify({code: 2040, message: 'sample data', data: null})])
  assert.equal(await downloadError(blob, 'application/json', 'attachment; filename="backup.json"'), null)
})
test('business error JSON without attachment reports its code and message', async () => {
  assert.deepEqual(await downloadError(new Blob(['{"code":403,"message":"禁止下载","data":null}']), 'application/json;charset=UTF-8'), {code:403, message:'禁止下载'})
})
test('regular binary files and successful JSON are not business errors', async () => {
  assert.equal(await downloadError(new Blob(['file']), 'application/octet-stream'), null)
  assert.equal(await downloadError(new Blob(['{"code":0,"message":"ok","data":[]}']), 'application/json'), null)
})
test('malformed JSON and arbitrary JSON metadata do not break downloads', async () => {
  assert.equal(await downloadError(new Blob(['{invalid']), 'application/json'), null)
  assert.equal(await downloadError(new Blob(['{"code":123,"message":"metadata"}']), 'application/json'), null)
})
