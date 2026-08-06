package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseFileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/** 证据域（自 CaseService 拆分）：八/九类证据、先行登记保存、封存、质证 */
@Service
@RequiredArgsConstructor
public class EvidenceService {

    private final CaseFileRepository caseRepository;
    private final BureauConfig config;
    private final JdbcTemplate jdbc;

    /** 证据种类：国家局令 8 类 + 辽23条第九类"其他材料" */
    private static final List<String> EVIDENCE_TYPES = List.of(
            "DOCUMENT", "PHYSICAL", "AV", "EDATA", "TESTIMONY", "STATEMENT", "EXPERT", "RECORD", "OTHER_MATERIAL");

    public record EvidenceReq(String type, String name, String source, LocalDate obtainedAt,
                              String keeper, String note, Boolean registerHold, Boolean sealed) {}

    @Transactional
    public void addEvidence(Long caseId, EvidenceReq req) {
        CaseGuards.requireActive(CaseGuards.get(caseRepository, caseId));
        if (!EVIDENCE_TYPES.contains(req.type()))
            throw new BizException(2027, "证据种类须为法定类型之一（第19条/辽23条）");
        LocalDate obtained = req.obtainedAt() != null ? req.obtainedAt() : LocalDate.now();
        boolean hold = Boolean.TRUE.equals(req.registerHold());
        boolean sealed = Boolean.TRUE.equals(req.sealed());
        jdbc.update("""
                insert into case_evidence (case_id, type, name, source, obtained_at, keeper, note,
                    register_hold, hold_expire_at, sealed, seal_expire_at)
                values (?,?,?,?,?,?,?,?,?,?,?)""",
                caseId, req.type(), req.name(), req.source(), obtained, req.keeper(), req.note(),
                // 第26条：7个工作日内作出处理决定（辽33条为7自然日，参数化）
                hold, hold ? config.plusByUnit(obtained, config.intVal("evidence_hold_days", 7), "evidence_hold_day_unit") : null,
                sealed, sealed ? obtained.plusDays(30) : null);          // 第31条：封存不超过30日
    }

    /** 先行登记保存处理决定（第28条）：PRESERVE 保全 / SEAL 转封存 / RELEASE 解除 */
    @Transactional
    public void disposeHold(Long caseId, Long evidenceId, String disposal) {
        CaseGuards.requireActive(CaseGuards.get(caseRepository, caseId));
        if (!List.of("PRESERVE", "SEAL", "RELEASE").contains(disposal))
            throw new BizException(2028, "处理措施须为 保全/封存/解除 之一（第28条）");
        int n;
        if ("SEAL".equals(disposal)) {
            n = jdbc.update("""
                    update case_evidence set hold_disposal = ?, register_hold = false,
                        sealed = true, seal_expire_at = ? where id = ? and case_id = ? and register_hold = true""",
                    disposal, LocalDate.now().plusDays(30), evidenceId, caseId);
        } else {
            n = jdbc.update("""
                    update case_evidence set hold_disposal = ?, register_hold = false
                    where id = ? and case_id = ? and register_hold = true""", disposal, evidenceId, caseId);
        }
        if (n == 0) throw new BizException(2029, "证据不存在或不在先行登记保存状态");
    }

    /** 延长封存（第31条：可延长一次，不超过30日）或解除封存（第33条） */
    @Transactional
    public void updateSeal(Long caseId, Long evidenceId, boolean extend) {
        CaseGuards.requireActive(CaseGuards.get(caseRepository, caseId));
        if (extend) {
            int n = jdbc.update("""
                    update case_evidence set seal_extended = true, seal_expire_at = seal_expire_at + 30
                    where id = ? and case_id = ? and sealed = true and seal_extended = false""", evidenceId, caseId);
            if (n == 0) throw new BizException(2030, "封存延长仅限一次且证据须在封存中（第31条）");
        } else {
            int n = jdbc.update("update case_evidence set sealed = false where id = ? and case_id = ? and sealed = true",
                    evidenceId, caseId);
            if (n == 0) throw new BizException(2029, "证据不在封存状态");
        }
    }

    /** 证据质证（辽24条：未经当事人发表意见的证据不能作为处罚决定依据） */
    @Transactional
    public void crossExam(Long caseId, Long evidenceId, String opinion) {
        CaseFile c = CaseGuards.get(caseRepository, caseId);
        if ("CLOSED".equals(c.getStatus())) throw new BizException(2031, "案件已结案归档");
        if (opinion == null || opinion.isBlank()) throw new BizException(2045, "须记录当事人对证据的意见（无异议也须注明）");
        int n = jdbc.update("update case_evidence set cross_exam_opinion = ?, cross_exam_at = ? where id = ? and case_id = ?",
                opinion, LocalDate.now(), evidenceId, caseId);
        if (n == 0) throw new BizException(2029, "证据不存在");
    }
}
