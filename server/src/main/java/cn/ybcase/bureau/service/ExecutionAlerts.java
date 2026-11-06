package cn.ybcase.bureau.service;

import cn.ybcase.bureau.entity.CaseFile;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
                select cf.*, cf.id as case_id, su.enabled,
                       d.fine_amount, d.recoup_amount, d.confiscate_amount,
                       (select coalesce(sum(e.amount),0) from case_execution e
                         where e.case_id=cf.id and e.kind in ('FINE','RECOUP','CONFISCATE')) as paid,
                       (select max(del.delivered_at) from case_delivery del join case_document doc on doc.id=del.document_id
                         where del.case_id=cf.id and doc.case_id=cf.id and doc.doc_type='URGE_LETTER') as urged_at
                from case_file cf join case_decision d on d.case_id=cf.id
                left join sys_user su on su.username=cf.owner_user
                where cf.status='DELIVERED' and cf.delivered_at is not null and d.decision_type='PUNISH'
                  and cf.court_enforce_applied=false
                  and ((select coalesce(sum(e.amount),0) from case_execution e where e.case_id=cf.id and e.kind='FINE') < d.fine_amount
                    or (select coalesce(sum(e.amount),0) from case_execution e where e.case_id=cf.id and e.kind='RECOUP') < d.recoup_amount
                    or (select coalesce(sum(e.amount),0) from case_execution e where e.case_id=cf.id and e.kind='CONFISCATE') < d.confiscate_amount)
                order by cf.delivered_at,cf.id""");
        for (var row : rows) {
            CaseFile c = new CaseFile();
            c.setId(((Number)row.get("id")).longValue()); c.setDeliveredAt(date(row.get("delivered_at")));
            c.setDeferApproved(Boolean.TRUE.equals(row.get("defer_approved"))); c.setDeferUntil(date(row.get("defer_until")));
            c.setReliefDeadline(date(row.get("relief_deadline"))); c.setReliefPending(Boolean.TRUE.equals(row.get("relief_pending")));
            c.setReliefBasis((String)row.get("relief_basis")); c.setCourtEnforceStatus((String)row.get("court_enforce_status"));
            var summary = deadlines.summary(c,today);
            row.put("pay_deadline",summary.get("paymentDeadline")); row.put("apply_deadline",summary.get("applicationDeadline"));
            row.put("application_state",summary.get("state"));
            LocalDate end=(LocalDate)summary.get("applicationDeadline");
            row.put("days_left",end==null?null:ChronoUnit.DAYS.between(today,end));
            row.put("urged",row.get("urged_at")!=null);
        }
        return rows;
    }

    public static LocalDate date(Object value) {
        if (value instanceof LocalDate date) return date;
        return value == null ? null : ((java.sql.Date)value).toLocalDate();
    }
}
