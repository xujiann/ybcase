package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.OwnerTransferService;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.web.SysUserController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL lock interleavings; all fixtures live in the dedicated test database. */
@SpringBootTest
@ActiveProfiles("test")
class OwnershipIntegrityIntegrationTest {
    @Autowired CaseService cases;
    @Autowired OwnerTransferService transfers;
    @Autowired SysUserController users;
    @Autowired SysUserRepository userRepo;
    @Autowired SysRoleRepository roles;
    @Autowired CaseCauseRepository causes;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager tm;
    @Autowired DataSource dataSource;

    static void auth() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", "n/a", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    @BeforeEach void login() { auth(); }
    @AfterEach void logout() { SecurityContextHolder.clearContext(); }

    SysUser user(String role) {
        var u = new SysUser();
        u.setUsername("owner_" + System.nanoTime()); u.setRealName("移交测试"); u.setPassword("unchanged");
        if (role != null) u.getRoles().add(roles.findByCode(role).orElseThrow());
        return userRepo.saveAndFlush(u);
    }
    long newCase(String owner) {
        var cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow();
        return cases.create(new CaseService.CaseCreateReq(null, cause.getId(), "SUMMARY", "IT-移交" + System.nanoTime(),
                "PROVIDER", null, null, null, null, "test", BigDecimal.TEN,
                List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
                        new CaseService.OfficerReq("张协办", "YB002", "MEMBER")), null, null, null, null), owner).getId();
    }
    <T> T tx(Supplier<T> action) { return new TransactionTemplate(tm).execute(s -> action.get()); }
    <T> Future<T> async(ExecutorService pool, Supplier<T> action) {
        return pool.submit(() -> { auth(); try { return tx(action); } finally { logout(); } });
    }
    void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "Expected database lock interleaving did not occur");
    }
    boolean blocked(int pid) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid));
    }

    @Test void recipientMustHaveEligibleRoleAndBeEnabled() {
        var source = user("HANDLER"); var invalid = user(null); long id = newCase(source.getUsername());
        assertEquals(2081, assertThrows(BizException.class, () -> cases.transferOwner(id, invalid.getUsername(), "admin")).code);
        jdbc.update("update sys_user set enabled=false where id=?", invalid.getId());
        assertEquals(2081, assertThrows(BizException.class, () -> cases.transferOwner(id, invalid.getUsername(), "admin")).code);
        assertEquals(source.getUsername(), cases.get(id).getOwnerUser());
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_message where username=?", Integer.class, invalid.getUsername()));
    }

    @Test void receiverApiMatchesExistingBusinessRoleCandidatePolicy() {
        var admin = user("ADMIN"); var invalid = user(null); var disabled = user("HANDLER");
        jdbc.update("update sys_user set enabled=false where id=?", disabled.getId());
        var names = transfers.candidates().stream().map(r -> r.get("username")).toList();
        assertFalse(names.contains(admin.getUsername())); assertFalse(names.contains(invalid.getUsername()));
        assertFalse(names.contains(disabled.getUsername()));
        long id = newCase("banban");
        assertEquals(2081, assertThrows(BizException.class, () -> cases.transferOwner(id, admin.getUsername(), "admin")).code);
        assertEquals("banban", cases.get(id).getOwnerUser());
    }

    @Test void transferClearsPreviousDepartmentWhenRecipientHasNone() {
        var source = user("HANDLER"); var recipient = user("LEGAL"); long id = newCase(source.getUsername());
        jdbc.update("update case_file set owner_dept_id=(select min(id) from sys_dept) where id=?", id);
        assertNotNull(cases.get(id).getOwnerDeptId());
        cases.transferOwner(id, recipient.getUsername(), "admin");
        assertNull(cases.get(id).getOwnerDeptId());
    }

    @Test void removingLastEligibleRoleIsRejectedBeforeAnyMutation() {
        var source = user("HANDLER"); long id = newCase(source.getUsername());
        Long dept = jdbc.queryForObject("select min(id) from sys_dept", Long.class);
        assertNotNull(dept);
        var request = new SysUserController.SaveUserRequest(source.getUsername(), "NewPassword2026!", "不得写入", null, dept, null, List.of());
        var result = users.update(source.getId(), request);
        assertEquals(1105, result.getCode());
        var saved = userRepo.findById(source.getId()).orElseThrow();
        assertEquals("移交测试", saved.getRealName()); assertEquals("unchanged", saved.getPassword());
        assertNull(saved.getDeptId()); assertEquals(0, saved.getTokenVersion());
        assertTrue(saved.getRoles().stream().anyMatch(r -> r.getCode().equals("HANDLER")));
        // Another eligible role preserves ownership; after transfer the last role may be removed.
        assertEquals(0, users.update(source.getId(), new SysUserController.SaveUserRequest(source.getUsername(), null, "移交测试", null, null, null, List.of("LEGAL"))).getCode());
        cases.transferOwner(id, "banban", "admin");
        assertEquals(0, users.update(source.getId(), request).getCode());
    }

    @Test void bulkTransferCannotOverwriteAnOwnerChangedWhileWaiting() throws Exception {
        var source = user("HANDLER"); var recipient = user("HANDLER"); var other = user("HANDLER");
        long id = newCase(source.getUsername());
        var pool = Executors.newFixedThreadPool(2); var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pid = new CompletableFuture<Integer>();
        try {
            var first = async(pool, () -> {
                jdbc.update("update case_file set owner_user=? where id=?", other.getUsername(), id);
                locked.countDown();
                try { assertTrue(release.await(20, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return true;
            });
            assertTrue(locked.await(15, TimeUnit.SECONDS));
            var second = async(pool, () -> {
                pid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return transfers.transfer(source.getUsername(), recipient.getUsername(), List.of(id), "admin");
            });
            int backend = pid.get(15, TimeUnit.SECONDS); await(() -> blocked(backend)); release.countDown();
            assertTrue(first.get(20, TimeUnit.SECONDS));
            var error = assertThrows(ExecutionException.class, () -> second.get(20, TimeUnit.SECONDS));
            assertInstanceOf(BizException.class, error.getCause());
            assertEquals(other.getUsername(), cases.get(id).getOwnerUser());
            assertEquals(0, jdbc.queryForObject("select count(*) from sys_message where username=?", Integer.class, recipient.getUsername()));
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS)); }
    }

    @Test void disableAndTransferSerializeEligibilityAndWorkloadChecks() throws Exception {
        raceReceiverChange(false);
    }

    @Test void roleRemovalAndTransferSerializeEligibilityAndWorkloadChecks() throws Exception {
        raceReceiverChange(true);
    }

    void raceReceiverChange(boolean removeRoles) throws Exception {
        var source = user("HANDLER"); var recipient = user("HANDLER"); long id = newCase(source.getUsername());
        long key = id + 87000000L;
        String trigger = "owner_pause_" + id;
        jdbc.execute("create function " + trigger + "() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(" + key + "); return new; end $$");
        jdbc.execute("create trigger " + trigger + " before update of owner_user on case_file for each row when (old.id=" + id + ") execute function " + trigger + "()");
        var pool = Executors.newFixedThreadPool(2); var transferPid = new CompletableFuture<Integer>(); var disablePid = new CompletableFuture<Integer>();
        try (Connection gate = dataSource.getConnection()) {
            gate.createStatement().execute("select pg_advisory_lock(" + key + ")");
            try {
                var transfer = async(pool, () -> {
                    transferPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return cases.transferOwner(id, recipient.getUsername(), "admin");
                });
                int transferBackend = transferPid.get(15, TimeUnit.SECONDS); await(() -> blocked(transferBackend));
                var disable = async(pool, () -> {
                    disablePid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return removeRoles
                            ? users.update(recipient.getId(), new SysUserController.SaveUserRequest(recipient.getUsername(), null,
                                    "不得写入", null, null, null, List.of()))
                            : users.setEnabled(recipient.getId(), false);
                });
                int disableBackend = disablePid.get(15, TimeUnit.SECONDS);
                await(() -> disable.isDone() || blocked(disableBackend));
                gate.createStatement().execute("select pg_advisory_unlock(" + key + ")");
                assertEquals(recipient.getUsername(), transfer.get(20, TimeUnit.SECONDS).getOwnerUser());
                assertEquals(1105, disable.get(20, TimeUnit.SECONDS).getCode());
                assertTrue(userRepo.findById(recipient.getId()).orElseThrow().getEnabled());
                assertEquals("移交测试", userRepo.findById(recipient.getId()).orElseThrow().getRealName());
            } finally { gate.createStatement().execute("select pg_advisory_unlock_all()"); }
        } finally {
            pool.shutdownNow(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS));
            jdbc.execute("drop trigger if exists " + trigger + " on case_file");
            jdbc.execute("drop function if exists " + trigger + "()");
        }
    }

    @Test void transferWaitsForDisableAndRejectsItsCommittedResult() throws Exception {
        var recipient = user("HANDLER"); long id = newCase("banban");
        var pool = Executors.newFixedThreadPool(2); var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pid = new CompletableFuture<Integer>();
        try {
            var first = async(pool, () -> {
                assertEquals(0, users.setEnabled(recipient.getId(), false).getCode()); locked.countDown();
                try { assertTrue(release.await(20, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return true;
            });
            assertTrue(locked.await(15, TimeUnit.SECONDS));
            var second = async(pool, () -> {
                pid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                return cases.transferOwner(id, recipient.getUsername(), "admin");
            });
            int backend = pid.get(15, TimeUnit.SECONDS); await(() -> blocked(backend)); release.countDown();
            assertTrue(first.get(20, TimeUnit.SECONDS));
            var error = assertThrows(ExecutionException.class, () -> second.get(20, TimeUnit.SECONDS));
            assertEquals(2081, assertInstanceOf(BizException.class, error.getCause()).code);
            assertEquals("banban", cases.get(id).getOwnerUser());
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS)); }
    }
}
