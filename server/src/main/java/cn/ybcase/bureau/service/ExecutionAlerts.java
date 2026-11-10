package cn.ybcase.bureau.service;

import cn.ybcase.bureau.entity.CaseFile;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared facts for reminders and supervision; absent relief facts never imply loss of rights. */
@Service
@RequiredArgsConstructor
public class ExecutionAlerts {
    private static final String EXECUTION_CASES = """
            with effective_decisions as (select * from case_effective_decisions(?,current_timestamp)),
                 balances as (
                    select case_id, coalesce(sum(amount) filter(where kind='FINE'),0) as fine,
                           coalesce(sum(amount) filter(where kind='RECOUP'),0) as recoup,
                           coalesce(sum(amount) filter(where kind='CONFISCATE'),0) as confiscate
                    from case_effective_executions(?,current_timestamp) group by case_id
                 )
            select c.*, c.id as case_id, u.enabled, d.fine_amount,d.recoup_amount,d.confiscate_amount,
                   d.version_id,d.decision_type, coalesce(b.fine,0)+coalesce(b.recoup,0)+coalesce(b.confiscate,0) as paid,
                   (coalesce(b.fine,0)<d.fine_amount or coalesce(b.recoup,0)<d.recoup_amount
                     or coalesce(b.confiscate,0)<d.confiscate_amount) as outstanding
            from case_file c join effective_decisions d on d.case_id=c.id
            left join balances b on b.case_id=c.id
            left join sys_user u on u.username=c.owner_user
            where ((c.status in ('DECIDED','DELIVERED') and c.archive_no is null)
                    or (c.status='CLOSED' and d.version_id is not null))
            """;
    private static final String OUTSTANDING = """
            (coalesce(b.fine,0)<d.fine_amount or coalesce(b.recoup,0)<d.recoup_amount
              or coalesce(b.confiscate,0)<d.confiscate_amount)
            """;
    private final JdbcTemplate jdbc;
    private final ExecutionDeadlines deadlines;

    public List<Map<String,Object>> rows(LocalDate today) {
        var rows = jdbc.queryForList(EXECUTION_CASES + " and d.decision_type='PUNISH' and "
                + OUTSTANDING + " order by c.delivered_at,c.id", today, today);
        var alerts = new ArrayList<Map<String,Object>>();
        for (var row : rows) {
            var c = caseFile(row);
            var summary = deadlines.summary(c,row,today);
            LocalDate delivered = (LocalDate)summary.get("deliveredAt");
            var court = (String) summary.get("courtStatus");
            if (delivered == null || court != null && !"RETURNED".equals(court) && !"REJECTED".equals(court)) continue;
            LocalDate urged = deadlines.urgedAt(c.getId(),ExecutionDeadlines.version(row),delivered,today);
            row.put("urged_at",urged == null ? null : java.sql.Date.valueOf(urged));
            row.put("pay_deadline",summary.get("paymentDeadline")); row.put("apply_deadline",summary.get("applicationDeadline"));
            row.put("application_state",summary.get("state"));
            row.put("active_court_status",court);
            row.put("active_court_round_id",summary.get("courtRoundId"));
            LocalDate end=(LocalDate)summary.get("applicationDeadline");
            row.put("days_left",end==null?null:ChronoUnit.DAYS.between(today,end));
            row.put("urged",urged != null);
            alerts.add(row);
        }
        return alerts;
    }

    record Work(long caseId, String taskKey, String title, String stage, LocalDate dueAt, String link) {}

    /** Only cases with an independent decision or a court application need a more specific task than the base workflow. */
    List<Work> work(LocalDate today, String scope, List<Object> scopeArgs, String assignment, List<Object> assignmentArgs) {
        var args = new ArrayList<Object>(List.of(today, today));
        args.addAll(scopeArgs);
        args.addAll(assignmentArgs);
        var cases = jdbc.queryForList(EXECUTION_CASES + """
                 and (d.version_id is not null or c.court_enforce_applied
                    or exists (select 1 from case_court_round r where r.case_id=c.id
                               and r.decision_version_id is not distinct from d.version_id))
                 and
                """ + scope + " and " + assignment, args.toArray());
        var tasks = new ArrayList<Work>();
        for (var row : cases) {
            var c = caseFile(row);
            var summary = deadlines.summary(c, row, today);
            var version = ExecutionDeadlines.version(row);
            var round = (Number) summary.get("courtRoundId");
            String court = (String) summary.get("courtStatus");
            String kind, title, stage;
            LocalDate due = null;
            boolean archived = c.getArchiveNo() != null || "CLOSED".equals(c.getStatus());
            if (version != null && summary.get("deliveredAt") == null && !"REVOKED".equals(row.get("decision_type"))) {
                kind = "DELIVERY"; title = "登记当前决定版本的实际送达"; stage = "新版待送达";
            } else if (Boolean.TRUE.equals(summary.get("reliefPending"))) {
                kind = "RELIEF"; title = "核对未办结救济事项"; stage = "救济处理中";
            } else if (!Boolean.TRUE.equals(row.get("outstanding")) || "ACCEPTED".equals(summary.get("state"))) {
                if (archived) continue;
                kind = "CLOSE"; title = "核对当前决定的结案条件"; stage = "待结案核对";
            } else if ("RETURNED".equals(court)) {
                kind = "RETURNED"; title = "核对法院退回补正事项"; stage = "法院退回补正";
                due = (LocalDate) summary.get("applicationDeadline");
            } else if ("REJECTED".equals(court)) {
                kind = "REJECTED"; title = "核处法院不予受理结果"; stage = "待人工核处";
            } else if (court != null) {
                kind = "COURT_RECEIPT"; title = "核对本轮法院实际回执"; stage = "待回执核对";
            } else {
                kind = "EXECUTION"; title = "核对当前决定的执行记录和期限依据"; stage = "待执行核对";
                due = (LocalDate) summary.get("paymentDeadline");
            }
            String key = "EXECUTION:" + c.getId() + ":" + (version == null ? "ORIGINAL" : version)
                    + ":" + (round == null ? "0" : round) + ":" + kind;
            String link = "DELIVERY".equals(kind) ? "/case/detail/" + c.getId() + "?deliveryVersion=" + version
                    : "CLOSE".equals(kind) ? "/case/detail/" + c.getId()
                    : executionLink(c.getId(), version, round);
            tasks.add(new Work(c.getId(), key, title, stage, due, link));
        }
        return tasks;
    }

    static String executionLink(long caseId, Long version, Number round) {
        return "/case/detail/" + caseId + "?executionVersion=" + (version == null ? 0 : version)
                + (round == null ? "" : "&courtRound=" + round);
    }

    private static CaseFile caseFile(Map<String,Object> row) {
        var c = new CaseFile();
        c.setId(((Number)row.get("id")).longValue());
        c.setArchiveNo((String) row.get("archive_no"));
        c.setDeliveredAt(date(row.get("delivered_at")));
        c.setStatus((String)row.get("status"));
        c.setDeferApproved(Boolean.TRUE.equals(row.get("defer_approved")));
        c.setDeferUntil(date(row.get("defer_until")));
        c.setReliefDeadline(date(row.get("relief_deadline")));
        c.setReliefPending(Boolean.TRUE.equals(row.get("relief_pending")));
        c.setReliefBasis((String)row.get("relief_basis"));
        c.setCourtEnforceStatus((String)row.get("court_enforce_status"));
        c.setCourtEnforceApplied(Boolean.TRUE.equals(row.get("court_enforce_applied")));
        c.setCourtAppliedAt(date(row.get("court_applied_at")));
        c.setCourtReceivedAt(date(row.get("court_received_at")));
        c.setCourtReceiptNo((String)row.get("court_receipt_no"));
        return c;
    }

    public static LocalDate date(Object value) {
        if (value instanceof LocalDate date) return date;
        return value == null ? null : ((java.sql.Date)value).toLocalDate();
    }
}
