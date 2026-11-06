package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.*;
import cn.ybcase.bureau.web.FormalizationController;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.AuthUser;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** Real PG snapshots and committed configuration API changes; no mocked policy SELECTs. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScopePolicySnapshotIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired BureauConfig config;
    @Autowired CaseService cases;
    @Autowired SourceHistoryService history;
    @Autowired BusinessStatisticsService statistics;
    @Autowired MessageService messages;
    @Autowired FormalizationController formalization;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager tm;

    String originalScope;
    final String marker = "SP" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    record Actor(long id, String name, String token, int version) {}
    record Fixture(Actor own, Actor other, List<Long> ownCases, List<Long> otherCases) {}

    @BeforeEach void configure() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class));
        SecurityContextHolder.clearContext();
        originalScope = dbScope();
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
        user.setUsername("policy_" + UUID.randomUUID().toString().replace("-", ""));
        user.setRealName("范围快照回归");
        user.setPassword("unused-jwt-fixture");
        user.getRoles().add(roles.findByCode(role).orElseThrow());
        user = users.saveAndFlush(user);
        return new Actor(user.getId(), user.getUsername(), jwt.issue(user.getUsername(), user.getTokenVersion()), user.getTokenVersion());
    }

    String dbScope() {
        return jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'", String.class);
    }

    void scope(String value) {
        jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'", value);
        config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
    }

    void changeScopeThroughApi(String value) throws Exception {
        var admin = actor("ADMIN");
        var response = mvc.perform(put("/api/config/case_view_scope").param("value", value)
                .header("Authorization", "Bearer " + admin.token())).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertEquals(0, mapper.readTree(response.getContentAsString()).path("code").asInt());
        assertEquals(value, dbScope(), "The API transaction must have committed before the old reader resumes");
    }

    long clue(Actor owner) {
        return jdbc.queryForObject("""
                insert into case_clue(clue_no,source,content,suspect_name,suspect_type,received_at,deadline_at,created_by)
                values(?,'COMPLAINT',?,?,'PROVIDER',current_date,current_date,?) returning id
                """, Long.class, marker + UUID.randomUUID().toString().substring(0, 8), marker, marker, owner.name());
    }

    long caseRow(Actor owner, boolean sourceRows) {
        long clue = clue(owner);
        long id = jdbc.queryForObject("""
                insert into case_file(case_no,name,clue_id,cause_id,party_name,party_type,filed_at,deadline_at,owner_user)
                values(?,?,?,(select min(id) from case_cause),?,'PROVIDER',current_date,current_date,?) returning id
                """, Long.class, marker + UUID.randomUUID().toString().substring(0, 8), marker, clue, marker, owner.name());
        if (sourceRows) {
            jdbc.update("""
                    insert into inspection(insp_no,object_name,object_type,officers,planned_at,done_at,clue_id,created_by)
                    values(?,?,'PROVIDER','回归甲、回归乙',current_date,current_date,?,?)
                    """, marker + UUID.randomUUID().toString().substring(0, 8), marker, clue, owner.name());
            jdbc.update("insert into reward(clue_id,reporter_name,amount,paid_at,note) values(?,'回归举报人',5,current_date,?)", clue, marker);
            jdbc.update("""
                    insert into case_transfer(clue_id,case_id,direction,target_org,kind,reason,sent_at)
                    values(?,?,'OUT',?,'ADMIN',?,current_date)
                    """, clue, id, marker, marker);
        }
        return id;
    }

    Fixture fixture(int count, boolean sourceRows) {
        var own = actor("HANDLER");
        var other = actor("HANDLER");
        var ownCases = new ArrayList<Long>();
        var otherCases = new ArrayList<Long>();
        for (int n = 0; n < count; n++) ownCases.add(caseRow(own, sourceRows));
        // Foreign rows deliberately sort before every own row.
        for (int n = 0; n < count; n++) otherCases.add(caseRow(other, sourceRows));
        return new Fixture(own, other, ownCases, otherCases);
    }

    <T> T repeatableRead(Supplier<T> work) {
        var tx = new TransactionTemplate(tm);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> work.get());
    }

    /** Reproduces a legal old ALL reader republishing its old config after the API's afterCommit eviction. */
    void refillOldAllCache() throws Exception {
        scope("ALL");
        var ready = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var oldReader = pool.submit(() -> repeatableRead(() -> {
                assertEquals("ALL", dbScope()); // Establish the actual PG snapshot.
                ready.countDown();
                await(resume);
                return config.str("case_view_scope", "SELF");
            }));
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            changeScopeThroughApi("SELF");
            // The old reader may be any legal RR consumer of a non-authorization parameter cache.
            config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
            resume.countDown();
            assertEquals("ALL", oldReader.get(20, TimeUnit.SECONDS));
            assertEquals("ALL", config.str("case_view_scope", "SELF"), "Prove the process cache really contains the stale grant");
            assertEquals("SELF", dbScope());
        } finally {
            resume.countDown();
            stop(pool);
        }
    }

    /** A fresh ALL grant/cache must not expand data inside an existing SELF snapshot. */
    void oldSelfReaderWithNewAllCache(Runnable oldSnapshotChecks, Runnable freshChecks) throws Exception {
        scope("SELF");
        var ready = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var oldReader = pool.submit(() -> repeatableRead(() -> {
                assertEquals("SELF", dbScope());
                ready.countDown();
                await(resume);
                assertEquals("ALL", config.str("case_view_scope", "SELF"), "Prove the cache belongs to the new grant");
                oldSnapshotChecks.run();
                return null;
            }));
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            changeScopeThroughApi("ALL");
            assertEquals("ALL", config.str("case_view_scope", "SELF"));
            resume.countDown();
            oldReader.get(20, TimeUnit.SECONDS);
            freshChecks.run();
        } finally {
            resume.countDown();
            stop(pool);
        }
    }

    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(20, TimeUnit.SECONDS), "The policy reader was not released"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    static void stop(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Object value) { return (List<Map<String, Object>>) value; }

    static Set<Long> ids(List<Map<String, Object>> rows) {
        var ids = new HashSet<Long>();
        for (var row : rows) assertTrue(ids.add(((Number) row.get("id")).longValue()));
        return ids;
    }

    void assertCaseResults(Fixture f, boolean self) {
        var expected = self ? f.ownCases() : f.otherCases();
        var lastTwenty = expected.subList(Math.max(0, expected.size() - 20), expected.size());
        assertEquals(new HashSet<>(lastTwenty), ids(cases.searchCases("%" + marker + "%", f.own().name(), false)));
        var page = cases.pageList(null, marker, 1, 7, f.own().name(), false);
        assertEquals(self ? f.ownCases().size() : f.ownCases().size() + f.otherCases().size(), ((Number) page.get("total")).intValue());
        assertEquals(new HashSet<>(expected.subList(expected.size() - 7, expected.size())), ids(rows(page.get("rows"))),
                "Scope belongs in SQL before the first page/20-result search limit");
    }

    void assertHistoryResults(Fixture f, boolean self) {
        for (var kind : SourceHistoryService.Kind.values()) {
            var page = (Map<?, ?>) history.list(kind, 1, 100, marker, f.own().name());
            assertEquals(self ? 2 : 4, ((Number) page.get("total")).intValue(), kind.name());
            var result = rows(page.get("rows"));
            assertEquals(self ? 2 : 4, result.size(), kind.name());
            for (var row : result) if (self) {
                if (kind == SourceHistoryService.Kind.INSPECTIONS) assertEquals(f.own().name(), row.get("created_by"));
                else if (kind == SourceHistoryService.Kind.TRANSFERS) assertTrue(f.ownCases().contains(((Number) row.get("case_id")).longValue()));
                else assertEquals(f.own().name(), jdbc.queryForObject("select created_by from case_clue where id=?", String.class, row.get("clue_id")));
            }
            assertEquals(ids(result), ids(rows(history.list(kind, null, 1, marker, f.own().name()))), "Legacy arrays also filter before LIMIT");
        }
    }

    void seedInbox(Fixture f) {
        for (long id : f.ownCases()) messages.send(f.own().name(), "DEADLINE", marker, marker, "/case/detail/" + id, null);
        for (long id : f.otherCases()) messages.send(f.own().name(), "DEADLINE", marker, marker, "/case/detail/" + id, null);
    }

    void assertAggregateResults(Fixture f, boolean self) {
        long expectedCases = self ? f.ownCases().size() : jdbc.queryForObject("select count(*) from case_file", Long.class);
        assertEquals(expectedCases, ((Number) statistics.overview(f.own().name()).get("caseTotal")).longValue());
        var expectedLinks = new HashSet<String>();
        f.ownCases().forEach(id -> expectedLinks.add("/case/detail/" + id));
        if (!self) f.otherCases().forEach(id -> expectedLinks.add("/case/detail/" + id));
        var resultLinks = new HashSet<String>();
        messages.inbox(f.own().name()).forEach(row -> resultLinks.add((String) row.get("link")));
        assertEquals(expectedLinks, resultLinks);
        assertEquals(expectedLinks.size(), messages.unread(f.own().name()).intValue());
    }

    @Test void oldAllCacheRefillCannotExpandFreshSelfCaseSearchOrPages() throws Exception {
        var f = fixture(25, false);
        refillOldAllCache();
        assertCaseResults(f, true);
    }

    @Test void oldAllCacheRefillCannotExpandFreshSelfSourceHistories() throws Exception {
        var f = fixture(2, true);
        refillOldAllCache();
        assertHistoryResults(f, true);
    }

    @Test void oldAllCacheRefillCannotExpandFreshSelfStatisticsOrInbox() throws Exception {
        var f = fixture(2, true);
        seedInbox(f);
        refillOldAllCache();
        assertAggregateResults(f, true);
    }

    @Test void newAllCacheCannotExpandOldSelfCaseSearchOrPages() throws Exception {
        var f = fixture(25, false);
        oldSelfReaderWithNewAllCache(() -> assertCaseResults(f, true), () -> assertCaseResults(f, false));
    }

    @Test void newAllCacheCannotExpandOldSelfSourceHistories() throws Exception {
        var f = fixture(2, true);
        oldSelfReaderWithNewAllCache(() -> assertHistoryResults(f, true), () -> assertHistoryResults(f, false));
    }

    @Test void newAllCacheCannotExpandOldSelfStatisticsOrInbox() throws Exception {
        var f = fixture(2, true);
        seedInbox(f);
        oldSelfReaderWithNewAllCache(() -> assertAggregateResults(f, true), () -> assertAggregateResults(f, false));
    }

    Authentication captured(Actor actor, String role) {
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var principal = new AuthUser(actor.name(), "unused", true, actor.version(), actor.id(), false, authorities);
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    <T> T as(Authentication auth, Supplier<T> work) {
        SecurityContextHolder.getContext().setAuthentication(auth);
        try { return work.get(); } finally { SecurityContextHolder.clearContext(); }
    }

    @Test void searchUsesDowngradedLiveRoleAndPreservesSharedClueSearch() {
        scope("SELF");
        var reader = actor("LEADER");
        var other = actor("HANDLER");
        var captured = captured(reader, "LEADER");
        long own = caseRow(reader, false), foreign = caseRow(other, false), sharedClue = clue(other);
        new TransactionTemplate(tm).executeWithoutResult(status -> {
            jdbc.update("delete from sys_user_role where user_id=?", reader.id());
            jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='HANDLER'", reader.id());
        });
        var result = as(captured, () -> formalization.search(marker, captured)).getData();
        assertEquals(Set.of(own), ids(rows(result.get("cases"))));
        assertFalse(ids(rows(result.get("cases"))).contains(foreign));
        assertTrue(ids(rows(result.get("clues"))).contains(sharedClue), "The existing shared clue register must remain searchable");
    }

    @Test void searchRejectsDisabledActorCapturedBeforeDisable() {
        var reader = actor("HANDLER");
        var captured = captured(reader, "HANDLER");
        jdbc.update("update sys_user set enabled=false where id=?", reader.id());
        assertEquals(403, assertThrows(BizException.class, () -> as(captured, () -> formalization.search(marker, captured))).code);
    }

    @Test void searchRejectsTokenVersionChangedAfterAuthentication() {
        var reader = actor("HANDLER");
        var captured = captured(reader, "HANDLER");
        jdbc.update("update sys_user set token_version=token_version+1 where id=?", reader.id());
        assertEquals(403, assertThrows(BizException.class, () -> as(captured, () -> formalization.search(marker, captured))).code);
    }

    @Test void searchRejectsActorWhoseLastBusinessRoleWasRemoved() {
        var reader = actor("LEADER");
        var captured = captured(reader, "LEADER");
        jdbc.update("delete from sys_user_role where user_id=?", reader.id());
        assertEquals(403, assertThrows(BizException.class, () -> as(captured, () -> formalization.search(marker, captured))).code);
    }
}
