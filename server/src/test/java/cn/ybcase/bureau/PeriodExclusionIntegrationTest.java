package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.OversightService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Open intervals must be resolved before a terminal operation, and historic terminal dates stop accumulation. */
@SpringBootTest
@ActiveProfiles("test")
class PeriodExclusionIntegrationTest {
    @Autowired CaseService cases;
    @Autowired OversightService oversight;
    @Autowired CaseCauseRepository causes;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    CaseFile fresh() {
        long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();
        return cases.create(new CaseService.CaseCreateReq(null,cause,"NORMAL","PERIOD-"+System.nanoTime(),"PROVIDER",null,null,null,null,
            "期限扣除回归",BigDecimal.TEN,List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),
            null,null,null,null),"banban");
    }
    CaseFile noticed() {
        var c=fresh(); cases.report(c.getId(),"调查终结","banban");
        cases.notify(c.getId(),new CaseService.NoticeReq("拟罚100元",new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,null));
        cases.recordStatement(c.getId(),new CaseService.StatementReq(null,null,null,null,true)); return c;
    }
    CaseService.DecisionReq decision() { return new CaseService.DecisionReq("PUNISH",new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,null,"罚款100元",null,"一般情形"); }
    long open(long id,LocalDate start) {
        cases.addExclusion(id,new CaseService.ExclusionReq("APPRAISE",start,null,"鉴定尚未结束"));
        return jdbc.queryForObject("select max(id) from case_period_exclusion where case_id=?",Long.class,id);
    }
    void reject2032(org.junit.jupiter.api.function.Executable action) { assertEquals(2032,assertThrows(BizException.class,action).code); }
    Map<String,Object> check(long id,String action) {
        var req=new CaseService.PreflightReq(action,"DECIDE".equals(action)?decision():null,"CLOSE".equals(action)?new CaseService.CloseReq("结案归档报告"):null);
        return mapper.convertValue(cases.preflight(id,req),new TypeReference<>(){});
    }
    @SuppressWarnings("unchecked") void openItemFailed(Map<String,Object> result) {
        assertEquals(false,result.get("ready"));
        assertTrue(((List<Map<String,Object>>)result.get("items")).stream().anyMatch(i->"exclusions_closed".equals(i.get("key"))&&Boolean.FALSE.equals(i.get("passed"))),result.toString());
    }
    void backdate(long id,int days) { jdbc.update("update case_file set filed_at=current_date-? where id=?",days,id); }

    @Test void decisionPreflightAndFinalGuardRequireOpenManualExclusionToBeClosed() {
        var c=noticed(); long period=open(c.getId(),LocalDate.now());
        openItemFailed(check(c.getId(),"DECIDE")); reject2032(()->cases.decide(c.getId(),decision()));
        assertEquals(0,jdbc.queryForObject("select count(*) from case_decision where case_id=?",Integer.class,c.getId()));
        cases.endExclusion(c.getId(),period,LocalDate.now());
        assertEquals(true,check(c.getId(),"DECIDE").get("ready"));
        assertNotNull(cases.decide(c.getId(),decision()).getDecisionNo());
    }

    @Test void terminateAndSuspendCannotLeaveOpenManualIntervalsAndResumeDoesNotDoubleCount() {
        var c=fresh(); backdate(c.getId(),10); long period=open(c.getId(),LocalDate.now().minusDays(5));
        reject2032(()->cases.suspend(c.getId(),"中止事由")); reject2032(()->cases.terminate(c.getId(),"违法事实不能成立"));
        assertEquals("INVESTIGATING",cases.get(c.getId()).getStatus());
        cases.endExclusion(c.getId(),period,LocalDate.now());
        cases.suspend(c.getId(),"扣除已收口后合法中止");
        LocalDate before=cases.get(c.getId()).getDeadlineAt();
        cases.resume(c.getId());
        assertEquals(before,cases.get(c.getId()).getDeadlineAt(),"Same-day suspension/resumption adds zero days");
        assertEquals(before.plusDays(5).toString(),String.valueOf(cases.detail(c.getId()).get("effectiveDeadline")));
        assertEquals("TERMINATED",cases.terminate(c.getId(),"违法事实不能成立").getStatus());
    }

    @Test void legacyTerminatedOpenIntervalBlocksArchiveUntilEndWithinTerminalDate() {
        var c=fresh(); backdate(c.getId(),20); long period=open(c.getId(),LocalDate.now().minusDays(10));
        jdbc.update("update case_file set status='TERMINATED',terminated_at=current_date-3 where id=?",c.getId());
        openItemFailed(check(c.getId(),"CLOSE")); reject2032(()->cases.close(c.getId(),"归档报告","admin"));
        reject2032(()->cases.endExclusion(c.getId(),period,LocalDate.now()));
        assertNull(jdbc.queryForObject("select end_at from case_period_exclusion where id=?",java.sql.Date.class,period));
        cases.endExclusion(c.getId(),period,LocalDate.now().minusDays(3));
        assertEquals(true,check(c.getId(),"CLOSE").get("ready"));
        assertNotNull(cases.close(c.getId(),"归档报告","admin").getArchiveNo());
    }

    @Test void historicArchivedOpenIntervalUsesEarliestTerminalDateWithoutChangingOriginalRecord() {
        var c=fresh(); backdate(c.getId(),30); long period=open(c.getId(),LocalDate.now().minusDays(20));
        jdbc.update("update case_file set status='CLOSED',decided_at=current_date-7,terminated_at=current_date-5,closed_at=current_date-3,archive_no='HISTORIC-PERIOD' where id=?",c.getId());
        var before=jdbc.queryForMap("select * from case_period_exclusion where id=?",period);
        assertEquals(c.getDeadlineAt().plusDays(13).toString(),String.valueOf(cases.detail(c.getId()).get("effectiveDeadline")),"Use 13 days up to the earliest decision date, not 20 days up to today");
        assertEquals(before,jdbc.queryForMap("select * from case_period_exclusion where id=?",period),"Historic read must not invent an end date");
        var e=assertThrows(BizException.class,()->cases.endExclusion(c.getId(),period,LocalDate.now().minusDays(7)));
        assertEquals(2031,e.code);
        // Invalid historical start after the terminal date must never contribute negative days.
        jdbc.update("update case_period_exclusion set start_at=current_date-2 where id=?",period);
        assertEquals(c.getDeadlineAt().toString(),String.valueOf(cases.detail(c.getId()).get("effectiveDeadline")));
    }

    @Test void closureRejectsOverlapFutureReverseForeignAndRepeatedEndButAcceptsAdjacentIntervals() {
        var c=fresh(); backdate(c.getId(),30);
        cases.addExclusion(c.getId(),new CaseService.ExclusionReq("TEST",LocalDate.now().minusDays(10),LocalDate.now().minusDays(5),"已结束检验"));
        // Historical row predates the overlap guard; ending it must not introduce a double-counted range.
        long period=jdbc.queryForObject("insert into case_period_exclusion(case_id,reason,start_at,note) values (?,'APPRAISE',current_date-20,'历史开放记录') returning id",Long.class,c.getId());
        reject2032(()->cases.endExclusion(c.getId(),period,LocalDate.now().minusDays(6)));
        reject2032(()->cases.endExclusion(c.getId(),period,LocalDate.now().plusDays(1)));
        reject2032(()->cases.endExclusion(c.getId(),period,LocalDate.now().minusDays(21)));
        var other=fresh(); reject2032(()->cases.endExclusion(other.getId(),period,LocalDate.now().minusDays(10)));
        assertNull(jdbc.queryForObject("select end_at from case_period_exclusion where id=?",java.sql.Date.class,period));
        cases.endExclusion(c.getId(),period,LocalDate.now().minusDays(10));
        reject2032(()->cases.endExclusion(c.getId(),period,LocalDate.now().minusDays(10)));
        cases.addExclusion(c.getId(),new CaseService.ExclusionReq("HEARING",LocalDate.now().minusDays(5),LocalDate.now(),"相邻听证区间"));
        assertEquals(c.getDeadlineAt().plusDays(20).toString(),String.valueOf(cases.detail(c.getId()).get("effectiveDeadline")));
    }

    @Test void spanLimitUsesActualEndMinusStartAndSameDayOpenCannotBeDuplicated() {
        var c=fresh(); backdate(c.getId(),200);
        cases.addExclusion(c.getId(),new CaseService.ExclusionReq("TEST",LocalDate.now().minusDays(180),LocalDate.now(),"恰好180日"));
        assertEquals(c.getDeadlineAt().plusDays(180).toString(),String.valueOf(cases.detail(c.getId()).get("effectiveDeadline")));
        var tooLong=fresh(); backdate(tooLong.getId(),200);
        reject2032(()->cases.addExclusion(tooLong.getId(),new CaseService.ExclusionReq("TEST",LocalDate.now().minusDays(181),LocalDate.now(),"181日超限")));
        var sameDay=fresh(); open(sameDay.getId(),LocalDate.now());
        reject2032(()->open(sameDay.getId(),LocalDate.now()));
        assertEquals(1,jdbc.queryForObject("select count(*) from case_period_exclusion where case_id=?",Integer.class,sameDay.getId()));
    }

    @Test void ongoingExpertReviewAlsoBlocksDecisionSuspendAndTerminationUntilCompleted() {
        var c=noticed(); oversight.startExpertReview(c.getId(),"专家组",LocalDate.now());
        long review=jdbc.queryForObject("select id from expert_review where case_id=?",Long.class,c.getId());
        openItemFailed(check(c.getId(),"DECIDE")); reject2032(()->cases.decide(c.getId(),decision()));
        reject2032(()->cases.terminate(c.getId(),"违法事实不能成立"));
        oversight.endExpertReview(c.getId(),review,"评审完成",null,LocalDate.now());
        assertEquals(true,check(c.getId(),"DECIDE").get("ready")); cases.decide(c.getId(),decision());
        assertThrows(BizException.class,()->oversight.startExpertReview(c.getId(),"决定后不可新开评审",LocalDate.now()));
        var suspended=fresh(); oversight.startExpertReview(suspended.getId(),"专家组",LocalDate.now());
        reject2032(()->cases.suspend(suspended.getId(),"评审仍进行中"));
    }

    @Test void legacyExpertCompletionCannotAddDaysAfterTerminalDate() {
        var c=fresh(); backdate(c.getId(),10);
        oversight.startExpertReview(c.getId(),"历史评审",LocalDate.now().minusDays(5));
        long review=jdbc.queryForObject("select id from expert_review where case_id=?",Long.class,c.getId());
        jdbc.update("update case_file set status='TERMINATED',terminated_at=current_date-2 where id=?",c.getId());
        assertThrows(BizException.class,()->oversight.endExpertReview(c.getId(),review,"事后延长不允许",null,LocalDate.now()));
        assertNull(jdbc.queryForObject("select ended_at from expert_review where id=?",java.sql.Date.class,review));
        oversight.endExpertReview(c.getId(),review,"按实际终止前结束日补录",null,LocalDate.now().minusDays(2));
        assertEquals(c.getDeadlineAt().plusDays(3).toString(),String.valueOf(cases.detail(c.getId()).get("effectiveDeadline")));
    }

    @Test void legacySuspensionOnlyAddsDaysNotAlreadyCoveredByExclusions() {
        var c=fresh(); backdate(c.getId(),20);
        cases.addExclusion(c.getId(),new CaseService.ExclusionReq("TEST",LocalDate.now().minusDays(8),LocalDate.now().minusDays(3),"历史扣除5日"));
        cases.suspend(c.getId(),"历史中止夹具");
        jdbc.update("update case_file set suspended_at=current_date-10 where id=?",c.getId());
        cases.resume(c.getId());
        assertEquals(c.getDeadlineAt().plusDays(5),cases.get(c.getId()).getDeadlineAt(),"10 suspension days overlap 5 existing days, so only 5 are additional");
        assertEquals(c.getDeadlineAt().plusDays(10).toString(),String.valueOf(cases.detail(c.getId()).get("effectiveDeadline")),"Suspension/exclusion union covers ten distinct days");
        assertEquals(2034,assertThrows(BizException.class,()->cases.resume(c.getId())).code);
    }
}
