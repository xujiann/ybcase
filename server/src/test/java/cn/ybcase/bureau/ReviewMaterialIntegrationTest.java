package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.entity.CaseReview;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.*;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** A passed legal opinion authorizes only its submitted material version, never a later revision. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReviewMaterialIntegrationTest {
    @Autowired CaseService cases;
    @Autowired CaseCauseRepository causes;
    @Autowired EvidenceService evidence;
    @Autowired ProcedureService procedure;
    @Autowired DocumentService documents;
    @Autowired ReviewMaterialService materials;
    @Autowired MessageService messages;
    @Autowired BureauConfig config;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager transactionManager;
    private final Map<String,String> original=new LinkedHashMap<>();
    void configure(String key,String value) {
        original.computeIfAbsent(key,k->jdbc.queryForObject("select cfg_value from sys_config where cfg_key=?",String.class,k));
        jdbc.update("update sys_config set cfg_value=? where cfg_key=?",value,key); config.onConfigChanged(new ConfigChangedEvent(key));
    }
    @BeforeEach void allCasesRequireReview() { configure("legal_review_mode","ALL"); configure("case_view_scope","ALL"); }
    @AfterEach void restore() { original.forEach((k,v)->{jdbc.update("update sys_config set cfg_value=? where cfg_key=?",v,k);config.onConfigChanged(new ConfigChangedEvent(k));});original.clear(); }
    CaseFile fresh() { return fresh("banban"); }
    CaseFile fresh(String owner) {
        long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();
        return cases.create(new CaseService.CaseCreateReq(null,cause,"NORMAL","REVIEW-VERSION-"+System.nanoTime(),"PROVIDER",null,null,null,null,
            "材料版本测试",BigDecimal.TEN,List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),owner);
    }
    CaseFile reported() { var c=fresh(); cases.report(c.getId(),"调查终结报告","banban"); return c; }
    void notice(long id) {
        cases.notify(id,new CaseService.NoticeReq("拟罚100元",new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,null));
        cases.recordStatement(id,new CaseService.StatementReq(null,null,null,null,true));
    }
    CaseFile noticed() { var c=reported(); notice(c.getId()); return c; }
    CaseService.DecisionReq decision() { return new CaseService.DecisionReq("PUNISH",new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,null,"罚款100元",null,"一般情形"); }
    CaseReview approve(long id) { var review=cases.submitReview(id,"需法制审核"); return cases.doReview(review.getId(),"fazhi","AGREE","所送材料程序合法"); }
    long addEvidence(long id) {
        evidence.addEvidence(id,new EvidenceService.EvidenceReq("DOCUMENT","书证-"+System.nanoTime(),"现场取得",LocalDate.now(),"王办案",null,false,false));
        return jdbc.queryForObject("select max(id) from case_evidence where case_id=?",Long.class,id);
    }
    void stale(long id) { stale(id,2150); }
    void stale(long id,int finalCode) {
        var result=cases.preflight(id,new CaseService.PreflightReq("DECIDE",decision(),null));
        var json=mapper.valueToTree(result);
        assertFalse(json.path("ready").asBoolean());
        boolean found=false; for(var item:json.path("items")) if("legal_review_materials".equals(item.path("key").asText())&&!item.path("passed").asBoolean())found=true;
        assertTrue(found,json.toString()); assertEquals(finalCode,assertThrows(BizException.class,()->cases.decide(id,decision())).code);
        assertEquals(0,jdbc.queryForObject("select count(*) from case_decision where case_id=?",Integer.class,id));
    }
    record Actor(String username,String token) {}
    Actor actor(String role) {
        var u=new SysUser();u.setUsername("review_"+System.nanoTime());u.setRealName("材料测试"+role);u.setPassword("unused-for-jwt-test");u.getRoles().add(roles.findByCode(role).orElseThrow());u=users.save(u);
        return new Actor(u.getUsername(),jwt.issue(u.getUsername(),u.getTokenVersion()));
    }
    long upload(long id,long evidenceId,Actor actor) throws Exception {
        var response=mvc.perform(multipart("/api/bureau/cases/{id}/attachments",id)
            .file(new MockMultipartFile("file","material.bin","application/octet-stream",new byte[]{1,2,3,0}))
            .param("evidenceId",String.valueOf(evidenceId)).header("Authorization","Bearer "+actor.token())).andReturn().getResponse();
        assertEquals(0,mapper.readTree(response.getContentAsString()).path("code").asInt(),response.getContentAsString());
        return jdbc.queryForObject("select max(id) from case_attachment where case_id=?",Long.class,id);
    }

    @Test void firstNoticeInvalidatesEarlierReportedReviewAndResubmissionRestoresDecision() {
        var c=reported(); var old=approve(c.getId());
        String oldHash=old.getMaterialFingerprint(); assertNotNull(oldHash); assertNotNull(old.getMaterialSnapshot());
        notice(c.getId()); stale(c.getId());
        var next=approve(c.getId()); assertNotEquals(oldHash,next.getMaterialFingerprint());
        var opinions=documents.archiveEntries(c.getId()).stream().filter(e->"LEGAL_OPINION".equals(e.get("doc_type"))).toList();
        assertTrue(opinions.stream().anyMatch(e->String.valueOf(e.get("content")).contains("#"+old.getId())&&String.valueOf(e.get("content")).contains(oldHash)));
        assertTrue(opinions.stream().anyMatch(e->String.valueOf(e.get("content")).contains("#"+next.getId())&&String.valueOf(e.get("content")).contains(next.getMaterialFingerprint())));
        assertNotNull(cases.decide(c.getId(),decision()).getDecisionNo());
    }

    @Test void changedStatementAndActuallyHeldHearingRequireReviewOfNewMaterials() {
        var c=noticed(); approve(c.getId());
        cases.recordStatement(c.getId(),new CaseService.StatementReq("新增实质陈述","对新增陈述复核",null,null,null));
        stale(c.getId()); approve(c.getId()); cases.decide(c.getId(),decision());
        configure("hearing_threshold_org","50");
        var hearing=noticed();
        cases.recordStatement(hearing.getId(),new CaseService.StatementReq(null,null,true,null,null));
        procedure.scheduleHearing(hearing.getId(),new ProcedureService.HearingScheduleReq(LocalDate.now().minusDays(10),LocalDate.now().minusDays(8),LocalDate.now(),"李法制","法制科","书记员"));
        long hid=jdbc.queryForObject("select id from case_hearing where case_id=?",Long.class,hearing.getId());
        approve(hearing.getId());
        procedure.holdHearing(hearing.getId(),hid,"真实举行听证并形成实质笔录");
        stale(hearing.getId()); approve(hearing.getId()); cases.decide(hearing.getId(),decision());
    }

    @Test void addedEvidenceAndChangedCrossExaminationInvalidatePassedOpinion() {
        var c=noticed(); approve(c.getId()); long eid=addEvidence(c.getId()); stale(c.getId());
        approve(c.getId()); evidence.crossExam(c.getId(),eid,"当事人对证据提出异议"); stale(c.getId());
        approve(c.getId()); cases.decide(c.getId(),decision());
    }

    @Test void effectiveAttachmentItsAssociationAndVoidingAreBoundToOpinion() throws Exception {
        var c=noticed(); long first=addEvidence(c.getId()), second=addEvidence(c.getId()); var handler=actor("HANDLER");
        approve(c.getId()); long aid=upload(c.getId(),first,handler); stale(c.getId());
        approve(c.getId());
        jdbc.update("update case_attachment set evidence_id=? where id=?",second,aid);
        stale(c.getId()); approve(c.getId());
        var response=mvc.perform(post("/api/bureau/cases/{cid}/attachments/{aid}/void",c.getId(),aid).header("Authorization","Bearer "+handler.token())
            .contentType("application/json").content("{\"reason\":\"证据关联核查后作废\"}")).andReturn().getResponse();
        assertEquals(0,mapper.readTree(response.getContentAsString()).path("code").asInt()); stale(c.getId());
    }

    @Test void correctedCaseFactsJurisdictionAndSubstantiveReportCannotReuseOldOpinion() {
        var c=reported(); var pending=cases.submitReview(c.getId(),"审核原摘要");
        cases.correctPartyInfo(c.getId(),new CaseService.CorrectionReq(null,null,null,null,null,"实质摘要已补正",null,null,"核查更正"),"banban");
        assertEquals(2150,assertThrows(BizException.class,()->cases.doReview(pending.getId(),"fazhi","AGREE","旧摘要不可批准")).code);
        notice(c.getId()); approve(c.getId());
        cases.updateJurisdiction(c.getId(),new CaseService.JurisdictionUpdateReq(new CaseService.JurisdictionReq("LOCAL","已核实属地依据","甲地",null,null,null,null),"补充管辖核查"),"banban");
        stale(c.getId()); approve(c.getId());
        cases.addDocument(c.getId(),new CaseService.DocumentReq("OTHER","新增实质说明","补充调查事实",LocalDate.now(),"banban",false,null,null));
        stale(c.getId());
    }

    @Test void pendingReviewWithChangedMaterialsAndSupersededPendingCannotBeApproved() {
        var c=noticed(); var old=cases.submitReview(c.getId(),"原材料"); addEvidence(c.getId());
        var before=jdbc.queryForMap("select passed,reviewed_at,opinion,material_fingerprint,material_snapshot from case_review where id=?",old.getId());
        assertEquals(2150,assertThrows(BizException.class,()->cases.doReview(old.getId(),"fazhi","AGREE","待审期间已补证")).code);
        assertEquals(before,jdbc.queryForMap("select passed,reviewed_at,opinion,material_fingerprint,material_snapshot from case_review where id=?",old.getId()));
        var current=cases.submitReview(c.getId(),"当前材料");
        assertEquals(2150,assertThrows(BizException.class,()->cases.doReview(old.getId(),"fazhi","AGREE","旧申请不能回流")).code);
        cases.doReview(current.getId(),"fazhi","AGREE","审核当前版本"); cases.decide(c.getId(),decision());
    }

    @Test void latestPendingOrNegativeReviewCannotFallBackToEarlierPassedReview() {
        var c=noticed(); approve(c.getId()); var latest=cases.submitReview(c.getId(),"再次送审");
        var pendingError=assertThrows(BizException.class,()->cases.decide(c.getId(),decision()));
        assertEquals(2005,pendingError.code);
        cases.doReview(latest.getId(),"fazhi","CORRECT","要求补正");
        var negativeError=assertThrows(BizException.class,()->cases.decide(c.getId(),decision()));
        assertEquals(2005,negativeError.code);
        approve(c.getId()); cases.decide(c.getId(),decision());
    }

    @Test void unboundHistoricInFlightReviewRequiresResubmissionButArchivedReadsNeverBackfill() {
        var c=noticed(); var review=approve(c.getId());
        jdbc.update("update case_review set material_fingerprint=null,material_snapshot=null where id=?",review.getId()); stale(c.getId());
        approve(c.getId()); cases.decide(c.getId(),decision());
        var historical=noticed(); var historicalReview=approve(historical.getId());
        cases.decide(historical.getId(),new CaseService.DecisionReq("NO_PUNISH",BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,null,"不予处罚",null,null));
        cases.close(historical.getId(),"不予处罚归档","admin");
        jdbc.update("update case_review set material_fingerprint=null,material_snapshot=null where id=?",historicalReview.getId());
        cases.detail(historical.getId());
        assertNull(jdbc.queryForObject("select material_fingerprint from case_review where id=?",String.class,historicalReview.getId()));
        assertNull(jdbc.queryForObject("select material_snapshot from case_review where id=?",String.class,historicalReview.getId()));
    }

    @Test void optionalStaleReviewDoesNotExpandThresholdModeReviewRequirement() {
        configure("legal_review_mode","THRESHOLD"); var c=noticed(); approve(c.getId()); addEvidence(c.getId());
        assertNotNull(cases.decide(c.getId(),decision()).getDecisionNo(),"Small non-hearing case stays exempt from mandatory review");
    }

    @Test void deadlineMessagesMeetingsAndReviewRowsDoNotInvalidateSnapshot() {
        var c=noticed(); var pending=cases.submitReview(c.getId(),"当前实质材料"); String fingerprint=materials.capture(c.getId()).fingerprint();
        cases.extend(c.getId(),5,"纯期限延期");
        cases.addExclusion(c.getId(),new CaseService.ExclusionReq("TEST",LocalDate.now(),LocalDate.now(),"零日闭合扣除"));
        cases.addMeeting(c.getId(),new CaseService.MeetingReq(LocalDate.now(),"局领导班子","后续讨论","同意"));
        messages.send("banban","SYSTEM","非材料消息","期限提醒","/case/detail/"+c.getId(),null);
        assertEquals(fingerprint,materials.capture(c.getId()).fingerprint());
        cases.doReview(pending.getId(),"fazhi","AGREE","当前版本意见");
        assertEquals(fingerprint,materials.capture(c.getId()).fingerprint(),"Writing the review itself must not change its source snapshot");
        cases.decide(c.getId(),decision());
    }

    @Test void concurrentEvidenceApprovalAndDecisionNeverAuthorizeUnreviewedSupplement() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            for(int run=0;run<3;run++) {
                var pendingCase=noticed();var pending=cases.submitReview(pendingCase.getId(),"并发待审");var gate=new CyclicBarrier(2);
                var supplement=pool.submit(()->{gate.await(10,TimeUnit.SECONDS);addEvidence(pendingCase.getId());return true;});
                var approval=pool.submit(()->{gate.await(10,TimeUnit.SECONDS);try{cases.doReview(pending.getId(),"fazhi","AGREE","并发审核");return 0;}catch(BizException e){return e.code;}});
                assertTrue(supplement.get(30,TimeUnit.SECONDS));int approvalCode=approval.get(30,TimeUnit.SECONDS);
                assertTrue(List.of(0,2150).contains(approvalCode));stale(pendingCase.getId(),approvalCode==0?2150:2005);
                assertEquals(approvalCode==0,jdbc.queryForObject("select passed is true from case_review where id=?",Boolean.class,pending.getId()));
                var decidedCase=noticed();approve(decidedCase.getId());var decideGate=new CyclicBarrier(2);
                var evidenceOutcome=pool.submit(()->{decideGate.await(10,TimeUnit.SECONDS);try{addEvidence(decidedCase.getId());return 0;}catch(BizException e){return e.code;}});
                var decisionOutcome=pool.submit(()->{decideGate.await(10,TimeUnit.SECONDS);try{cases.decide(decidedCase.getId(),decision());return 0;}catch(BizException e){return e.code;}});
                int e=evidenceOutcome.get(30,TimeUnit.SECONDS),d=decisionOutcome.get(30,TimeUnit.SECONDS);
                assertTrue(e==0&&d==2150||e==2044&&d==0,"Exactly one safe serialization is allowed: evidence="+e+", decide="+d);
            }
        } finally {pool.shutdownNow();}
    }

    @Test void handlerCannotApproveAndSelfScopeCannotSubmitOrPreflightForeignReview() throws Exception {
        var c=noticed();var review=cases.submitReview(c.getId(),"权限核查");var handler=actor("HANDLER");
        var denied=mvc.perform(post("/api/bureau/cases/reviews/{id}",review.getId()).header("Authorization","Bearer "+handler.token())
            .contentType("application/json").content("{\"reviewer\":\"李法制\",\"opinionType\":\"AGREE\",\"opinion\":\"伪造姓名\"}")).andReturn().getResponse();
        assertEquals(403,mapper.readTree(denied.getContentAsString()).path("code").asInt());
        configure("case_view_scope","SELF");
        for(String path:List.of("/api/bureau/cases/"+c.getId()+"/reviews","/api/bureau/cases/"+c.getId()+"/preflight")) {
            String body=path.endsWith("reviews")?"{\"requiredReason\":\"跨案送审\"}":mapper.writeValueAsString(Map.of("action","DECIDE","decision",decision()));
            var response=mvc.perform(post(path).header("Authorization","Bearer "+handler.token()).contentType("application/json").content(body)).andReturn().getResponse();
            assertEquals(2080,mapper.readTree(response.getContentAsString()).path("code").asInt());
        }
        assertNull(jdbc.queryForObject("select reviewed_at from case_review where id=?",java.sql.Date.class,review.getId()));
    }

    @Test void nestedJpaCorrectionIsFlushedBeforeSubmissionSnapshotInSameOuterTransaction() {
        var c=reported(); String correctedName="CORRECTED-IN-TRANSACTION-"+System.nanoTime();
        CaseReview submitted=new TransactionTemplate(transactionManager).execute(status->{
            cases.correctPartyInfo(c.getId(),new CaseService.CorrectionReq(correctedName,null,null,null,null,null,null,null,"同事务核查更正主体"),"banban");
            var review=cases.submitReview(c.getId(),"更正后同事务送审");
            var snapshot=assertDoesNotThrow(()->mapper.readTree(review.getMaterialSnapshot()));
            assertEquals(correctedName,snapshot.path("caseFacts").path("party_name").asText());
            assertTrue(materials.matches(review),"The capture must see a JPA change pending in the same write transaction");
            return review;
        });
        assertNotNull(submitted); assertEquals(correctedName,cases.get(c.getId()).getPartyName());
        assertTrue(materials.matches(submitted));
    }
}
