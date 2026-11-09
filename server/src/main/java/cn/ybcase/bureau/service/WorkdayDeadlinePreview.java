package cn.ybcase.bureau.service;

import cn.ybcase.core.security.AdminReadAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Read-only comparison against the current rule and calendar, with no inferred historical configuration. */
@Service
@RequiredArgsConstructor
public class WorkdayDeadlinePreview {
    private static final int MAX_PREVIEW_DAYS = 3660;
    private static final String CANDIDATES = """
            with candidates as (
                select 'CLUE' as kind, id, clue_no as reference_no, received_at as started_at, deadline_at
                from case_clue where status='PENDING' and not extended and excluded_days=0
                union all
                select 'LEGAL_REVIEW', r.id, c.case_no, r.submitted_at, r.deadline_at
                from case_file c
                cross join lateral (
                    select id, submitted_at, deadline_at, reviewed_at from case_review
                    where case_id=c.id order by id desc limit 1
                ) r
                where c.procedure_type='NORMAL' and c.status in ('REPORTED','NOTIFIED')
                  and c.archive_no is null and c.decided_at is null and r.reviewed_at is null
                  and r.submitted_at is not null and r.deadline_at is not null
                  and not exists (select 1 from case_decision d where d.case_id=c.id)
            )
            """;

    private final JdbcTemplate jdbc;
    private final BureauConfig config;
    private final AdminReadAccess access;

    public record Comparison(String kind, long id, String referenceNo, LocalDate startedAt,
                             LocalDate storedDeadline, LocalDate recalculatedDeadline,
                             Integer days, String unit, String status, List<Integer> missingYears) {}
    public record Page(List<Comparison> rows, long total, long excludedAdjustedClues, int page, int size) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page preview(int requestedPage, int requestedSize) {
        access.requireCurrentAdmin();
        int page = Math.max(1, requestedPage);
        int size = Math.min(100, Math.max(1, requestedSize));
        var params = config.readParameters();
        Rule clue = Rule.current(params, "clue_verify", 15);
        Rule review = Rule.current(params, "legal_review", 10);
        var calendar = config.holidays();
        long total = jdbc.queryForObject(CANDIDATES + "select count(*) from candidates", Long.class);
        long excluded = jdbc.queryForObject("""
                select count(*) from case_clue where status='PENDING' and (extended or excluded_days<>0)
                """, Long.class);
        var rows = jdbc.query(CANDIDATES + """
                select * from candidates order by started_at desc, kind, id desc limit ? offset ?
                """, (rs, rowNum) -> {
            String kind = rs.getString("kind");
            return compare(kind, rs.getLong("id"), rs.getString("reference_no"),
                    rs.getObject("started_at", LocalDate.class), rs.getObject("deadline_at", LocalDate.class),
                    "CLUE".equals(kind) ? clue : review, calendar);
        }, size, (long) (page - 1) * size);
        return new Page(rows, total, excluded, page, size);
    }

    private record Rule(Integer days, String unit) {
        static Rule current(Map<String, String> params, String key, int fallbackDays) {
            Integer days;
            try {
                days = Integer.valueOf(params.getOrDefault(key + "_days", String.valueOf(fallbackDays)).trim());
            } catch (NumberFormatException ex) {
                days = null;
            }
            String value = params.getOrDefault(key + "_day_unit", "WORKDAY");
            String unit = "NATURAL".equalsIgnoreCase(value) ? "NATURAL"
                    : "WORKDAY".equalsIgnoreCase(value) ? "WORKDAY" : null;
            return new Rule(days, unit);
        }
    }

    private Comparison compare(String kind, long id, String reference, LocalDate started, LocalDate stored,
                               Rule rule, BureauConfig.Holidays calendar) {
        if (rule.unit() == null || rule.days() == null || rule.days() < 0 || rule.days() > MAX_PREVIEW_DAYS)
            return new Comparison(kind, id, reference, started, stored, null, rule.days(), rule.unit(),
                    "UNSUPPORTED_RULE", List.of());
        boolean natural = "NATURAL".equals(rule.unit());
        LocalDate recalculated = natural ? started.plusDays(rule.days()) : calendar.plus(started, rule.days());
        List<Integer> missing = natural || rule.days() == 0 ? List.of()
                : IntStream.rangeClosed(started.plusDays(1).getYear(), recalculated.getYear())
                        .filter(year -> !calendar.hasHolidayRecords(year)).boxed().toList();
        String status = !missing.isEmpty() ? "MISSING_CALENDAR" : recalculated.equals(stored) ? "SAME" : "DIFFERENT";
        return new Comparison(kind, id, reference, started, stored, missing.isEmpty() ? recalculated : null,
                rule.days(), rule.unit(), status, missing);
    }
}
