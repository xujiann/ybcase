package cn.ybcase.bureau;

import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.CaseGuards;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** The request must pass its original authorization before real PostgreSQL case-lock waits. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MeetingSignatureConcurrencyIntegrationTest {
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
    String originalScope;
    record Actor(long id, String name, String token) {}
    record Meeting(long caseId, long id) {}

    @BeforeEach void setup() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class),
                "Concurrency fixtures must use a disposable database");
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
        user.setUsername("meeting_" + UUID.randomUUID().toString().replace("-", ""));
        user.setRealName("集体讨论回归");user.setPassword("unused-jwt-fixture");user.setEnabled(true);
        user.getRoles().add(roles.findByCode(role).orElseThrow());
        user = users.saveAndFlush(user);
        return new Actor(user.getId(), user.getUsername(), jwt.issue(user.getUsername(), user.getTokenVersion()));
    }

    Meeting meeting(Actor owner) {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        long caseId = cases.create(new CaseService.CaseCreateReq(null, cause, "NORMAL", "讨论签字" + System.nanoTime(),
                "PROVIDER", null, null, null, null, "原始事实", BigDecimal.TEN,
                List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
                        new CaseService.OfficerReq("张协办", "YB002", "MEMBER")), null, null, null, null), owner.name()).getId();
        long id = jdbc.queryForObject("""
                insert into case_meeting(case_id,held_at,attendees,record,conclusion)
                values(?,current_date,'原参加人员','原讨论记录','原结论') returning id""", Long.class, caseId);
        // Authorization in these tests must rely on ownership, not an accidental certificate-account mapping.
        assertEquals(0, jdbc.queryForObject("select count(*) from case_officer where case_id=? and user_id=?",
                Integer.class, caseId, owner.id()));
        return new Meeting(caseId, id);
    }

    String path(Meeting meeting) {return "/api/bureau/cases/" + meeting.caseId() + "/meetings/" + meeting.id() + "/sign";}
    Map<String,Object> row(Meeting meeting) {return jdbc.queryForMap("select * from case_meeting where id=?", meeting.id());}

    int sign(Actor actor, Meeting meeting, Object body) throws Exception {
        var response = mvc.perform(post(path(meeting)).header("Authorization", "Bearer " + actor.token())
                .contentType("application/json").content(mapper.writeValueAsString(body))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), "The request was authenticated before entering the controller");
        return mapper.readTree(response.getContentAsString()).path("code").asInt();
    }

    void awaitBlocked(int blocker, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    with recursive blocked(pid) as (
                        select pid from pg_locks where not granted and ?=any(pg_blocking_pids(pid))
                        union
                        select locks.pid from pg_locks locks join blocked parent
                          on parent.pid=any(pg_blocking_pids(locks.pid)) where not locks.granted
                    ) select count(distinct pid) from blocked""", Integer.class, blocker);
            if (waiting != null && waiting >= count) return;
            Thread.sleep(25);
        }
        fail("Authenticated meeting writes did not reach the held PostgreSQL case lock");
    }

    void stop(ExecutorService pool) throws Exception {
        pool.shutdownNow();assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));
    }

    int race(Actor actor, Meeting meeting, Runnable change) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        var pending = new AtomicReference<Future<Integer>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                CaseGuards.lock(jdbc, meeting.caseId());
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                pending.set(pool.submit(() -> sign(actor, meeting, Map.of("signNames", "不应签入的名单"))));
                try {awaitBlocked(blocker, 1);} catch (Exception failure) {throw new RuntimeException(failure);}
                // Only change permissions after the authenticated request demonstrably waits on the case.
                change.run();
            });
            return pending.get().get(20, TimeUnit.SECONDS);
        } finally {stop(pool);}
    }

    @Test void transferWhileWaitingRejectsPreviousOwnerWithoutChangingMeeting() throws Exception {
        var owner = actor("HANDLER");var next = actor("HANDLER");var meeting = meeting(owner);var before = row(meeting);
        assertEquals(2080, race(owner, meeting, () -> jdbc.update("update case_file set owner_user=? where id=?", next.name(), meeting.caseId())));
        assertEquals(before, row(meeting));
    }

    @Test void roleDowngradeWhileWaitingRejectsOldHandlerAuthority() throws Exception {
        var owner = actor("HANDLER");var meeting = meeting(owner);var before = row(meeting);
        assertEquals(403, race(owner, meeting, () -> {
            jdbc.queryForList("select id from sys_user where id=? for update", owner.id());
            jdbc.update("delete from sys_user_role where user_id=?", owner.id());
            jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='LEGAL'", owner.id());
        }));
        assertEquals(before, row(meeting));
    }

    @Test void disableWhileWaitingRejectsAuthenticatedButNowDisabledAccount() throws Exception {
        var owner = actor("HANDLER");var meeting = meeting(owner);var before = row(meeting);
        assertEquals(403, race(owner, meeting, () -> jdbc.update("update sys_user set enabled=false where id=?", owner.id())));
        assertEquals(before, row(meeting));
    }

    @Test void tokenVersionChangeWhileWaitingRejectsPreviousSession() throws Exception {
        var owner = actor("HANDLER");var meeting = meeting(owner);var before = row(meeting);
        assertEquals(403, race(owner, meeting, () -> jdbc.update("update sys_user set token_version=token_version+1 where id=?", owner.id())));
        assertEquals(before, row(meeting));
    }

    @Test void exactly255UnicodeCodePointsAreStoredWithoutTrimmingOriginalText() throws Exception {
        var owner = actor("HANDLER");var meeting = meeting(owner);
        String names = "  " + "😀".repeat(250) + "\n  ";
        assertEquals(255, names.codePointCount(0, names.length()));
        var before = row(meeting);
        assertEquals(0, sign(owner, meeting, Map.of("signNames", names)));
        var after = row(meeting);
        assertEquals(names, after.get("sign_names"));assertEquals(true, after.get("sign_confirmed"));
        for (String key : before.keySet()) if (!Set.of("sign_names", "sign_confirmed").contains(key))
            assertEquals(before.get(key), after.get(key), key);
    }

    @Test void invalidNamesNeverReplaceAnExistingConfirmationOrAlterOriginalRecord() throws Exception {
        var owner = actor("HANDLER");var meeting = meeting(owner);
        jdbc.update("update case_meeting set sign_confirmed=true,sign_names='  原确认名单  ' where id=?", meeting.id());
        var before = row(meeting);var invalid = new ArrayList<Object>();invalid.add(Map.of());
        for (Object value : Arrays.asList(null, true, 123, List.of("甲"), Map.of("name", "甲"), "", " \n\t ", "名\0单", "😀".repeat(256))) {
            var body = new LinkedHashMap<String,Object>();body.put("signNames", value);invalid.add(body);
        }
        for (Object body : invalid) {
            assertEquals(2069, sign(owner, meeting, body));assertEquals(before, row(meeting));
        }
    }

    @Test void crossCaseAndArchivedMeetingSignaturesCannotAlterEitherOriginal() throws Exception {
        var owner = actor("HANDLER");var first = meeting(owner);var second = meeting(owner);
        var beforeFirst = row(first);var beforeSecond = row(second);
        assertEquals(2069, sign(owner, new Meeting(first.caseId(), second.id()), Map.of("signNames", "串案名单")));
        assertEquals(beforeFirst, row(first));assertEquals(beforeSecond, row(second));
        jdbc.update("update case_file set archive_no='MEETING-SEALED' where id=?", first.caseId());
        assertEquals(2031, sign(owner, first, Map.of("signNames", "封卷后名单")));
        assertEquals(beforeFirst, row(first));assertEquals(beforeSecond, row(second));
    }

    @Test void concurrentAndRepeatedConfirmationsCannotOverwriteTheFirstConfirmedNames() throws Exception {
        var owner = actor("HANDLER");var meeting = meeting(owner);var before = row(meeting);
        var pool = Executors.newFixedThreadPool(2);var pending = new ArrayList<Future<Integer>>();
        var names = List.of("甲组名单", "乙组名单");
        try {
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                CaseGuards.lock(jdbc, meeting.caseId());int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                for (String proposed : names)
                    pending.add(pool.submit(() -> sign(owner, meeting, Map.of("signNames", proposed))));
                try {awaitBlocked(blocker, 2);} catch (Exception failure) {throw new RuntimeException(failure);}
            });
            var codes = new ArrayList<Integer>();
            for (var result : pending) codes.add(result.get(20, TimeUnit.SECONDS));
            assertEquals(Set.of(0, 2069), new HashSet<>(codes));
            var after = row(meeting);assertEquals(true, after.get("sign_confirmed"));
            String winner = names.get(codes.indexOf(0));
            assertEquals(winner, after.get("sign_names"));
            for (String key : before.keySet()) if (!Set.of("sign_names", "sign_confirmed").contains(key))
                assertEquals(before.get(key), after.get(key), key);
            assertEquals(1, jdbc.queryForObject("select count(*) from case_meeting where case_id=?", Integer.class, meeting.caseId()));
            for (String proposed : List.of(winner, "另一份不得覆盖的名单")) {
                assertEquals(2069, sign(owner, meeting, Map.of("signNames", proposed)));
                assertEquals(after, row(meeting), "Same or different repeated confirmations must retain the full original row");
            }
        } finally {stop(pool);}
    }
}
