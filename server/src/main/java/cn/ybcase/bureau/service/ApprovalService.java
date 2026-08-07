package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 审批留痕单据化（第17条立案审批表精神推广到全部"负责人批准"动作）：
 * 办案人员提交申请（PENDING）→ 负责人批准（执行动作）/驳回；
 * 负责人直接操作原接口时自动补记"申请即批准"单据，保证凡批准必有单。
 */
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final CaseService caseService;
    private final ExecutionService executionService;
    private final JdbcTemplate jdbc;
    // 用 Spring 全局 ObjectMapper（含 JavaTime 模块，LocalDate 反序列化正确）
    private final ObjectMapper objectMapper;

    private static final List<String> KINDS = List.of("FILE_CASE", "EXTEND", "SUSPEND", "TERMINATE", "DEFER");

    public record ApplyReq(String kind, Long clueId, Long caseId, Map<String, Object> payload, String reason) {}

    @Transactional
    public long apply(ApplyReq req, String applicant) {
        if (!KINDS.contains(req.kind())) throw new BizException(2071, "审批类型须为 立案/延期/中止/终止/暂缓 之一");
        if (req.reason() == null || req.reason().isBlank()) throw new BizException(2071, "须填写申请理由");
        if ("FILE_CASE".equals(req.kind())) {
            if (req.payload() == null || req.payload().get("partyName") == null)
                throw new BizException(2071, "立案申请须附立案审批表内容（当事人/案由/办案人员）");
        } else if (req.caseId() == null) {
            throw new BizException(2071, "须关联案件");
        }
        String payload = toJson(req.payload());
        return jdbc.queryForObject("""
                insert into biz_approval (kind, clue_id, case_id, payload, reason, applicant)
                values (?,?,?,?,?,?) returning id""", Long.class,
                req.kind(), req.clueId(), req.caseId(), payload, req.reason(), applicant);
    }

    /** 负责人裁决：批准即执行对应动作（同事务，失败则整单回滚） */
    @Transactional
    public Map<String, Object> decide(long approvalId, boolean approve, String opinion, String approver) {
        var rows = jdbc.queryForList("select * from biz_approval where id = ? and status = 'PENDING'", approvalId);
        if (rows.isEmpty()) throw new BizException(2072, "审批单不存在或已办结");
        var a = rows.get(0);
        Object executed = null;
        if (approve) {
            executed = execute((String) a.get("kind"), a, approver);
        }
        jdbc.update("""
                update biz_approval set status = ?, approver = ?, decided_at = now(), opinion = ?
                where id = ?""",
                approve ? "APPROVED" : "REJECTED", approver, opinion, approvalId);
        return Map.of("approved", approve, "result", executed == null ? Map.of() : executed);
    }

    /** 负责人直接操作原接口时补记单据（申请即批准） */
    @Transactional
    public void recordDirect(String kind, Long caseId, String reason, String operator) {
        jdbc.update("""
                insert into biz_approval (kind, case_id, reason, applicant, status, approver, decided_at, opinion)
                values (?,?,?,?, 'APPROVED', ?, now(), '负责人直接批准')""",
                kind, caseId, reason == null ? "（直接操作）" : reason, operator, operator);
    }

    public List<Map<String, Object>> pending() {
        return jdbc.queryForList("""
                select a.*, cf.case_no, cf.name as case_name, c.clue_no
                from biz_approval a
                left join case_file cf on cf.id = a.case_id
                left join case_clue c on c.id = a.clue_id
                where a.status = 'PENDING' order by a.id""");
    }

    public List<Map<String, Object>> byCase(Long caseId) {
        return jdbc.queryForList("select * from biz_approval where case_id = ? order by id desc", caseId);
    }

    private Object execute(String kind, Map<String, Object> a, String approver) {
        Long caseId = a.get("case_id") == null ? null : ((Number) a.get("case_id")).longValue();
        Map<String, Object> p = fromJson((String) a.get("payload"));
        String reason = (String) a.get("reason");
        return switch (kind) {
            case "FILE_CASE" -> {
                CaseService.CaseCreateReq req = objectMapper.convertValue(p, CaseService.CaseCreateReq.class);
                yield caseService.create(req, (String) a.get("applicant"));
            }
            case "EXTEND" -> caseService.extend(caseId, ((Number) p.getOrDefault("days", 0)).intValue(), reason);
            case "SUSPEND" -> caseService.suspend(caseId, reason);
            case "TERMINATE" -> caseService.terminate(caseId, reason);
            case "DEFER" -> executionService.approveDefer(caseId);
            default -> throw new BizException(2071, "未知审批类型");
        };
    }

    private String toJson(Map<String, Object> m) {
        try {
            return m == null ? null : objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            throw new BizException(2071, "申请参数序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String s) {
        try {
            return s == null ? Map.of() : objectMapper.readValue(s, Map.class);
        } catch (Exception e) {
            throw new BizException(2072, "审批单参数解析失败");
        }
    }
}
