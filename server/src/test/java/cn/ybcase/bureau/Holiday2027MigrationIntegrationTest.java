package cn.ybcase.bureau;

import cn.ybcase.bureau.service.BureauConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class Holiday2027MigrationIntegrationTest {
    private static final String MIGRATION = "db/migration/V67__holiday_2027_calendar.sql";

    @Autowired JdbcTemplate jdbc;
    @Autowired BureauConfig config;

    @BeforeEach
    void requireIsolatedDatabase() {
        String database = jdbc.queryForObject("select current_database()", String.class);
        assertTrue(database.matches("ybcase_authowner_test_20261028_[a-f0-9]{8}"),
                "Calendar migration tests require an owned isolated database");
    }

    @Test
    void flywayInstallsAllOfficialHolidayAndShiftWorkRows() {
        assertEquals(29, jdbc.queryForObject("""
                select count(*) from public.sys_holiday
                where day >= date '2027-01-01' and day < date '2028-01-01' and kind='HOLIDAY'
                """, Integer.class));
        assertEquals(List.of(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 14),
                        LocalDate.of(2027, 5, 8), LocalDate.of(2027, 9, 26), LocalDate.of(2027, 10, 9)),
                jdbc.queryForList("""
                        select day from public.sys_holiday
                        where day >= date '2027-01-01' and day < date '2028-01-01' and kind='SHIFT_WORK'
                        order by day
                        """, java.sql.Date.class).stream().map(java.sql.Date::toLocalDate).toList());
        assertEquals(34, jdbc.queryForObject("""
                select count(*) from public.sys_holiday
                where day >= date '2027-01-01' and day < date '2028-01-01'
                """, Integer.class));
        assertEquals(1, jdbc.queryForObject("""
                select count(*) from public.flyway_schema_history where version='67' and success
                """, Integer.class));
        assertFalse(config.missingHolidayYears().contains(2027));
        assertEquals(LocalDate.of(2027, 1, 4), config.plusWorkdays(LocalDate.of(2026, 12, 31), 1));
        assertEquals(LocalDate.of(2027, 2, 14), config.plusWorkdays(LocalDate.of(2027, 2, 4), 1));
    }

    @Test
    void migrationPreservesManualCalendarRowsAndCanBeReplayed() throws Exception {
        isolatedCalendar();
        jdbc.update("""
                insert into sys_holiday(day,kind,name) values
                ('2026-01-01','HOLIDAY','既有元旦名称'),
                ('2027-03-01','HOLIDAY','本地自定义休假'),
                ('2027-01-31','HOLIDAY','管理员已核定同日配置')
                """);
        List<Map<String, Object>> manual = jdbc.queryForList("select * from sys_holiday order by day");
        applyMigration();
        assertEquals(36, jdbc.queryForObject("select count(*) from sys_holiday", Integer.class));
        for (var row : manual) {
            assertEquals(row, jdbc.queryForMap("select * from sys_holiday where day=?", row.get("day")));
        }
        var first = jdbc.queryForList("select * from sys_holiday order by day");
        applyMigration();
        assertEquals(first, jdbc.queryForList("select * from sys_holiday order by day"));
    }

    @Test
    void newCalendarDoesNotRewriteStoredClueOrReviewDeadlines() throws Exception {
        String reference = "CAL-2027-" + UUID.randomUUID().toString().substring(0, 8);
        long clue = jdbc.queryForObject("""
                insert into public.case_clue(clue_no,source,content,suspect_name,suspect_type,received_at,deadline_at)
                values(?,'COMPLAINT','日历迁移期限保全测试','合成机构','PROVIDER',date '2026-12-28',date '2027-01-18')
                returning id
                """, Long.class, reference);
        long caseId = jdbc.queryForObject("""
                insert into public.case_file(case_no,name,cause_id,party_name,party_type,status,filed_at,deadline_at)
                values(?,'日历迁移合成案件',(select min(id) from public.case_cause where subject_type='PROVIDER'),
                       '合成机构','PROVIDER','REPORTED',date '2026-12-28',date '2027-04-01') returning id
                """, Long.class, reference);
        long review = jdbc.queryForObject("""
                insert into public.case_review(case_id,required_reason,submitted_at,deadline_at)
                values(?,'日历迁移期限保全',date '2026-12-31',date '2027-01-14') returning id
                """, Long.class, caseId);
        var clueBefore = jdbc.queryForMap("select * from public.case_clue where id=?", clue);
        var caseBefore = jdbc.queryForMap("select * from public.case_file where id=?", caseId);
        var reviewBefore = jdbc.queryForMap("select * from public.case_review where id=?", review);
        isolatedCalendar();
        applyMigration();
        assertEquals(LocalDate.of(2027, 1, 19), config.plusWorkdays(LocalDate.of(2026, 12, 28), 15));
        assertEquals(LocalDate.of(2027, 1, 15), config.plusWorkdays(LocalDate.of(2026, 12, 31), 10));
        assertEquals(clueBefore, jdbc.queryForMap("select * from public.case_clue where id=?", clue));
        assertEquals(caseBefore, jdbc.queryForMap("select * from public.case_file where id=?", caseId));
        assertEquals(reviewBefore, jdbc.queryForMap("select * from public.case_review where id=?", review));
    }

    private void isolatedCalendar() {
        String schema = "holiday_2027_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.execute("create schema " + schema);
        jdbc.execute("set local search_path to " + schema + ", public");
        jdbc.execute("create table sys_holiday (like public.sys_holiday including all)");
    }

    private void applyMigration() throws Exception {
        try (var stream = new ClassPathResource(MIGRATION).getInputStream()) {
            jdbc.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
