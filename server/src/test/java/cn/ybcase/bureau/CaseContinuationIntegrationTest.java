package cn.ybcase.bureau;

import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** HTTP requests commit independently: approvals, rollback and races are not hidden by an outer transaction. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CaseContinuationIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    record Actor(long id,String name,String token,long dept) {}

    @BeforeEach void isolated() {
        assertTrue(jdbc.queryForObject("select current_database()",String.class)
                .matches("ybcase_authowner_test_20261028_[0-9a-f]{8}"),"Continuation tests require the dedicated guarded database");
    }

    @Test void closedCaseContinuationRequiresDistinctReviewAndKeepsOriginalVolumeUntouched() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");
        long id=caseFile(owner,"CLOSED");String original=original(id);
        var body=request("EXECUTION",payment("50",LocalDate.now().minusDays(2)));
        long request=write(owner,path(id)+"/requests",body,0).path("id").asLong();
        assertEquals(request,write(owner,path(id)+"/requests",body,0).path("id").asLong(),"retry returns original request");
        assertEquals(0,count("case_execution_continuation",id));
        var known=jdbc.queryForObject("select clock_timestamp()::text",String.class);
        review(legal,id,request,true,0);
        assertEquals(0,new BigDecimal("50").compareTo(sum(id,LocalDate.now())));
        assertEquals(BigDecimal.ZERO,jdbc.queryForObject("select coalesce(sum(amount),0) from case_effective_executions(current_date,?::timestamptz) where case_id=?",BigDecimal.class,known,id));
        assertEquals(0,sum(id,LocalDate.now().minusDays(3)).signum());
        assertEquals(original,original(id));
        assertEquals("CLOSED",read(owner,path(id),0).path("originalCaseStatus").asText());
        review(legal,id,request,true,2193);
        write(owner,path(id)+"/requests",request("EXECUTION",payment("51",LocalDate.now())),2193);
    }

    @Test void permissionsSelfApprovalAndChangedAssignmentAreAuthoritativelyRejected() throws Exception {
        var owner=actor("DISTRICT","LEADER");var outsider=actor("DISTRICT","LEADER");
        var admin=actor("BUREAU","ADMIN");var legal=actor("BUREAU","LEGAL");
        long id=caseFile(owner,"CLOSED");
        read(outsider,path(id),2080);
        long request=write(owner,path(id)+"/requests",request("EXECUTION",payment("5",LocalDate.now())),0).path("id").asLong();
        review(owner,id,request,true,403);review(admin,id,request,true,403);review(outsider,id,request,true,2080);
        jdbc.update("update sys_user set dept_id=? where id=?",outsider.dept(),owner.id());
        review(legal,id,request,true,2080);
        assertEquals("PENDING",jdbc.queryForObject("select status from case_continuation_request where id=?",String.class,request));
        assertEquals(0,count("case_execution_continuation",id));
        review(legal,id,request,false,0);
    }

    @Test void reversalIsWholeSourceOnceAndBusinessCutoffSeparatesOriginalAndCorrection() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"CLOSED");
        long execution=jdbc.queryForObject("insert into case_execution(case_id,kind,amount,paid_at,method,receipt_no) values(?,'FINE',40,current_date-4,'BANK',?) returning id",Long.class,id,"old-"+UUID.randomUUID());
        String original=original(id);
        var payload=Map.<String,Object>of("source","ORIGINAL","sourceId",execution,"paidAt",LocalDate.now().minusDays(1).toString(),"receiptNo","reverse-"+UUID.randomUUID());
        long request=write(owner,path(id)+"/requests",request("REVERSAL",payload),0).path("id").asLong();review(legal,id,request,true,0);
        assertEquals(0,new BigDecimal("40").compareTo(sum(id,LocalDate.now().minusDays(2))));
        assertEquals(0,sum(id,LocalDate.now()).signum());
        var duplicate=new HashMap<>(payload);duplicate.put("receiptNo","other-"+UUID.randomUUID());
        write(owner,path(id)+"/requests",request("REVERSAL",duplicate),2193);
        assertEquals(original,original(id));
    }

    @Test void concurrentRequestsAndReviewsCommitAtMostOnce() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");var leader=actor("BUREAU","LEADER");long id=caseFile(owner,"CLOSED");
        var pool=Executors.newFixedThreadPool(2);
        try {
            var gate=new CountDownLatch(1);List<Future<Integer>> results=new ArrayList<>();
            for(int i=0;i<2;i++) results.add(pool.submit(()->{gate.await();return writeCode(owner,path(id)+"/requests",request("EXECUTION",payment("60",LocalDate.now())));}));
            gate.countDown();var codes=new ArrayList<Integer>();for(var result:results)codes.add(result.get(20,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(0,2193),codes);
            long request=jdbc.queryForObject("select id from case_continuation_request where case_id=?",Long.class,id);
            var gate2=new CountDownLatch(1);results.clear();
            for(var reviewer:List.of(legal,leader)) results.add(pool.submit(()->{gate2.await();return writeCode(reviewer,path(id)+"/requests/"+request+"/review",Map.of("approve",true,"opinion","核对凭证"));}));
            gate2.countDown();codes.clear();for(var result:results)codes.add(result.get(20,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(0,2193),codes);
            assertEquals(1,count("case_execution_continuation",id));assertEquals(0,new BigDecimal("60").compareTo(sum(id,LocalDate.now())));
        } finally {pool.shutdownNow();}
    }

    @Test void courtReturnCreatesSeparateRoundsAndReceiptsWithoutRewritingOldCase() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"DELIVERED");
        backdateDecision(id);
        jdbc.update("""
                update case_file set court_enforce_applied=true,court_enforce_status='RETURNED',
                  court_applied_at=current_date-6,court_received_at=current_date-4,court_receipt_no='原退回回执',
                  relief_deadline=current_date-19,relief_basis='原决定救济期限核定材料' where id=?
                """,id);
        long urge=jdbc.queryForObject("insert into case_document(case_id,doc_type,title,content,made_at,maker) values(?,'URGE_LETTER','催告书','催告正文',current_date-17,?) returning id",Long.class,id,owner.name());
        jdbc.update("insert into case_delivery(case_id,method,delivered_at,doc_kind,document_id) values(?,'DIRECT',current_date-17,'URGE_LETTER',?)",id,urge);
        String original=original(id);
        for(int round=2;round<=3;round++) {
            var apply=Map.<String,Object>of("appliedAt",LocalDate.now().minusDays(2).toString(),"applicationReference","补正申请"+round,"eligibilityBasis","负责人核实本次再次申请材料和期限依据");
            long req=write(owner,path(id)+"/requests",request("COURT_APPLY",apply),0).path("id").asLong();review(legal,id,req,true,0);
            long rid=jdbc.queryForObject("select id from case_court_round where case_id=? and round_no=?",Long.class,id,round);
            var receipt=Map.<String,Object>of("roundId",rid,"status",round==2?"RETURNED":"ACCEPTED","receivedAt",LocalDate.now().minusDays(2).toString(),"receiptNo","回执"+round);
            req=write(owner,path(id)+"/requests",request("COURT_RECEIPT",receipt),0).path("id").asLong();review(legal,id,req,true,0);
        }
        assertEquals(2,count("case_court_round",id));assertEquals(2,count("case_court_receipt",id));assertEquals(original,original(id));
        assertEquals(3,read(owner,path(id),0).path("courtRounds").size());
    }

    @Test void remedyResultCreatesApprovedEffectiveVersionAndPreservesOriginalDecision() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"CLOSED");String original=original(id);
        long remedy=write(owner,path(id)+"/remedies",remedy(),0).path("id").asLong();
        var result=result(remedy,"CHANGE","PUNISH","80");
        long req=write(owner,path(id)+"/requests",request("REMEDY_RESULT",result),0).path("id").asLong();
        assertEquals(0,new BigDecimal("100").compareTo(effectiveFine(id,LocalDate.now())));
        String before=jdbc.queryForObject("select clock_timestamp()::text",String.class);
        review(legal,id,req,true,0);
        assertEquals(0,new BigDecimal("80").compareTo(effectiveFine(id,LocalDate.now())));
        assertEquals(0,new BigDecimal("100").compareTo(effectiveFine(id,LocalDate.now().minusDays(3))));
        assertEquals(0,new BigDecimal("100").compareTo(jdbc.queryForObject("select fine_amount from case_effective_decisions(current_date,?::timestamptz) where case_id=?",BigDecimal.class,before,id)));
        write(owner,"/bureau/cases/"+id+"/publish",Map.of(),2193);
        assertEquals(original,original(id));
        write(owner,path(id)+"/requests",request("REMEDY_RESULT",result),2193);
    }

    @Test void revocationDoesNotInferRefundOrMutateExistingExecution() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"CLOSED");
        jdbc.update("insert into case_execution(case_id,kind,amount,paid_at,method) values(?,'FINE',40,current_date-3,'BANK')",id);
        String original=original(id);long remedy=write(owner,path(id)+"/remedies",remedy(),0).path("id").asLong();
        var result=result(remedy,"REVOKE","REVOKED","0");result.put("recoupAmount",0);result.put("confiscateAmount",0);
        long req=write(owner,path(id)+"/requests",request("REMEDY_RESULT",result),0).path("id").asLong();review(legal,id,req,true,0);
        var state=read(owner,path(id),0);assertEquals("REVOKED",state.path("effectiveDecision").path("decision_type").asText());
        assertEquals(-40,state.path("balances").path("FINE").path("remaining").asInt());
        assertEquals(original,original(id));assertEquals(0,count("case_execution_continuation",id));
        write(owner,path(id)+"/requests",request("EXECUTION",payment("1",LocalDate.now())),2193);
    }

    @Test void preciseTailUnknownFieldsAndStaleSourcesCannotApproveFinancialFacts() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"CLOSED");
        var invalid=payment("1",LocalDate.now());invalid.put("amount",new BigDecimal("999999999999.990000000001"));
        write(owner,path(id)+"/requests",request("EXECUTION",invalid),2140);
        invalid=payment("1",LocalDate.now());invalid.put("sourceCaseId",id);
        write(owner,path(id)+"/requests",request("EXECUTION",invalid),2193);
        long req=write(owner,path(id)+"/requests",request("EXECUTION",payment("1",LocalDate.now())),0).path("id").asLong();
        jdbc.update("update case_decision set content='夹具模拟来源变化' where case_id=?",id);
        review(legal,id,req,true,2193);assertEquals(0,count("case_execution_continuation",id));
        assertEquals("PENDING",jdbc.queryForObject("select status from case_continuation_request where id=?",String.class,req));
        review(legal,id,req,false,0);
    }

    @Test void explicitCloseUsesReviewedVersionNetExecutionAndBlocksUnfinishedRemedy() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");var leader=actor("BUREAU","LEADER");
        long id=caseFile(owner,"DELIVERED");
        jdbc.update("""
                update case_file set court_enforce_applied=true,court_enforce_status='ACCEPTED',
                  court_applied_at=current_date-8,court_received_at=current_date-7,court_receipt_no='仅对应原决定的受理回执' where id=?
                """,id);
        long remedy=write(owner,path(id)+"/remedies",remedy(),0).path("id").asLong();
        write(leader,"/bureau/cases/"+id+"/close",Map.of("closeReport","申请结案"),2193);
        var result=result(remedy,"CHANGE","PUNISH","80");result.put("recoupAmount",0);result.put("confiscateAmount",0);
        long request=write(owner,path(id)+"/requests",request("REMEDY_RESULT",result),0).path("id").asLong();review(legal,id,request,true,0);
        assertFalse(closeCheck(leader,id,"delivery"));
        long version=jdbc.queryForObject("select id from case_decision_version where case_id=?",Long.class,id);
        var day=LocalDate.now().minusDays(1);
        long document=supplementDocument(owner,id,version,"DECISION",day);
        String deliveryPath="/bureau/cases/"+id+"/supplement-bundles/documents/"+document+"/deliver";
        var announcement=Map.<String,Object>of("method","ANNOUNCE","announcedAt",day.toString(),
                "receiver","受送达人","receiptNo","公告文号","sourceReference","公告原件");
        long announcementId=write(owner,deliveryPath,announcement,0).path("id").asLong();
        assertEquals(announcementId,write(owner,deliveryPath,announcement,0).path("id").asLong());
        assertTrue(jdbc.queryForObject("select delivered_at>current_date from case_supplement_delivery where id=?",Boolean.class,announcementId));
        assertFalse(closeCheck(leader,id,"delivery"),"新决定公告期未满，尚无有效送达");
        write(owner,deliveryPath,Map.of("method","DIRECT","deliveredAt",day.toString(),
                "receiver","受送达人","receiptNo","直接送达回证","sourceReference","签收原件"),0);
        assertTrue(closeCheck(leader,id,"delivery"));
        assertFalse(closeCheck(leader,id,"execution_or_court"),"原决定法院回执不能用于新决定结案");
        write(leader,"/bureau/cases/"+id+"/close",Map.of("closeReport","尚未执行"),2011);
        request=write(owner,path(id)+"/requests",request("EXECUTION",payment("80",LocalDate.now())),0).path("id").asLong();review(legal,id,request,true,0);
        write(leader,"/bureau/cases/"+id+"/close",Map.of("closeReport","核对有效决定及续执行后结案"),0);
        assertEquals("CLOSED",jdbc.queryForObject("select status from case_file where id=?",String.class,id));
        assertEquals(0,new BigDecimal("100").compareTo(jdbc.queryForObject("select fine_amount from case_decision where case_id=?",BigDecimal.class,id)));
        assertEquals(0,jdbc.queryForObject("select count(*) from case_execution where case_id=?",Integer.class,id));
    }

    @Test void revisedDecisionCourtApplicationNeedsExactDeliveryBasisAndUrging() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");var leader=actor("BUREAU","LEADER");
        long id=caseFile(owner,"DELIVERED");
        backdateDecision(id);
        String original=original(id);
        var intake=new LinkedHashMap<>(remedy());
        intake.put("acceptedAt",LocalDate.now().minusDays(40).toString());
        long remedy=write(owner,path(id)+"/remedies",intake,0).path("id").asLong();
        var result=result(remedy,"CHANGE","PUNISH","80");
        result.put("effectiveAt",LocalDate.now().minusDays(35).toString());
        long request=write(owner,path(id)+"/requests",request("REMEDY_RESULT",result),0).path("id").asLong();
        review(legal,id,request,true,0);
        long version=jdbc.queryForObject("select id from case_decision_version where case_id=?",Long.class,id);
        var application=Map.<String,Object>of("appliedAt",LocalDate.now().toString(),
                "applicationReference","新决定执行申请","eligibilityBasis","核实新决定送达及申请期限");
        var submission=request("COURT_APPLY",application);
        write(owner,path(id)+"/requests",submission,2193);
        long receipt=supplementDelivery(owner,id,version,"DECISION",LocalDate.now().minusDays(32));
        write(owner,path(id)+"/requests",submission,2193);
        var basis=new LinkedHashMap<String,Object>(Map.of("decisionVersionId",version,"deliveryId",receipt+1,
                "reliefDeadline",LocalDate.now().minusDays(16).toString(),"reliefPending",false,"basis","核定的新决定救济期限材料"));
        write(legal,path(id)+"/execution-basis",basis,2193);
        basis.put("deliveryId",receipt);
        long basisId=write(legal,path(id)+"/execution-basis",basis,0).path("id").asLong();
        assertEquals(basisId,write(legal,path(id)+"/execution-basis",basis,0).path("id").asLong());
        write(owner,path(id)+"/requests",submission,2193);
        supplementDelivery(owner,id,version,"URGE_LETTER",LocalDate.now().minusDays(14));
        request=write(owner,path(id)+"/requests",submission,0).path("id").asLong();
        review(legal,id,request,true,0);
        long round=jdbc.queryForObject("select id from case_court_round where case_id=? and decision_version_id=?",Long.class,id,version);
        assertFalse(closeCheck(leader,id,"execution_or_court"));
        request=write(owner,path(id)+"/requests",request("COURT_RECEIPT",Map.of("roundId",round,
                "status","ACCEPTED","receivedAt",LocalDate.now().toString(),"receiptNo","新决定正式受理文号")),0).path("id").asLong();
        review(legal,id,request,true,0);
        assertTrue(closeCheck(leader,id,"execution_or_court"));
        assertEquals(original,original(id));
    }

    @Test void announcedDecisionCannotCloseBeforeDeemedDelivery() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var leader=actor("BUREAU","LEADER");
        long id=caseFile(owner,"DELIVERED");
        jdbc.update("update case_file set delivered_at=current_date+1 where id=?",id);
        jdbc.update("insert into case_delivery(case_id,method,delivered_at,doc_kind) values(?,'ANNOUNCE',current_date+1,'DECISION')",id);
        jdbc.update("update case_decision set fine_amount=0,recoup_amount=0,confiscate_amount=0 where case_id=?",id);
        assertFalse(closeCheck(leader,id,"delivery"));
        assertTrue(closeCheck(leader,id,"execution_or_court"));
        write(leader,"/bureau/cases/"+id+"/close",Map.of("closeReport","公告未到期"),2011);
    }

    @Test void mistakenRemedyStopsBlockingOnlyAfterIndependentReviewAndKeepsIntake() throws Exception {
        var owner=actor("DISTRICT","LEADER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"DELIVERED");
        String original=original(id);
        long remedy=write(owner,path(id)+"/remedies",remedy(),0).path("id").asLong();
        String intake=jdbc.queryForObject("select to_jsonb(r)::text from case_remedy r where id=?",String.class,remedy);
        var payload=Map.<String,Object>of("remedyId",remedy);
        long request=write(owner,path(id)+"/requests",request("REMEDY_VOID",payload),0).path("id").asLong();
        assertFalse(closeCheck(owner,id,"continuation_review"));
        review(owner,id,request,true,403);
        assertEquals("OPEN",read(owner,path(id),0).path("remedies").get(0).path("status").asText());
        review(legal,id,request,true,0);
        assertEquals("VOIDED",read(owner,path(id),0).path("remedies").get(0).path("status").asText());
        assertTrue(closeCheck(owner,id,"continuation_review"));
        write(owner,path(id)+"/requests",request("REMEDY_VOID",payload),2193);
        write(owner,path(id)+"/requests",request("REMEDY_RESULT",result(remedy,"CHANGE","PUNISH","80")),2193);
        assertEquals(intake,jdbc.queryForObject("select to_jsonb(r)::text from case_remedy r where id=?",String.class,remedy));
        assertEquals(original,original(id));
    }

    @Test void partialRefundsKeepOriginalPaymentAndCannotExceedOrReverseReturnedPrincipal() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"CLOSED");
        long source=jdbc.queryForObject("insert into case_execution(case_id,kind,amount,paid_at,method) values(?,'FINE',40,current_date-4,'BANK') returning id",Long.class,id);
        String original=original(id);String receipt="refund-"+UUID.randomUUID();
        var refund=new LinkedHashMap<String,Object>(Map.of("source","ORIGINAL","sourceId",source,"paidAt",LocalDate.now().minusDays(1).toString(),"receiptNo",receipt,"amount",10));
        long request=write(owner,path(id)+"/requests",request("REFUND",refund),0).path("id").asLong();review(legal,id,request,true,0);
        assertEquals(0,new BigDecimal("30").compareTo(sum(id,LocalDate.now())));
        write(owner,path(id)+"/requests",request("REFUND",refund),2193);
        refund.put("receiptNo","refund-"+UUID.randomUUID());refund.put("amount",31);
        write(owner,path(id)+"/requests",request("REFUND",refund),2193);
        refund.put("amount",30);request=write(owner,path(id)+"/requests",request("REFUND",refund),0).path("id").asLong();review(legal,id,request,true,0);
        assertEquals(0,sum(id,LocalDate.now()).signum());
        var reversal=new HashMap<>(refund);reversal.remove("amount");reversal.put("receiptNo","reversal-"+UUID.randomUUID());
        write(owner,path(id)+"/requests",request("REVERSAL",reversal),2193);
        assertEquals(original,original(id));assertEquals(2,count("case_execution_continuation",id));
    }

    @Test void lateFeeRequiresManualAssessmentAndBothAssessmentAndPrincipalCaps() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"CLOSED");String original=original(id);
        var fee=payment("15",LocalDate.now());fee.put("kind","LATE_FEE");
        write(owner,path(id)+"/requests",request("EXECUTION",fee),2193);
        fee.put("assessedLateFeeAmount",20);fee.put("assessmentReference","核定文号：已按日核算，材料第4页");
        long request=write(owner,path(id)+"/requests",request("EXECUTION",fee),0).path("id").asLong();review(legal,id,request,true,0);
        fee.put("receiptNo","fee-"+UUID.randomUUID());fee.put("amount",6);
        write(owner,path(id)+"/requests",request("EXECUTION",fee),2193);
        fee.put("assessedLateFeeAmount",101);
        write(owner,path(id)+"/requests",request("EXECUTION",fee),2193);
        fee.put("assessedLateFeeAmount",20);fee.put("amount",5);
        request=write(owner,path(id)+"/requests",request("EXECUTION",fee),0).path("id").asLong();review(legal,id,request,true,0);
        assertEquals(0,new BigDecimal("20").compareTo(jdbc.queryForObject("select sum(amount) from case_effective_executions(current_date) where case_id=? and kind='LATE_FEE'",BigDecimal.class,id)));
        assertEquals(original,original(id));
    }

    @Test void downstreamLedgerFailureRollsBackApprovalAndCanThenBeReviewedNormally() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"CLOSED");String original=original(id);
        long request=write(owner,path(id)+"/requests",request("EXECUTION",payment("10",LocalDate.now())),0).path("id").asLong();
        String trigger="fixture_cont_"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        jdbc.execute("create function "+trigger+"() returns trigger language plpgsql as $$ begin if new.case_id="+id+" then raise exception 'expected continuation rollback fixture'; end if; return new; end; $$");
        try {
            jdbc.execute("create trigger "+trigger+" before insert on case_execution_continuation for each row execute function "+trigger+"()");
            review(legal,id,request,true,500);
            assertEquals("PENDING",jdbc.queryForObject("select status from case_continuation_request where id=?",String.class,request));
            assertEquals(0,count("case_execution_continuation",id));assertEquals(original,original(id));
        } finally {jdbc.execute("drop trigger if exists "+trigger+" on case_execution_continuation");jdbc.execute("drop function "+trigger+"()");}
        review(legal,id,request,true,0);assertEquals(1,count("case_execution_continuation",id));
    }

    private Actor actor(String category,String role) {
        String suffix=UUID.randomUUID().toString().replace("-","");String username="cont_"+suffix;
        long dept=jdbc.queryForObject("insert into sys_dept(name,code,type,org_kind) values(?,?,'ADMIN',?) returning id",Long.class,"续办测试"+suffix,"CONT-"+suffix.substring(0,20),category);
        FixtureUsers.ensure(jdbc,username,role);jdbc.update("update sys_user set dept_id=?,account_category=? where username=?",dept,category,username);
        long id=jdbc.queryForObject("select id from sys_user where username=?",Long.class,username);return new Actor(id,username,jwt.issue(username,0),dept);
    }
    private long caseFile(Actor owner,String status) {
        String no="CONT-"+UUID.randomUUID();
        long id=jdbc.queryForObject("""
            insert into case_file(case_no,name,cause_id,party_name,party_type,status,filed_at,deadline_at,decided_at,delivered_at,owner_user,owner_dept_id,archive_no)
            values(?,'续办测试',(select min(id) from case_cause),'测试对象','PROVIDER',?,current_date-12,current_date+30,current_date-10,current_date-9,?,?,?) returning id
            """,Long.class,no,status,owner.name(),owner.dept(),"CLOSED".equals(status)?no+"卷":null);
        jdbc.update("insert into case_decision(case_id,decision_type,decision_no,content,penalty_basis,decided_at,fine_amount,recoup_amount,confiscate_amount) values(?,'PUNISH',?,'原决定全文','原依据',current_date-10,100,20,10)",id,no);
        jdbc.update("insert into case_document(case_id,doc_type,title,content,made_at,maker) values(?,'DECISION','原决定文书','原文书全文',current_date-10,?)",id,owner.name());return id;
    }
    private void backdateDecision(long id) {
        jdbc.update("update case_file set filed_at=current_date-60,decided_at=current_date-50,delivered_at=current_date-44 where id=?",id);
        jdbc.update("update case_decision set decided_at=current_date-50 where case_id=?",id);
        jdbc.update("update case_document set made_at=current_date-50 where case_id=? and doc_type='DECISION'",id);
    }
    private long supplementDelivery(Actor owner,long id,long version,String type,LocalDate deliveredAt) {
        long document=supplementDocument(owner,id,version,type,deliveredAt);
        return jdbc.queryForObject("""
                insert into case_supplement_delivery(document_id,method,delivered_at,receiver,receipt_no,source_reference,recorded_by)
                values(?,'DIRECT',?,'收件人','补充送达回证','测试原件第1页',?) returning id
                """,Long.class,document,deliveredAt,owner.name());
    }
    private long supplementDocument(Actor owner,long id,long version,String type,LocalDate madeAt) {
        long bundle=jdbc.queryForObject("""
                insert into case_supplement_bundle(case_id,request_key,title,source_reference,status,
                  created_by,applicant_id,source_snapshot)
                values(?,?, '已复核材料','测试原件','APPROVED',?,?,'{}') returning id
                """,Long.class,id,UUID.randomUUID().toString(),owner.name(),owner.id());
        return jdbc.queryForObject("""
                insert into case_supplement_document(case_id,bundle_id,decision_version_id,request_key,doc_type,
                  title,content,content_sha256,made_at,created_by)
                values(?,?,?,?,?,'对应当前决定的送达文书','文书内容',?,?,?) returning id
                """,Long.class,id,bundle,version,UUID.randomUUID().toString(),type,"0".repeat(64),madeAt,owner.name());
    }
    private boolean closeCheck(Actor actor,long id,String key) throws Exception {
        var checklist=write(actor,"/bureau/cases/"+id+"/preflight",Map.of("action","CLOSE","close",Map.of("closeReport","核对后结案")),0);
        for (var item : checklist.path("items")) if (key.equals(item.path("key").asText())) return item.path("passed").asBoolean();
        throw new AssertionError("Missing close preflight item "+key+": "+checklist);
    }
    private String original(long id) { return jdbc.queryForObject("""
        select jsonb_build_object('case',to_jsonb(c),'decision',to_jsonb(d),'documents',(select jsonb_agg(to_jsonb(x) order by id) from case_document x where x.case_id=c.id),
          'executions',(select jsonb_agg(to_jsonb(x) order by id) from case_execution x where x.case_id=c.id))::text
        from case_file c join case_decision d on d.case_id=c.id where c.id=?
        """,String.class,id); }
    private int count(String table,long id) {return jdbc.queryForObject("select count(*) from "+table+" where case_id=?",Integer.class,id);}
    private BigDecimal sum(long id,LocalDate cutoff) {return jdbc.queryForObject("select coalesce(sum(amount),0) from case_effective_executions(?) where case_id=? and kind='FINE'",BigDecimal.class,cutoff,id);}
    private BigDecimal effectiveFine(long id,LocalDate cutoff) {return jdbc.queryForObject("select fine_amount from case_effective_decisions(?) where case_id=?",BigDecimal.class,cutoff,id);}
    private static String path(long id) {return "/bureau/cases/"+id+"/continuations";}
    private Map<String,Object> payment(String amount,LocalDate day) {return new LinkedHashMap<>(Map.of("kind","FINE","amount",new BigDecimal(amount),"paidAt",day.toString(),"receiptNo","cont-"+UUID.randomUUID(),"method","BANK"));}
    private Map<String,Object> request(String type,Map<String,Object> payload) {return Map.of("type",type,"requestKey",UUID.randomUUID().toString(),"reason","核实原始材料后申请续办","sourceReference","测试凭证第3页","payload",payload);}
    private Map<String,Object> remedy() {return Map.of("requestKey",UUID.randomUUID().toString(),"remedyType","RECONSIDERATION","acceptedAt",LocalDate.now().minusDays(5).toString(),"reference","受理文号","reason","登记受理事实","sourceReference","受理通知书第1页");}
    private Map<String,Object> result(long remedy,String outcome,String decision,String fine) {return new LinkedHashMap<>(Map.ofEntries(Map.entry("remedyId",remedy),Map.entry("outcome",outcome),Map.entry("effectiveAt",LocalDate.now().minusDays(2).toString()),Map.entry("resultReference","正式结果文号"),Map.entry("resultContent","经材料核实的实际结果"),Map.entry("decisionType",decision),Map.entry("decisionNo","新结果文号"),Map.entry("fineAmount",new BigDecimal(fine)),Map.entry("recoupAmount",20),Map.entry("confiscateAmount",10),Map.entry("penaltyBasis","结果依据"),Map.entry("content","新有效决定正文"),Map.entry("otherMeasures","其他措施")));}
    private void review(Actor actor,long caseId,long id,boolean approve,int expected) throws Exception {write(actor,path(caseId)+"/requests/"+id+"/review",Map.of("approve",approve,"opinion","独立核实材料及金额"),expected);}
    private JsonNode read(Actor actor,String path,int expected) throws Exception {var response=mvc.perform(get("/api"+path).header("Authorization","Bearer "+actor.token())).andReturn().getResponse();var result=mapper.readTree(response.getContentAsString());assertEquals(expected,result.path("code").asInt(-1),result.toString());return result.path("data");}
    private JsonNode write(Actor actor,String path,Map<String,Object> body,int expected) throws Exception {var result=send(actor,path,body);assertEquals(expected,result.path("code").asInt(-1),result.toString());return result.path("data");}
    private int writeCode(Actor actor,String path,Map<String,Object> body) throws Exception {return send(actor,path,body).path("code").asInt(-1);}
    private JsonNode send(Actor actor,String path,Map<String,Object> body) throws Exception {var response=mvc.perform(post("/api"+path).header("Authorization","Bearer "+actor.token()).contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();return mapper.readTree(response.getContentAsString());}
}
