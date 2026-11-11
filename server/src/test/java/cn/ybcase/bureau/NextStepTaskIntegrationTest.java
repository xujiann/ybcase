package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.NextStepTaskService;
import cn.ybcase.bureau.service.MessageService;
import cn.ybcase.bureau.service.ReportingSupplementService;
import cn.ybcase.bureau.service.DepartmentHandoffService;
import cn.ybcase.bureau.service.SourceAttachmentAccess;
import cn.ybcase.core.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@org.springframework.transaction.annotation.Transactional
class NextStepTaskIntegrationTest {
    @Autowired NextStepTaskService tasks;
    @Autowired ReportingSupplementService supplements;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired MessageService messages;
    @Autowired DepartmentHandoffService handoffs;
    @Autowired SourceAttachmentAccess sourceAccess;
    private String previousScope;

    private record Actor(long id, String name, long department) {}
    private record ReceivedSource(long sourceId, long handoffId) {}

    @AfterEach void restoreScope() {
        if (previousScope != null) jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'", previousScope);
    }

    @Test void legalOnlyQueueUsesStableUnionColumnsAndHidesSelfReview() {
        var owner = actor("DISTRICT", "HANDLER");
        var legal = actor("BUREAU", "LEGAL");
        long caseId = caseFile(owner, "REPORTED");
        long review = jdbc.queryForObject("""
                insert into case_review(case_id,required_reason,submitted_at,deadline_at)
                values(?,'测试',current_date,current_date) returning id
                """, Long.class, caseId);
        var pending = tasks.bureau(legal.name(), 1, 100);
        assertTrue(has(pending, "REVIEW", review));
        assertFalse(has(pending, "CASE", caseId), "read-only legal role has no case-writing stage task");
        jdbc.update("insert into case_officer(case_id,name,cert_no,duty,user_id) values(?,'法制本人',?,'MEMBER',?)",
                caseId, unique(), legal.id());
        assertFalse(has(tasks.bureau(legal.name(), 1, 100), "REVIEW", review));
    }

    @Test void handlerTasksApplySelfAndDistrictScopeBeforePagination() {
        previousScope = jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'", String.class);
        jdbc.update("update sys_config set cfg_value='SELF' where cfg_key='case_view_scope'");
        var own = actor("DISTRICT", "HANDLER");
        var colleague = actorAt("DISTRICT", "HANDLER", own.department());
        var foreign = actor("DISTRICT", "HANDLER");
        long ownCase = caseFile(own, "INVESTIGATING");
        long sameDepartment = caseFile(colleague, "INVESTIGATING");
        long otherDepartment = caseFile(foreign, "INVESTIGATING");
        var page = tasks.bureau(own.name(), 1, 1);
        assertEquals(1, page.total());
        assertTrue(has(page, "CASE", ownCase));
        jdbc.update("insert into case_officer(case_id,name,cert_no,duty,user_id) values(?,'参办',?,'MEMBER',?),(?,'外区参办',?,'MEMBER',?)",
                sameDepartment, unique(), own.id(), otherDepartment, unique(), own.id());
        assertEquals(2, tasks.bureau(own.name(), 1, 1).total());
        assertFalse(has(tasks.bureau(own.name(), 1, 100), "CASE", otherDepartment));

        // The source scope stays authoritative. Legacy certificate mapping only adds task ownership in already-readable ALL scope.
        jdbc.update("update sys_config set cfg_value='ALL' where cfg_key='case_view_scope'");
        long legacyCase = caseFile(colleague, "INVESTIGATING");
        String certificate = unique();
        jdbc.update("insert into enforcer(name,cert_no,dept,cert_expire_at,user_id) values('历史参办',?,'测试',current_date+30,?)",
                certificate, own.id());
        jdbc.update("insert into case_officer(case_id,name,cert_no,duty) values(?,'历史参办',?,'MEMBER')",
                legacyCase, certificate);
        assertTrue(has(tasks.bureau(own.name(), 1, 100), "CASE", legacyCase));
    }

    @Test void receivedClueReturnsToAssignmentWhenItsCurrentHandlerBecomesIneligible() {
        assertIneligibleAssigneeReturnsToAssignment("CLUE");
    }

    @Test void receivedInspectionReturnsToAssignmentWhenItsCurrentHandlerBecomesIneligible() {
        assertIneligibleAssigneeReturnsToAssignment("INSPECTION");
    }

    private void assertIneligibleAssigneeReturnsToAssignment(String type) {
        for(String change:List.of("DISABLED","DEPARTMENT","CATEGORY","ROLE")) {
            var sender=actor("DISTRICT","LEADER");
            var leader=actor("DISTRICT","LEADER");
            var worker=actorAt("DISTRICT","HANDLER",leader.department());
            var replacement=actorAt("DISTRICT","HANDLER",leader.department());
            var outsider=actor("DISTRICT","LEADER");
            var received=receivedSource(type,sender,leader);
            assign(received,type,worker,leader);
            assertTrue(canReadSource(type,received.sourceId(),worker.name()));
            assertFalse(has(tasks.bureau(leader.name(),1,100),"HANDOFF",received.handoffId()));
            switch(change) {
                case "DISABLED" -> jdbc.update("update sys_user set enabled=false where id=?",worker.id());
                case "DEPARTMENT" -> jdbc.update("update sys_user set dept_id=? where id=?",outsider.department(),worker.id());
                case "CATEGORY" -> jdbc.update("update sys_user set account_category='BUREAU' where id=?",worker.id());
                case "ROLE" -> jdbc.update("delete from sys_user_role where user_id=?",worker.id());
            }
            assertFalse(canReadSource(type,received.sourceId(),worker.name()),change);
            var pending=tasks.bureau(leader.name(),1,100);
            var assignment=pending.rows().stream().filter(row->row.taskKey().equals("HANDOFF:"+received.handoffId()+":ASSIGN")).toList();
            assertEquals(1,assignment.size(),change);
            assertEquals("/case/department-handoffs?focus="+received.handoffId(),assignment.getFirst().link());
            assertFalse(has(pending,type,received.sourceId()),"负责人待指派来源只显示现有交接办理入口");
            assertFalse(has(tasks.bureau(outsider.name(),1,100),"HANDOFF",received.handoffId()));
            assertFalse(has(tasks.bureau(replacement.name(),1,100),"HANDOFF",received.handoffId()));
            assertThrows(BizException.class,()->assign(received,type,replacement,outsider));
            assertThrows(BizException.class,()->assign(received,type,replacement,replacement));
            assign(received,type,replacement,leader);
            assertFalse(has(tasks.bureau(leader.name(),1,100),"HANDOFF",received.handoffId()));
            assertTrue(has(tasks.bureau(replacement.name(),1,100),type,received.sourceId()));
            assertTrue(canReadSource(type,received.sourceId(),replacement.name()));
            assertFalse(canReadSource(type,received.sourceId(),worker.name()));

            // Historical valid assignments cannot hide an invalid current assignment.
            jdbc.update("update sys_user set enabled=true,dept_id=?,account_category='DISTRICT' where id=?",leader.department(),worker.id());
            FixtureUsers.ensure(jdbc,worker.name(),"HANDLER");
            jdbc.update("update sys_user set enabled=false where id=?",replacement.id());
            assertTrue(has(tasks.bureau(leader.name(),1,100),"HANDOFF",received.handoffId()));
            assign(received,type,worker,leader);
            assertFalse(has(tasks.bureau(leader.name(),1,100),"HANDOFF",received.handoffId()));
        }
    }

    private ReceivedSource receivedSource(String type,Actor sender,Actor leader) {
        String number=unique();
        long source;
        if("CLUE".equals(type)) source=jdbc.queryForObject("""
                insert into case_clue(clue_no,source,content,suspect_name,suspect_type,received_at,deadline_at,created_by,owner_dept_id)
                values(?,'TRANSFER','已接收待办','合成机构','PROVIDER',current_date,current_date,?,?) returning id
                """,Long.class,number,sender.name(),leader.department());
        else source=jdbc.queryForObject("""
                insert into inspection(insp_no,object_name,object_type,officers,planned_at,created_by,owner_dept_id)
                values(?,'合成检查','PROVIDER','甲乙',current_date,?,?) returning id
                """,Long.class,number,sender.name(),leader.department());
        String column="CLUE".equals(type)?"clue_id":"inspection_id";
        long handoff=jdbc.queryForObject("""
                insert into department_handoff(%s,source_no,from_dept_id,to_dept_id,requested_by,reason,status,decided_by,decided_at,opinion)
                values(?,?,?,?,?,'合成机构交接','ACCEPTED',?,current_timestamp,'已接收') returning id
                """.formatted(column),Long.class,source,number,sender.department(),leader.department(),sender.id(),leader.id());
        return new ReceivedSource(source,handoff);
    }

    private void assign(ReceivedSource received,String type,Actor worker,Actor leader) {
        if("CLUE".equals(type)) {
            var ids=jdbc.queryForList("select id from clue_assignment where handoff_id=? order by id desc limit 1",Long.class,received.handoffId());
            handoffs.assignClue(received.handoffId(),new DepartmentHandoffService.ClueAssignment(worker.name(),
                    ids.isEmpty()?null:ids.getFirst(),"核实当前承办人后指派"),leader.name());
        } else {
            Long previous=jdbc.queryForObject("select assigned_user_id from inspection where id=?",Long.class,received.sourceId());
            handoffs.assignInspection(received.handoffId(),new DepartmentHandoffService.InspectionAssignment(worker.name(),
                    previous,"核实当前承办人后指派"),leader.name());
        }
    }

    private boolean canReadSource(String type,long id,String username) {
        var predicate="CLUE".equals(type)?sourceAccess.clueReadPredicate("s.id",username):sourceAccess.inspectionReadPredicate("s",username);
        var args=new ArrayList<>(predicate.args());args.add(id);
        String table="CLUE".equals(type)?"case_clue":"inspection";
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from "+table+" s where ("+predicate.sql()+") and s.id=?)",Boolean.class,args.toArray()));
    }

    @Test void agencyTasksFollowCurrentReceiverCycleAndDisappearWhenActualInterviewRecorded() throws Exception {
        var owner = actor("DISTRICT", "HANDLER");
        var first = actor("AGENCY", "AGENCY");
        var receiver = actor("AGENCY", "AGENCY");
        long caseId = caseFile(owner, "CLOSED");
        long action = jdbc.queryForObject("""
                insert into agreement_action(case_id,action,org,sent_at,agency_dept_id,agency_status,agency_cycle,agency_due_at)
                values(?,'INTERVIEW','经办测试',current_date,?,'PENDING',1,current_date) returning id
                """, Long.class, caseId, first.department());
        assertEquals(1, tasks.agency(first.name(), 1, 100).rows().stream()
                .filter(row -> row.sourceId() == action).findFirst().orElseThrow().cycle());
        jdbc.update("update agreement_action set agency_dept_id=?,agency_cycle=2 where id=?", receiver.department(), action);
        assertFalse(has(tasks.agency(first.name(), 1, 100), "AGREEMENT", action));
        assertEquals(2, tasks.agency(receiver.name(), 1, 100).rows().stream()
                .filter(row -> row.sourceId() == action).findFirst().orElseThrow().cycle());
        jdbc.update("update agreement_action set agency_status='COMPLETED',replied_at=current_date,result='已完成' where id=?", action);
        var interviewTask = tasks.agency(receiver.name(), 1, 100).rows().stream()
                .filter(row -> row.sourceId() == action).findFirst().orElseThrow();
        assertEquals("待登记实际约谈", interviewTask.stage());
        assertTrue(interviewTask.link().endsWith("focus=" + action + "&cycle=2"));
        jdbc.update("""
                insert into department_interview(dept_id,department_name,institution_code,institution_name,
                    interview_type,reason,content,participants,interview_date,created_by,source_agreement_id,source_agency_cycle)
                values(?,'测试经办','H-001','测试机构','提醒','事由','实际约谈内容','甲',current_date,?,?,2)
                """, receiver.department(), receiver.id(), action);
        assertFalse(has(tasks.agency(receiver.name(), 1, 100), "AGREEMENT", action));
        assertThrows(BizException.class, () -> tasks.bureau(receiver.name(), 1, 20));
        mvc.perform(get("/api/bureau/my/tasks").header("Authorization", "Bearer " + jwt.issue(receiver.name(), 0)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
    }

    @Test void onlyAnotherBureauReviewerGetsPendingHistoricalSupplement() {
        var owner = actor("DISTRICT", "HANDLER");
        var reviewer = actor("BUREAU", "LEGAL");
        var admin = actor("BUREAU", "ADMIN");
        long caseId = caseFile(owner, "CLOSED");
        jdbc.update("""
                insert into case_decision(case_id,decision_type,content,decided_at,fine_amount)
                values(?,'PUNISH','原决定',current_date,1)
                """, caseId);
        long request = supplements.request(caseId, Map.of("reason", "缺失编码", "sourceReference", "原始材料",
                "values", Map.of("institutionCode", "H-001")), owner.name());
        assertTrue(has(tasks.bureau(reviewer.name(), 1, 100), "REPORT_SUPPLEMENT", request));
        assertFalse(has(tasks.bureau(owner.name(), 1, 100), "REPORT_SUPPLEMENT", request));
        assertFalse(has(tasks.bureau(admin.name(), 1, 100), "REPORT_SUPPLEMENT", request));
    }

    @Test void archivedContinuationUsesCurrentScopeAndAnotherActualReviewRole() {
        var owner=actor("DISTRICT","HANDLER");var reviewer=actor("BUREAU","LEGAL");var admin=actor("BUREAU","ADMIN");var foreign=actor("DISTRICT","LEADER");
        long c=caseFile(owner,"CLOSED");
        long id=jdbc.queryForObject("insert into case_continuation_request(case_id,request_key,request_type,payload,source_snapshot,reason,source_reference,requested_by,requested_username) values(?,?,'EXECUTION','{}','{}','后续到账','合成凭据',?,?) returning id",Long.class,c,unique(),owner.id(),owner.name());
        assertTrue(has(tasks.bureau(reviewer.name(),1,100),"CONTINUATION",id));
        assertFalse(has(tasks.bureau(admin.name(),1,100),"CONTINUATION",id));assertFalse(has(tasks.bureau(foreign.name(),1,100),"CONTINUATION",id));
        long selfCase=caseFile(reviewer,"CLOSED");
        long selfRequest=jdbc.queryForObject("insert into case_continuation_request(case_id,request_key,request_type,payload,source_snapshot,reason,source_reference,requested_by,requested_username) values(?,?,'EXECUTION','{}','{}','本人历史申请','合成凭据',?,?) returning id",Long.class,selfCase,unique(),reviewer.id(),reviewer.name());
        assertFalse(has(tasks.bureau(reviewer.name(),1,100),"CONTINUATION",selfRequest));
    }

    @Test void archivedEffectiveDecisionUsesItsOwnDeliveryAndCourtRoundForTasksAndReminders() {
        var owner = actor("DISTRICT","HANDLER");
        var legal = actor("BUREAU","LEGAL");
        var outsider = actor("DISTRICT","LEADER");
        long caseId = caseFile(owner,"CLOSED");
        jdbc.update("insert into case_decision(case_id,decision_type,content,decided_at,fine_amount) values(?,'PUNISH','原决定',current_date-60,50)",caseId);
        long remedy = jdbc.queryForObject("""
                insert into case_remedy(case_id,request_key,remedy_type,accepted_at,reference,reason,source_reference,registered_by,registered_username)
                values(?,?,'RECONSIDERATION',current_date-58,'原卷','登记','合成材料',?,?) returning id
                """,Long.class,caseId,unique(),owner.id(),owner.name());
        long result = approved(caseId, owner, legal, "REMEDY_RESULT", "{\"remedyId\":" + remedy + "}");
        long version = jdbc.queryForObject("""
                insert into case_decision_version(case_id,request_id,remedy_id,outcome,effective_at,decision_type,decision_no,
                                                 fine_amount,recoup_amount,confiscate_amount,penalty_basis,content)
                values(?,?,?,'CHANGE',current_date-50,'PUNISH',?,100,0,0,'合成依据','后续决定') returning id
                """,Long.class,caseId,result,remedy,unique());
        assertEquals("/case/detail/" + caseId + "?deliveryVersion=" + version, caseTask(owner,caseId).link());
        assertFalse(has(tasks.bureau(outsider.name(),1,100),"CASE",caseId));
        long bundle = jdbc.queryForObject("""
                insert into case_supplement_bundle(case_id,request_key,title,source_reference,status,created_by,applicant_id,source_snapshot)
                values(?,?,'后续材料','合成回证','APPROVED',?,?,'{}') returning id
                """,Long.class,caseId,unique(),owner.name(),owner.id());
        long document = jdbc.queryForObject("""
                insert into case_supplement_document(case_id,bundle_id,decision_version_id,request_key,doc_type,title,content,
                                                      content_sha256,made_at,created_by)
                values(?,?,?,?,'DECISION','后续决定','后续决定',?,current_date-49,?) returning id
                """,Long.class,caseId,bundle,version,unique(),"a".repeat(64),owner.name());
        jdbc.update("""
                insert into case_supplement_delivery(document_id,method,delivered_at,receiver,receipt_no,source_reference,recorded_by)
                values(?,'DIRECT',current_date-45,'合成当事人',?,'合成回证',?)
                """,document,unique(),owner.name());
        assertEquals("待执行核对",caseTask(owner,caseId).stage());
        long application = approved(caseId,owner,legal,"COURT_APPLY","{}");
        long round = jdbc.queryForObject("""
                insert into case_court_round(case_id,request_id,round_no,applied_at,application_reference,eligibility_basis,decision_version_id)
                values(?,?,1,current_date-5,'合成申请','核定期限材料',?) returning id
                """,Long.class,caseId,application,version);
        assertEquals("待回执核对",caseTask(owner,caseId).stage());
        long returned = approved(caseId,owner,legal,"COURT_RECEIPT","{}");
        jdbc.update("""
                insert into case_court_receipt(case_id,request_id,round_id,status,received_at,receipt_no)
                values(?,?,?,'RETURNED',current_date-2,?)
                """,caseId,returned,round,unique());
        var task = caseTask(owner,caseId);
        assertEquals("法院退回补正",task.stage());
        assertEquals("/case/detail/" + caseId + "?executionVersion=" + version + "&courtRound=" + round,task.link());
        messages.generateReminders();
        messages.generateReminders();
        assertEquals(1,jdbc.queryForObject("select count(*) from sys_message where username=? and link=?",Integer.class,owner.name(),task.link()));
        assertEquals(0,jdbc.queryForObject("select count(*) from sys_message where username=? and link=?",Integer.class,outsider.name(),task.link()));
        jdbc.update("insert into case_execution(case_id,kind,amount,paid_at,method) values(?,'FINE',100,current_date,'BANK')",caseId);
        assertFalse(has(tasks.bureau(owner.name(),1,100),"CASE",caseId));
        assertEquals("CLOSED",jdbc.queryForObject("select status from case_file where id=?",String.class,caseId));
    }

    private NextStepTaskService.TaskRow caseTask(Actor owner, long caseId) {
        return tasks.bureau(owner.name(),1,100).rows().stream()
                .filter(row -> row.sourceType().equals("CASE") && row.sourceId()==caseId).findFirst().orElseThrow();
    }

    private long approved(long caseId, Actor owner, Actor legal, String type, String payload) {
        return jdbc.queryForObject("""
                insert into case_continuation_request(case_id,request_key,request_type,payload,source_snapshot,reason,source_reference,
                    requested_by,requested_username,status,reviewed_by,reviewed_username,reviewed_at,opinion)
                values(?,?,?,?::jsonb,'{}','合成事项','合成材料',?,?,'APPROVED',?,?,current_timestamp,'已核对') returning id
                """,Long.class,caseId,unique(),type,payload,owner.id(),owner.name(),legal.id(),legal.name());
    }

    @Test void generatedReportTaskRequiresAnotherFormalBureauReviewer() {
        var generator=actor("BUREAU","LEADER");var reviewer=actor("BUREAU","LEGAL");var district=actor("DISTRICT","LEADER");var admin=actor("BUREAU","ADMIN");
        long id=jdbc.queryForObject("""
            insert into report_frozen_batch(request_key,report_type,period_start,period_end,business_cutoff,known_at,criteria,
                template_version,template_sha256,rule_version,filename,workbook,file_sha256,row_count,generated_by,generated_username)
            values(?::uuid,'PENALTIES',current_date,current_date,current_date,current_timestamp,'{}','test',?,'test','合成.xlsx',decode('00','hex'),?,0,?,?) returning id
            """,Long.class,UUID.randomUUID().toString(),"a".repeat(64),"b".repeat(64),generator.id(),generator.name());
        var task=tasks.bureau(reviewer.name(),1,100).rows().stream().filter(row->row.sourceType().equals("REPORT_VERSION")&&row.sourceId()==id).findFirst().orElseThrow();
        assertEquals("/case/stats?reportBatch="+id,task.link());
        assertFalse(has(tasks.bureau(generator.name(),1,100),"REPORT_VERSION",id));assertFalse(has(tasks.bureau(district.name(),1,100),"REPORT_VERSION",id));assertFalse(has(tasks.bureau(admin.name(),1,100),"REPORT_VERSION",id));
    }

    @Test void treasuryTasksTrackAssignmentAndIndependentReviewWithoutLeakingAnotherDistrict() {
        var owner=actor("DISTRICT","HANDLER");var reviewer=actor("BUREAU","LEGAL");var foreign=actor("DISTRICT","LEADER");long c=caseFile(owner,"CLOSED");
        long batch=jdbc.queryForObject("insert into treasury_batch(request_key,request_hash,created_by) values(?,?,?) returning id",Long.class,unique(),"a".repeat(64),owner.name());
        long item=jdbc.queryForObject("insert into treasury_batch_item(batch_id,source,source_id,case_id,kind,amount,paid_at,result) values(?,'ORIGINAL',1,?,'FINE',1,current_date,'DIFFERENT') returning id",Long.class,batch,c);
        assertTrue(has(tasks.bureau(reviewer.name(),1,100),"TREASURY",item));assertFalse(has(tasks.bureau(owner.name(),1,100),"TREASURY",item));assertFalse(has(tasks.bureau(foreign.name(),1,100),"TREASURY",item));
        jdbc.update("update treasury_batch_item set review_status='ASSIGNED',assignee=? where id=?",owner.name(),item);
        assertTrue(has(tasks.bureau(owner.name(),1,100),"TREASURY",item));assertFalse(has(tasks.bureau(reviewer.name(),1,100),"TREASURY",item));
        jdbc.update("update treasury_batch_item set review_status='SUBMITTED',investigated_by=? where id=?",owner.name(),item);
        assertTrue(has(tasks.bureau(reviewer.name(),1,100),"TREASURY",item));
        jdbc.update("update treasury_batch_item set review_status='RESOLVED' where id=?",item);assertFalse(has(tasks.bureau(reviewer.name(),1,100),"TREASURY",item));
    }

    @Test void retryableDeliveryExceptionsDisappearAfterSendOrArchiveAndRejectionIsOnlyEvidence() {
        var owner=actor("DISTRICT","HANDLER");long c=caseFile(owner,"DECIDED");
        long doc=jdbc.queryForObject("insert into case_document(case_id,doc_type,title,content,made_at) values(?,'DECISION','合成决定','合成正文',current_date) returning id",Long.class,c);
        long id=jdbc.queryForObject("insert into electronic_delivery(case_id,document_id,request_key,document_hash,receiver,channel,status,created_by) values(?,?,?,'hash','受送达人','fixture@example.invalid','FAILED',?) returning id",Long.class,c,doc,unique(),owner.name());
        assertTrue(has(tasks.bureau(owner.name(),1,100),"DELIVERY_EXCEPTION",id));
        jdbc.update("update electronic_delivery set status='SENT' where id=?",id);assertFalse(has(tasks.bureau(owner.name(),1,100),"DELIVERY_EXCEPTION",id));
        jdbc.update("update electronic_delivery set status='REJECTED' where id=?",id);assertFalse(has(tasks.bureau(owner.name(),1,100),"DELIVERY_EXCEPTION",id));
        jdbc.update("update electronic_delivery set status='TIMED_OUT' where id=?",id);jdbc.update("update case_file set archive_no=? where id=?",unique(),c);assertFalse(has(tasks.bureau(owner.name(),1,100),"DELIVERY_EXCEPTION",id));
    }

    @Test void supplementalMaterialReviewTasksUseCaseScopeAndAnotherFormalBureauReviewer() {
        var owner=actor("DISTRICT","HANDLER");var reviewer=actor("BUREAU","LEGAL");var admin=actor("BUREAU","ADMIN");var district=actor("DISTRICT","LEADER");
        long c=caseFile(owner,"CLOSED"),bundle=pendingBundle(c,owner);
        var task=tasks.bureau(reviewer.name(),1,100).rows().stream().filter(row->row.sourceType().equals("SUPPLEMENT_BUNDLE")&&row.sourceId()==bundle).findFirst().orElseThrow();
        assertEquals("/case/detail/"+c+"?supplementBundle="+bundle,task.link());
        assertFalse(has(tasks.bureau(owner.name(),1,100),"SUPPLEMENT_BUNDLE",bundle));
        assertFalse(has(tasks.bureau(admin.name(),1,100),"SUPPLEMENT_BUNDLE",bundle));
        assertFalse(has(tasks.bureau(district.name(),1,100),"SUPPLEMENT_BUNDLE",bundle));
        long own=pendingBundle(caseFile(reviewer,"CLOSED"),reviewer);
        assertFalse(has(tasks.bureau(reviewer.name(),1,100),"SUPPLEMENT_BUNDLE",own));
        jdbc.update("update sys_dept set org_kind=null where id=?",reviewer.department());
        assertFalse(has(tasks.bureau(reviewer.name(),1,100),"SUPPLEMENT_BUNDLE",bundle));
        jdbc.update("update sys_dept set org_kind='BUREAU',enabled=false where id=?",reviewer.department());
        assertEquals(403,assertThrows(BizException.class,()->tasks.bureau(reviewer.name(),1,100)).code);
        jdbc.update("update sys_dept set enabled=true where id=?",reviewer.department());
        jdbc.update("update case_supplement_bundle set status='REJECTED',reviewed_by=?,reviewed_at=clock_timestamp(),opinion='核对退回' where id=?",reviewer.name(),bundle);
        assertFalse(has(tasks.bureau(reviewer.name(),1,100),"SUPPLEMENT_BUNDLE",bundle));
    }

    @Test void archivedSupplementDeliveryExceptionsRemainActionableButSupersededAndForeignSourcesDoNot() {
        var owner=actor("DISTRICT","HANDLER");var foreign=actor("DISTRICT","LEADER");long c=caseFile(owner,"CLOSED");
        long bundle=jdbc.queryForObject("insert into case_supplement_bundle(case_id,request_key,title,source_reference,status,created_by,applicant_id,applicant_dept_id,applicant_category,source_snapshot) values(?,?,'后续材料','实际回证','APPROVED',?,?,?,'DISTRICT','{}') returning id",Long.class,c,unique(),owner.name(),owner.id(),owner.department());
        long doc=jdbc.queryForObject("insert into case_supplement_document(case_id,bundle_id,request_key,doc_type,title,content,content_sha256,made_at,created_by) values(?,?,?,'NOTICE','后续告知','后续正文',?,current_date,?) returning id",Long.class,c,bundle,unique(),"a".repeat(64),owner.name());
        long delivery=jdbc.queryForObject("insert into electronic_delivery(case_id,supplement_document_id,request_key,document_hash,receiver,channel,status,created_by) values(?,?,?,'hash','受送达人','fixture@example.invalid','FAILED',?) returning id",Long.class,c,doc,unique(),owner.name());
        assertTrue(has(tasks.bureau(owner.name(),1,100),"DELIVERY_EXCEPTION",delivery));
        assertFalse(has(tasks.bureau(foreign.name(),1,100),"DELIVERY_EXCEPTION",delivery));
        jdbc.update("update electronic_delivery set status='TIMED_OUT' where id=?",delivery);
        assertTrue(has(tasks.bureau(owner.name(),1,100),"DELIVERY_EXCEPTION",delivery));
        jdbc.update("update electronic_delivery set superseded_at=clock_timestamp(),superseded_reason='已核对替代轮次' where id=?",delivery);
        assertFalse(has(tasks.bureau(owner.name(),1,100),"DELIVERY_EXCEPTION",delivery));
    }

    private long pendingBundle(long caseId,Actor owner) {
        return jdbc.queryForObject("insert into case_supplement_bundle(case_id,request_key,title,source_reference,status,created_by,applicant_id,applicant_dept_id,applicant_category,source_snapshot,manifest,manifest_sha256) values(?,?,'合成待核对材料','原件第3页','PENDING',?,?,?,?, '{}','{}',?) returning id",Long.class,caseId,unique(),owner.name(),owner.id(),owner.department(),jdbc.queryForObject("select account_category from sys_user where id=?",String.class,owner.id()),"b".repeat(64));
    }

    private Actor actor(String category, String role) {
        String code = unique();
        long dept = jdbc.queryForObject("insert into sys_dept(name,code,type,org_kind) values(?,?,'ADMIN',?) returning id",
                Long.class, code, code, category);
        return actorAt(category, role, dept);
    }

    private Actor actorAt(String category, String role, long department) {
        String username = unique();
        FixtureUsers.ensure(jdbc, username, role);
        jdbc.update("update sys_user set dept_id=?,account_category=? where username=?", department, category, username);
        return new Actor(jdbc.queryForObject("select id from sys_user where username=?", Long.class, username), username, department);
    }

    private long caseFile(Actor owner, String status) {
        String number = unique();
        return jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,status,filed_at,deadline_at,
                    owner_user,owner_dept_id,archive_no)
                values(?,'待办测试',(select min(id) from case_cause),'测试机构','PROVIDER',?,current_date,current_date,?,?,?)
                returning id
                """, Long.class, number, status, owner.name(), owner.department(), "CLOSED".equals(status) ? number : null);
    }

    private static boolean has(NextStepTaskService.TaskPage page, String sourceType, long id) {
        return page.rows().stream().anyMatch(row -> row.sourceType().equals(sourceType) && row.sourceId() == id);
    }

    private static String unique() {
        return "TASK-" + UUID.randomUUID().toString().substring(0, 18);
    }
}
