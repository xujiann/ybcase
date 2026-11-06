package cn.ybcase.bureau.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serialize certificate decisions before taking user locks, including foreign-key user locks.
 * Source case/clue -> registry gate -> users -> certificate rows is the common write order.
 * The gate also covers missing certificate rows and changes to a reviewer's qualification set.
 */
@Service
@RequiredArgsConstructor
public class EnforcerRegistryAccess {
    public static final int GATE_NAMESPACE = 0x59424345;
    public static final int GATE_KEY = 0x454E4643;

    private final JdbcTemplate jdbc;

    public record Snapshot(Long id, String certNo, String name, LocalDate expiresAt,
                           boolean enabled, boolean legalQualified, Long userId) {}

    public void lockRegistry() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Certificate decisions must be transactional");
        jdbc.queryForList("select pg_advisory_xact_lock(?, ?)", GATE_NAMESPACE, GATE_KEY);
    }

    public Map<String, Snapshot> lockCertificates(Collection<String> certNos) {
        lockRegistry();
        var numbers = certNos.stream().distinct().toList();
        if (numbers.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(numbers.size(), "?"));
        return snapshots(jdbc.queryForList("select id, cert_no, name, cert_expire_at, enabled, legal_qualified, user_id"
                + " from enforcer where cert_no in (" + placeholders + ") order by id for share", numbers.toArray()));
    }

    public Map<String, Snapshot> lockReviewCertificates(Long actorId, Long caseId) {
        lockRegistry();
        return snapshots(jdbc.queryForList("""
                select e.id, e.cert_no, e.name, e.cert_expire_at, e.enabled, e.legal_qualified, e.user_id
                from enforcer e
                where e.user_id = ? or exists (
                    select 1 from case_officer o where o.case_id = ? and o.cert_no = e.cert_no)
                order by e.id for share""", actorId, caseId));
    }

    private Map<String, Snapshot> snapshots(List<Map<String, Object>> rows) {
        Map<String, Snapshot> snapshots = new LinkedHashMap<>();
        for (var row : rows) {
            String certNo = (String) row.get("cert_no");
            Date expires = (Date) row.get("cert_expire_at");
            Long userId = row.get("user_id") == null ? null : ((Number) row.get("user_id")).longValue();
            snapshots.put(certNo, new Snapshot(((Number) row.get("id")).longValue(), certNo,
                    (String) row.get("name"), expires == null ? null : expires.toLocalDate(),
                    Boolean.TRUE.equals(row.get("enabled")), Boolean.TRUE.equals(row.get("legal_qualified")), userId));
        }
        return Collections.unmodifiableMap(snapshots);
    }
}
