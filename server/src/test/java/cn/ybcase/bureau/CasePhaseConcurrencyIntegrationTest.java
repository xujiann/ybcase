package cn.ybcase.bureau;

import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.CaseGuards;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.EnforcerRegistryAccess;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** Captured JWT authorities may precede a case/registry/user lock wait and must not authorize a later mutation. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CasePhaseConcurrencyIntegrationTest {
    @Autowired CaseService cases;
    @Autowired CaseCauseRepository causes;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired BureauConfig config;
    @Autowired PlatformTransactionManager tm;
    @Autowired EnforcerRegistryAccess registry;
    final Map<String,String> original = new LinkedHashMap<>();

    enum Kind {
        JURISDICTION, CORRECT, ADD_OFFICER, AVOID_OFFICER, EXCLUSION_ADD, EXCLUSION_END,
        REPORT, REVIEW_SUBMIT, NOTICE, STATEMENT, MEETING, DELIVER, PUBLISH, GOV_RECORD,
        SUSPEND, RESUME, TERMINATE, EXTEND, DECIDE, CLOSE, TRANSFER_OWNER;
        boolean leader() {return EnumSet.of(SUSPEND, RESUME, TERMINATE, EXTEND, DECIDE, CLOSE, TRANSFER_OWNER).contains(this);}
        boolean summary() {return EnumSet.of(DELIVER, PUBLISH, GOV_RECORD, DECIDE, CLOSE).contains(this);}
    }
    record Actor(long id, String name, String token) {}
    record Probe(long caseId, String method, String path, Object body) {}

    static Stream<Kind> ordinaryKinds() {return Arrays.stream(Kind.values()).filter(k -> !k.leader());}

    @BeforeEach void setup() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class));
        SecurityContextHolder.clearContext();
        cfg("case_view_scope", "SELF");cfg("archive_completeness_required", "false");
    }
    void cfg(String key, String value) {
        original.computeIfAbsent(key, k -> jdbc.queryForObject("select cfg_value from sys_config where cfg_key=?", String.class, k));
        jdbc.update("update sys_config set cfg_value=? where cfg_key=?", value, key);
        config.onConfigChanged(new ConfigChangedEvent(key));
    }
    @AfterEach void restore() {
        SecurityContextHolder.clearContext();
        original.forEach((key,value) -> {
            jdbc.update("update sys_config set cfg_value=? where cfg_key=?", value, key);
            config.onConfigChanged(new ConfigChangedEvent(key));
        });
    }

    Actor actor(String role) {
        var user = new SysUser();user.setUsername("phase_" + UUID.randomUUID().toString().replace("-", ""));
        user.setRealName("案件阶段回归");user.setPassword("unused-jwt-fixture");user.setEnabled(true);
        user.getRoles().add(roles.findByCode(role).orElseThrow());
        user = users.saveAndFlush(user);
        return new Actor(user.getId(), user.getUsername(), jwt.issue(user.getUsername(), user.getTokenVersion()));
    }

    CaseService.CaseCreateReq filingRequest(boolean summary) {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        return new CaseService.CaseCreateReq(null, cause, summary ? "SUMMARY" : "NORMAL",
                "案件阶段" + System.nanoTime(), "PROVIDER", null, null, null, null, "原案件事实", BigDecimal.TEN,
                List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
                        new CaseService.OfficerReq("张协办", "YB002", "MEMBER")), null, null, null, null);
    }
    long fresh(Actor owner, boolean summary) {
        long id = cases.create(filingRequest(summary), owner.name()).getId();
        assertEquals(0, jdbc.queryForObject("select count(*) from case_officer where case_id=? and user_id=?", Integer.class, id, owner.id()));
        return id;
    }

    CaseService.DecisionReq decision(String type) {
        return new CaseService.DecisionReq(type, "PUNISH".equals(type) ? BigDecimal.valueOf(100) : BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, null, "原处理决定", null, "一般情形");
    }

    Probe probe(Actor owner, Kind kind) {
        long id = fresh(owner, kind.summary());String path = "/api/bureau/cases/" + id;
        if (EnumSet.of(Kind.REVIEW_SUBMIT, Kind.NOTICE, Kind.STATEMENT).contains(kind))
            cases.report(id, "原调查终结报告", owner.name());
        if (kind == Kind.STATEMENT)
            cases.notify(id, new CaseService.NoticeReq("原处罚告知", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, null));
        if (EnumSet.of(Kind.DELIVER, Kind.PUBLISH, Kind.GOV_RECORD, Kind.CLOSE).contains(kind))
            cases.decide(id, decision(kind == Kind.CLOSE ? "NO_PUNISH" : "PUNISH"));
        if (kind == Kind.PUBLISH || kind == Kind.GOV_RECORD)
            cases.deliver(id, new CaseService.DeliveryReq("DIRECT", LocalDate.now(), "原受送达人", null, null, null));
        return switch (kind) {
            case JURISDICTION -> new Probe(id, "PUT", path + "/jurisdiction", Map.of("jurisdiction",
                    Map.of("type", "LOCAL", "basis", "原有管辖事实核实", "violationPlace", "本辖区"), "reason", "核实补录"));
            case CORRECT -> new Probe(id, "PUT", path + "/party-info", Map.of("partyName", "更正后的当事人", "reason", "名称录入错误"));
            case ADD_OFFICER -> new Probe(id, "POST", path + "/officers", Map.of("name", "李替补", "certNo", "YB003", "duty", "MEMBER"));
            case AVOID_OFFICER -> {
                cases.addOfficer(id, new CaseService.OfficerReq("李替补", "YB003", "MEMBER"));
                long officer = jdbc.queryForObject("select id from case_officer where case_id=? and cert_no='YB002'", Long.class, id);
                yield new Probe(id, "POST", path + "/officers/" + officer + "/avoid", Map.of("reason", "存在利害关系", "applicant", "SELF"));
            }
            case EXCLUSION_ADD -> new Probe(id, "POST", path + "/exclusions", Map.of("reason", "TEST", "startAt", LocalDate.now().toString(), "endAt", LocalDate.now().toString()));
            case EXCLUSION_END -> {
                cases.addExclusion(id, new CaseService.ExclusionReq("TEST", LocalDate.now(), null, "原扣除事由"));
                long exclusion = jdbc.queryForObject("select id from case_period_exclusion where case_id=?", Long.class, id);
                yield new Probe(id, "POST", path + "/exclusions/" + exclusion + "/end", Map.of("endAt", LocalDate.now().toString()));
            }
            case REPORT -> new Probe(id, "POST", path + "/report", Map.of("content", "完整调查终结报告"));
            case REVIEW_SUBMIT -> new Probe(id, "POST", path + "/reviews", Map.of("requiredReason", "疑难复杂案件"));
            case NOTICE -> new Probe(id, "POST", path + "/notice", Map.of("content", "处罚告知", "proposedFine", 10));
            case STATEMENT -> new Probe(id, "POST", path + "/statement", Map.of("statement", "当事人陈述", "statementReview", "已复核"));
            case MEETING -> new Probe(id, "POST", path + "/meetings", Map.of("attendees", "原参加人员", "record", "原讨论记录", "conclusion", "原结论"));
            case DELIVER -> new Probe(id, "POST", path + "/deliver", Map.of("method", "DIRECT", "receiver", "法人", "deliveredAt", LocalDate.now().toString()));
            case PUBLISH -> new Probe(id, "POST", path + "/publish", Map.of());
            case GOV_RECORD -> new Probe(id, "POST", path + "/gov-record", Map.of("recordNo", "阶段回归备案文号"));
            case SUSPEND -> new Probe(id, "POST", path + "/suspend", Map.of("reason", "法定中止情形"));
            case RESUME -> {
                cases.suspend(id, "原法定中止情形");
                yield new Probe(id, "POST", path + "/resume", Map.of());
            }
            case TERMINATE -> new Probe(id, "POST", path + "/terminate", Map.of("reason", "法定终止情形"));
            case EXTEND -> new Probe(id, "POST", path + "/extend", Map.of("days", 1, "reason", "复杂案件需延期"));
            case DECIDE -> new Probe(id, "POST", path + "/decide", decision("PUNISH"));
            case CLOSE -> new Probe(id, "POST", path + "/close", Map.of("closeReport", "完整结案报告"));
            case TRANSFER_OWNER -> {
                var receiver = actor("HANDLER");
                yield new Probe(id, "POST", path + "/transfer-owner", Map.of("newOwner", receiver.name()));
            }
        };
    }

    int call(Actor actor, Probe probe) throws Exception {
        var response = mvc.perform(request(HttpMethod.valueOf(probe.method()), probe.path())
                .header("Authorization", "Bearer " + actor.token()).contentType("application/json")
                .content(mapper.writeValueAsBytes(probe.body()))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), "Request must authenticate before waiting on its business lock");
        return mapper.readTree(response.getContentAsString()).path("code").asInt();
    }

    Map<String,Object> snapshot(long id) {
        var result = new LinkedHashMap<String,Object>();var row = new LinkedHashMap<>(jdbc.queryForMap("select * from case_file where id=?", id));
        row.remove("owner_user");result.put("case", row);
        for (String table : List.of("case_correction", "case_officer", "case_period_exclusion", "case_document", "case_notice", "case_review", "case_meeting", "case_decision", "case_delivery", "case_evidence", "case_execution", "biz_approval"))
            result.put(table, jdbc.queryForList("select * from " + table + " where case_id=? order by id", id));
        result.put("messages", jdbc.queryForList("select * from sys_message where link=? order by id", "/case/detail/" + id));
        return result;
    }

    void awaitBlocked(int blocker, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            int waiting = jdbc.queryForObject("""
                    with recursive blocked(pid) as (
                      select pid from pg_locks where not granted and ?=any(pg_blocking_pids(pid))
                      union select locks.pid from pg_locks locks join blocked parent
                        on parent.pid=any(pg_blocking_pids(locks.pid)) where not locks.granted
                    ) select count(distinct pid) from blocked""", Integer.class, blocker);
            if (waiting >= count) return;
            Thread.sleep(25);
        }
        fail("Authenticated phase write did not reach the held PostgreSQL lock");
    }

    int race(Actor actor, Probe probe, Runnable change) throws Exception {
        var pool = Executors.newSingleThreadExecutor();var pending = new AtomicReference<Future<Integer>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                CaseGuards.lock(jdbc, probe.caseId());int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                pending.set(pool.submit(() -> call(actor, probe)));
                try {awaitBlocked(blocker, 1);} catch (Exception failure) {throw new RuntimeException(failure);}
                change.run();
            });
            return pending.get().get(20, TimeUnit.SECONDS);
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));}
    }

    void role(Actor actor, String role) {
        jdbc.queryForList("select id from sys_user where id=? for update", actor.id());
        jdbc.update("delete from sys_user_role where user_id=?", actor.id());
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", actor.id(), role);
    }

    @ParameterizedTest @MethodSource("ordinaryKinds")
    void movedOwnerCannotWriteAnyOrdinaryPhaseAfterWaitingForCaseLock(Kind kind) throws Exception {
        var owner = actor("HANDLER");var next = actor("HANDLER");var probe = probe(owner, kind);var before = snapshot(probe.caseId());
        assertEquals(2080, race(owner, probe, () -> jdbc.update("update case_file set owner_user=? where id=?", next.name(), probe.caseId())), kind.toString());
        assertEquals(before, snapshot(probe.caseId()), kind.toString());
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void currentAuthorizedActorStillCompletesEachExistingPhase(Kind kind) throws Exception {
        var owner = actor(kind.leader() ? "LEADER" : "HANDLER");var probe = probe(owner, kind);var before = snapshot(probe.caseId());
        assertEquals(0, call(owner, probe), kind.toString());
        assertNotEquals(before, snapshot(probe.caseId()), "Success must change the actual case record or append its trace");
    }

    @ParameterizedTest @EnumSource(value=Kind.class, names={"SUSPEND","RESUME","TERMINATE","EXTEND","DECIDE","CLOSE","TRANSFER_OWNER"})
    void formerLeaderWhoRemainsOwnerCannotExerciseLeaderPowersAsHandler(Kind kind) throws Exception {
        var owner = actor("LEADER");var probe = probe(owner, kind);var before = snapshot(probe.caseId());
        assertEquals(403, race(owner, probe, () -> role(owner, "HANDLER")), kind.toString());
        assertEquals(before, snapshot(probe.caseId()), kind.toString());
    }

    @Test void disabledActorCannotCreateNoticeAfterWaiting() throws Exception {
        var actor = actor("HANDLER");var probe = probe(actor, Kind.NOTICE);var before = snapshot(probe.caseId());
        assertEquals(403, race(actor, probe, () -> jdbc.update("update sys_user set enabled=false where id=?", actor.id())));
        assertEquals(before, snapshot(probe.caseId()));
    }
    @Test void roleRemovalCannotCreateMeetingAfterWaiting() throws Exception {
        var actor = actor("HANDLER");var probe = probe(actor, Kind.MEETING);var before = snapshot(probe.caseId());
        assertEquals(403, race(actor, probe, () -> role(actor, "LEGAL")));assertEquals(before, snapshot(probe.caseId()));
    }
    @Test void oldSessionCannotRecordStatementAfterPasswordVersionChanges() throws Exception {
        var actor = actor("HANDLER");var probe = probe(actor, Kind.STATEMENT);var before = snapshot(probe.caseId());
        assertEquals(403, race(actor, probe, () -> jdbc.update("update sys_user set token_version=token_version+1 where id=?", actor.id())));
        assertEquals(before, snapshot(probe.caseId()));
    }
    @Test void officerAdditionKeepsRegistryOrderAndRejectsRevokedSession() throws Exception {
        var actor = actor("HANDLER");var probe = probe(actor, Kind.ADD_OFFICER);var before = snapshot(probe.caseId());
        assertEquals(403, race(actor, probe, () -> jdbc.update("update sys_user set token_version=token_version+1 where id=?", actor.id())));
        assertEquals(before, snapshot(probe.caseId()));
    }

    Probe review(Actor reviewer) {
        var owner = actor("HANDLER");long id = fresh(owner, false);cases.report(id, "原调查终结报告", owner.name());
        var review = cases.submitReview(id, "疑难复杂案件");
        jdbc.update("insert into enforcer(name,cert_no,cert_expire_at,legal_qualified,enabled,user_id) values(?,?,?,true,true,?)",
                "案件阶段审核人", "PHASE-" + UUID.randomUUID(), LocalDate.now().plusYears(1), reviewer.id());
        return new Probe(id, "POST", "/api/bureau/cases/reviews/" + review.getId(), Map.of("opinionType", "AGREE", "opinion", "依法审核同意"));
    }
    @Test void legalReviewRejectsOldTokenAfterActualReviewerLockWithoutAlteringOpinion() throws Exception {
        var legal = actor("LEGAL");var probe = review(legal);var before = snapshot(probe.caseId());
        assertEquals(403, race(legal, probe, () -> jdbc.update("update sys_user set token_version=token_version+1 where id=?", legal.id())));
        assertEquals(before, snapshot(probe.caseId()));
    }
    @Test void currentQualifiedLegalReviewerStillCompletesBoundReview() throws Exception {
        var legal = actor("LEGAL");var probe = review(legal);assertEquals(0, call(legal, probe));
        assertEquals(true, jdbc.queryForObject("select passed from case_review where case_id=?", Boolean.class, probe.caseId()));
    }

    @Test void reciprocalTransfersLockOperatorAndReceiverInCommonOrder() throws Exception {
        var first = actor("LEADER");var second = actor("LEADER");long one = fresh(first, false), two = fresh(second, false);
        var pool = Executors.newFixedThreadPool(2);var pending = new ArrayList<Future<Integer>>();
        var a = new Probe(one, "POST", "/api/bureau/cases/" + one + "/transfer-owner", Map.of("newOwner", second.name()));
        var b = new Probe(two, "POST", "/api/bureau/cases/" + two + "/transfer-owner", Map.of("newOwner", first.name()));
        try {
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                jdbc.queryForList("select id from sys_user where id=? for update", Math.min(first.id(), second.id()));
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                pending.add(pool.submit(() -> call(first, a)));pending.add(pool.submit(() -> call(second, b)));
                try {awaitBlocked(blocker, 2);} catch (Exception failure) {throw new RuntimeException(failure);}
            });
            for (var result : pending) assertEquals(0, result.get(20, TimeUnit.SECONDS));
            assertEquals(second.name(), jdbc.queryForObject("select owner_user from case_file where id=?", String.class, one));
            assertEquals(first.name(), jdbc.queryForObject("select owner_user from case_file where id=?", String.class, two));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));}
    }
    @Test void mappedParticipantMayContinueNotificationAfterOwnerTransfer() throws Exception {
        var participant = actor("HANDLER");var next = actor("HANDLER");var probe = probe(participant, Kind.NOTICE);
        jdbc.update("update case_officer set user_id=? where case_id=? and cert_no='YB001'", participant.id(), probe.caseId());
        assertEquals(0, race(participant, probe, () -> jdbc.update("update case_file set owner_user=? where id=?", next.name(), probe.caseId())));
        assertEquals("NOTIFIED", jdbc.queryForObject("select status from case_file where id=?", String.class, probe.caseId()));
    }
    @Test void authorizedExternalPublicationMetadataStillWorksAfterArchive() throws Exception {
        var owner = actor("HANDLER");var publish = probe(owner, Kind.PUBLISH);
        jdbc.update("update case_file set status='CLOSED',archive_no='PHASE-SEALED',closed_at=current_date where id=?", publish.caseId());
        var before = jdbc.queryForMap("select * from case_file where id=?", publish.caseId());
        assertEquals(0, call(owner, publish));
        assertEquals(0, call(owner, new Probe(publish.caseId(), "POST", "/api/bureau/cases/" + publish.caseId() + "/gov-record", Map.of("recordNo", "归档后外部备案"))));
        assertEquals(before, jdbc.queryForMap("select * from case_file where id=?", publish.caseId()));
    }

    Map<String,Object> filingState(Actor actor) {
        var state = new LinkedHashMap<String,Object>();
        state.put("cases", jdbc.queryForList("select * from case_file where created_by=? order by id", actor.name()));
        state.put("approvals", jdbc.queryForList("select * from biz_approval where applicant=? or approver=? order by id", actor.name(), actor.name()));
        state.put("sequences", jdbc.queryForList("select * from biz_seq order by kind,year"));
        for (String table : List.of("case_attachment", "case_document"))
            state.put(table, jdbc.queryForMap("select count(*) as count,md5(coalesce(string_agg(md5(row_to_json(t)::text),'' order by id),'')) as digest from " + table + " t"));
        return state;
    }
    int filingRace(Actor actor, Runnable change) throws Exception {
        var probe = new Probe(0, "POST", "/api/bureau/cases", filingRequest(false));
        var pool = Executors.newSingleThreadExecutor();var pending = new AtomicReference<Future<Integer>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                registry.lockRegistry();int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                pending.set(pool.submit(() -> call(actor, probe)));
                try {awaitBlocked(blocker, 1);} catch (Exception failure) {throw new RuntimeException(failure);}
                change.run();
            });
            return pending.get().get(20, TimeUnit.SECONDS);
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));}
    }
    @Test void directFilingCannotTurnRevokedLeaderPowerIntoAFalseApprovedApplication() throws Exception {
        var leader = actor("LEADER");var before = filingState(leader);
        assertEquals(403, filingRace(leader, () -> role(leader, "HANDLER")));
        assertEquals(before, filingState(leader), "No case, approved filing, number or material may be written");
    }
    @Test void directFilingRejectsRevokedSessionAfterRegistryWaitBeforeAllocatingCaseNumber() throws Exception {
        var leader = actor("LEADER");var before = filingState(leader);
        assertEquals(403, filingRace(leader, () -> jdbc.update("update sys_user set token_version=token_version+1 where id=?", leader.id())));
        assertEquals(before, filingState(leader));
    }
    @Test void currentLeaderDirectFilingStillCreatesExactlyOneBoundApprovedApplication() throws Exception {
        var leader = actor("LEADER");assertEquals(0, call(leader, new Probe(0, "POST", "/api/bureau/cases", filingRequest(false))));
        long id = jdbc.queryForObject("select id from case_file where created_by=?", Long.class, leader.name());
        assertEquals(1, jdbc.queryForObject("select count(*) from biz_approval where case_id=? and kind='FILE_CASE' and status='APPROVED' and applicant=? and approver=?",
                Integer.class, id, leader.name(), leader.name()));
        assertEquals(2, jdbc.queryForObject("select count(*) from case_officer where case_id=?", Integer.class, id));
    }
}
