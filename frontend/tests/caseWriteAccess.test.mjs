import test from 'node:test'
import assert from 'node:assert/strict'
import { caseWriteAccess, attachmentVoidAccess, caseOperationAccess, governmentRecordAvailable, leaderOperationAccess, adminOperationAccess, businessReadAccess } from '../src/api/caseWriteAccess.ts'
test('case writers exclude legal-only and unknown roles while preserving handler/leader/admin material operations', () => {
  for (const role of ['HANDLER','LEADER','ADMIN']) assert.equal(caseWriteAccess([role],{status:'DECIDED'}),true)
  for (const role of ['LEGAL','AUDITOR','']) assert.equal(caseWriteAccess([role],{status:'INVESTIGATING'}),false)
  assert.equal(caseWriteAccess(['LEGAL','HANDLER'],{status:'INVESTIGATING'}),true)
  assert.equal(caseWriteAccess(undefined,{status:'INVESTIGATING'}),false)
})
test('archive number seals terminated cases as well as regular closed cases', () => {
  assert.equal(caseWriteAccess(['HANDLER'],{status:'TERMINATED'}),true)
  assert.equal(caseWriteAccess(['HANDLER'],{status:'TERMINATED',archiveNo:'历史卷'}),false)
  assert.equal(caseWriteAccess(['ADMIN'],{status:'CLOSED'}),false)
  assert.equal(caseWriteAccess(['ADMIN'],{}),false)
})
test('attachment voiding additionally requires uploader identity or leader/admin and a valid unsealed record', () => {
  const c={status:'DECIDED'},row={uploaded_by:'owner',voided:false}
  assert.equal(attachmentVoidAccess(['HANDLER'],'owner',c,row),true)
  assert.equal(attachmentVoidAccess(['HANDLER'],'other',c,row),false)
  assert.equal(attachmentVoidAccess(['LEGAL'],'owner',c,row),false)
  assert.equal(attachmentVoidAccess(['LEADER'],'boss',c,row),true)
  assert.equal(attachmentVoidAccess(['ADMIN'],'admin',{status:'TERMINATED',archiveNo:'卷'},row),false)
  assert.equal(attachmentVoidAccess(['HANDLER'],'owner',c,{...row,voided:true}),false)
})

test('external publication and government filing remain role-based obligations after sealing, while core materials stay read-only', () => {
  assert.equal(caseWriteAccess(['HANDLER'],{status:'CLOSED',archiveNo:'卷'}),false)
  assert.equal(caseOperationAccess(['HANDLER']),true)
  assert.equal(caseOperationAccess(['LEGAL']),false)
  assert.equal(governmentRecordAvailable(['HANDLER'],{decisionType:'PUNISH'}),true)
  assert.equal(governmentRecordAvailable(['HANDLER'],{decisionType:'NO_PUNISH'}),false)
  assert.equal(governmentRecordAvailable(['ADMIN'],{decisionType:'PUNISH',govRecordNo:'原文号'}),false)
  assert.equal(governmentRecordAvailable(['ADMIN'],{decisionType:'PUNISH',govRecordAt:'2026-10-01'}),false)
})

test('delegation, approval and reward approval are leader duties; template and certificate maintenance are admin duties', () => {
  for (const [role,leader,admin] of [['HANDLER',false,false],['LEGAL',false,false],['LEADER',true,false],['ADMIN',true,true]]) {
    assert.equal(leaderOperationAccess([role]),leader)
    assert.equal(adminOperationAccess([role]),admin)
  }
})

test('business shell reading remains available to all four business roles but not generic or unassigned accounts', () => {
 for(const role of ['HANDLER','LEGAL','LEADER','ADMIN'])assert.equal(businessReadAccess([role]),true)
 assert.equal(businessReadAccess([]),false);assert.equal(businessReadAccess(['OTHER']),false)
})
