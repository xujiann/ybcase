import test from 'node:test'
import assert from 'node:assert/strict'
import { createPreflightCheck, prioritizePreflightItems } from '../src/api/preflight.ts'
function fixture() {
  const state = { result: null, checking: false }
  let current = { caseId: '1', body: { action: 'DECIDE', decision: { fineAmount: 100, content: '决定' } } }
  const pending = []
  const guard = createPreflightCheck(state, () => current, (id, body) => new Promise((resolve, reject) => pending.push({id,body,resolve,reject})))
  return {state, guard, pending, get current() {return current}, set current(value) {current=value}}
}
const result = ready => ({action:'DECIDE',ready,items:[{key:'hearing',label:'听证',passed:ready,message:ready?'完成':'尚未举行',role:'HANDLER'}]})
test('a failed preflight lists conditions and preserves the editable form', async () => {
  const f=fixture(), original=structuredClone(f.current)
  const checked=f.guard.check()
  f.pending[0].resolve(result(false))
  assert.equal(await checked,false)
  assert.equal(f.state.result.items[0].message,'尚未举行')
  assert.deepEqual(f.current,original)
  assert.equal(f.state.checking,false)
})
test('form change discards a response for an older amount', async () => {
  const f=fixture(), checked=f.guard.check()
  f.current.body.decision.fineAmount=200
  f.guard.invalidate()
  f.pending[0].resolve(result(true))
  assert.equal(await checked,false)
  assert.equal(f.state.result,null)
})
test('switching cases cannot accept an earlier case response even without explicit invalidation', async () => {
  const f=fixture(), checked=f.guard.check()
  f.current.caseId='2'
  f.pending[0].resolve(result(true))
  assert.equal(await checked,false)
  assert.equal(f.state.result,null)
})
test('an older request cannot replace a newer checklist or stop its spinner', async () => {
  const f=fixture(), old=f.guard.check(), latest=f.guard.check()
  f.pending[0].resolve(result(true))
  assert.equal(await old,false)
  assert.equal(f.state.checking,true)
  f.pending[1].resolve(result(false))
  assert.equal(await latest,false)
  assert.equal(f.state.result.ready,false)
})
test('transport errors fail closed, clear old results and allow retry', async () => {
  const f=fixture()
  f.state.result=result(true)
  const checked=f.guard.check()
  f.pending[0].reject(new Error('offline'))
  await assert.rejects(checked,/offline/)
  assert.equal(f.state.result,null)
  assert.equal(f.state.checking,false)
})
test('ready requires correct action and every item passed', async () => {
  const f=fixture(), checked=f.guard.check()
  f.pending[0].resolve({...result(false),ready:true})
  assert.equal(await checked,false)
  const retry=f.guard.check()
  f.pending[1].resolve({...result(true),action:'CLOSE'})
  assert.equal(await retry,false)
  assert.equal(f.state.result,null)
})
test('successful check snapshots the submitted form without mutating it', async () => {
  const f=fixture(), checked=f.guard.check()
  assert.notEqual(f.pending[0].body,f.current.body)
  f.pending[0].resolve(result(true))
  assert.equal(await checked,true)
  assert.equal(f.state.result.ready,true)
})

test('unmet checklist conditions appear first, preserving server order without mutation', () => {
  const items = [{key:'ok1',passed:true},{key:'missing1',passed:false},{key:'ok2',passed:true},{key:'missing2',passed:false}]
  const original = structuredClone(items)
  assert.deepEqual(prioritizePreflightItems(items).map(item => item.key), ['missing1','missing2','ok1','ok2'])
  assert.deepEqual(items,original)
})
