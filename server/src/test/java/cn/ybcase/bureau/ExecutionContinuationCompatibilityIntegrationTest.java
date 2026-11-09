package cn.ybcase.bureau;

import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.ExecutionAlerts;
import cn.ybcase.core.common.ConfigChangedEvent;
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
class ExecutionContinuationCompatibilityIntegrationTest {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt; @Autowired ExecutionAlerts alerts; @Autowired BureauConfig config;
    record Actor(String name,String token) {}
    @BeforeEach void guard(){assertTrue(jdbc.queryForObject("select current_database()",String.class).matches("ybcase_authowner_test_20261028_[0-9a-f]{8}"));}
    Actor actor(String role){
        String suffix=UUID.randomUUID().toString().replace("-","");String user="exc_"+suffix;
        long dept=jdbc.queryForObject("insert into sys_dept(name,code,type,org_kind) values('执行兼容测试',?,'ADMIN','BUREAU') returning id",Long.class,"EXC-"+suffix.substring(0,20));
        FixtureUsers.ensure(jdbc,user,role);jdbc.update("update sys_user set dept_id=?,account_category='BUREAU' where username=?",dept,user);return new Actor(user,jwt.issue(user,0));
    }
    long caseFile(Actor owner,String status){
        String no="EXC-"+UUID.randomUUID();long dept=jdbc.queryForObject("select dept_id from sys_user where username=?",Long.class,owner.name());
        long id=jdbc.queryForObject("""
          insert into case_file(case_no,name,cause_id,party_name,party_type,status,filed_at,deadline_at,decided_at,delivered_at,owner_user,owner_dept_id,archive_no)
          values(?,'执行提醒兼容',(select min(id) from case_cause),'合成机构','PROVIDER',?,current_date-60,current_date+30,current_date-50,current_date-30,?,?,?) returning id
          """,Long.class,no,status,owner.name(),dept,"CLOSED".equals(status)?no+"卷":null);
        jdbc.update("insert into case_decision(case_id,decision_type,decision_no,fine_amount,content,decided_at) values(?,'PUNISH',?,100,'原处罚决定',current_date-50)",id,no);
        return id;
    }
    String source(long id){return jdbc.queryForObject("""
        select jsonb_build_object('case',to_jsonb(c),'decision',to_jsonb(d),'executions',
          (select jsonb_agg(to_jsonb(e) order by e.id) from case_execution e where e.case_id=c.id),
          'documents',(select jsonb_agg(to_jsonb(doc) order by doc.id) from case_document doc where doc.case_id=c.id))::text
        from case_file c join case_decision d on d.case_id=c.id where c.id=?
        """,String.class,id);}
    Map<String,Object> alert(long id){return alerts.rows(LocalDate.now()).stream().filter(r->((Number)r.get("case_id")).longValue()==id).findFirst().orElse(null);}
    String path(long id){return "/bureau/cases/"+id+"/continuations";}
    JsonNode post(Actor a,String path,Object body,int code)throws Exception{
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api"+path).header("Authorization","Bearer "+a.token()).contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
        var root=mapper.readTree(response.getContentAsByteArray());assertEquals(code,root.path("code").asInt(-1),root.toString());return root.path("data");
    }
    void approve(Actor owner,Actor reviewer,long id,String type,Map<String,Object> payload)throws Exception{
        long request=post(owner,path(id)+"/requests",Map.of("type",type,"requestKey",UUID.randomUUID().toString(),"reason","按实际材料核实","sourceReference","合成原始凭证第1页","payload",payload),0).path("id").asLong();
        post(reviewer,path(id)+"/requests/"+request+"/review",Map.of("approve",true,"opinion","独立复核通过"),0);
    }
    void pay(Actor owner,Actor reviewer,long id)throws Exception{approve(owner,reviewer,id,"EXECUTION",Map.of("kind","FINE","amount",100,"paidAt",LocalDate.now().toString(),"method","BANK","receiptNo","exc-"+UUID.randomUUID()));}

    @Test void revokedCurrentVersionStopsOldPenaltyRemindersWithoutChangingOriginalCase()throws Exception{
        var owner=actor("HANDLER");var reviewer=actor("LEGAL");long id=caseFile(owner,"DELIVERED");String original=source(id);assertNotNull(alert(id));
        long remedy=post(owner,path(id)+"/remedies",Map.of("requestKey",UUID.randomUUID().toString(),"remedyType","RECONSIDERATION","acceptedAt",LocalDate.now().minusDays(5).toString(),"reference","受理001","reason","登记受理事实","sourceReference","受理材料第1页"),0).path("id").asLong();
        approve(owner,reviewer,id,"REMEDY_RESULT",Map.ofEntries(Map.entry("remedyId",remedy),Map.entry("outcome","REVOKE"),Map.entry("effectiveAt",LocalDate.now().minusDays(1).toString()),Map.entry("resultReference","撤销001"),Map.entry("resultContent","人工核实撤销结果"),Map.entry("decisionType","REVOKED"),Map.entry("decisionNo","结果001"),Map.entry("fineAmount",0),Map.entry("recoupAmount",0),Map.entry("confiscateAmount",0),Map.entry("penaltyBasis","结果依据"),Map.entry("content","撤销结果正文"),Map.entry("otherMeasures","无")));
        assertNull(alert(id));assertEquals(original,source(id));
    }
    @Test void reviewedContinuationPaymentStopsReminderAndClosedSourceRemainsSealed()throws Exception{
        var owner=actor("HANDLER");var reviewer=actor("LEGAL");long id=caseFile(owner,"DELIVERED");String original=source(id);assertNotNull(alert(id));
        pay(owner,reviewer,id);assertNull(alert(id));assertEquals(original,source(id));
        long closed=caseFile(owner,"CLOSED");original=source(closed);pay(owner,reviewer,closed);assertNull(alert(closed));assertEquals(original,source(closed));
    }
    @Test void approvedPartialRefundRestoresNetOutstandingReminderWithoutDeletingOriginalPayment()throws Exception{
        var owner=actor("HANDLER");var reviewer=actor("LEGAL");long id=caseFile(owner,"DELIVERED");
        long payment=jdbc.queryForObject("insert into case_execution(case_id,kind,amount,paid_at,method) values(?,'FINE',100,current_date-2,'BANK') returning id",Long.class,id);
        String original=source(id);assertNull(alert(id));
        approve(owner,reviewer,id,"REFUND",Map.of("source","ORIGINAL","sourceId",payment,"amount",10,"paidAt",LocalDate.now().toString(),"receiptNo","ref-"+UUID.randomUUID()));
        assertEquals(0,new BigDecimal("90").compareTo((BigDecimal)Objects.requireNonNull(alert(id)).get("paid")));assertEquals(original,source(id));
    }
    @Test void selfScopeCannotReadOtherCasesAndOldQuoteRefusesEffectiveContinuationAmounts()throws Exception{
        var owner=actor("HANDLER");var outsider=actor("HANDLER");var reviewer=actor("LEGAL");long id=caseFile(owner,"DELIVERED");pay(owner,reviewer,id);
        String old=jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'",String.class);
        try{
            jdbc.update("update sys_config set cfg_value='SELF' where cfg_key='case_view_scope'");config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
            for(var item:List.of(new Object[]{owner,"/bureau/cases/"+id+"/late-fee-quote",2193},new Object[]{outsider,"/bureau/cases/"+id+"/late-fee-quote",2080},new Object[]{outsider,path(id),2080})){
                var actor=(Actor)item[0];var response=mvc.perform(get("/api"+item[1]).header("Authorization","Bearer "+actor.token())).andReturn().getResponse();
                var result=mapper.readTree(response.getContentAsByteArray());assertEquals(item[2],result.path("code").asInt(-1),result.toString());
                assertFalse(result.has("fineAmount"));
            }
        }finally{jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'",old);config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));}
    }

    @Test void approvedIndependentCourtApplicationStopsOriginalApplicationRemindersWithoutChangingLegacyFlag()throws Exception{
        var owner=actor("HANDLER");var reviewer=actor("LEGAL");long id=caseFile(owner,"DELIVERED");
        jdbc.update("update case_file set relief_deadline=current_date-14,relief_basis='原决定救济期限核定材料' where id=?",id);
        long urge=jdbc.queryForObject("insert into case_document(case_id,doc_type,title,content,made_at,maker) values(?,'URGE_LETTER','催告书','催告正文',current_date-12,?) returning id",Long.class,id,owner.name());
        jdbc.update("insert into case_delivery(case_id,method,delivered_at,doc_kind,document_id) values(?,'DIRECT',current_date-12,'URGE_LETTER',?)",id,urge);
        String original=source(id);
        assertNotNull(alert(id));
        approve(owner,reviewer,id,"EXECUTION",Map.of("kind","FINE","amount",10,"paidAt",LocalDate.now().toString(),"method","BANK","receiptNo","exc-partial-"+UUID.randomUUID()));
        assertNotNull(alert(id));
        approve(owner,reviewer,id,"COURT_APPLY",Map.of("appliedAt",LocalDate.now().toString(),"applicationReference","后续法院申请001","eligibilityBasis","人工核实当前材料及申请期限依据"));
        assertNull(alert(id));
        post(reviewer,"/bureau/cases/"+id+"/relief-deadline",Map.of(
                "deadline",LocalDate.now().toString(),"pending",false,"basis","不得覆盖已用的期限依据"),2142);
        assertEquals(false,jdbc.queryForObject("select court_enforce_applied from case_file where id=?",Boolean.class,id));
        assertEquals(1,jdbc.queryForObject("select count(*) from case_court_round where case_id=?",Integer.class,id));
        assertEquals(original,source(id));
    }
}
