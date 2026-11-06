package cn.ybcase.bureau;

import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class BusinessAccessIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired CaseService cases;
    @Autowired CaseCauseRepository causes;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    String actor(String... roleCodes) {
        var user=new SysUser();user.setUsername("access_"+System.nanoTime());user.setRealName("权限隔离测试");user.setPassword("unused-jwt-fixture");
        for(String role:roleCodes)user.getRoles().add(roles.findByCode(role).orElseThrow());
        user=users.save(user);return jwt.issue(user.getUsername(),user.getTokenVersion());
    }
    long fresh() {
        long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();
        return cases.create(new CaseService.CaseCreateReq(null,cause,"NORMAL","ACCESS-"+System.nanoTime(),"PROVIDER",null,null,null,null,"权限回归",BigDecimal.TEN,
            List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),"banban").getId();
    }
    int code(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mapper.readTree(mvc.perform(request).andReturn().getResponse().getContentAsString()).path("code").asInt();
    }
    Map<String,Object> counts(long id) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String table:List.of("case_officer","case_evidence","case_document","case_review","case_notice","case_meeting","case_execution","case_attachment"))
            result.put(table,jdbc.queryForObject("select count(*) from "+table+" where case_id=?",Integer.class,id));
        result.put("status",jdbc.queryForObject("select status from case_file where id=?",String.class,id));return result;
    }
    @Test void genericAccountCannotReadOrWriteBureauEvenWhenCaseScopeIsAll() throws Exception {
        long id=fresh();String token=actor();var before=counts(id);
        for(String url:List.of("/api/bureau/cases","/api/bureau/cases/"+id,"/api/bureau/search?q=ACCESS"))
            assertEquals(403,code(get(url).header("Authorization","Bearer "+token)));
        assertEquals(403,code(post("/api/bureau/cases/{id}/evidences",id).header("Authorization","Bearer "+token).contentType("application/json").content("{\"type\":\"DOCUMENT\",\"name\":\"越权证据\"}")));
        assertEquals(before,counts(id));
        assertEquals(401,mvc.perform(get("/api/bureau/cases/{id}",id)).andReturn().getResponse().getStatus());
    }
    @Test void legalRoleCanInspectButCannotAlterInvestigationMaterialsOrExecution() throws Exception {
        long id=fresh();String token=actor("LEGAL");var before=counts(id);
        Map<String,String> writes=new LinkedHashMap<>();
        writes.put("officers","{\"name\":\"越权\",\"certNo\":\"YB003\",\"duty\":\"MEMBER\"}");
        writes.put("officers/999/avoid","{\"reason\":\"测试\"}");
        writes.put("evidences","{\"type\":\"DOCUMENT\",\"name\":\"越权\"}");
        writes.put("evidences/999/hold-disposal","{\"disposal\":\"RELEASE\"}");
        writes.put("evidences/999/cross-exam","{\"opinion\":\"越权\"}");
        writes.put("evidences/999/seal?extend=false","{}");
        writes.put("documents","{\"docType\":\"FINAL_REPORT\",\"title\":\"越权\",\"content\":\"越权\"}");
        writes.put("report","{\"content\":\"越权报告\"}");
        writes.put("reviews","{\"requiredReason\":\"自己送审\"}");
        writes.put("notice","{\"content\":\"越权告知\",\"proposedFine\":1}");
        writes.put("statement","{\"statement\":\"越权陈述\"}");
        writes.put("meetings","{\"attendees\":\"越权\",\"record\":\"测试\",\"conclusion\":\"测试\"}");
        writes.put("publish","{}");writes.put("gov-record","{\"recordNo\":\"越权备案\"}");
        writes.put("deliver","{\"method\":\"DIRECT\"}");
        writes.put("executions","{\"kind\":\"FINE\",\"amount\":1,\"method\":\"BANK\"}");writes.put("court-enforce","{}");
        writes.put("attachments/999/void","{\"reason\":\"越权作废\"}");
        for(var write:writes.entrySet())assertEquals(403,code(post("/api/bureau/cases/"+id+"/"+write.getKey()).header("Authorization","Bearer "+token).contentType("application/json").content(write.getValue())),write.getKey());
        assertEquals(403,code(multipart("/api/bureau/cases/{id}/attachments",id).file(new MockMultipartFile("file","synthetic.txt","text/plain",new byte[]{1})).header("Authorization","Bearer "+token)));
        assertEquals(0,code(get("/api/bureau/cases/{id}",id).header("Authorization","Bearer "+token)));
        assertEquals(0,code(post("/api/bureau/cases/{id}/preflight",id).header("Authorization","Bearer "+token).contentType("application/json").content("{\"action\":\"DECIDE\"}")));
        assertEquals(before,counts(id),"Denied writes must not create business rows or change state");
    }
    @Test void handlerCanAddEvidenceAndUploadWhileLegalStillMayReachReviewQualificationGuard() throws Exception {
        long id=fresh();String token=actor("HANDLER");
        assertEquals(0,code(post("/api/bureau/cases/{id}/evidences",id).header("Authorization","Bearer "+token).contentType("application/json").content("{\"type\":\"DOCUMENT\",\"name\":\"正常证据\"}")));
        assertEquals(0,code(multipart("/api/bureau/cases/{id}/attachments",id).file(new MockMultipartFile("file","synthetic.txt","text/plain",new byte[]{1,2})).header("Authorization","Bearer "+token)));
        cases.report(id,"正常报告","banban");var review=cases.submitReview(id,"法制审核");
        assertEquals(2070,code(post("/api/bureau/cases/reviews/{id}",review.getId()).header("Authorization","Bearer "+actor("LEGAL")).contentType("application/json").content("{\"opinionType\":\"AGREE\",\"opinion\":\"有岗位但缺资格\"}")),"LEGAL must retain its review endpoint; qualification remains a separate guard");
    }
}
