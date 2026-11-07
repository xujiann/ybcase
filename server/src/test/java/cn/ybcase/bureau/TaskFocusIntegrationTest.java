package cn.ybcase.bureau;

import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc @Transactional
class TaskFocusIntegrationTest {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired JwtService jwt; @Autowired ObjectMapper mapper;
    record Actor(String name,long id,long dept){}
    Actor actor(){String name="focus_"+UUID.randomUUID().toString().replace("-","").substring(0,16);long dept=jdbc.queryForObject("insert into sys_dept(name,code,type,org_kind) values(?,?,'ADMIN','DISTRICT') returning id",Long.class,name,name);FixtureUsers.ensure(jdbc,name,"LEADER");jdbc.update("update sys_user set dept_id=?,account_category='DISTRICT' where username=?",dept,name);return new Actor(name,jdbc.queryForObject("select id from sys_user where username=?",Long.class,name),dept);}
    com.fasterxml.jackson.databind.JsonNode read(Actor a,String path)throws Exception{return mapper.readTree(mvc.perform(get("/api/bureau"+path).header("Authorization","Bearer "+jwt.issue(a.name(),0))).andReturn().getResponse().getContentAsByteArray());}
    long clue(Actor a){return jdbc.queryForObject("insert into case_clue(clue_no,source,suspect_name,suspect_type,content,received_at,deadline_at,created_by,owner_dept_id) values(?,'OTHER','合成来源','PROVIDER','焦点',current_date,current_date,?,?) returning id",Long.class,"FOCUS-"+UUID.randomUUID().toString().replace("-","").substring(0,16),a.name(),a.dept());}
    @Test void clueTargetBeyondLegacyWindowIgnoresFiltersButNotDepartmentScope()throws Exception{
        Actor own=actor(),foreign=actor();long target=clue(own);
        for(int i=0;i<205;i++)clue(own);
        var result=read(own,"/clues?focusId="+target+"&page=99&size=1&status=REJECTED&q=不存在").path("data");
        assertEquals(1,result.path("total").asInt());assertEquals(target,result.path("rows").get(0).path("id").asLong());
        assertEquals(0,read(foreign,"/clues?focusId="+target).path("data").path("total").asInt());
        assertNotEquals(0,read(own,"/clues?focusId=0").path("code").asInt());
    }
    @Test void pendingApprovalFocusShowsOneCurrentAuthorizedTargetAndClearsAfterCompletion()throws Exception{
        Actor own=actor(),foreign=actor();long c=clue(own);
        long id=jdbc.queryForObject("insert into biz_approval(kind,clue_id,reason,applicant,payload,applicant_dept_id) values('CLUE_EXTEND',?,'合成审批',?,'{}',?) returning id",Long.class,c,own.name(),own.dept());
        var result=read(own,"/approvals/pending?focusId="+id).path("data");assertEquals(1,result.size());assertEquals(id,result.get(0).path("id").asLong());
        assertEquals(0,read(foreign,"/approvals/pending?focusId="+id).path("data").size());
        jdbc.update("update biz_approval set status='REJECTED' where id=?",id);assertEquals(0,read(own,"/approvals/pending?focusId="+id).path("data").size());
    }
    @Test void handoffFocusReadsExactReceiverRecordWithoutFetchingEarlierPages()throws Exception{
        Actor sender=actor(),receiver=actor(),foreign=actor();long c=clue(sender);
        long target=jdbc.queryForObject("insert into department_handoff(clue_id,source_no,from_dept_id,to_dept_id,requested_by,reason) values(?,'交接焦点',?,?,?,'合成原因') returning id",Long.class,c,sender.dept(),receiver.dept(),sender.id());
        var result=read(receiver,"/department-handoffs?focusId="+target+"&page=99&status=REJECTED").path("data");
        assertEquals(1,result.path("total").asInt());assertEquals(target,result.path("rows").get(0).path("id").asLong());
        assertEquals(0,read(foreign,"/department-handoffs?focusId="+target).path("data").path("total").asInt());
    }
}
