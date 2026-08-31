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
    private final MessageService messageService;
    private final JdbcTemplate jdbc;
    // 用 Spring 全局 ObjectMapper（含 JavaTime 模块，LocalDate 反序列化正确）
    private final ObjectMapper objectMapper;

    private static final Map<String, String> KIND_NAMES = Map.of(
            "FILE_CASE", "立案", "EXTEND", "延期", "SUSPEND", "中止", "TERMINATE", "终止", "DEFER", "暂缓分期");

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
        Long id = jdbc.queryForObject("""
                insert into biz_approval (kind, clue_id, case_id, payload, reason, applicant)
                values (?,?,?,?,?,?) returning id""", Long.class,
                req.kind(), req.clueId(), req.caseId(), payload, req.reason(), applicant);
        // 即时通知负责人
        messageService.sendToRole("LEADER", "APPROVAL",
                "新审批单：" + KIND_NAMES.getOrDefault(req.kind(), req.kind()) + "（" + applicant + "）",
                req.reason(), "/case/approvals");
        return id;
    }

    /** 负责人裁决：批准即执行对应动作（同事务，失败则整单回滚） */
    @Transactional
    public Map<String, Object> decide(long approvalId, boolean approve, String opinion, String approver) {
        // 先抢占单据（带状态条件的原子 update）：先查后改会被双击/并发裁决执行两次，
        // EXTEND 会把期限顺延两倍、FILE_CASE 会建出两个案件
        int claimed = jdbc.update("""
                update biz_approval set status = ?, approver = ?, decided_at = now(), opinion = ?
                where id = ? and status = 'PENDING'""",
                approve ? "APPROVED" : "REJECTED", approver, opinion, approvalId);
        if (claimed == 0) throw new BizException(2072, "审批单不存在或已办结");
        var rows = jdbc.queryForList("select * from biz_approval where id = ?", approvalId);
        var a = rows.get(0);
        Object executed = null;
        if (approve) {
            executed = execute((String) a.get("kind"), a, approver);
            // FILE_CASE 是唯一"批准时才产生案件"的类型，申请时 case_id 必然为 null；
            // 不回填的话这张立案审批表挂不到案件上（byCase 查不到、案卷里看不见第17条批准留痕）
            if (executed instanceof cn.ybcase.bureau.entity.CaseFile created && a.get("case_id") == null)
                jdbc.update("update biz_approval set case_id = ? where id = ?", created.getId(), approvalId);
        }
        // 即时通知申请人
        messageService.send((String) a.get("applicant"), "APPROVAL",
                "审批单 #" + approvalId + "（" + KIND_NAMES.getOrDefault((String) a.get("kind"), "") + "）"
                        + (approve ? "已批准" : "已驳回"),
                opinion, a.get("case_id") == null ? "/case/approvals" : "/case/detail/" + a.get("case_id"), null);
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

    /** applicant 非空时只看本人提交的（非负责人/管理员视角） */
    public List<Map<String, Object>> pending(String applicant) {
        String base = """
                select a.*, cf.case_no, cf.name as case_name, c.clue_no
                from biz_approval a
                left join case_file cf on cf.id = a.case_id
                left join case_clue c on c.id = a.clue_id
                where a.status = 'PENDING'""";
        return applicant == null
                ? jdbc.queryForList(base + " order by a.id")
                : jdbc.queryForList(base + " and a.applicant = ? order by a.id", applicant);
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
