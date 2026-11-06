package cn.ybcase.bureau;

import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.ClueService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real PostgreSQL waits verify authority at the clue mutation, beyond the captured HTTP principal. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ClueWorkflowConcurrencyIntegrationTest {
    @Autowired ClueService clues;
    @Autowired BureauConfig config;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager tm;
    final Map<String, String> original = new LinkedHashMap<>();

    enum Action { EXTEND, REJECT, EXCLUDE }
    enum Revocation { ROLE, DISABLED, TOKEN }
    enum Creation { SINGLE, IMPORT }
    record Actor(long id, String name, String token) {}
    record Probe(String path, Object body) {}
    record Mutation(Action action, Revocation revocation) {}
    record NewMutation(Creation creation, Revocation revocation) {}

    static Stream<Mutation> mutations() {
        return Arrays.stream(Action.values()).flatMap(action -> Arrays.stream(Revocation.values()).map(mode -> new Mutation(action, mode)));
    }
    static Stream<NewMutation> creations() {
        return Arrays.stream(Creation.values()).flatMap(creation -> Arrays.stream(Revocation.values()).map(mode -> new NewMutation(creation, mode)));
    }

    @BeforeEach void setup() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class));
        SecurityContextHolder.clearContext();
        cfg("clue_extension_approver_role", "LEADER");
        cfg("case_view_scope", "SELF");
    }
    @AfterEach void restore() {
        SecurityContextHolder.clearContext();
        original.forEach((key, value) -> {
            jdbc.update("update sys_config set cfg_value=? where cfg_key=?", value, key);
            config.onConfigChanged(new ConfigChangedEvent(key));
        });
    }
    void cfg(String key, String value) {
        original.computeIfAbsent(key, name -> jdbc.queryForObject("select cfg_value from sys_config where cfg_key=?", String.class, name));
        jdbc.update("update sys_config set cfg_value=? where cfg_key=?", value, key);
        config.onConfigChanged(new ConfigChangedEvent(key));
    }
    Actor actor(String role) {
        var user = new SysUser();
        user.setUsername("clue_race_" + UUID.randomUUID().toString().replace("-", ""));
        user.setRealName("线索权限回归");user.setPassword("unused-jwt-fixture");user.setEnabled(true);
        user.getRoles().add(roles.findByCode(role).orElseThrow());user = users.saveAndFlush(user);
        return new Actor(user.getId(), user.getUsername(), jwt.issue(user.getUsername(), user.getTokenVersion()));
    }
    long clue(Actor owner) {
        return clues.create(new ClueService.ClueCreateReq("COMPLAINT", "线索并发回归", "回归机构", "PROVIDER",
                LocalDate.now(), owner.name()), owner.name()).getId();
    }
    Probe probe(Action action, long id) {
        String path = "/api/bureau/clues/" + id;
        return switch (action) {
            case EXTEND -> new Probe(path + "/extend", Map.of("reason", "特殊核查情况"));
            case REJECT -> new Probe(path + "/reject", Map.of("verifyResult", "已核实不予立案"));
            case EXCLUDE -> new Probe(path + "/exclusions", Map.of("days", 2, "reason", "检验期间"));
        };
    }
    Probe creation(Creation kind, Actor owner, String marker) {
        Map<String, String> row = Map.of("source", "MONITOR", "content", marker, "suspectName", "回归机构",
                "suspectType", "PROVIDER", "handler", owner.name());
        return kind == Creation.SINGLE ? new Probe("/api/bureau/clues", row)
                : new Probe("/api/bureau/clues/import", List.of(row, new LinkedHashMap<>(row)));
    }
    int call(Actor actor, Probe probe) throws Exception {
        var response = mvc.perform(post(probe.path()).header("Authorization", "Bearer " + actor.token())
                .contentType("application/json").content(mapper.writeValueAsBytes(probe.body()))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), "Request must authenticate before reaching the held database lock");
        return mapper.readTree(response.getContentAsString()).path("code").asInt();
    }
    void awaitBlocked(int blocker, int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            int waiting = jdbc.queryForObject("""
                    with recursive blocked(pid) as (
                      select pid from pg_locks where not granted and ?=any(pg_blocking_pids(pid))
                      union select locks.pid from pg_locks locks join blocked parent
                        on parent.pid=any(pg_blocking_pids(locks.pid)) where not locks.granted
                    ) select count(distinct pid) from blocked""", Integer.class, blocker);
            if (waiting >= expected) return;
            Thread.sleep(25);
        }
        fail("Authenticated clue request did not reach its held source/actor/numbering lock");
    }
    int race(String lockSql, Object lockId, Actor actor, Probe probe, Runnable change) throws Exception {
        var pool = Executors.newSingleThreadExecutor();var pending = new AtomicReference<Future<Integer>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                jdbc.queryForList(lockSql, lockId);
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                pending.set(pool.submit(() -> call(actor, probe)));
                try { awaitBlocked(blocker, 1); } catch (Exception failure) { throw new RuntimeException(failure); }
                change.run();
            });
            return pending.get().get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow();assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS)); }
    }
    int clueRace(long clueId, Actor actor, Probe probe, Runnable change) throws Exception {
        return race("select id from case_clue where id=? for update", clueId, actor, probe, change);
    }
    void revoke(Actor actor, Revocation mode, boolean leader) {
        jdbc.queryForList("select id from sys_user where id=? for update", actor.id());
        switch (mode) {
            case ROLE -> {
                jdbc.update("delete from sys_user_role where user_id=?", actor.id());
                jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?",
                        actor.id(), leader ? "HANDLER" : "LEGAL");
            }
            case DISABLED -> jdbc.update("update sys_user set enabled=false where id=?", actor.id());
            case TOKEN -> jdbc.update("update sys_user set token_version=token_version+1 where id=?", actor.id());
        }
    }
    Map<String, Object> snapshot(long id) {
        return Map.of("clue", jdbc.queryForMap("select * from case_clue where id=?", id),
                "approvals", jdbc.queryForList("select * from biz_approval where clue_id=? order by id", id));
    }

    @ParameterizedTest @MethodSource("mutations")
    void revokedActorCannotMutateAfterWaitingForClue(Mutation mutation) throws Exception {
        boolean leader = mutation.action() == Action.EXTEND;
        var actor = actor(leader ? "LEADER" : "HANDLER");long id = clue(actor);var before = snapshot(id);
        assertEquals(403, clueRace(id, actor, probe(mutation.action(), id), () -> revoke(actor, mutation.revocation(), leader)));
        assertEquals(before, snapshot(id));
    }

    @ParameterizedTest @EnumSource(value = Action.class, names = {"REJECT", "EXCLUDE"})
    void reassignedClueCannotBeChangedByFormerHandler(Action action) throws Exception {
        var owner = actor("HANDLER");var replacement = actor("HANDLER");long id = clue(owner);
        assertEquals(2080, clueRace(id, owner, probe(action, id), () ->
                jdbc.update("update case_clue set created_by=?,handler=? where id=?", replacement.name(), replacement.name(), id)));
        assertEquals("PENDING", jdbc.queryForObject("select status from case_clue where id=?", String.class, id));
        assertEquals(0, jdbc.queryForObject("select excluded_days from case_clue where id=?", Integer.class, id));
    }

    @Test void extensionUsesCurrentConfiguredRoleDespiteWarmParameterCache() throws Exception {
        var leader = actor("LEADER");long id = clue(leader);var before = snapshot(id);
        assertEquals("LEADER", config.str("clue_extension_approver_role", "LEADER"));
        assertTrue(clues.canApproveExtension(leader.name()));
        assertEquals(403, clueRace(id, leader, probe(Action.EXTEND, id), () ->
                jdbc.update("update sys_config set cfg_value='LEGAL' where cfg_key='clue_extension_approver_role'")));
        assertEquals(before, snapshot(id));
        assertFalse(clues.canApproveExtension(leader.name()));
    }

    @Test void configuredLegalApproverRetainsExistingExtensionPermission() throws Exception {
        var owner = actor("HANDLER");var legal = actor("LEGAL");long id = clue(owner);
        cfg("clue_extension_approver_role", "LEGAL");
        assertEquals(0, call(legal, probe(Action.EXTEND, id)));
        assertEquals(legal.name(), jdbc.queryForObject("select extend_approved_by from case_clue where id=?", String.class, id));
        assertEquals(1, jdbc.queryForObject("select count(*) from biz_approval where clue_id=? and kind='CLUE_EXTEND' and approver=?", Integer.class, id, legal.name()));
    }

    @ParameterizedTest @MethodSource("creations")
    void revokedCreatorCannotAllocateClueNumbersOrPartiallyImport(NewMutation mutation) throws Exception {
        var owner = actor("HANDLER");String marker = "CLUE-CREATE-" + UUID.randomUUID();int year = LocalDate.now().getYear();
        jdbc.update("insert into biz_seq(kind,year,val) values('CLUE',?,0) on conflict do nothing", year);
        int before = jdbc.queryForObject("select val from biz_seq where kind='CLUE' and year=?", Integer.class, year);
        var pool = Executors.newSingleThreadExecutor();var pending = new AtomicReference<Future<Integer>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                jdbc.queryForList("select id from sys_user where id=? for update", owner.id());
                // The old implementation reaches this row without ever waiting for its actor.
                // Holding both rows gives old/new code the same observable request-arrival barrier.
                jdbc.queryForList("select kind from biz_seq where kind='CLUE' and year=? for update", year);
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                pending.set(pool.submit(() -> call(owner, creation(mutation.creation(), owner, marker))));
                try { awaitBlocked(blocker, 1); } catch (Exception failure) { throw new RuntimeException(failure); }
                revoke(owner, mutation.revocation(), false);
            });
            assertEquals(403, pending.get().get(20, TimeUnit.SECONDS));
        } finally { pool.shutdownNow();assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS)); }
        assertEquals(0, jdbc.queryForObject("select count(*) from case_clue where content=?", Integer.class, marker));
        assertEquals(before, jdbc.queryForObject("select val from biz_seq where kind='CLUE' and year=?", Integer.class, year));
    }

    @ParameterizedTest @EnumSource(Action.class)
    void completedClueCannotBeMutatedAfterSourceWait(Action action) throws Exception {
        var owner = actor(action == Action.EXTEND ? "LEADER" : "HANDLER");long id = clue(owner);
        var before = new LinkedHashMap<>(jdbc.queryForMap("select * from case_clue where id=?", id));before.put("status", "REJECTED");
        assertEquals(2021, clueRace(id, owner, probe(action, id), () -> jdbc.update("update case_clue set status='REJECTED' where id=?", id)));
        assertEquals(before, jdbc.queryForMap("select * from case_clue where id=?", id));
        assertEquals(0, jdbc.queryForObject("select count(*) from biz_approval where clue_id=?", Integer.class, id));
    }

    @Test void concurrentExtensionApprovesExactlyOnceAndKeepsFirstRecord() throws Exception {
        var leader = actor("LEADER");long id = clue(leader);var pool = Executors.newFixedThreadPool(2);
        var pending = new ArrayList<Future<Integer>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status -> {
                jdbc.queryForList("select id from case_clue where id=? for update", id);
                int blocker = jdbc.queryForObject("select pg_backend_pid()", Integer.class);
                pending.add(pool.submit(() -> call(leader, new Probe("/api/bureau/clues/" + id + "/extend", Map.of("reason", "并发理由甲")))));
                pending.add(pool.submit(() -> call(leader, new Probe("/api/bureau/clues/" + id + "/extend", Map.of("reason", "并发理由乙")))));
                try { awaitBlocked(blocker, 2); } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            var codes = new ArrayList<Integer>();for (var request : pending) codes.add(request.get(20, TimeUnit.SECONDS));codes.sort(Integer::compareTo);
            assertEquals(List.of(0, 2022), codes);
        } finally { pool.shutdownNow();assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS)); }
        assertEquals(1, jdbc.queryForObject("select count(*) from biz_approval where clue_id=? and kind='CLUE_EXTEND'", Integer.class, id));
        String reason = jdbc.queryForObject("select extend_reason from case_clue where id=?", String.class, id);
        assertEquals(reason, jdbc.queryForObject("select reason from biz_approval where clue_id=?", String.class, id));
        assertEquals(leader.name(), jdbc.queryForObject("select extend_approved_by from case_clue where id=?", String.class, id));
    }

    @Test void ownerAssignedHandlerAndLeaderRetainBusinessWrites() throws Exception {
        var owner = actor("HANDLER");var assigned = actor("HANDLER");var leader = actor("LEADER");
        long first = clue(owner);long second = clue(owner);long third = clue(owner);
        jdbc.update("update case_clue set handler=? where id=?", "其他人；" + assigned.name() + "，别名", second);
        assertEquals(0, call(owner, probe(Action.EXCLUDE, first)));
        assertEquals(0, call(assigned, probe(Action.EXCLUDE, second)));
        assertEquals(0, call(leader, probe(Action.REJECT, third)));
        assertEquals(2, jdbc.queryForObject("select excluded_days from case_clue where id=?", Integer.class, first));
        assertEquals(2, jdbc.queryForObject("select excluded_days from case_clue where id=?", Integer.class, second));
        assertEquals("REJECTED", jdbc.queryForObject("select status from case_clue where id=?", String.class, third));
    }

    @Test void currentCreatorAndImporterRetainOriginalNumberingAndSharedRegisterVisibility() throws Exception {
        var owner = actor("HANDLER");var other = actor("HANDLER");var legal = actor("LEGAL");
        String marker = "CLUE-SHARED-" + UUID.randomUUID();
        assertEquals(0, call(owner, creation(Creation.SINGLE, owner, marker)));
        assertEquals(0, call(owner, creation(Creation.IMPORT, owner, marker)));
        assertEquals(3, jdbc.queryForObject("select count(*) from case_clue where content=? and created_by=?", Integer.class, marker, owner.name()));
        assertEquals(3, jdbc.queryForObject("select count(distinct clue_no) from case_clue where content=?", Integer.class, marker));
        for (var reader : List.of(other, legal)) {
            var response = mvc.perform(get("/api/bureau/clues").param("page", "1").param("q", marker)
                    .header("Authorization", "Bearer " + reader.token())).andReturn().getResponse();
            assertEquals(200, response.getStatus());var result = mapper.readTree(response.getContentAsString());
            assertEquals(0, result.path("code").asInt());assertEquals(3, result.path("data").path("total").asInt());
        }
    }
}
