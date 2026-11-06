package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.ApprovalService;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.ClueService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class ApprovalBindingIntegrationTest {
    @Autowired ApprovalService approvals;
    @Autowired CaseService cases;
    @Autowired ClueService clues;
    @Autowired CaseCauseRepository causes;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    Map<String,Object> payload() {
        var p = new LinkedHashMap<String,Object>();
        p.put("causeId", causes.findAll().stream().filter(c -> c.getItemNo()==13).findFirst().orElseThrow().getId());
        p.put("partyName", "BINDING-"+System.nanoTime()); p.put("partyType","PROVIDER"); p.put("procedureType","NORMAL");
        p.put("summary","审批绑定测试"); p.put("amountInvolved",10);
        p.put("officers",List.of(Map.of("name","王办案","certNo","YB001","duty","LEAD"),Map.of("name","张协办","certNo","YB002","duty","MEMBER")));
        return p;
    }
    long createCase() { return cases.create(mapper.convertValue(payload(),CaseService.CaseCreateReq.class),"banban").getId(); }
    long clue(String owner) { return clues.create(new ClueService.ClueCreateReq("COMPLAINT","绑定测试","医院","PROVIDER",LocalDate.now(),owner),owner).getId(); }
    long count(String table) { return jdbc.queryForObject("select count(*) from "+table,Long.class); }

    @Test void invalidSourceCombinationsAreRejectedBeforeAnyWrite() {
        long c=createCase(), clue=clue("banban");
        long a=count("biz_approval"), casesBefore=count("case_file");
        for(var r:List.of(new ApprovalService.ApplyReq("FILE_CASE",null,c,payload(),"不应串案"),
                new ApprovalService.ApplyReq("CLUE_EXTEND",clue,c,Map.of(),"不应串案"),
                new ApprovalService.ApplyReq("CLUE_EXTEND",null,null,Map.of(),"缺线索"),
                new ApprovalService.ApplyReq("EXTEND",clue,c,Map.of("days",1),"不应串源"),
                new ApprovalService.ApplyReq("SUSPEND",clue,c,Map.of(),"不应串源"),
                new ApprovalService.ApplyReq("TERMINATE",clue,c,Map.of(),"不应串源"),
                new ApprovalService.ApplyReq("DEFER",clue,c,Map.of(),"不应串源"))) {
            assertEquals(2071,assertThrows(BizException.class,()->approvals.apply(r,"banban",false)).code);
        }
        assertEquals(a,count("biz_approval")); assertEquals(casesBefore,count("case_file"));
    }

    @Test void historicalMalformedPendingApplicationsCannotExecuteButCanBeRejected() throws Exception {
        long c=createCase(), clue=clue("banban");
        for(String kind:List.of("FILE_CASE","CLUE_EXTEND","EXTEND","SUSPEND","TERMINATE","DEFER")) {
            long id=jdbc.queryForObject("insert into biz_approval(kind,case_id,clue_id,payload,reason,applicant) values(?,?,?,?,?,?) returning id",Long.class,
                kind,c,clue,mapper.writeValueAsString(payload()),"历史异常绑定","banban");
            long before=count("case_file");
            assertEquals(2071,assertThrows(BizException.class,()->approvals.decide(id,true,"同意","juzhang")).code);
            assertEquals(before,count("case_file"));
            var row=jdbc.queryForMap("select status,approver,decided_at,case_id,clue_id from biz_approval where id=?",id);
            assertEquals("PENDING",row.get("status")); assertNull(row.get("approver")); assertNull(row.get("decided_at"));
            assertEquals(c,((Number)row.get("case_id")).longValue()); assertEquals(clue,((Number)row.get("clue_id")).longValue());
            approvals.decide(id,false,"绑定有误请重提","juzhang");
            assertEquals("REJECTED",jdbc.queryForObject("select status from biz_approval where id=?",String.class,id));
        }
    }

    @Test void normalFilingBindsExactlyTheCreatedCaseAndPreservesClue() {
        long clue=clue("banban"); var p=payload(); p.put("clueId",clue);
        long id=approvals.apply(new ApprovalService.ApplyReq("FILE_CASE",clue,null,p,"正常立案"),"banban",false);
        assertNull(jdbc.queryForObject("select case_id from biz_approval where id=?",Long.class,id));
        CaseFile created=(CaseFile)approvals.decide(id,true,"同意","juzhang").get("result");
        assertEquals(created.getId(),jdbc.queryForObject("select case_id from biz_approval where id=?",Long.class,id));
        assertEquals(clue,jdbc.queryForObject("select clue_id from biz_approval where id=?",Long.class,id));
        assertEquals(1,approvals.byCase(created.getId()).stream().filter(a->((Number)a.get("id")).longValue()==id).count());
    }

    @Test void filingSourceScopeAndConflictingPayloadStillRejectWithoutApplication() {
        long foreign=clue("foreign_binding_owner"); long before=count("biz_approval");
        var p=payload(); p.put("clueId",foreign);
        assertEquals(2080,assertThrows(BizException.class,()->approvals.apply(new ApprovalService.ApplyReq("FILE_CASE",foreign,null,p,"越权来源"),"banban",false)).code);
        long own=clue("banban");
        assertEquals(2146,assertThrows(BizException.class,()->approvals.apply(new ApprovalService.ApplyReq("FILE_CASE",own,null,p,"来源冲突"),"banban",false)).code);
        assertEquals(before,count("biz_approval"));
    }
    @Test void historicalConflictingFilingCluesCannotExecuteAndRemainRejectable() throws Exception {
        long outer=clue("banban"), inner=clue("banban"); var p=payload(); p.put("clueId",inner);
        String json=mapper.writeValueAsString(p);
        long id=jdbc.queryForObject("insert into biz_approval(kind,clue_id,payload,reason,applicant) values('FILE_CASE',?,?,?,?) returning id",Long.class,outer,json,"历史冲突来源","banban");
        long before=count("case_file");
        assertEquals(2146,assertThrows(BizException.class,()->approvals.decide(id,true,"同意","juzhang")).code);
        assertEquals(before,count("case_file"));
        assertEquals("PENDING",jdbc.queryForObject("select status from biz_approval where id=?",String.class,id));
        assertEquals(json,jdbc.queryForObject("select payload from biz_approval where id=?",String.class,id));
        for(long clue:List.of(outer,inner)) assertEquals("PENDING",jdbc.queryForObject("select status from case_clue where id=?",String.class,clue));
        approvals.decide(id,false,"来源冲突请重提","juzhang");
        assertEquals("REJECTED",jdbc.queryForObject("select status from biz_approval where id=?",String.class,id));
    }

    @Test void historicalOuterOnlyClueIsAdoptedWithoutRewritingOriginalPayload() throws Exception {
        long clue=clue("banban"); String json=mapper.writeValueAsString(payload());
        long attachment=jdbc.queryForObject("insert into case_attachment(source_clue_id,filename,content_type,size_bytes,data,category,uploaded_by) values(?,?,?,1,?,'OTHER','banban') returning id",Long.class,clue,"historical-source.bin","application/octet-stream",new byte[]{42});
        long id=jdbc.queryForObject("insert into biz_approval(kind,clue_id,payload,reason,applicant) values('FILE_CASE',?,?,?,?) returning id",Long.class,clue,json,"历史仅顶层线索","banban");
        CaseFile created=(CaseFile)approvals.decide(id,true,"同意","juzhang").get("result");
        assertEquals(clue,jdbc.queryForObject("select clue_id from case_file where id=?",Long.class,created.getId()));
        assertEquals(created.getId(),jdbc.queryForObject("select case_id from case_attachment where id=?",Long.class,attachment));
        assertArrayEquals(new byte[]{42},jdbc.queryForObject("select data from case_attachment where id=?",byte[].class,attachment));
        assertEquals(json,jdbc.queryForObject("select payload from biz_approval where id=?",String.class,id));
        assertEquals(created.getId(),jdbc.queryForObject("select case_id from biz_approval where id=?",Long.class,id));
    }

}
