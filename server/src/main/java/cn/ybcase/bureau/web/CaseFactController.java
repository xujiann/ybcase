package cn.ybcase.bureau.web;

import cn.ybcase.bureau.service.CaseFactService;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.MaterialAccess;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/bureau/cases/{caseId}/fact-versions")
@RequiredArgsConstructor
public class CaseFactController {
    private final CaseService cases;
    private final CaseFactService facts;
    private final MaterialAccess access;
    private final JdbcTemplate jdbc;

    @GetMapping
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public R<Map<String,Object>> state(@PathVariable long caseId,Authentication auth) {
        var actor=access.requireCaseReader(caseId,auth.getName());
        var c=cases.get(caseId);
        boolean writer=actor.roles().stream().anyMatch(Set.of("HANDLER","LEADER","ADMIN")::contains);
        boolean editable=writer && c.getArchiveNo()==null && Set.of("INVESTIGATING","REPORTED").contains(c.getStatus());
        boolean returned=Boolean.TRUE.equals(jdbc.queryForObject("""
          select exists(select 1 from case_review r where r.case_id=? and r.id=(select max(id) from case_review where case_id=?)
            and r.reviewed_at is not null and r.opinion_type in ('CONTINUE','CHANGE','CORRECT') and not coalesce(r.passed,false)
            and not exists(select 1 from case_investigation_return t where t.review_id=r.id))
          """,Boolean.class,caseId,caseId));
        var result=new LinkedHashMap<String,Object>();
        result.put("originalFacts",facts.snapshot(caseId));
        result.put("effectiveFacts",jdbc.queryForMap("select * from case_effective_reporting_facts() where case_id=?",caseId));
        result.put("canCorrectInstitution",editable && "PROVIDER".equals(c.getPartyType()));
        result.put("canReturnInvestigation",writer && c.getArchiveNo()==null && "NORMAL".equals(c.getProcedureType())
                && Set.of("REPORTED","NOTIFIED").contains(c.getStatus()) && returned);
        result.put("history",facts.history(caseId,auth.getName()));
        return R.ok(result);
    }

    @PostMapping("/return-investigation")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<?> reopen(@PathVariable long caseId,@RequestBody CaseService.InvestigationReturnReq request,Authentication auth) {
        return R.ok(cases.returnForInvestigation(caseId,request,auth.getName()));
    }
}
