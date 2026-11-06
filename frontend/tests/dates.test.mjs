import test from 'node:test'
import assert from 'node:assert/strict'
import { formatLocalDate, formatLocalDateTime, localDate, createTodayReset } from '../src/api/dates.ts'

// Node supports TZ on Windows too. These cases reproduce the deployed Shanghai calendar.
process.env.TZ = 'Asia/Shanghai'
test('UTC evening timestamps display the next Shanghai day and clock time', () => {
  assert.equal(formatLocalDateTime('2026-10-20T16:30:00Z'),'2026-10-21 00:30')
  assert.equal(formatLocalDate('2026-10-20T16:30:00Z'),'2026-10-21')
  assert.equal(formatLocalDateTime('2026-10-21T00:30:00+08:00'),'2026-10-21 00:30')
})
test('calendar-only and offset-less database timestamps preserve their calendar and wall time', () => {
  assert.equal(formatLocalDate('2026-10-21'),'2026-10-21')
  assert.equal(formatLocalDateTime('2026-10-21'),'2026-10-21')
  assert.equal(formatLocalDateTime('2026-10-21 00:30:00.123'),'2026-10-21 00:30')
  assert.equal(formatLocalDateTime('2026-10-21T00:30:00'),'2026-10-21 00:30')
})
test('midnight conversion covers month and year boundaries without changing source dates', () => {
  assert.equal(formatLocalDateTime('2026-12-31T16:00:00Z'),'2027-01-01 00:00')
  assert.equal(formatLocalDate('2026-01-31T16:00:00Z'),'2026-02-01')
  assert.equal(formatLocalDateTime('2026-10-20T15:59:00Z'),'2026-10-20 23:59')
  assert.equal(localDate(new Date('2026-10-20T16:00:00Z')),'2026-10-21')
})
test('missing or invalid values render safely without throwing or invented dates', () => {
  for (const format of [formatLocalDate,formatLocalDateTime]) {
    assert.equal(format(null),'—')
    assert.equal(format(''),'—')
    assert.equal(format('无法解析'),'无法解析')
  }
})
test('form resets read their injected clock each time, including overnight and year changes', () => {
  let now=new Date('2026-10-20T15:59:00Z')
  const form={heldAt:'',note:'draft'}
  const reset=createTodayReset(form,today=>({heldAt:today,note:''}),()=>now)
  reset();assert.equal(form.heldAt,'2026-10-20')
  now=new Date('2026-10-20T16:01:00Z')
  form.note='later draft';reset()
  assert.equal(form.heldAt,'2026-10-21')
  assert.equal(form.note,'')
  now=new Date('2026-12-31T16:00:00Z');reset()
  assert.equal(form.heldAt,'2027-01-01')
})
