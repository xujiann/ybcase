package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.ApprovalService;
import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.ClueService;
import cn.ybcase.bureau.web.FormalizationController;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.web.SysUserController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real-user filing and approval invariants, including deterministic PostgreSQL lock interleavings. */
@SpringBootTest
@ActiveProfiles("test")
class FilingApprovalIntegrityIntegrationTest {
    @Autowired CaseService cases;
    @Autowired ApprovalService approvals;
    @Autowired ClueService clues;
    @Autowired FormalizationController controller;
    @Autowired SysUserController users;
    @Autowired SysUserRepository userRepo;
    @Autowired SysRoleRepository roles;
    @Autowired CaseCauseRepository causes;
    @Autowired BureauConfig config;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired PlatformTransactionManager tm;
    @Autowired DataSource dataSource;

    private final List<SysUser> fixtures = new CopyOnWriteArrayList<>();
    private String oldScope;
    private static final List<CaseService.OfficerReq> TWO = List.of(
            new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
            new CaseService.OfficerReq("张协办", "YB002", "MEMBER"));

    static Authentication auth(String username, String role) {
        return new UsernamePasswordAuthenticationToken(username, "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
    static void adminContext() {
        SecurityContextHolder.getContext().setAuthentication(auth("admin", "ADMIN"));
    }
    @BeforeEach void setUp() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            String url = connection.getMetaData().getURL();
            String database = url.substring(url.lastIndexOf('/') + 1).split("\\?")[0];
            assertTrue(database.startsWith("ybcase_"), "A dedicated test database is required; original ybcase is forbidden");
        }
        adminContext();
        oldScope = jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'", String.class);
        scope("SELF");
    }
    @AfterEach void cleanupOwnFixtures() {
        try {
            if (oldScope != null) scope(oldScope);
            // No broad cleanup: each row predicate names only accounts created by this test instance.
            tx(() -> {
                for (SysUser user : fixtures) {
                    jdbc.update("delete from sys_message where username=? or (kind='APPROVAL' and title like ?)",
                            user.getUsername(), "%" + user.getUsername() + "%");
                    jdbc.update("delete from biz_approval where applicant=?", user.getUsername());
                }
                for (SysUser user : fixtures) {
                    jdbc.update("delete from case_officer where case_id in (select id from case_file where created_by=?)", user.getUsername());
                    jdbc.update("delete from case_file where created_by=?", user.getUsername());
                    jdbc.update("delete from case_clue where created_by=?", user.getUsername());
                }
                for (SysUser user : fixtures) {
                    jdbc.update("delete from sys_user_role where user_id=?", user.getId());
                    jdbc.update("delete from sys_user where id=? and username=?", user.getId(), user.getUsername());
                }
                return true;
            });
        } finally { SecurityContextHolder.clearContext(); }
    }
    void scope(String value) {
        jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'", value);
        config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
    }
    SysUser user(String role) {
        var u = new SysUser();
        u.setUsername("filing_integrity_" + System.nanoTime()); u.setRealName("立案审批测试"); u.setPassword("unused-real-user-fixture");
        if (role != null) u.getRoles().add(roles.findByCode(role).orElseThrow());
        u = userRepo.saveAndFlush(u); fixtures.add(u); return u;
    }
    CaseService.CaseCreateReq request(Long clueId) {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        return new CaseService.CaseCreateReq(clueId, cause, "NORMAL", "FILING-INTEGRITY-" + System.nanoTime(),
                "PROVIDER", null, null, null, null, "隔离立案审批一致性回归", BigDecimal.TEN, TWO, null, null, null, null);
    }
    Map<String, Object> payload(Long clueId) {
        return mapper.convertValue(request(clueId), new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
    }
    long clue(SysUser owner) {
        return clues.create(new ClueService.ClueCreateReq("COMPLAINT", "立案审批隔离线索", "测试机构", "PROVIDER",
                LocalDate.now(), owner.getUsername()), owner.getUsername()).getId();
    }
    long filing(SysUser owner, Long clueId) {
        return approvals.apply(new ApprovalService.ApplyReq("FILE_CASE", clueId, null, payload(clueId), "立案审批一致性"),
                owner.getUsername(), false);
    }
    long historical(String kind, SysUser applicant, Long caseId, Map<String, Object> payload) throws Exception {
        return jdbc.queryForObject("insert into biz_approval(kind,case_id,payload,reason,applicant) values(?,?,?,?,?) returning id",
                Long.class, kind, caseId, mapper.writeValueAsString(payload), "历史异常材料回归", applicant.getUsername());
    }
    Map<String, Object> approval(long id) { return jdbc.queryForMap("select * from biz_approval where id=?", id); }
    long caseCount(SysUser owner) { return jdbc.queryForObject("select count(*) from case_file where created_by=?", Long.class, owner.getUsername()); }
    void fails(int code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BizException.class, action).code);
    }
    void noDecision(long id, Map<String, Object> before) {
        assertEquals(before, approval(id));
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_message where kind='APPROVAL' and title like ?",
                Integer.class, "审批单 #" + id + "（%"));
    }
    void removeRoles(SysUser user, List<String> codes) {
        assertEquals(0, users.update(user.getId(), new SysUserController.SaveUserRequest(user.getUsername(), null,
                user.getRealName(), null, user.getDeptId(), null, codes)).getCode());
    }
    <T> T tx(Supplier<T> action) { return new TransactionTemplate(tm).execute(s -> action.get()); }
    <T> Future<T> async(ExecutorService pool, Supplier<T> action) {
        return pool.submit(() -> { adminContext(); try { return tx(action); } finally { SecurityContextHolder.clearContext(); } });
    }
    boolean blocked(int pid) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?))>0", Boolean.class, pid));
    }
    void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "Required PostgreSQL lock interleaving was not observed");
    }
    void stop(ExecutorService pool) throws Exception { pool.shutdownNow(); assertTrue(pool.awaitTermination(25, TimeUnit.SECONDS)); }

    @Test void directCreationRequiresExistingEnabledFilingRole() {
        for (String role : new String[]{null, "LEGAL", "HANDLER"}) {
            var applicant = user(role);
            if ("HANDLER".equals(role)) assertEquals(0, users.setEnabled(applicant.getId(), false).getCode());
            fails(403, () -> cases.create(request(null), applicant.getUsername()));
            assertEquals(0, caseCount(applicant));
        }
        fails(403, () -> cases.create(request(null), "missing-filing-user-" + System.nanoTime()));
        for (String role : List.of("HANDLER", "LEADER", "ADMIN")) {
            var applicant = user(role); assertEquals(applicant.getUsername(), cases.create(request(null), applicant.getUsername()).getOwnerUser());
        }
    }
    @Test void disabledApplicantsCannotBeApprovedWithOrWithoutClueButCanBeRejected() {
        invalidApplicant("DISABLED");
    }
    @Test void rolelessApplicantsCannotBeApprovedWithOrWithoutClueButCanBeRejected() {
        invalidApplicant("ROLELESS");
    }
    @Test void legalOnlyApplicantsCannotBeApprovedWithOrWithoutClueButCanBeRejected() {
        invalidApplicant("LEGAL");
    }
    void invalidApplicant(String change) {
        var leader = user("LEADER");
        for (boolean withClue : List.of(false, true)) {
            var applicant = user("HANDLER"); Long clueId = withClue ? clue(applicant) : null;
            long id = filing(applicant, clueId);
            if (change.equals("DISABLED")) assertEquals(0, users.setEnabled(applicant.getId(), false).getCode());
            else removeRoles(applicant, change.equals("LEGAL") ? List.of("LEGAL") : List.of());
            var before = approval(id);
            fails(403, () -> approvals.decide(id, true, "同意", leader.getUsername()));
            noDecision(id, before); assertEquals(0, caseCount(applicant));
            if (clueId != null) assertEquals("PENDING", clues.get(clueId).getStatus());
            approvals.decide(id, false, "申请人岗位已变化，请重新提交", leader.getUsername());
            assertEquals("REJECTED", approval(id).get("status")); assertEquals(0, caseCount(applicant));
        }
    }
    @Test void serviceLayerRejectsIneligibleApproversForBothOutcomes() {
        var applicant = user("HANDLER"); long id = filing(applicant, null); var before = approval(id);
        for (String role : new String[]{null, "HANDLER", "LEGAL", "LEADER"}) {
            var approver = user(role);
            if ("LEADER".equals(role)) assertEquals(0, users.setEnabled(approver.getId(), false).getCode());
            for (boolean approve : List.of(false, true)) {
                fails(403, () -> approvals.decide(id, approve, "无权裁决", approver.getUsername())); noDecision(id, before);
            }
        }
        fails(403, () -> approvals.decide(id, false, "无账号", "missing-approver-" + System.nanoTime()));
        noDecision(id, before); assertEquals(0, caseCount(applicant));
        approvals.decide(id, false, "合法驳回", user("LEADER").getUsername());
    }
    @Test void pendingCaseApprovalRechecksScopeAfterTransfer() {
        var applicant = user("HANDLER"); var receiver = user("HANDLER"); var leader = user("LEADER");
        var c = cases.create(request(null), applicant.getUsername());
        long id = approvals.apply(new ApprovalService.ApplyReq("EXTEND", null, c.getId(), Map.of("days", 10), "期限申请"), applicant.getUsername(), false);
        cases.transferOwner(c.getId(), receiver.getUsername(), leader.getUsername()); var before = approval(id);
        fails(2080, () -> approvals.decide(id, true, "已移交的旧申请", leader.getUsername()));
        noDecision(id, before); assertEquals(c.getDeadlineAt(), cases.get(c.getId()).getDeadlineAt());
        approvals.decide(id, false, "已移交，原申请驳回", leader.getUsername());
    }
    @Test void callerFlagCannotGrantAnApplicantGlobalScope() {
        var applicant = user("HANDLER"); var owner = user("HANDLER"); var c = cases.create(request(null), owner.getUsername());
        fails(2080, () -> approvals.apply(new ApprovalService.ApplyReq("EXTEND", null, c.getId(), Map.of("days", 10), "伪造全局标志"), applicant.getUsername(), true));
        assertEquals(0, jdbc.queryForObject("select count(*) from biz_approval where applicant=?", Integer.class, applicant.getUsername()));
    }
    @Test void malformedDecisionTypesCannotClaimAnApplication() {
        var applicant = user("HANDLER"); var leader = user("LEADER"); long id = filing(applicant, null); var before = approval(id);
        List<Map<String, Object>> bodies = new ArrayList<>();
        bodies.add(Map.of()); bodies.add(Map.of("approve", "true")); bodies.add(Map.of("approve", 1));
        bodies.add(Map.of("approve", true, "opinion", 7)); bodies.add(Map.of("approve", true, "opinion", List.of("错误类型")));
        var nullApprove = new LinkedHashMap<String, Object>(); nullApprove.put("approve", null); bodies.add(nullApprove);
        for (var body : bodies) {
            fails(2071, () -> controller.decide(id, body, auth(leader.getUsername(), "LEADER"))); noDecision(id, before);
        }
        assertEquals(0, caseCount(applicant));
    }
    @Test void fractionalAndOverflowExtensionDaysNeverTruncateOrExecute() throws Exception {
        var applicant = user("HANDLER"); var leader = user("LEADER"); var c = cases.create(request(null), applicant.getUsername());
        for (Number days : List.<Number>of(new BigDecimal("1.5"), new BigInteger("2147483648"), new BigInteger("4294967297"))) {
            long id = historical("EXTEND", applicant, c.getId(), Map.of("days", days)); var before = approval(id);
            fails(2071, () -> approvals.decide(id, true, "错误天数", leader.getUsername())); noDecision(id, before);
            assertEquals(c.getDeadlineAt(), cases.get(c.getId()).getDeadlineAt());
            assertEquals(0, cases.get(c.getId()).getExtensionDays());
            approvals.decide(id, false, "无效参数驳回", leader.getUsername());
        }
        long valid = historical("EXTEND", applicant, c.getId(), Map.of("days", new BigDecimal("10.0")));
        approvals.decide(valid, true, "精确整数", leader.getUsername());
        assertEquals(c.getDeadlineAt().plusDays(10), cases.get(c.getId()).getDeadlineAt());
    }
    @Test void malformedHistoricalFilingPayloadProducesBusinessErrorAndRemainsRejectable() throws Exception {
        var applicant = user("HANDLER"); var leader = user("LEADER");
        for (String field : List.of("causeId", "officers", "amountInvolved", "violationEndDate")) {
            var malformed = payload(null); malformed.put(field, field.equals("causeId") ? Map.of("invalid", 1) : "invalid-value");
            long id = historical("FILE_CASE", applicant, null, malformed); var before = approval(id);
            fails(2071, () -> approvals.decide(id, true, "无效材料", leader.getUsername())); noDecision(id, before);
            assertEquals(0, caseCount(applicant)); approvals.decide(id, false, "材料错误请重提", leader.getUsername());
        }
    }
    @Test void nullOfficersAndNullNamesAreBusinessErrorsRatherThanNullPointerExceptions() {
        var applicant = user("HANDLER");
        List<List<CaseService.OfficerReq>> malformed = new ArrayList<>();
        var nullElement = new ArrayList<>(TWO); nullElement.add(null); malformed.add(nullElement);
        malformed.add(List.of(new CaseService.OfficerReq(null, "YB001", "LEAD"), TWO.get(1)));
        malformed.add(List.of(new CaseService.OfficerReq("王办案", null, "LEAD"), TWO.get(1)));
        for (var officers : malformed) {
            var req = request(null);
            var bad = new CaseService.CaseCreateReq(null, req.causeId(), "NORMAL", req.partyName(), req.partyType(), null, null, null, null,
                    req.summary(), BigDecimal.TEN, officers, null, null, null, null);
            var error = assertThrows(BizException.class, () -> cases.create(bad, applicant.getUsername()));
            assertEquals(2002, error.code, "Null officer data must be rejected before persistence");
            assertEquals(0, caseCount(applicant));
        }
    }
    @Test void normalFilingBindsNewCaseAndNotificationLink() {
        var applicant = user("HANDLER"); var leader = user("LEADER"); long clueId = clue(applicant); long id = filing(applicant, clueId);
        var c = (CaseFile) approvals.decide(id, true, "同意立案", leader.getUsername()).get("result");
        assertEquals(1, caseCount(applicant)); assertEquals(c.getId(), ((Number) approval(id).get("case_id")).longValue());
        assertEquals("FILED", clues.get(clueId).getStatus());
        var messages = jdbc.queryForList("select link from sys_message where username=? and title like ?", applicant.getUsername(), "审批单 #" + id + "（%");
        assertEquals(1, messages.size()); assertEquals("/case/detail/" + c.getId(), messages.get(0).get("link"));
    }
    @Test void concurrentDecisionsCreateExactlyOneCase() throws Exception {
        var applicant = user("HANDLER"); var firstLeader = user("LEADER"); var secondLeader = user("LEADER");
        long id = filing(applicant, null); var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            List<Future<Integer>> outcomes = new ArrayList<>();
            for (var leader : List.of(firstLeader, secondLeader)) outcomes.add(pool.submit(() -> {
                adminContext();
                try {
                    assertTrue(start.await(15, TimeUnit.SECONDS));
                    return tx(() -> { approvals.decide(id, true, "并发批准", leader.getUsername()); return 0; });
                } catch (BizException e) {
                    // Catch outside TransactionTemplate: the proxy may mark its transaction rollback-only.
                    return e.code;
                } finally { SecurityContextHolder.clearContext(); }
            }));
            start.countDown(); List<Integer> codes = new ArrayList<>(); for (var future : outcomes) codes.add(future.get(20, TimeUnit.SECONDS));
            codes.sort(Integer::compareTo); assertEquals(List.of(0, 2072), codes);
            assertEquals(1, caseCount(applicant)); assertEquals("APPROVED", approval(id).get("status"));
            assertEquals(1, jdbc.queryForObject("select count(*) from sys_message where username=? and title like ?", Integer.class,
                    applicant.getUsername(), "审批单 #" + id + "（%"));
        } finally { start.countDown(); stop(pool); }
    }
    @Test void creationCommitsBeforeDisableWorkloadCheck() throws Exception { creationWins(false); }
    @Test void creationCommitsBeforeRoleRemovalWorkloadCheck() throws Exception { creationWins(true); }
    void creationWins(boolean roleChange) throws Exception {
        var applicant = user("HANDLER"); long key = 98000000L + applicant.getId(); String hook = "filing_pause_" + applicant.getId();
        jdbc.execute("create function " + hook + "() returns trigger language plpgsql as $$ begin perform pg_advisory_xact_lock(" + key + "); return new; end $$");
        jdbc.execute("create trigger " + hook + " before insert on case_file for each row when (new.created_by='" + applicant.getUsername() + "') execute function " + hook + "()");
        var pool = Executors.newFixedThreadPool(2); var createPid = new CompletableFuture<Integer>(); var changePid = new CompletableFuture<Integer>();
        try (Connection gate = dataSource.getConnection()) {
            gate.createStatement().execute("select pg_advisory_lock(" + key + ")");
            try {
                var creation = async(pool, () -> { createPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); return cases.create(request(null), applicant.getUsername()); });
                int creating = createPid.get(15, TimeUnit.SECONDS); await(() -> blocked(creating));
                var change = async(pool, () -> {
                    changePid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    return roleChange ? users.update(applicant.getId(), new SysUserController.SaveUserRequest(applicant.getUsername(), null,
                            "不应修改", null, null, null, List.of())).getCode() : users.setEnabled(applicant.getId(), false).getCode();
                });
                int changing = changePid.get(15, TimeUnit.SECONDS); await(() -> change.isDone() || blocked(changing));
                gate.createStatement().execute("select pg_advisory_unlock(" + key + ")");
                assertNotNull(creation.get(20, TimeUnit.SECONDS).getId()); assertEquals(1105, change.get(20, TimeUnit.SECONDS));
                var saved = userRepo.findById(applicant.getId()).orElseThrow(); assertTrue(saved.getEnabled());
                assertEquals("立案审批测试", saved.getRealName()); assertTrue(saved.getRoles().stream().anyMatch(r -> r.getCode().equals("HANDLER")));
            } finally { gate.createStatement().execute("select pg_advisory_unlock_all()"); }
        } finally { stop(pool); jdbc.execute("drop trigger if exists " + hook + " on case_file"); jdbc.execute("drop function if exists " + hook + "()"); }
    }
    @Test void committedDisablePreventsWaitingCreation() throws Exception { accountChangeWins(false); }
    @Test void committedRoleRemovalPreventsWaitingCreation() throws Exception { accountChangeWins(true); }
    void accountChangeWins(boolean roleChange) throws Exception {
        var applicant = user("HANDLER"); var pool = Executors.newFixedThreadPool(2); var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var createPid = new CompletableFuture<Integer>();
        try {
            var first = async(pool, () -> {
                if (roleChange) removeRoles(applicant, List.of()); else assertEquals(0, users.setEnabled(applicant.getId(), false).getCode());
                locked.countDown(); try { assertTrue(release.await(20, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); } return true;
            });
            assertTrue(locked.await(15, TimeUnit.SECONDS));
            var second = async(pool, () -> { createPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); return cases.create(request(null), applicant.getUsername()); });
            int creating = createPid.get(15, TimeUnit.SECONDS); await(() -> second.isDone() || blocked(creating)); release.countDown();
            assertTrue(first.get(20, TimeUnit.SECONDS));
            var failure = assertThrows(ExecutionException.class, () -> second.get(20, TimeUnit.SECONDS));
            assertEquals(403, assertInstanceOf(BizException.class, failure.getCause()).code); assertEquals(0, caseCount(applicant));
        } finally { release.countDown(); stop(pool); }
    }
    @Test void opposingApplicantApproverPairsUseStableUserLockOrder() throws Exception {
        var lower = user("LEADER"); var higher = user("LEADER"); assertTrue(lower.getId() < higher.getId());
        long firstId = filing(lower, null), secondId = filing(higher, null);
        var pool = Executors.newFixedThreadPool(2); var firstPid = new CompletableFuture<Integer>(); var secondPid = new CompletableFuture<Integer>();
        try (Connection gate = dataSource.getConnection()) {
            gate.setAutoCommit(false);
            try (var statement = gate.prepareStatement("select id from sys_user where id=? for update")) { statement.setLong(1, higher.getId()); statement.executeQuery().close(); }
            try {
                var first = async(pool, () -> { firstPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); return approvals.decide(firstId, true, "交叉审批一", higher.getUsername()); });
                int firstBackend = firstPid.get(15, TimeUnit.SECONDS); await(() -> blocked(firstBackend));
                var second = async(pool, () -> { secondPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); return approvals.decide(secondId, true, "交叉审批二", lower.getUsername()); });
                int secondBackend = secondPid.get(15, TimeUnit.SECONDS); await(() -> blocked(secondBackend));
                gate.commit();
                assertEquals(true, first.get(20, TimeUnit.SECONDS).get("approved")); assertEquals(true, second.get(20, TimeUnit.SECONDS).get("approved"));
                assertEquals(1, caseCount(lower)); assertEquals(1, caseCount(higher));
            } finally { gate.rollback(); }
        } finally { stop(pool); }
    }
    @Test void approvalWaitingForTransferredSourceRechecksCommittedScope() throws Exception {
        var applicant = user("HANDLER"); var receiver = user("HANDLER"); var leader = user("LEADER");
        var c = cases.create(request(null), applicant.getUsername());
        long id = approvals.apply(new ApprovalService.ApplyReq("EXTEND", null, c.getId(), Map.of("days", 10), "旧承办人延期申请"), applicant.getUsername(), false);
        var before = approval(id); var pool = Executors.newFixedThreadPool(2); var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var approvalPid = new CompletableFuture<Integer>();
        try {
            var transfer = async(pool, () -> {
                cases.transferOwner(c.getId(), receiver.getUsername(), leader.getUsername()); locked.countDown();
                try { assertTrue(release.await(20, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new RuntimeException(e); } return true;
            });
            assertTrue(locked.await(15, TimeUnit.SECONDS));
            var decision = async(pool, () -> { approvalPid.complete(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); return approvals.decide(id, true, "等待期间移交", leader.getUsername()); });
            int deciding = approvalPid.get(15, TimeUnit.SECONDS); await(() -> blocked(deciding)); release.countDown(); assertTrue(transfer.get(20, TimeUnit.SECONDS));
            var failure = assertThrows(ExecutionException.class, () -> decision.get(20, TimeUnit.SECONDS));
            assertEquals(2080, assertInstanceOf(BizException.class, failure.getCause()).code); noDecision(id, before);
            assertEquals(c.getDeadlineAt(), cases.get(c.getId()).getDeadlineAt());
        } finally { release.countDown(); stop(pool); }
    }
}