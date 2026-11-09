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
    private final JdbcTemplate jdbc;
    private final ExecutionDeadlines deadlines;

    public List<Map<String,Object>> rows(LocalDate today) {
        var rows = jdbc.queryForList("""
                with effective_decisions as (select * from case_effective_decisions(?,clock_timestamp())),
                     effective_executions as (select * from case_effective_executions(?,clock_timestamp()))
                select cf.*, cf.id as case_id, su.enabled,
                       d.fine_amount,d.recoup_amount,d.confiscate_amount,d.version_id,d.decision_type,
                       (select coalesce(sum(e.amount),0) from effective_executions e
                         where e.case_id=cf.id and e.kind in ('FINE','RECOUP','CONFISCATE')) as paid
                from case_file cf join effective_decisions d on d.case_id=cf.id
                left join sys_user su on su.username=cf.owner_user
                where cf.status in ('DECIDED','DELIVERED') and cf.archive_no is null and d.decision_type='PUNISH'
                  and ((select coalesce(sum(e.amount),0) from effective_executions e where e.case_id=cf.id and e.kind='FINE') < d.fine_amount
                    or (select coalesce(sum(e.amount),0) from effective_executions e where e.case_id=cf.id and e.kind='RECOUP') < d.recoup_amount
                    or (select coalesce(sum(e.amount),0) from effective_executions e where e.case_id=cf.id and e.kind='CONFISCATE') < d.confiscate_amount)
                order by cf.delivered_at,cf.id""",today,today);
        var alerts = new ArrayList<Map<String,Object>>();
        for (var row : rows) {
            CaseFile c = new CaseFile();
            c.setId(((Number)row.get("id")).longValue()); c.setDeliveredAt(date(row.get("delivered_at")));
            c.setStatus((String)row.get("status"));
            c.setDeferApproved(Boolean.TRUE.equals(row.get("defer_approved"))); c.setDeferUntil(date(row.get("defer_until")));
            c.setReliefDeadline(date(row.get("relief_deadline"))); c.setReliefPending(Boolean.TRUE.equals(row.get("relief_pending")));
            c.setReliefBasis((String)row.get("relief_basis")); c.setCourtEnforceStatus((String)row.get("court_enforce_status"));
            c.setCourtEnforceApplied(Boolean.TRUE.equals(row.get("court_enforce_applied")));
            c.setCourtAppliedAt(date(row.get("court_applied_at")));
            c.setCourtReceivedAt(date(row.get("court_received_at")));
            c.setCourtReceiptNo((String)row.get("court_receipt_no"));
            var summary = deadlines.summary(c,row,today);
            LocalDate delivered = (LocalDate)summary.get("deliveredAt");
            if (delivered == null || summary.get("courtStatus") != null) continue;
            LocalDate urged = deadlines.urgedAt(c.getId(),ExecutionDeadlines.version(row),delivered,today);
            row.put("urged_at",urged == null ? null : java.sql.Date.valueOf(urged));
            row.put("pay_deadline",summary.get("paymentDeadline")); row.put("apply_deadline",summary.get("applicationDeadline"));
            row.put("application_state",summary.get("state"));
            LocalDate end=(LocalDate)summary.get("applicationDeadline");
            row.put("days_left",end==null?null:ChronoUnit.DAYS.between(today,end));
            row.put("urged",urged != null);
            alerts.add(row);
        }
        return alerts;
    }

    public static LocalDate date(Object value) {
        if (value instanceof LocalDate date) return date;
        return value == null ? null : ((java.sql.Date)value).toLocalDate();
    }
}
