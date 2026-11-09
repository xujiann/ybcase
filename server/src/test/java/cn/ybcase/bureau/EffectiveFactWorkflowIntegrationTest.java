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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class EffectiveFactWorkflowIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    record Actor(long id,String name,String token,long dept) {}
    @BeforeEach void guardedDatabase() {
        assertTrue(jdbc.queryForObject("select current_database()",String.class).matches("ybcase_authowner_test_20261028_[0-9a-f]{8}"));
    }

    @Test void activeInstitutionCorrectionIsScopedAndRecordsOriginalAndNewFacts() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var outsider=actor("DISTRICT","HANDLER");long id=caseFile(owner,"INVESTIGATING",false);
        var body=Map.<String,Object>of("institution",Map.of("code","CORRECTED-01","ownership","SOCIAL","grade","PRIMARY","kind","SINGLE_PHARMACY"),"reason","核对登记资料","sourceReference","机构证照第2页");
        put(owner,id,body,0);
        assertEquals("CORRECTED-01",jdbc.queryForObject("select institution_code from case_file where id=?",String.class,id));
        var history=read(owner,"/bureau/cases/"+id+"/fact-versions",0).path("history");
        assertEquals("ORIGINAL-01",history.get(0).path("previous_facts").path("institutionCode").asText());
        assertEquals("CORRECTED-01",history.get(0).path("facts").path("institutionCode").asText());
        put(outsider,id,body,2080);
        assertEquals(1,jdbc.queryForObject("select count(*) from case_fact_revision where case_id=?",Integer.class,id));
    }

    @Test void closedFactCorrectionNeedsDistinctReviewerAndBusinessKnowledgeCutoffsPreserveOriginal() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"CLOSED",true);String original=original(id);
        var payload=finding("6000",LocalDate.now().minusDays(1));
        var request=request("FACT_CORRECTION",payload);
        long entry=post(owner,path(id)+"/requests",request,0).path("id").asLong();
        assertEquals(entry,post(owner,path(id)+"/requests",request,0).path("id").asLong());
        assertEquals(0,amount(id,LocalDate.now()).compareTo(new BigDecimal("10000")));
        String known=jdbc.queryForObject("select clock_timestamp()::text",String.class);
        review(owner,id,entry,403);review(legal,id,entry,0);
        assertEquals(0,amount(id,LocalDate.now()).compareTo(new BigDecimal("6000")));
        assertEquals(0,amount(id,LocalDate.now().minusDays(2)).compareTo(new BigDecimal("10000")));
        assertEquals(0,jdbc.queryForObject("select confirmed_violation_amount from case_effective_reporting_facts(current_date,?::timestamptz) where case_id=?",BigDecimal.class,known,id).compareTo(new BigDecimal("10000")));
        assertEquals(original,original(id));
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("update case_fact_revision set reason='覆盖' where case_id=?",id));
        assertEquals(1,jdbc.queryForObject("select count(*) from case_fact_revision where case_id=?",Integer.class,id));
    }

    @Test void remedyResultBindsNewFactsToApprovedVersionWhileOriginalDecisionAndFactsRemain() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"CLOSED",true);String original=original(id);
        long remedy=post(owner,path(id)+"/remedies",Map.of("requestKey",UUID.randomUUID().toString(),"remedyType","RECONSIDERATION","acceptedAt",LocalDate.now().minusDays(4).toString(),"reference","正式受理号","reason","收到受理材料","sourceReference","受理材料1页"),0).path("id").asLong();
        var payload=finding("6000",LocalDate.now().minusDays(1));
        payload.putAll(Map.ofEntries(Map.entry("remedyId",remedy),Map.entry("outcome","CHANGE"),Map.entry("resultReference","结果号"),Map.entry("resultContent","人工核对变更事实"),Map.entry("decisionType","PUNISH"),Map.entry("decisionNo","变更决定号"),Map.entry("fineAmount",60),Map.entry("recoupAmount",0),Map.entry("confiscateAmount",0),Map.entry("penaltyBasis","变更后依据"),Map.entry("content","变更决定全文")));
        long entry=post(owner,path(id)+"/requests",request("REMEDY_RESULT",payload),0).path("id").asLong();
        assertEquals(0,amount(id,LocalDate.now()).compareTo(new BigDecimal("10000")));
        review(legal,id,entry,0);
        assertEquals("经结果材料核定的新事实",jdbc.queryForObject("select confirmed_violation_facts from case_effective_reporting_facts() where case_id=?",String.class,id));
        assertEquals(0,new BigDecimal("60").compareTo(jdbc.queryForObject("select fine_amount from case_effective_decisions(current_date) where case_id=?",BigDecimal.class,id)));
        assertEquals(entry,jdbc.queryForObject("select request_id from case_fact_revision where case_id=?",Long.class,id));
        assertEquals(original,original(id));
    }

    @Test void partialFactsFutureDatesAndUnclassifiedReviewersCannotCreateEffectiveFacts() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"CLOSED",true);
        post(owner,path(id)+"/requests",request("FACT_CORRECTION",Map.of("effectiveAt",LocalDate.now().toString(),"confirmedViolationFacts","只有文字")),2193);
        post(owner,path(id)+"/requests",request("FACT_CORRECTION",finding("0",LocalDate.now().plusDays(1))),2193);
        var fields=finding("0",LocalDate.now());long entry=post(owner,path(id)+"/requests",request("FACT_CORRECTION",fields),0).path("id").asLong();
        jdbc.update("update sys_dept set org_kind=null where id=?",legal.dept());review(legal,id,entry,403);
        assertEquals("PENDING",jdbc.queryForObject("select status from case_continuation_request where id=?",String.class,entry));
        assertEquals(0,jdbc.queryForObject("select count(*) from case_fact_revision where case_id=?",Integer.class,id));
    }

    @Test void returnForFurtherInvestigationRetainsOldReportsAndRequiresNewNoticeAndReview() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"NOTIFIED",false);
        long oldReport=jdbc.queryForObject("insert into case_document(case_id,doc_type,title,content,made_at,maker) values(?,'FINAL_REPORT','原调查终结报告','原报告100',current_date-3,?) returning id",Long.class,id,owner.name());
        long notice=jdbc.queryForObject("insert into case_notice(case_id,content,proposed_fine,proposed_recoup,proposed_confiscate,notified_at,hearing_requested) values(?,'原告知100',100,0,0,current_date-2,true) returning id",Long.class,id);
        long review=jdbc.queryForObject("insert into case_review(case_id,required_reason,submitted_at,deadline_at,reviewer,reviewer_user_id,opinion_type,opinion,reviewed_at,passed) values(?,'应补查',current_date-1,current_date+3,?,?,'CONTINUE','事实需核实',current_date,false) returning id",Long.class,id,legal.name(),legal.id());
        var body=Map.<String,Object>of("reason","依据最新意见补查","sourceReference","法审意见第1页");
        post(owner,"/bureau/cases/"+id+"/fact-versions/return-investigation",body,0);
        assertEquals("INVESTIGATING",jdbc.queryForObject("select status from case_file where id=?",String.class,id));
        assertEquals("原报告100",jdbc.queryForObject("select content from case_document where id=?",String.class,oldReport));
        assertTrue(jdbc.queryForObject("select hearing_requested from case_notice where id=?",Boolean.class,notice));
        post(owner,"/bureau/cases/"+id+"/fact-versions/return-investigation",body,2194);
        post(owner,"/bureau/cases/"+id+"/report",Map.of("content","补查后确认报告80","finding",Map.of("start",LocalDate.now().minusDays(20).toString(),"end",LocalDate.now().minusDays(12).toString(),"facts","补查确认事实","amount",80)),0);
        var decision=Map.<String,Object>of("decisionType","PUNISH","fineAmount",80,"recoupAmount",0,"confiscateAmount",0,"content","拟决定","penaltyBasis","依据","discretionReason","理由");
        var checks=post(owner,"/bureau/cases/"+id+"/preflight",Map.of("action","DECIDE","decision",decision),0);
        assertFalse(checks.path("ready").asBoolean());
        assertTrue(checks.toString().contains("reinvestigation_notice"));
        post(owner,"/bureau/cases/"+id+"/notice",Map.of("content","补查后的新告知80","proposedFine",80,"proposedRecoup",0,"proposedConfiscate",0,"changeReason","补查认定金额减少"),0);
        assertEquals(2,jdbc.queryForObject("select count(*) from case_notice where case_id=?",Integer.class,id));
        assertTrue(jdbc.queryForObject("select hearing_requested from case_notice where case_id=? order by id desc limit 1",Boolean.class,id));
        checks=post(owner,"/bureau/cases/"+id+"/preflight",Map.of("action","DECIDE","decision",decision),0);
        assertFalse(checks.path("ready").asBoolean());
        assertTrue(checks.toString().contains("reinvestigation_review"));
        assertEquals(review,jdbc.queryForObject("select review_id from case_investigation_return where case_id=?",Long.class,id));
        assertEquals(0,new BigDecimal("80").compareTo(jdbc.queryForObject("select confirmed_violation_amount from case_file where id=?",BigDecimal.class,id)));
    }

    @Test void postDecisionInstitutionCorrectionNeverMutatesClosedCase() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"CLOSED",true);String original=original(id);
        var fields=Map.<String,Object>of("effectiveAt",LocalDate.now().toString(),"institutionCode","NEW-CODE","institutionOwnership","SOCIAL","institutionGrade","UNGRADED","institutionKind","CHAIN_PHARMACY");
        long entry=post(owner,path(id)+"/requests",request("FACT_CORRECTION",fields),0).path("id").asLong();review(legal,id,entry,0);
        assertEquals("NEW-CODE",jdbc.queryForObject("select institution_code from case_effective_reporting_facts() where case_id=?",String.class,id));
        assertEquals("CHAIN_PHARMACY",jdbc.queryForObject("select institution_kind from case_effective_reporting_facts() where case_id=?",String.class,id));
        assertEquals(original,original(id));
    }

    @Test void effectiveReplacementPreventsNewDeliveryOfOriginalDecision() throws Exception {
        var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long id=caseFile(owner,"DECIDED",true);
        long remedy=post(owner,path(id)+"/remedies",Map.of("requestKey",UUID.randomUUID().toString(),"remedyType","CORRECTION","acceptedAt",LocalDate.now().minusDays(4).toString(),"reference","受理号","reason","收到变更材料","sourceReference","原始材料1页"),0).path("id").asLong();
        var payload=new LinkedHashMap<String,Object>(Map.ofEntries(Map.entry("remedyId",remedy),Map.entry("outcome","CHANGE"),Map.entry("effectiveAt",LocalDate.now().minusDays(1).toString()),Map.entry("resultReference","变更结果号"),Map.entry("resultContent","已核定变更"),Map.entry("decisionType","PUNISH"),Map.entry("decisionNo","独立新决定"),Map.entry("fineAmount",60),Map.entry("recoupAmount",0),Map.entry("confiscateAmount",0),Map.entry("penaltyBasis","变更依据"),Map.entry("content","独立新决定全文")));
        long request=post(owner,path(id)+"/requests",request("REMEDY_RESULT",payload),0).path("id").asLong();review(legal,id,request,0);
        post(owner,"/bureau/cases/"+id+"/deliver",Map.of("method","DIRECT","deliveredAt",LocalDate.now().toString(),"receiver","真实收件人","receiptNo","原决定不得补送"),2193);
        assertEquals("DECIDED",jdbc.queryForObject("select status from case_file where id=?",String.class,id));
        assertNull(jdbc.queryForObject("select delivered_at from case_file where id=?",java.sql.Date.class,id));
    }

    private Actor actor(String category,String role) {
        String name="facts_"+UUID.randomUUID().toString().replace("-","");
        long dept=jdbc.queryForObject("insert into sys_dept(code,name,type,org_kind) values(?,?,'ADMIN',?) returning id",Long.class,name.substring(0,31),"事实版本测试机构",category);
        FixtureUsers.ensure(jdbc,name,role);jdbc.update("update sys_user set dept_id=?,account_category=? where username=?",dept,category,name);
        long id=jdbc.queryForObject("select id from sys_user where username=?",Long.class,name);return new Actor(id,name,jwt.issue(name,0),dept);
    }
    private long caseFile(Actor actor,String status,boolean decided) {
        String no="FACT-"+UUID.randomUUID();
        long id=jdbc.queryForObject("""
          insert into case_file(case_no,name,cause_id,party_name,party_type,status,procedure_type,filed_at,deadline_at,owner_user,owner_dept_id,
           archive_no,decided_at,reported_at,institution_code,institution_ownership,institution_grade,institution_kind,
           confirmed_violation_start,confirmed_violation_end,confirmed_violation_facts,confirmed_violation_amount)
          values(?,'事实版本测试',(select min(id) from case_cause),'测试机构','PROVIDER',?,'NORMAL',current_date-30,current_date+30,?,?,?,
           ?,current_date-3,'ORIGINAL-01','PUBLIC','SECONDARY','MEDICAL',current_date-20,current_date-12,'原事实10000',10000) returning id
          """,Long.class,no,status,actor.name(),actor.dept(),"CLOSED".equals(status)?no+"卷":null,decided?LocalDate.now().minusDays(10):null);
        if(decided)jdbc.update("insert into case_decision(case_id,decision_type,decision_no,content,decided_at,fine_amount,recoup_amount,confiscate_amount,penalty_basis) values(?,'PUNISH',?,'原决定',current_date-10,100,0,0,'原依据')",id,no);
        return id;
    }
    private String original(long id){return jdbc.queryForObject("select jsonb_build_object('case',to_jsonb(c),'decision',to_jsonb(d))::text from case_file c left join case_decision d on d.case_id=c.id where c.id=?",String.class,id);}
    private BigDecimal amount(long id,LocalDate date){return jdbc.queryForObject("select confirmed_violation_amount from case_effective_reporting_facts(?) where case_id=?",BigDecimal.class,date,id);}
    private Map<String,Object> finding(String amount,LocalDate effective){return new LinkedHashMap<>(Map.of("effectiveAt",effective.toString(),"confirmedViolationStart",LocalDate.now().minusDays(20).toString(),"confirmedViolationEnd",LocalDate.now().minusDays(12).toString(),"confirmedViolationFacts","经结果材料核定的新事实","confirmedViolationAmount",new BigDecimal(amount)));}
    private Map<String,Object> request(String type,Map<String,Object> p){return Map.of("type",type,"requestKey",UUID.randomUUID().toString(),"reason","真实材料核对后的更正","sourceReference","原始凭证第2页","payload",p);}
    private String path(long id){return "/bureau/cases/"+id+"/continuations";}
    private void review(Actor a,long id,long request,int expected)throws Exception{post(a,path(id)+"/requests/"+request+"/review",Map.of("approve",true,"opinion","另一人核实原始依据"),expected);}
    private JsonNode post(Actor a,String path,Map<String,Object> body,int expected)throws Exception{return check(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api"+path).header("Authorization","Bearer "+a.token()).contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse().getContentAsString(),expected);}
    private void put(Actor a,long id,Map<String,Object> body,int expected)throws Exception{check(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/bureau/cases/"+id+"/party-info").header("Authorization","Bearer "+a.token()).contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse().getContentAsString(),expected);}
    private JsonNode read(Actor a,String path,int expected)throws Exception{return check(mvc.perform(get("/api"+path).header("Authorization","Bearer "+a.token())).andReturn().getResponse().getContentAsString(),expected);}
    private JsonNode check(String text,int expected)throws Exception{var node=mapper.readTree(text);assertEquals(expected,node.path("code").asInt(-1),node.toString());return node.path("data");}
}
