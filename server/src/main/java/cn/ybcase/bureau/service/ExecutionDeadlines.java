package cn.ybcase.bureau.service;

import cn.ybcase.bureau.entity.CaseFile;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** The relief deadline is a recorded legal fact, never inferred from the payment deadline. */
@Service
@RequiredArgsConstructor
public class ExecutionDeadlines {
    private final JdbcTemplate jdbc;
    private final BureauConfig config;

    public LocalDate paymentDeadline(CaseFile c) {
        if (c.getDeliveredAt() == null) return null;
        LocalDate deadline = c.getDeliveredAt().plusDays(config.intVal("payment_days", 15));
        if (Boolean.TRUE.equals(c.getDeferApproved())) {
            java.sql.Date plan = jdbc.queryForObject("select max(due_at) from case_installment where case_id = ?",
                    java.sql.Date.class, c.getId());
            LocalDate deferred = c.getDeferUntil();
            if (plan != null && (deferred == null || plan.toLocalDate().isAfter(deferred))) deferred = plan.toLocalDate();
            // Approval alone is insufficient: the approved period must be explicitly recorded.
            if (deferred == null) return null;
            if (deferred.isAfter(deadline)) deadline = deferred;
        }
        return deadline;
    }

    public LocalDate applicationStart(CaseFile c) {
        LocalDate payment = paymentDeadline(c);
        if (payment == null || c.getReliefDeadline() == null || c.getReliefBasis() == null
                || c.getReliefBasis().isBlank() || Boolean.TRUE.equals(c.getReliefPending())) return null;
        return payment.isAfter(c.getReliefDeadline()) ? payment : c.getReliefDeadline();
    }

    public LocalDate applicationDeadline(CaseFile c) {
        LocalDate start = applicationStart(c);
        return start == null ? null : start.plusMonths(config.intVal("court_application_months", 3));
    }

    public Map<String, Object> summary(CaseFile c, LocalDate today) {
        var result = new LinkedHashMap<String, Object>();
        LocalDate payment = paymentDeadline(c);
        LocalDate start = applicationStart(c);
        LocalDate deadline = applicationDeadline(c);
        result.put("paymentDeadline", payment);
        result.put("reliefDeadline", c.getReliefDeadline());
        result.put("applicationStart", start);
        result.put("applicationDeadline", deadline);
        result.put("reliefPending", c.getReliefPending());
        String state = Boolean.TRUE.equals(c.getCourtEnforceApplied()) ? "APPLIED"
                : Boolean.TRUE.equals(c.getReliefPending()) ? "RELIEF_PENDING"
                : start == null ? "UNCONFIRMED" : !today.isAfter(start) ? "WAITING"
                : today.isAfter(deadline) ? "EXPIRED" : "OPEN";
        if ("ACCEPTED".equals(c.getCourtEnforceStatus())) state = "ACCEPTED";
        result.put("state", state);
        return result;
    }
}
