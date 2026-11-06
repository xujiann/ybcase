import test from 'node:test'
import assert from 'node:assert/strict'
import { canWritePrecase, canVoidPrecase } from '../src/api/precaseAttachments.ts'
const clue={type:'clues',id:1,status:'PENDING',owner:'handler'}
test('precase writes require own source or manager, reject legal-only and transferred sources', () => {
  assert.equal(canWritePrecase(['HANDLER'],'handler',clue,false),true)
  assert.equal(canWritePrecase(['HANDLER'],'other',clue,false),false)
  assert.equal(canWritePrecase(['LEGAL'],'handler',clue,false),false)
  assert.equal(canWritePrecase(['LEADER'],'boss',clue,false),true)
  assert.equal(canWritePrecase(['ADMIN'],'admin',{...clue,status:'FILED'},false),false)
  assert.equal(canWritePrecase(['HANDLER'],'handler',clue,true),false)
  assert.equal(canWritePrecase(['LEADER'],'boss',{type:'inspections',id:2,clueId:1},false),false)
})
test('voiding requires source write plus uploader or manager, rejects archived source mapping', () => {
  const row={uploaded_by:'handler',voided:false}
  assert.equal(canVoidPrecase(['HANDLER'],'handler',clue,row),true)
  assert.equal(canVoidPrecase(['HANDLER'],'handler',clue,{...row,uploaded_by:'someone'}),false)
  assert.equal(canVoidPrecase(['LEADER'],'boss',clue,row),true)
  assert.equal(canVoidPrecase(['LEADER'],'boss',clue,{...row,case_id:10}),false)
  assert.equal(canVoidPrecase(['LEADER'],'boss',clue,{...row,voided:true}),false)
})

test('clue creator can upload with an empty handler assignment', () => {
  assert.equal(canWritePrecase(['HANDLER'],'creator',{type:'clues',id:5,status:'PENDING',owner:'creator',handler:''},false),true)
})
test('multiple clue handlers match exact login usernames across supported delimiters', () => {
  const source={type:'clues',id:5,status:'PENDING',owner:'creator',handler:'handlerA、handlerB,handlerC，handlerD;handlerE；handlerF handlerG\nhandlerH'}
  for (const name of ['handlerA','handlerB','handlerC','handlerD','handlerE','handlerF','handlerG','handlerH']) assert.equal(canWritePrecase(['HANDLER'],name,source,false),true)
  for (const name of ['handler','handlerAA','andlerA','真实姓名']) assert.equal(canWritePrecase(['HANDLER'],name,source,false),false)
  assert.equal(canWritePrecase(['HANDLER'],'handlerA',{...source,type:'inspections'},false),false)
})
