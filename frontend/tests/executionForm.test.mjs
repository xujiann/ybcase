import test from 'node:test'
import assert from 'node:assert/strict'
import { executionDateError, lateFeeTimingError } from '../src/api/executionForm.ts'
test('payment dates reject future, pre-decision and invalid dates, while inclusive limits are valid', () => {
  assert.equal(executionDateError('2026-10-21','2026-10-01','2026-10-21'),'')
  assert.equal(executionDateError('2026-10-01','2026-10-01','2026-10-21'),'')
  assert.match(executionDateError('2026-10-22','2026-10-01','2026-10-21'),/今天/)
  assert.match(executionDateError('2026-09-30','2026-10-01','2026-10-21'),/决定/)
  assert.match(executionDateError('2026-02-30','2026-01-01','2026-10-21'),/有效/)
  assert.match(executionDateError('2026-10-21',undefined,'2026-10-21'),/尚未明确/)
})
test('late fee dates require valid service and confirmed elapsed payment deadline, with no invented defaults', () => {
  assert.equal(lateFeeTimingError('2026-10-21','PUNISH','DELIVERED','2026-10-01','2026-10-20'),'')
  assert.match(lateFeeTimingError('2026-10-20','PUNISH','DELIVERED','2026-10-01','2026-10-20'),/届满后/)
  assert.match(lateFeeTimingError('2026-10-21','PUNISH','DELIVERED','2026-10-01',undefined),/尚未明确/)
  assert.match(lateFeeTimingError('2026-10-21','PUNISH','DECIDED',undefined,'2026-10-20'),/有效送达/)
})
