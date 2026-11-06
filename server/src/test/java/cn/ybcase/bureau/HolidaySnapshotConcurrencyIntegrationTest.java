package cn.ybcase.bureau;

import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real PG snapshots, committed maintenance requests and an observed in-flight calendar SELECT. */
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class HolidaySnapshotConcurrencyIntegrationTest {
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired BureauConfig config;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager transactions;

    // Each test restores these future fixture dates; no seeded or current operational calendar is changed.
    final LocalDate monday = LocalDate.of(2098, 1, 1).with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    final LocalDate saturday = monday.plusDays(5);
    List<Map<String,Object>> original;
    String token;
    record Pause(CountDownLatch reached, CountDownLatch resume) {
        Pause() { this(new CountDownLatch(1), new CountDownLatch(1)); }
    }

    @BeforeEach void isolate() {
        assertTrue(jdbc.queryForObject("select current_database()", String.class)
                .matches("ybcase_authowner_(?:test|final)_202610(?:28|29)_[a-f0-9]{8}"),
                "Only the explicitly owned disposable regression database is permitted");
        SecurityContextHolder.clearContext();
        original = new ArrayList<>(jdbc.queryForList("select * from sys_holiday where day in (?,?) order by day", monday, saturday));
        jdbc.update("delete from sys_holiday where day in (?,?)", monday, saturday);
        config.evictHolidays();
        var user = new SysUser();
        user.setUsername("calendar_" + UUID.randomUUID().toString().replace("-", ""));
        user.setRealName("日历快照回归");
        user.setPassword("unused-jwt-fixture");
        user.getRoles().add(roles.findByCode("ADMIN").orElseThrow());
        user = users.saveAndFlush(user);
        token = jwt.issue(user.getUsername(), user.getTokenVersion());
    }

    @AfterEach void restore() {
        reset(jdbc);
        SecurityContextHolder.clearContext();
        if (original != null) {
            jdbc.update("delete from sys_holiday where day in (?,?)", monday, saturday);
            for (var row : original)
                jdbc.update("insert into sys_holiday(day,kind,name) values(?,?,?)", row.get("day"), row.get("kind"), row.get("name"));
        }
        config.evictHolidays();
    }

    <T> T repeatableRead(Supplier<T> body) {
        var tx = new TransactionTemplate(transactions);
        tx.setReadOnly(true);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return tx.execute(status -> body.get());
    }
    void calendarRow(LocalDate day, boolean present) {
        if (present) jdbc.update("insert into sys_holiday(day,kind,name) values(?,'HOLIDAY','日历快照原值')", day);
        else jdbc.update("delete from sys_holiday where day=?", day);
        config.evictHolidays();
    }
    void changeThroughApi(LocalDate day, boolean present) throws Exception {
        var req = present ? post("/api/bureau/holidays").contentType("application/json")
                .content(mapper.writeValueAsBytes(Map.of("day", day.toString(), "kind", "HOLIDAY", "name", "日历快照维护")))
                : delete("/api/bureau/holidays/{day}", day);
        var response = mvc.perform(req.header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        assertEquals(0, mapper.readTree(response.getContentAsString()).path("code").asInt(), response.getContentAsString());
        assertEquals(present ? 1 : 0, jdbc.queryForObject("select count(*) from sys_holiday where day=?", Integer.class, day));
    }
    static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(20, TimeUnit.SECONDS), "Calendar worker was not released"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }
    static void stop(ExecutorService pool) throws InterruptedException {
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));
    }
    void assertMondayCalendar(boolean holiday) {
        assertEquals(!holiday, config.isWorkingDay(monday));
        assertEquals(monday.plusDays(holiday ? 1 : 0), config.plusWorkdays(monday.minusDays(3), 1));
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void oldRepeatableReadCannotRepublishCalendarAfterCommittedMaintenance(boolean initialHoliday) throws Exception {
        calendarRow(monday, initialHoliday);
        var pause = new Pause();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var old = pool.submit(() -> repeatableRead(() -> {
                assertEquals(initialHoliday ? 1 : 0, jdbc.queryForObject("select count(*) from sys_holiday where day=?", Integer.class, monday));
                pause.reached().countDown();
                await(pause.resume());
                assertMondayCalendar(initialHoliday); // An already established legal RR remains on its old calendar.
                return null;
            }));
            assertTrue(pause.reached().await(15, TimeUnit.SECONDS));
            changeThroughApi(monday, !initialHoliday); // Includes the actual afterCommit invalidation.
            pause.resume().countDown();
            old.get(25, TimeUnit.SECONDS);
            assertMondayCalendar(!initialHoliday); // A fresh reader must not inherit the old transaction's snapshot.
        } finally { pause.resume().countDown(); stop(pool); }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void oldRepeatableReadDoesNotConsumeCalendarCachedByANewerReader(boolean initialHoliday) throws Exception {
        calendarRow(monday, initialHoliday);
        var pause = new Pause();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var old = pool.submit(() -> repeatableRead(() -> {
                assertEquals(initialHoliday ? 1 : 0, jdbc.queryForObject("select count(*) from sys_holiday where day=?", Integer.class, monday));
                pause.reached().countDown();
                await(pause.resume());
                assertMondayCalendar(initialHoliday);
                return null;
            }));
            assertTrue(pause.reached().await(15, TimeUnit.SECONDS));
            changeThroughApi(monday, !initialHoliday);
            assertMondayCalendar(!initialHoliday); // Warm the shared nontransactional cache with the new calendar.
            pause.resume().countDown();
            old.get(25, TimeUnit.SECONDS);
            assertMondayCalendar(!initialHoliday);
        } finally { pause.resume().countDown(); stop(pool); }
    }

    @Test void rolledBackCalendarCannotEscapeIntoAnotherRequest() {
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            jdbc.update("insert into sys_holiday(day,kind,name) values(?,'HOLIDAY','必须回滚')", monday);
            assertMondayCalendar(true);
            status.setRollbackOnly();
        });
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_holiday where day=?", Integer.class, monday));
        assertMondayCalendar(false);
    }

    @Test void transactionReadsItsOwnCalendarChangeDespiteAWarmSharedCache() {
        assertMondayCalendar(false);
        var tx = new TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            jdbc.update("insert into sys_holiday(day,kind,name) values(?,'HOLIDAY','事务本身可见')", monday);
            assertMondayCalendar(true);
            status.setRollbackOnly();
        });
        assertMondayCalendar(false);
    }

    @Test void committedCalendarAddAndDeleteImmediatelyUpdateNormalReaders() throws Exception {
        assertMondayCalendar(false);
        changeThroughApi(monday, true);
        assertMondayCalendar(true);
        changeThroughApi(monday, false);
        assertMondayCalendar(false);
    }

    @Test void shiftWorkOverrideAlsoBelongsToTheReadersSnapshot() throws Exception {
        var pause = new Pause();
        var pool = Executors.newSingleThreadExecutor();
        try {
            var old = pool.submit(() -> repeatableRead(() -> {
                assertEquals(0, jdbc.queryForObject("select count(*) from sys_holiday where day=?", Integer.class, saturday));
                pause.reached().countDown();
                await(pause.resume());
                assertFalse(config.isWorkingDay(saturday));
                assertEquals(monday.plusWeeks(1), config.plusWorkdays(saturday.minusDays(1), 1));
                return null;
            }));
            assertTrue(pause.reached().await(15, TimeUnit.SECONDS));
            var response = mvc.perform(post("/api/bureau/holidays/batch").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content(mapper.writeValueAsBytes(List.of(Map.of("day", saturday.toString(), "kind", "SHIFT_WORK", "name", "合成补班")))))
                    .andReturn().getResponse();
            assertEquals(0, mapper.readTree(response.getContentAsString()).path("code").asInt());
            assertTrue(config.isWorkingDay(saturday));
            assertEquals(saturday, config.plusWorkdays(saturday.minusDays(1), 1));
            pause.resume().countDown();
            old.get(25, TimeUnit.SECONDS);
        } finally { pause.resume().countDown(); stop(pool); }
    }

    @Test void inFlightOrdinaryLoadCannotUndoACommittedInvalidation() throws Exception {
        var pause = new Pause();
        var pool = Executors.newFixedThreadPool(2);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod(); // Execute the real SELECT; only delay publication of its result.
            if (invocation.getArgument(0, String.class).equals("select day, kind from sys_holiday")) {
                pause.reached().countDown();
                await(pause.resume());
            }
            return result;
        }).when(jdbc).queryForList(anyString());
        try {
            var old = pool.submit(() -> config.isWorkingDay(monday));
            assertTrue(pause.reached().await(15, TimeUnit.SECONDS));
            var mutation = pool.submit(() -> { changeThroughApi(monday, true); return null; });
            // Complete the real afterCommit callback before allowing the old SELECT to publish.
            // Invalidation must not wait for a slow ordinary reader that may itself need a pool connection.
            mutation.get(15, TimeUnit.SECONDS);
            pause.resume().countDown();
            assertTrue(old.get(25, TimeUnit.SECONDS));
            assertMondayCalendar(true);
        } finally { pause.resume().countDown(); stop(pool); }
    }
}
