package cn.ybcase.bureau;

import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.*;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real source/actor waits distinguish an authorized entry from authority at the write boundary. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ExecutionScopeConcurrencyIntegrationTest {
    @Autowired CaseService cases;
    @Autowired ClueService clues;
    @Autowired CaseCauseRepository causes;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired BureauConfig config;
    @Autowired PlatformTransactionManager tm;
    String originalScope;

    enum Kind { EXECUTION, COURT_APPLY, INSTALLMENT_ADD, INSTALLMENT_PAY, EXPERT_START, EXPERT_END,
        DEFER, RELIEF, COURT_RESULT, WITHDRAW }
    record Actor(long id, String name, String token) {}
    record Probe(long caseId, String path, Object body) {}
    record Reward(long caseId, long clueId, Long rewardId) {}

    @BeforeEach void setup() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class));
        SecurityContextHolder.clearContext();
        originalScope = jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'", String.class);
        scope("SELF");
    }

    @AfterEach void restore() {
        SecurityContextHolder.clearContext();
        if (originalScope != null) scope(originalScope);
    }

    void scope(String value) {
        jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'", value);
        config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
    }

    Actor actor(String role) {
        var user = new SysUser();user.setUsername("execution_" + UUID.randomUUID().toString().replace("-", ""));
        user.setRealName("执行权限回归");user.setPassword("unused-jwt-fixture");user.setEnabled(true);
        user.getRoles().add(roles.findByCode(role).orElseThrow());user = users.saveAndFlush(user);
        return new Actor(user.getId(), user.getUsername(), jwt.issue(user.getUsername(), user.getTokenVersion()));
    }

    long newCase(Actor owner) {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        long id = cases.create(new CaseService.CaseCreateReq(null, cause, "NORMAL", "执行权限" + System.nanoTime(),
                "PROVIDER", null, null, null, null, "原始事实", BigDecimal.TEN,
                List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
                        new CaseService.OfficerReq("张协办", "YB002", "MEMBER")), null, null, null, null), owner.name()).getId();
        assertEquals(0, jdbc.queryForObject("select count(*) from case_officer where case_id=? and user_id=?", Integer.class, id, owner.id()));
        return id;
    }

    Probe probe(Actor owner, Kind kind) {
        long id = newCase(owner);String path = "/api/bureau/cases/" + id;
        if (kind == Kind.EXPERT_START) return new Probe(id, path + "/expert-reviews", Map.of("experts", "合法评审专家"));
        if (kind == Kind.EXPERT_END) {
            jdbc.update("update case_file set filed_at=current_date-5 where id=?", id);
            long review = jdbc.queryForObject("insert into expert_review(case_id,experts,started_at) values(?,'原专家',current_date-2) returning id", Long.class, id);
            return new Probe(id, path + "/expert-reviews/" + review + "/end", Map.of("opinion", "合法评审意见"));
        }
        jdbc.update("""
                update case_file set status='DELIVERED',filed_at=current_date-60,deadline_at=current_date+30,
                decided_at=current_date-50,delivered_at=current_date-30,
                relief_deadline=current_date-16,relief_basis='原期限依据',relief_pending=false where id=?""", id);
        jdbc.update("""
                insert into case_decision(case_id,decision_type,decision_no,fine_amount,content,decided_at)
                values(?,'PUNISH',?,100,'原处罚决定',current_date-50)""", id, "EXEC-" + UUID.randomUUID());
        return switch (kind) {
            case EXECUTION -> new Probe(id, path + "/executions", Map.of("kind", "FINE", "amount", 10, "method", "BANK"));
            case COURT_APPLY -> {
                long doc = jdbc.queryForObject("""
                        insert into case_document(case_id,doc_type,title,content,made_at,maker)
                        values(?,'URGE_LETTER','原催告书','原催告正文',current_date-11,'原制作人') returning id""", Long.class, id);
                jdbc.update("insert into case_delivery(case_id,document_id,method,delivered_at,receiver) values(?,?,'DIRECT',current_date-11,'原受送达人')", id, doc);
                yield new Probe(id, path + "/court-enforce", Map.of());
            }
            case INSTALLMENT_ADD, INSTALLMENT_PAY -> {
                jdbc.update("update case_file set defer_approved=true where id=?", id);
                if (kind == Kind.INSTALLMENT_ADD) yield new Probe(id, path + "/installments", Map.of("seq", 1, "dueAt", LocalDate.now().plusDays(5).toString(), "amount", 10));
                long installment = jdbc.queryForObject("insert into case_installment(case_id,seq,due_at,amount,kind) values(?,1,current_date+5,10,'FINE') returning id", Long.class, id);
                yield new Probe(id, "/api/bureau/installments/" + installment + "/pay", Map.of());
            }
            case DEFER -> new Probe(id, path + "/approve-defer", Map.of());
            case RELIEF -> new Probe(id, path + "/relief-deadline", Map.of("deadline", LocalDate.now().minusDays(1).toString(), "basis", "合法救济期限依据", "pending", false));
            case COURT_RESULT -> {
                jdbc.update("update case_file set court_enforce_applied=true,court_enforce_status='APPLIED',court_applied_at=current_date-1 where id=?", id);
                yield new Probe(id, path + "/court-result", Map.of("status", "ACCEPTED", "receiptNo", "合法法院回执", "receivedAt", LocalDate.now().toString()));
            }
            case WITHDRAW -> {
                jdbc.update("update case_decision set published=true,published_at=current_date-2 where case_id=?", id);
                yield new Probe(id, path + "/publish-withdraw", Map.of("reason", "合法撤回依据"));
            }
            default -> throw new IllegalArgumentException();
        };
    }

    int call(Actor actor, Probe probe) throws Exception {return call(actor, probe, probe.body());}
    int call(Actor actor, Probe probe, Object body) throws Exception {
        var response = mvc.perform(post(probe.path()).header("Authorization", "Bearer " + actor.token())
                .contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), "These authenticated requests must reach their business boundary");
        return mapper.readTree(response.getContentAsString()).path("code").asInt();
    }

    Map<String,Object> snapshot(long id) {
        Map<String,Object> result = new LinkedHashMap<>();var c = new LinkedHashMap<>(jdbc.queryForMap("select * from case_file where id=?", id));
        c.remove("owner_user");result.put("case", c);
        for (String table : List.of("case_decision", "case_execution", "case_installment", "case_document", "case_delivery", "expert_review", "case_period_exclusion", "biz_approval"))
            result.put(table, jdbc.queryForList("select * from " + table + " where case_id=? order by id", id));
        result.put("reward", jdbc.queryForList("select * from reward where clue_id=(select clue_id from case_file where id=?) order by id", id));
        return result;
    }

    void awaitBlocked(int blocker) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < end) {
            if (jdbc.queryForObject("select count(distinct pid) from pg_locks where not granted and ?=any(pg_blocking_pids(pid))", Integer.class, blocker) > 0) return;
            Thread.sleep(25);
        }
        fail("Authenticated request did not reach the held PostgreSQL business/actor lock");
    }

    int race(String lockSql, Object lockId, Actor actor, Probe probe, Runnable change) throws Exception {
        var pool = Executors.newSingleThreadExecutor();var pending = new AtomicReference<Future<Integer>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                jdbc.queryForList(lockSql, lockId);int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                pending.set(pool.submit(() -> call(actor, probe)));
                try {awaitBlocked(blocker);} catch (Exception failure) {throw new RuntimeException(failure);}
                change.run();
            });
            return pending.get().get(20, TimeUnit.SECONDS);
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));}
    }
    int caseRace(Actor actor, Probe probe, Runnable change) throws Exception {return race("select id from case_file where id=? for update", probe.caseId(), actor, probe, change);}

    void changeActor(Actor actor, String mode) {
        jdbc.queryForList("select id from sys_user where id=? for update", actor.id());
        switch (mode) {
            case "role" -> {jdbc.update("delete from sys_user_role where user_id=?", actor.id());jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='HANDLER'", actor.id());}
            case "disabled" -> jdbc.update("update sys_user set enabled=false where id=?", actor.id());
            case "token" -> jdbc.update("update sys_user set token_version=token_version+1 where id=?", actor.id());
            default -> throw new IllegalArgumentException();
        }
    }

    @ParameterizedTest @EnumSource(value=Kind.class,names={"EXECUTION","COURT_APPLY","INSTALLMENT_ADD","INSTALLMENT_PAY","EXPERT_START","EXPERT_END"})
    void ownerLossWhileWaitingRejectsEveryOrdinaryExecutionFamily(Kind kind) throws Exception {
        var owner = actor("HANDLER");var next = actor("HANDLER");var probe = probe(owner, kind);var before = snapshot(probe.caseId());
        assertEquals(2080, caseRace(owner, probe, () -> jdbc.update("update case_file set owner_user=? where id=?", next.name(), probe.caseId())), kind.toString());
        assertEquals(before, snapshot(probe.caseId()));
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void existingAuthorizedOperationsStillChangeTheirIntendedRecords(Kind kind) throws Exception {
        var actor = actor(Set.of(Kind.DEFER, Kind.RELIEF, Kind.COURT_RESULT, Kind.WITHDRAW).contains(kind) ? "LEADER" : "HANDLER");
        var probe = probe(actor, kind);var before = snapshot(probe.caseId());assertEquals(0, call(actor, probe));
        assertNotEquals(before, snapshot(probe.caseId()), kind.toString());
    }

    @ParameterizedTest @EnumSource(value=Kind.class,names={"DEFER","RELIEF","COURT_RESULT","WITHDRAW"})
    void leaderDowngradedToHandlerCannotCompletePrivilegedAction(Kind kind) throws Exception {
        var leader = actor("LEADER");var probe = probe(leader, kind);var before = snapshot(probe.caseId());
        assertEquals(403, caseRace(leader, probe, () -> changeActor(leader, "role")));assertEquals(before, snapshot(probe.caseId()));
    }

    @ParameterizedTest @ValueSource(strings={"role","disabled","token"})
    void changedActorCannotRecordMoneyAfterCaseWait(String mode) throws Exception {
        var owner = actor("HANDLER");var probe = probe(owner, Kind.EXECUTION);var before = snapshot(probe.caseId());
        assertEquals(403, caseRace(owner, probe, () -> {
            if (mode.equals("role")) {jdbc.update("delete from sys_user_role where user_id=?", owner.id());jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='LEGAL'", owner.id());}
            else changeActor(owner, mode);
        }));assertEquals(before, snapshot(probe.caseId()));
    }

    @Test void participantAndLeaderContinueRecordingMoneyAfterTransfer() throws Exception {
        var participant = actor("HANDLER");var next = actor("HANDLER");var first = probe(participant, Kind.EXECUTION);
        jdbc.update("update case_officer set user_id=? where id=(select min(id) from case_officer where case_id=?)", participant.id(), first.caseId());
        assertEquals(0, caseRace(participant, first, () -> jdbc.update("update case_file set owner_user=? where id=?", next.name(), first.caseId())));
        var leader = actor("LEADER");var second = probe(leader, Kind.EXECUTION);
        assertEquals(0, caseRace(leader, second, () -> jdbc.update("update case_file set owner_user=? where id=?", next.name(), second.caseId())));
        for (long id : List.of(first.caseId(), second.caseId())) assertEquals(0, BigDecimal.TEN.compareTo(jdbc.queryForObject("select sum(amount) from case_execution where case_id=?", BigDecimal.class, id)));
    }

    @Test void legalRoleStillRegistersReliefForAnotherOwnersCase() throws Exception {
        var owner = actor("HANDLER");var legal = actor("LEGAL");var probe = probe(owner, Kind.RELIEF);
        assertEquals(0, call(legal, probe));assertEquals(legal.name(), jdbc.queryForObject("select relief_registered_by from case_file where id=?", String.class, probe.caseId()));
    }

    @Test void absentOrNullCourtStatusIsControlledAndAtomic() throws Exception {
        var leader = actor("LEADER");var probe = probe(leader, Kind.COURT_RESULT);var before = snapshot(probe.caseId());
        var body = new LinkedHashMap<String,Object>((Map<String,Object>) probe.body());body.remove("status");
        assertEquals(2142, call(leader, probe, body));assertEquals(before, snapshot(probe.caseId()));
        body.put("status", null);assertEquals(2142, call(leader, probe, body));assertEquals(before, snapshot(probe.caseId()));
        assertEquals(0, call(leader, probe));
    }

    Reward reward(Actor owner, boolean insert, boolean approved) {
        long caseId = newCase(owner);
        long clueId = clues.create(new ClueService.ClueCreateReq("COMPLAINT", "奖励原来源", "来源机构", "PROVIDER", LocalDate.now(), owner.name()), owner.name()).getId();
        jdbc.update("update case_file set clue_id=? where id=?", clueId, caseId);jdbc.update("update case_clue set status='FILED' where id=?", clueId);
        Long rewardId = insert ? jdbc.queryForObject("insert into reward(clue_id,reporter_name,verified) values(?,'原举报人',true) returning id", Long.class, clueId) : null;
        if (approved) jdbc.update("update reward set amount=10,approved_by=?,approved_at=current_date where id=?", owner.name(), rewardId);
        return new Reward(caseId, clueId, rewardId);
    }
    Probe createReward(Reward reward) {return new Probe(reward.caseId(), "/api/bureau/rewards", Map.of("clueId", reward.clueId(), "reporterName", "合法举报人"));}
    Probe rewardAction(Reward reward, String action) {return new Probe(reward.caseId(), "/api/bureau/rewards/" + reward.rewardId() + "/" + action, action.equals("approve") ? Map.of("amount", 10) : Map.of());}

    @Test void rewardCreationRechecksAdoptedCaseAfterOwnershipWait() throws Exception {
        var owner = actor("HANDLER");var next = actor("HANDLER");var reward = reward(owner, false, false);var probe = createReward(reward);var before = snapshot(reward.caseId());
        assertEquals(2080, caseRace(owner, probe, () -> jdbc.update("update case_file set owner_user=? where id=?", next.name(), reward.caseId())));
        assertEquals(before, snapshot(reward.caseId()));
    }

    @Test void rewardCreationRechecksTokenAfterAdoptedCaseWait() throws Exception {
        var owner = actor("HANDLER");var reward = reward(owner, false, false);var before = snapshot(reward.caseId());
        assertEquals(403, caseRace(owner, createReward(reward), () -> changeActor(owner, "token")));assertEquals(before, snapshot(reward.caseId()));
    }

    @Test void rewardCreationKeepsAllScopeAndArchivedCaseContract() throws Exception {
        var owner = actor("HANDLER");var handler = actor("HANDLER");var reward = reward(owner, false, false);scope("ALL");
        jdbc.update("update case_file set status='CLOSED',archive_no='原封卷号' where id=?", reward.caseId());
        assertEquals(0, call(handler, createReward(reward)));assertEquals(1, jdbc.queryForObject("select count(*) from reward where clue_id=?", Integer.class, reward.clueId()));
    }

    @Test void duplicateRewardRequestsOnlyCreateOneRecordAndKeepItUnchanged() throws Exception {
        var owner = actor("HANDLER");var reward = reward(owner, false, false);var probe = createReward(reward);
        var pool = Executors.newFixedThreadPool(2);var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> {start.await();return call(owner, probe);});var second = pool.submit(() -> {start.await();return call(owner, probe);});start.countDown();
            var codes = new ArrayList<>(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));Collections.sort(codes);assertEquals(List.of(0,2064), codes);
            var before = snapshot(reward.caseId());assertEquals(2064, call(owner, probe));assertEquals(before, snapshot(reward.caseId()));
            assertEquals(1, jdbc.queryForObject("select count(*) from reward where clue_id=?", Integer.class, reward.clueId()));
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));}
    }

    @ParameterizedTest @ValueSource(strings={"approve","pay"})
    void rewardRoleDowngradeDuringRewardRowWaitCannotApproveOrPay(String action) throws Exception {
        var leader = actor("LEADER");var reward = reward(leader, true, action.equals("pay"));var before = snapshot(reward.caseId());
        assertEquals(403, race("select id from reward where id=? for update", reward.rewardId(), leader, rewardAction(reward, action), () -> changeActor(leader, "role")));
        assertEquals(before, snapshot(reward.caseId()));
    }

    @ParameterizedTest @ValueSource(strings={"approve","pay"})
    void currentRewardLeaderCanCompleteEachOperationOnlyOnce(String action) throws Exception {
        var leader = actor("LEADER");var reward = reward(leader, true, action.equals("pay"));var probe = rewardAction(reward, action);var before = snapshot(reward.caseId());
        assertEquals(0, call(leader, probe));assertNotEquals(before, snapshot(reward.caseId()));
        var completed = snapshot(reward.caseId());assertEquals(2064, call(leader, probe));assertEquals(completed, snapshot(reward.caseId()));
    }

    Probe inspection() {return new Probe(0, "/api/bureau/inspections", Map.of("objectName", "合法检查对象", "objectType", "PROVIDER", "officers", "甲、乙"));}
    Map<String,Object> inspections() {return Map.of("rows", jdbc.queryForList("select * from inspection order by id"), "sequence", jdbc.queryForList("select * from biz_seq where kind='INSP' order by year"));}

    @ParameterizedTest @ValueSource(strings={"role","disabled","token"})
    void inspectionCreationRejectsActorChangesWhileWaitingWithoutTakingANumber(String mode) throws Exception {
        var leader = actor("LEADER");var before = inspections();
        assertEquals(403, race("select id from sys_user where id=? for update", leader.id(), leader, inspection(), () -> {
            if (mode.equals("role")) {jdbc.update("delete from sys_user_role where user_id=?", leader.id());jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='LEGAL'", leader.id());}
            else changeActor(leader, mode);
        }));assertEquals(before, inspections());
    }

    @Test void currentHandlerCanStillCreateInspection() throws Exception {
        var handler = actor("HANDLER");int before = jdbc.queryForObject("select count(*) from inspection", Integer.class);
        assertEquals(0, call(handler, inspection()));assertEquals(before + 1, jdbc.queryForObject("select count(*) from inspection", Integer.class));
        assertEquals(handler.name(), jdbc.queryForObject("select created_by from inspection order by id desc limit 1", String.class));
    }

    @Test void missingExecutionCaseIsAControlledNotFoundWithoutLedgerMutation() throws Exception {
        var admin = actor("ADMIN");int before = jdbc.queryForObject("select count(*) from case_execution", Integer.class);
        var probe = new Probe(Long.MAX_VALUE, "/api/bureau/cases/" + Long.MAX_VALUE + "/executions", Map.of("kind", "FINE", "amount", 10, "method", "BANK"));
        assertEquals(2043, call(admin, probe));assertEquals(before, jdbc.queryForObject("select count(*) from case_execution", Integer.class));
    }
}
