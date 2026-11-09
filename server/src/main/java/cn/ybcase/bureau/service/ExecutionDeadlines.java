package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** The relief deadline is a recorded legal fact, never inferred from the payment deadline. */
@Service
@RequiredArgsConstructor
public class ExecutionDeadlines {
    private final JdbcTemplate jdbc;
    private final BureauConfig config;

    record Delivery(Long id, LocalDate date) {}
    record CourtRound(String status, LocalDate receivedAt, String receiptNo) {
        boolean accepted(LocalDate today) {
            return "ACCEPTED".equals(status) && receivedAt != null && !receivedAt.isAfter(today)
                    && receiptNo != null && !receiptNo.isBlank();
        }
    }
    record Window(Delivery delivery, LocalDate payment, LocalDate relief, LocalDate start, LocalDate end,
                  boolean reliefPending, Long basisId) {}

    static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }

    Map<String,Object> effectiveDecision(long caseId, LocalDate day) {
        var rows = jdbc.queryForList("select * from case_effective_decisions(?,clock_timestamp()) where case_id=?", day, caseId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    static Long version(Map<String,Object> decision) {
        return decision.get("version_id") instanceof Number id ? id.longValue() : null;
    }

    Delivery effectiveDelivery(CaseFile c, Map<String,Object> decision, LocalDate day) {
        if (decision.isEmpty()) return null;
        Long versionId = version(decision);
        if (versionId == null) {
            LocalDate delivered = c.getDeliveredAt();
            return delivered != null && !delivered.isAfter(day)
                    && ("DELIVERED".equals(c.getStatus()) || "CLOSED".equals(c.getStatus()))
                    ? new Delivery(null, delivered) : null;
        }
        var rows = jdbc.queryForList("""
                select p.id,p.delivered_at
                from case_supplement_delivery p join case_supplement_document d on d.id=p.document_id
                join case_supplement_bundle b on b.id=d.bundle_id and b.case_id=d.case_id and b.status='APPROVED'
                join case_decision_version v on v.id=d.decision_version_id and v.case_id=d.case_id
                where d.case_id=? and d.decision_version_id=? and d.doc_type='DECISION'
                  and p.delivered_at>=v.effective_at and p.delivered_at<=?
                order by p.delivered_at,p.id limit 1
                """,c.getId(),versionId,day);
        return rows.isEmpty() ? null : new Delivery(((Number)rows.getFirst().get("id")).longValue(),
                date(rows.getFirst().get("delivered_at")));
    }

    boolean openRemedy(long caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from case_remedy_status where case_id=? and status='OPEN')",
                Boolean.class,caseId));
    }

    boolean fullyExecuted(long caseId, Map<String,Object> decision, LocalDate day) {
        if (decision.isEmpty()) return false;
        var totals = jdbc.queryForMap("""
                select coalesce(sum(amount) filter (where kind='FINE'),0) as fine_amount,
                       coalesce(sum(amount) filter (where kind='RECOUP'),0) as recoup_amount,
                       coalesce(sum(amount) filter (where kind='CONFISCATE'),0) as confiscate_amount
                from case_effective_executions(?,clock_timestamp()) where case_id=?
                """,day,caseId);
        for (var total : totals.entrySet()) {
            if (((BigDecimal)total.getValue()).compareTo((BigDecimal)decision.get(total.getKey())) < 0) return false;
        }
        return true;
    }

    CourtRound currentCourtRound(CaseFile c, Map<String,Object> decision, LocalDate day) {
        Long versionId = version(decision);
        var rows = jdbc.queryForList("""
                select coalesce(p.status,'APPLIED') as status,p.received_at,p.receipt_no
                from case_court_round r join case_continuation_request a on a.id=r.request_id and a.status='APPROVED'
                left join case_court_receipt p on p.round_id=r.id and p.received_at<=?
                where r.case_id=? and r.applied_at<=? and r.decision_version_id is not distinct from ?::bigint
                order by r.round_no desc limit 1
                """,day,c.getId(),day,versionId);
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            return new CourtRound((String)row.get("status"),date(row.get("received_at")),(String)row.get("receipt_no"));
        }
        if (versionId == null && Boolean.TRUE.equals(c.getCourtEnforceApplied())
                && (c.getCourtAppliedAt() == null || !c.getCourtAppliedAt().isAfter(day))) {
            String status = c.getCourtEnforceStatus();
            return new CourtRound(status == null ? "APPLIED" : status,c.getCourtReceivedAt(),c.getCourtReceiptNo());
        }
        return null;
    }

    boolean courtAccepted(CaseFile c, Map<String,Object> decision, LocalDate day) {
        var round = currentCourtRound(c,decision,day);
        return round != null && round.accepted(day);
    }

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

    Window window(CaseFile c, Map<String,Object> decision, LocalDate day) {
        Delivery delivery = effectiveDelivery(c,decision,day);
        Long versionId = version(decision);
        LocalDate payment = null;
        LocalDate relief = null;
        Long basisId = null;
        boolean pending = openRemedy(c.getId());
        if (versionId == null) {
            if (delivery != null) payment = paymentDeadline(c);
            if (c.getReliefBasis() != null && !c.getReliefBasis().isBlank()) relief = c.getReliefDeadline();
            pending |= Boolean.TRUE.equals(c.getReliefPending());
        } else if (delivery != null) {
            payment = delivery.date().plusDays(config.intVal("payment_days",15));
            var rows = jdbc.queryForList("""
                    select id,relief_deadline,relief_pending,defer_until from case_version_execution_basis
                    where decision_version_id=? and delivery_id=? order by id desc limit 1
                    """,versionId,delivery.id());
            if (!rows.isEmpty()) {
                var row = rows.getFirst();
                basisId = ((Number)row.get("id")).longValue();
                relief = date(row.get("relief_deadline"));
                pending |= Boolean.TRUE.equals(row.get("relief_pending"));
                LocalDate defer = date(row.get("defer_until"));
                if (defer != null && defer.isAfter(payment)) payment = defer;
            }
        }
        LocalDate start = payment == null || relief == null || pending ? null : later(payment,relief);
        LocalDate end = start == null ? null : start.plusMonths(config.intVal("court_application_months",3));
        return new Window(delivery,payment,relief,start,end,pending,basisId);
    }

    LocalDate urgedAt(long caseId, Long versionId, LocalDate delivered, LocalDate applied) {
        java.sql.Date date = versionId == null ? jdbc.queryForObject("""
                select max(d.delivered_at) from case_delivery d join case_document doc on doc.id=d.document_id
                where d.case_id=? and doc.case_id=d.case_id and doc.doc_type='URGE_LETTER' and d.delivered_at between ? and ?
                """,java.sql.Date.class,caseId,delivered,applied)
                : jdbc.queryForObject("""
                select max(p.delivered_at) from case_supplement_delivery p
                join case_supplement_document d on d.id=p.document_id
                join case_supplement_bundle b on b.id=d.bundle_id and b.case_id=d.case_id and b.status='APPROVED'
                where d.case_id=? and d.decision_version_id=? and d.doc_type='URGE_LETTER' and p.delivered_at between ? and ?
                """,java.sql.Date.class,caseId,versionId,delivered,applied);
        return date(date);
    }

    void requireCourtApplication(CaseFile c, Map<String,Object> decision, LocalDate applied, LocalDate day,
                                 boolean continuation) {
        Window window = window(c,decision,day);
        if (!"PUNISH".equals(decision.get("decision_type")))
            throw applicationFailure(continuation,2142,"仅未履行的有效处罚决定可申请法院强制执行");
        if (window.delivery() == null || applied.isBefore(window.delivery().date()))
            throw applicationFailure(continuation,2042,"申请日期须有当前有效决定的送达依据，且不得早于送达");
        if (fullyExecuted(c.getId(),decision,day))
            throw applicationFailure(continuation,2142,"该决定已执行完毕，无需申请法院强制执行");
        if (window.reliefPending())
            throw applicationFailure(continuation,2142,"尚有未结束的复议或诉讼，请先核实救济结果与期限依据");
        if (window.start() == null)
            throw applicationFailure(continuation,2142,"须由法制或负责人登记当前决定的救济期限依据；暂缓分期须登记期限");
        if (!applied.isAfter(window.start()))
            throw applicationFailure(continuation,2142,"缴款或法定救济期限尚未届满（"+window.start()+"）");
        LocalDate urged = urgedAt(c.getId(),version(decision),window.delivery().date(),applied);
        int urgeDays = config.intVal("court_urge_days",10);
        if (urged == null)
            throw applicationFailure(continuation,2042,"申请法院强制执行前须登记对应决定的催告文书送达");
        if (applied.isBefore(urged.plusDays(urgeDays)))
            throw applicationFailure(continuation,2042,"催告后须满 "+urgeDays+" 日当事人仍不履行方可申请");
        if (applied.isAfter(window.end()))
            throw applicationFailure(continuation,2142,"已超过登记的法定申请期限，请由法制核实后另行处理");
    }

    public Map<String, Object> summary(CaseFile c, LocalDate day) {
        return summary(c,effectiveDecision(c.getId(),day),day);
    }

    Map<String, Object> summary(CaseFile c, Map<String,Object> decision, LocalDate day) {
        var result = new LinkedHashMap<String, Object>();
        var window = window(c,decision,day);
        var round = currentCourtRound(c,decision,day);
        result.put("decisionVersionId",version(decision));
        result.put("decisionType",decision.get("decision_type"));
        result.put("deliveryId",window.delivery() == null ? null : window.delivery().id());
        result.put("deliveredAt",window.delivery() == null ? null : window.delivery().date());
        result.put("paymentDeadline",window.payment());
        result.put("reliefDeadline",window.relief());
        result.put("applicationStart",window.start());
        result.put("applicationDeadline",window.end());
        result.put("reliefPending",window.reliefPending());
        result.put("courtStatus",round == null ? null : round.status());
        String state = round != null ? round.accepted(day) ? "ACCEPTED" : "APPLIED"
                : window.reliefPending() ? "RELIEF_PENDING"
                : window.start() == null ? "UNCONFIRMED" : !day.isAfter(window.start()) ? "WAITING"
                : day.isAfter(window.end()) ? "EXPIRED" : "OPEN";
        result.put("state", state);
        return result;
    }

    private static LocalDate later(LocalDate a,LocalDate b) { return a.isAfter(b) ? a : b; }
    private static LocalDate date(Object value) {
        if (value instanceof LocalDate day) return day;
        return value instanceof java.sql.Date day ? day.toLocalDate() : null;
    }
    private static BizException applicationFailure(boolean continuation,int legacyCode,String message) {
        return new BizException(continuation ? 2193 : legacyCode,message);
    }
}
