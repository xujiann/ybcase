import test from 'node:test'
import assert from 'node:assert/strict'
import { emptyJurisdiction, serializeJurisdiction, jurisdictionError, canEditJurisdiction } from '../src/api/jurisdiction.ts'
test('new and reset records are independent pending drafts without invented facts', () => {
  const old=emptyJurisdiction(), fresh=emptyJurisdiction()
  old.type='DESIGNATED'; old.designatedNo='批复1'
  assert.equal(fresh.type,'PENDING')
  assert.equal(fresh.designatedNo,'')
  assert.equal(jurisdictionError(fresh),'')
})
test('serialization trims known facts and excludes unrelated form values', () => {
  const draft={...emptyJurisdiction(),type:'LOCAL',basis:' 依据 ',violationPlace:' 发生地 ',reason:'不应写入事实快照'}
  assert.deepEqual(serializeJurisdiction(draft),{type:'LOCAL',basis:'依据',violationPlace:'发生地',treatmentPlace:'',insuredPlace:'',designatedNo:'',disputeNote:''})
  assert.equal(draft.basis,' 依据 ')
})
test('record validation matches basis, designation and local-place requirements', () => {
  assert.match(jurisdictionError({...emptyJurisdiction(),type:'TRANSFERRED'}),/依据/)
  assert.match(jurisdictionError({...emptyJurisdiction(),type:'DESIGNATED',basis:'批复'}),/文号/)
  assert.match(jurisdictionError({...emptyJurisdiction(),type:'LOCAL',basis:'本地事实'}),/至少填写/)
  assert.equal(jurisdictionError({...emptyJurisdiction(),type:'LOCAL',basis:'事实',insuredPlace:'参保地'}),'')
  assert.match(jurisdictionError({...emptyJurisdiction(),basis:'a'.repeat(2001)}),/长度/)
  assert.match(jurisdictionError({...emptyJurisdiction(),type:'INVENTED'}),/有效/)
})
test('only handlers and leaders can edit writable cases, legal-only and archived cases stay read-only', () => {
  for (const role of ['HANDLER','LEADER','ADMIN']) {
    for (const status of ['INVESTIGATING','REPORTED','NOTIFIED']) assert.equal(canEditJurisdiction([role],{status}),true)
    for (const status of ['SUSPENDED','DECIDED','DELIVERED','TERMINATED','CLOSED']) assert.equal(canEditJurisdiction([role],{status}),false)
    assert.equal(canEditJurisdiction([role],{status:'INVESTIGATING',archiveNo:'已归档'}),false)
  }
  assert.equal(canEditJurisdiction(['LEGAL'],{status:'NOTIFIED'}),false)
  assert.equal(canEditJurisdiction(undefined,{status:'NOTIFIED'}),false)
})
