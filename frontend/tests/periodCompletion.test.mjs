import test from 'node:test'
import assert from 'node:assert/strict'
import { canCompletePeriod, canStartPeriod, periodCutoff, periodEndError } from '../src/api/periodCompletion.ts'
test('open periods can be completed in legacy terminated cases, while archived and legal-only cases are read-only', () => {
  assert.equal(canCompletePeriod(['HANDLER'],{status:'TERMINATED'}),true)
  assert.equal(canCompletePeriod(['LEADER'],{status:'DECIDED'}),true)
  assert.equal(canCompletePeriod(['ADMIN'],{status:'TERMINATED',archiveNo:'卷号'}),false)
  assert.equal(canCompletePeriod(['ADMIN'],{status:'CLOSED'}),false)
  assert.equal(canCompletePeriod(['LEGAL'],{status:'INVESTIGATING'}),false)
  assert.equal(canCompletePeriod(['HANDLER'],{}),false)
})
test('new exclusions and expert reviews are restricted to active investigation phases', () => {
  for (const status of ['INVESTIGATING','REPORTED','NOTIFIED']) assert.equal(canStartPeriod(['HANDLER'],{status}),true)
  for (const status of ['SUSPENDED','DECIDED','DELIVERED','TERMINATED','CLOSED']) assert.equal(canStartPeriod(['ADMIN'],{status}),false)
})
test('end date is bounded by registered start, today and the earliest procedural endpoint', () => {
  const c={decidedAt:'2026-10-15',terminatedAt:'2026-10-18',closedAt:'2026-10-19'}
  assert.equal(periodCutoff(c,'2026-10-21'),'2026-10-15')
  assert.equal(periodEndError('2026-10-10','2026-10-15',c,'2026-10-21'),'')
  assert.match(periodEndError('2026-10-10','2026-10-16',c,'2026-10-21'),/2026-10-15/)
  assert.match(periodEndError('2026-10-10','2026-10-09',{},'2026-10-21'),/早于/)
  assert.match(periodEndError('2026-10-10','2026-10-22',{},'2026-10-21'),/2026-10-21/)
})
test('invalid dates and empty selections cannot be submitted', () => {
  for (const end of ['', '2026-02-30', '2026-13-01', 'arbitrary']) assert.match(periodEndError('2026-01-01',end,{},'2026-10-21'),/有效/)
})
