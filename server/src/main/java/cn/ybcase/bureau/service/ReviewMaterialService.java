package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseReview;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A deterministic, read-only submission bundle. Mutating callers hold the case lock before capture/compare.
 * Explicit projections exclude workflow completion and clocks, while preserving substantive facts.
 * Attachment metadata identifies immutable application records; this is not a digest of original file bytes.
 */
@Service
@RequiredArgsConstructor
public class ReviewMaterialService {
    private final JdbcTemplate jdbc;
    private final MaterialAccess materialAccess;
    private final ObjectMapper objectMapper;
    private final jakarta.persistence.EntityManager entityManager;
    public static final int SCHEMA_VERSION = 1;

    // Include unknown/custom document types by default so new fact documents cannot bypass review binding.
    // Generic OTHER/APPROVAL_FORM/FILING_APPROVAL remain included; only specifically procedural types are excluded.
    private static final List<String> PROCEDURAL_DOCUMENT_TYPES = List.of(
            "LEGAL_OPINION", "DECISION", "CLOSE_REPORT", "MEETING_RECORD",
            "SUSPEND", "SUSPEND_DECISION", "RESUME", "RESUME_NOTICE", "EXTEND", "EXTEND_DECISION",
            "EXTENSION_DECISION", "EXTENSION_APPROVAL", "EXTEND_APPROVAL",
            "EXCLUSION_RECORD", "PERIOD_EXCLUSION", "DELIVERY_RECEIPT", "URGE_LETTER");

    public record Snapshot(String json, String fingerprint) {}
    public record MaterialStatus(Long reviewId, boolean current, String reason, boolean bound,
                                 String fingerprint, String currentFingerprint) {}

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Snapshot capture(Long caseId) {
        materialAccess.requireCurrentCaseReader(caseId);
        // Cause wording is shared across cases. Prevent a dictionary edit between comparison and commit.
        // Read-only preflight remains observational; final writes hold this lock until their transaction ends.
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            entityManager.flush(); // Include JPA fact edits made by a caller earlier in the same transaction.
            jdbc.queryForList("select id from case_cause where id = (select cause_id from case_file where id = ?) for share", caseId);
        }
        // JDBC is intentional: a caller may have changed facts earlier in this transaction, leaving a JPA entity stale.
        var facts = jdbc.queryForList("""
                select c.id,c.clue_id,c.cause_id,c.enforce_item_id,c.procedure_type,
                       c.party_name,c.party_type,c.party_credit_no,c.party_address,c.party_legal_rep,c.party_contact,
                       c.summary,c.amount_involved,c.violation_end_date,c.health_harm,
                       c.institution_code,c.institution_ownership,c.institution_grade,c.institution_kind,
                       c.confirmed_violation_start,c.confirmed_violation_end,c.confirmed_violation_facts,c.confirmed_violation_amount,
                       c.jurisdiction_type,c.jurisdiction_basis,c.violation_place,c.treatment_place,c.insured_place,
                       c.designated_no,c.jurisdiction_dispute_note,
                       cause.subject_type as cause_subject_type,cause.category as cause_category,
                       cause.item_no as cause_item_no,cause.description as cause_description
                from case_file c left join case_cause cause on cause.id=c.cause_id where c.id=?""", caseId);
        if (facts.isEmpty()) throw new BizException(2043, "案件不存在");
        Map<String, Object> bundle = new TreeMap<>();
        bundle.put("schemaVersion", SCHEMA_VERSION);
        bundle.put("caseFacts", facts.get(0));
        List<Object> docArgs = new ArrayList<>(); docArgs.add(caseId); docArgs.addAll(PROCEDURAL_DOCUMENT_TYPES);
        bundle.put("documents", jdbc.queryForList("""
                select id,doc_type,title,content,maker,note from case_document
                where case_id=? and coalesce(doc_type,'') not in (
                """ + String.join(",", java.util.Collections.nCopies(PROCEDURAL_DOCUMENT_TYPES.size(), "?"))
                + ") order by id", docArgs.toArray()));
        bundle.put("evidences", jdbc.queryForList("""
                select id,type,name,source,obtained_at,keeper,note,cross_exam_opinion
                from case_evidence where case_id=? order by id""", caseId));
        bundle.put("attachments", jdbc.queryForList("""
                select id,document_id,evidence_id,source_clue_id,source_inspection_id,
                       filename,content_type,size_bytes,category,uploaded_by
                from case_attachment where case_id=? and voided=false order by id""", caseId));
        bundle.put("notices", jdbc.queryForList("""
                select id,content,proposed_fine,proposed_recoup,proposed_confiscate,
                       change_reason,statement,statement_review
                from case_notice where case_id=? order by id""", caseId));
        bundle.put("hearings", jdbc.queryForList("""
                select id,host,host_dept,recorder,record,opinion from case_hearing
                where case_id=? and status in ('HELD','OPINION_DONE')
                  and coalesce(btrim(record),'')<>'' order by id""", caseId));
        bundle.put("expertReviews", jdbc.queryForList("""
                select id,experts,opinion from expert_review
                where case_id=? and ended_at is not null and coalesce(btrim(opinion),'')<>'' order by id""", caseId));
        bundle.put("assists", jdbc.queryForList("""
                select id,direction,org,content,result,refused,refuse_reason from case_assist
                where case_id=? and (replied_at is not null or refused=true or coalesce(btrim(result),'')<>'') order by id""", caseId));
        try {
            String json = objectMapper.writer().with(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
                    .writeValueAsString(canonical(bundle));
            return new Snapshot(json, fingerprint(json));
        } catch (JsonProcessingException e) {
            throw new BizException(2150, "无法生成送审材料快照，请核查材料格式");
        }
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public boolean matches(CaseReview review) {
        return status(review).current();
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public MaterialStatus status(CaseReview review) {
        if (review == null) return new MaterialStatus(null, false, "尚未提交法制审核", false, null, null);
        boolean bound = review.getMaterialFingerprint() != null && !review.getMaterialFingerprint().isBlank()
                && review.getMaterialSnapshot() != null && !review.getMaterialSnapshot().isBlank();
        if (!bound) return new MaterialStatus(review.getId(), false, "历史审核未绑定送审材料，请重新提交审核", false,
                review.getMaterialFingerprint(), null);
        if (!review.getMaterialFingerprint().equals(fingerprint(review.getMaterialSnapshot())))
            return new MaterialStatus(review.getId(), false, "送审材料快照校验失败，请重新提交审核", true,
                    review.getMaterialFingerprint(), null);
        Snapshot now = capture(review.getCaseId());
        boolean current = review.getMaterialFingerprint().equals(now.fingerprint());
        return new MaterialStatus(review.getId(), current,
                current ? "当前材料与送审版本一致" : "材料已变化，请重新提交法制审核", true,
                review.getMaterialFingerprint(), now.fingerprint());
    }

    private static String fingerprint(String json) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 is unavailable", e); }
    }

    /** Map keys and SQL row orders are stable; JDBC temporal and decimal representation cannot add timezone/scale noise. */
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> list) return list.stream().map(ReviewMaterialService::canonical).toList();
        if (value instanceof BigDecimal decimal) return decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
        if (value instanceof java.sql.Date date) return date.toLocalDate().toString();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant().toString();
        if (value instanceof OffsetDateTime timestamp) return timestamp.toInstant().toString();
        if (value instanceof ZonedDateTime timestamp) return timestamp.toInstant().toString();
        if (value instanceof Instant instant) return instant.toString();
        if (value instanceof java.time.temporal.TemporalAccessor temporal) return temporal.toString();
        return value;
    }
}
