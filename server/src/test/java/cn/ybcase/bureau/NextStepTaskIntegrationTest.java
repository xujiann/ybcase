package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.NextStepTaskService;
import cn.ybcase.bureau.service.ReportingSupplementService;
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
    private String previousScope;

    private record Actor(long id, String name, long department) {}

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
