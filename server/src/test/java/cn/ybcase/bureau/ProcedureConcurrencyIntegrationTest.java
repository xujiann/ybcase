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

/** Authenticated requests must recheck actors only after every business-source lock is held. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProcedureConcurrencyIntegrationTest {
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

    enum Kind { SCHEDULE, HOLD, OPINION, CANCEL, SUMMARY, CONSENT, ASSIST, REPLY,
        TRANSFER, TRANSFER_CONFIRM, CLUE_TRANSFER, CLUE_TRANSFER_CONFIRM }
    record Actor(long id, String name, String token) {}
    record Probe(long caseId, String path, Object body) {}

    @BeforeEach void setup() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class));
        SecurityContextHolder.clearContext();
        originalScope = jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'", String.class);
        jdbc.update("update sys_config set cfg_value='SELF' where cfg_key='case_view_scope'");
        config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
    }

    @AfterEach void restore() {
        SecurityContextHolder.clearContext();
        if (originalScope != null) {
            jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'", originalScope);
            config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
        }
    }

    Actor actor(String role) {
        var user = new SysUser();
        user.setUsername("procedure_" + UUID.randomUUID().toString().replace("-", ""));
        user.setRealName("程序并发回归");user.setPassword("unused-jwt-fixture");user.setEnabled(true);
        user.getRoles().add(roles.findByCode(role).orElseThrow());
        user = users.saveAndFlush(user);
        return new Actor(user.getId(), user.getUsername(), jwt.issue(user.getUsername(), user.getTokenVersion()));
    }

    Probe probe(Actor owner, Kind kind) {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        long id = cases.create(new CaseService.CaseCreateReq(null, cause, kind == Kind.SUMMARY ? "SUMMARY" : "NORMAL",
                "程序并发" + System.nanoTime(), "PROVIDER", null, null, null, null, "原始事实", BigDecimal.TEN,
                List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
                        new CaseService.OfficerReq("张协办", "YB002", "MEMBER")), null, null, null, null), owner.name()).getId();
        assertEquals(0, jdbc.queryForObject("select count(*) from case_officer where case_id=? and user_id=?",
                Integer.class, id, owner.id()));
        String path = "/api/bureau/cases/" + id;
        return switch (kind) {
            case SCHEDULE -> {
                jdbc.update("insert into case_notice(case_id,notified_at,content,hearing_requested) values(?,current_date,'原告知',true)", id);
                yield new Probe(id, path + "/hearings", Map.of("scheduledAt", LocalDate.now().plusDays(8).toString(),
                        "noticeSentAt", LocalDate.now().toString(), "host", "独立主持人"));
            }
            case HOLD, OPINION, CANCEL -> {
                long hearing = jdbc.queryForObject("""
                        insert into case_hearing(case_id,notice_sent_at,scheduled_at,host,status)
                        values(?,current_date-8,current_date,'原主持人',?) returning id""", Long.class, id,
                        kind == Kind.OPINION ? "HELD" : "SCHEDULED");
                String action = kind == Kind.HOLD ? "hold" : kind == Kind.OPINION ? "opinion" : "cancel";
                String field = kind == Kind.HOLD ? "record" : kind == Kind.OPINION ? "opinion" : "reason";
                yield new Probe(id, path + "/hearings/" + hearing + "/" + action, Map.of(field, "有效程序材料"));
            }
            case SUMMARY -> {
                jdbc.update("update case_file set decided_at=current_date where id=?", id);
                yield new Probe(id, path + "/summary-record", Map.of());
            }
            case CONSENT -> new Probe(id, path + "/e-delivery-consent", Map.of("receiver", "受送达人", "channel", "a@example.test"));
            case ASSIST -> new Probe(id, path + "/assists", Map.of("direction", "OUT", "org", "协查机关", "content", "合法协查事项"));
            case REPLY -> {
                long assist = jdbc.queryForObject("""
                        insert into case_assist(case_id,direction,org,content,sent_at,due_at)
                        values(?,'OUT','原机关','原事项',current_date,current_date+15) returning id""", Long.class, id);
                yield new Probe(id, path + "/assists/" + assist + "/reply", Map.of("result", "合法协查复函", "refused", false));
            }
            case TRANSFER, TRANSFER_CONFIRM, CLUE_TRANSFER, CLUE_TRANSFER_CONFIRM -> {
                Long clue = null;
                if (kind == Kind.CLUE_TRANSFER || kind == Kind.CLUE_TRANSFER_CONFIRM) {
                    clue = clues.create(new ClueService.ClueCreateReq("COMPLAINT", "程序来源", "来源医院", "PROVIDER", LocalDate.now(), owner.name()), owner.name()).getId();
                    jdbc.update("update case_file set clue_id=? where id=?", clue, id);
                    jdbc.update("update case_clue set status='FILED' where id=?", clue);
                }
                if (kind == Kind.TRANSFER_CONFIRM || kind == Kind.CLUE_TRANSFER_CONFIRM) {
                    long transfer = jdbc.queryForObject("""
                            insert into case_transfer(case_id,clue_id,direction,target_org,kind,reason,sent_at)
                            values(?,?,'IN','原移送机关','ADMIN','原移送理由',current_date) returning id""",
                            Long.class, clue == null ? id : null, clue);
                    yield new Probe(id, "/api/bureau/transfers/" + transfer + "/confirm", Map.of());
                }
                var body = new LinkedHashMap<String,Object>();
                body.put(clue == null ? "caseId" : "clueId", clue == null ? id : clue);
                body.put("direction", "IN");body.put("targetOrg", "移送机关");body.put("kind", "ADMIN");body.put("reason", "合法移送理由");
                yield new Probe(id, "/api/bureau/transfers", body);
            }
        };
    }

    int call(Actor actor, Probe probe, Object body) throws Exception {
        var response = mvc.perform(post(probe.path()).header("Authorization", "Bearer " + actor.token())
                .contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), "Request must authenticate before its business lock");
        return mapper.readTree(response.getContentAsString()).path("code").asInt();
    }

    int call(Actor actor, Probe probe) throws Exception {return call(actor, probe, probe.body());}

    Map<String,Object> snapshot(long id) {
        Map<String,Object> snapshot = new LinkedHashMap<>();
        var row = new LinkedHashMap<>(jdbc.queryForMap("select * from case_file where id=?", id));
        row.remove("owner_user");snapshot.put("case", row);
        for (String table : List.of("case_notice", "case_hearing", "case_assist", "case_document", "case_transfer"))
            snapshot.put(table, jdbc.queryForList("select * from " + table + " where case_id=? order by id", id));
        snapshot.put("clueTransfers", jdbc.queryForList("select * from case_transfer where clue_id=(select clue_id from case_file where id=?) order by id", id));
        return snapshot;
    }

    void awaitBlocked(int blocker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            int waiting = jdbc.queryForObject("select count(distinct pid) from pg_locks where not granted and ?=any(pg_blocking_pids(pid))",
                    Integer.class, blocker);
            if (waiting > 0) return;
            Thread.sleep(25);
        }
        fail("Authenticated procedure write never reached the held PostgreSQL case lock");
    }

    int race(Actor actor, Probe probe, Runnable change) throws Exception {
        var pool = Executors.newSingleThreadExecutor();var pending = new AtomicReference<Future<Integer>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                CaseGuards.lock(jdbc, probe.caseId());
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                pending.set(pool.submit(() -> call(actor, probe)));
                try {awaitBlocked(blocker);} catch (Exception failure) {throw new RuntimeException(failure);}
                change.run();
            });
            return pending.get().get(20, TimeUnit.SECONDS);
        } finally {pool.shutdownNow();assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));}
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void ownerTransferWhileWaitingRejectsEveryProcedureFamilyWithoutChangingRecords(Kind kind) throws Exception {
        var owner = actor("HANDLER");var next = actor("HANDLER");var probe = probe(owner, kind);var before = snapshot(probe.caseId());
        assertEquals(2080, race(owner, probe, () -> jdbc.update("update case_file set owner_user=? where id=?", next.name(), probe.caseId())), kind.toString());
        assertEquals(before, snapshot(probe.caseId()), kind.toString());
    }

    @ParameterizedTest @EnumSource(Kind.class)
    void currentOwnerStillCompletesEveryExistingProcedureFamily(Kind kind) throws Exception {
        var owner = actor("HANDLER");var probe = probe(owner, kind);var before = snapshot(probe.caseId());
        assertEquals(0, call(owner, probe), kind.toString());
        assertNotEquals(before, snapshot(probe.caseId()), "Success must change the concrete procedure record");
    }

    @Test void roleDowngradeWhileWaitingRejectsPreviouslyAuthorizedHearing() throws Exception {
        var owner = actor("HANDLER");var probe = probe(owner, Kind.HOLD);var before = snapshot(probe.caseId());
        assertEquals(403, race(owner, probe, () -> {
            jdbc.queryForList("select id from sys_user where id=? for update", owner.id());
            jdbc.update("delete from sys_user_role where user_id=?", owner.id());
            jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='LEGAL'", owner.id());
        }));assertEquals(before, snapshot(probe.caseId()));
    }

    @Test void tokenVersionChangeWhileWaitingRejectsElectronicConsentFromOldSession() throws Exception {
        var owner = actor("HANDLER");var probe = probe(owner, Kind.CONSENT);var before = snapshot(probe.caseId());
        assertEquals(403, race(owner, probe, () -> jdbc.update("update sys_user set token_version=token_version+1 where id=?", owner.id())));
        assertEquals(before, snapshot(probe.caseId()));
    }

    @Test void disableWhileWaitingRejectsCaseTransferWithoutCreatingLedgerRow() throws Exception {
        var owner = actor("HANDLER");var probe = probe(owner, Kind.TRANSFER);var before = snapshot(probe.caseId());
        assertEquals(403, race(owner, probe, () -> jdbc.update("update sys_user set enabled=false where id=?", owner.id())));
        assertEquals(before, snapshot(probe.caseId()));
    }

    @Test void participantAndLeaderRemainAuthorizedAfterOwnershipChanges() throws Exception {
        var participant = actor("HANDLER");var next = actor("HANDLER");var assist = probe(participant, Kind.ASSIST);
        jdbc.update("update case_officer set user_id=? where id=(select min(id) from case_officer where case_id=?)", participant.id(), assist.caseId());
        assertEquals(0, race(participant, assist, () -> jdbc.update("update case_file set owner_user=? where id=?", next.name(), assist.caseId())));
        assertEquals(1, jdbc.queryForObject("select count(*) from case_assist where case_id=?", Integer.class, assist.caseId()));
        var leader = actor("LEADER");var consent = probe(leader, Kind.CONSENT);
        assertEquals(0, race(leader, consent, () -> jdbc.update("update case_file set owner_user=? where id=?", next.name(), consent.caseId())));
        assertEquals(true, jdbc.queryForObject("select e_delivery_consent from case_file where id=?", Boolean.class, consent.caseId()));
    }

    @Test void missingHearingDateOrHostIsAControlledValidationFailureAndLeavesNoPartialSchedule() throws Exception {
        var owner = actor("HANDLER");var probe = probe(owner, Kind.SCHEDULE);var before = snapshot(probe.caseId());
        for (Object body : List.of(Map.of(), Map.of("host", "主持人"),
                Map.of("scheduledAt", LocalDate.now().plusDays(8).toString()),
                Map.of("scheduledAt", LocalDate.now().plusDays(8).toString(), "host", " \n\t"))) {
            assertEquals(2057, call(owner, probe, body));assertEquals(before, snapshot(probe.caseId()));
        }
        assertEquals(0, call(owner, probe));
    }

    @Test void blankNonRefusalCannotPermanentlyCloseAssistButReasonedRefusalStillCan() throws Exception {
        var owner = actor("HANDLER");var probe = probe(owner, Kind.REPLY);var before = snapshot(probe.caseId());
        for (Object body : List.of(Map.of(), Map.of("result", ""), Map.of("result", " \n\t", "refused", false))) {
            assertEquals(2060, call(owner, probe, body));assertEquals(before, snapshot(probe.caseId()));
        }
        assertEquals(0, call(owner, probe, Map.of("refused", true, "refuseReason", "请转有权机关办理")));
        var row = jdbc.queryForMap("select * from case_assist where case_id=?", probe.caseId());
        assertNotNull(row.get("replied_at"));assertEquals(true, row.get("refused"));
        assertEquals("请转有权机关办理", row.get("refuse_reason"));
        assertEquals(2060, call(owner, probe));
    }

    @Test void missingNullOrUnknownTransferAndAssistEnumsRejectWithoutCreatingLedgerRows() throws Exception {
        var owner = actor("HANDLER");var transfer = probe(owner, Kind.TRANSFER);var assist = probe(owner, Kind.ASSIST);
        var beforeTransfer = snapshot(transfer.caseId());var beforeAssist = snapshot(assist.caseId());
        for (String field : List.of("direction", "kind")) {
            var body = new LinkedHashMap<Object,Object>((Map<?,?>) transfer.body());body.remove(field);
            assertEquals(2059, call(owner, transfer, body));
            for (Object value : Arrays.asList(null, "", "UNKNOWN")) {
                body.put(field, value);assertEquals(2059, call(owner, transfer, body));
            }
            assertEquals(beforeTransfer, snapshot(transfer.caseId()));
        }
        var body = new LinkedHashMap<Object,Object>((Map<?,?>) assist.body());body.remove("direction");
        assertEquals(2060, call(owner, assist, body));
        for (Object value : Arrays.asList(null, "", "UNKNOWN")) {
            body.put("direction", value);assertEquals(2060, call(owner, assist, body));
        }
        assertEquals(beforeAssist, snapshot(assist.caseId()));
        assertEquals(0, call(owner, transfer));assertEquals(0, call(owner, assist));
    }
}
