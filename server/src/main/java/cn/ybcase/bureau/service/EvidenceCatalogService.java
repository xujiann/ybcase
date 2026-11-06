package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Evidence volume indexes material without duplicating binary files or treating attachments as examined evidence. */
@Service
@RequiredArgsConstructor
public class EvidenceCatalogService {
    private final JdbcTemplate jdbc;
    private final MaterialAccess materialAccess;

    /** Stable material identifiers use immutable primary keys, never a row's current display position. */
    public static String number(String kind, long id) {
        return String.format(Locale.ROOT, "%s-%08d", "EVIDENCE".equals(kind) ? "ZJ" : "FJ", id);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> catalog(Long caseId, boolean withVoided) {
        var reader = materialAccess.requireCurrentCaseReader(caseId);
        if (reader != null) withVoided = withVoided && reader.roles().stream().anyMatch(java.util.Set.of("LEADER", "ADMIN")::contains);
        if (jdbc.queryForObject("select count(*) from case_file where id = ?", Integer.class, caseId) == 0)
            throw new BizException(2043, "案件不存在");
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> evidences = new ArrayList<>();
        for (var row : jdbc.queryForList("""
                select id, type, name, source, obtained_at, keeper, cross_exam_at
                from case_evidence where case_id = ? order by id""", caseId)) {
            long id = ((Number) row.get("id")).longValue();
            Map<String, Object> item = entry("EVIDENCE", id, (String) row.get("name"));
            item.put("category", row.get("type"));
            item.put("evidenceId", id);
            item.put("obtainedAt", row.get("obtained_at"));
            item.put("source", row.get("source"));
            item.put("keeper", row.get("keeper"));
            item.put("crossExamAt", row.get("cross_exam_at"));
            items.add(item);
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("id", id);
            evidence.put("name", row.get("name"));
            evidence.put("number", item.get("number"));
            evidences.add(evidence);
        }
        // Project metadata only: bytea/file paths never enter an index response or its printable representation.
        for (var row : jdbc.queryForList("""
                select a.id, a.filename, a.category, a.size_bytes, a.uploaded_by, a.uploaded_at,
                       a.document_id, a.evidence_id, a.source_clue_id, a.source_inspection_id,
                       a.voided, a.void_reason, a.voided_by, a.voided_at,
                       d.title as document_title, e.name as evidence_title
                from case_attachment a
                left join case_document d on d.id = a.document_id and d.case_id = a.case_id
                left join case_evidence e on e.id = a.evidence_id and e.case_id = a.case_id
                where a.case_id = ? and (not a.voided or ?) order by a.id""", caseId, withVoided)) {
            long id = ((Number) row.get("id")).longValue();
            Map<String, Object> item = entry("ATTACHMENT", id, (String) row.get("filename"));
            item.put("filename", row.get("filename"));
            item.put("category", row.get("category"));
            item.put("sizeBytes", row.get("size_bytes"));
            item.put("uploadedBy", row.get("uploaded_by"));
            item.put("uploadedAt", row.get("uploaded_at"));
            item.put("documentId", row.get("document_id"));
            item.put("documentTitle", row.get("document_title"));
            item.put("evidenceId", row.get("evidence_id"));
            item.put("evidenceTitle", row.get("evidence_title"));
            item.put("sourceClueId", row.get("source_clue_id"));
            item.put("sourceInspectionId", row.get("source_inspection_id"));
            item.put("voided", row.get("voided"));
            item.put("voidReason", row.get("void_reason"));
            item.put("voidedBy", row.get("voided_by"));
            item.put("voidedAt", row.get("voided_at"));
            item.put("downloadUrl", "/bureau/cases/attachments/" + id + "/download");
            items.add(item);
        }
        return Map.of("caseId", caseId, "items", items, "evidences", evidences);
    }

    private static Map<String, Object> entry(String kind, long id, String title) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("number", number(kind, id));
        item.put("kind", kind);
        item.put("id", id);
        item.put("title", title);
        for (String key : List.of("category", "evidenceId", "documentId", "documentTitle", "evidenceTitle",
                "sourceClueId", "sourceInspectionId", "filename", "sizeBytes", "uploadedBy", "uploadedAt", "voidReason"))
            item.put(key, null);
        item.put("voided", false);
        return item;
    }
}
