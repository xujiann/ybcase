package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseClue;
import cn.ybcase.bureau.repository.CaseClueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** 线索管理（第14条：15个工作日内核查并决定是否立案，特殊情况经主要负责人批准延长15个工作日） */
@Service
@RequiredArgsConstructor
public class ClueService {

    private final CaseClueRepository clueRepository;
    private final BureauConfig cfg;
    private final BizSeqService seq;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public record ClueCreateReq(String source, String content, String suspectName,
                                String suspectType, LocalDate receivedAt, String handler) {}

    @Transactional
    public CaseClue create(ClueCreateReq req, String username) {
        if (req.content() == null || req.content().isBlank()) throw new BizException(2020, "线索内容不能为空");
        LocalDate received = req.receivedAt() != null ? req.receivedAt() : LocalDate.now();
        CaseClue clue = new CaseClue();
        clue.setClueNo("XS" + received.getYear() + String.format("%04d", seq.next("CLUE", received.getYear())));
        clue.setSource(req.source());
        clue.setContent(req.content());
        clue.setSuspectName(req.suspectName());
        clue.setSuspectType(req.suspectType());
        clue.setReceivedAt(received);
        clue.setDeadlineAt(cfg.plusByUnit(received, cfg.intVal("clue_verify_days", 15), "clue_verify_day_unit"));
        clue.setHandler(req.handler());
        clue.setCreatedBy(username);
        return clueRepository.save(clue);
    }

    /** 延长核查期限：仅限一次，再延 15 个工作日（第14条） */
    @Transactional
    public CaseClue extend(Long id, String reason) {
        CaseClue clue = get(id);
        if (!"PENDING".equals(clue.getStatus())) throw new BizException(2021, "线索已办结，不可延期");
        if (Boolean.TRUE.equals(clue.getExtended())) throw new BizException(2022, "核查期限只能延长一次（第14条）");
        if (reason == null || reason.isBlank()) throw new BizException(2023, "延期须说明特殊情况并经主要负责人批准");
        clue.setExtended(true);
        clue.setExtendReason(reason);
        clue.setDeadlineAt(cfg.plusByUnit(clue.getDeadlineAt(), cfg.intVal("clue_extend_days", 15), "clue_verify_day_unit"));
        return clueRepository.save(clue);
    }

    /** 核查期限扣除（辽15条：鉴定、检验时间不计入核查期限） */
    @Transactional
    public CaseClue addExclusion(Long id, int days, String reason) {
        CaseClue clue = get(id);
        if (!"PENDING".equals(clue.getStatus())) throw new BizException(2021, "线索已办结");
        if (days <= 0) throw new BizException(2026, "扣除天数须为正数");
        if (reason == null || reason.isBlank()) throw new BizException(2026, "须说明鉴定/检验事由");
        clue.setExcludedDays(clue.getExcludedDays() + days);
        clue.setDeadlineAt(clue.getDeadlineAt().plusDays(days));
        clue.setExtendReason((clue.getExtendReason() == null ? "" : clue.getExtendReason() + "；")
                + "期限扣除" + days + "日：" + reason);
        return clueRepository.save(clue);
    }

    /** 不予立案 */
    @Transactional
    public CaseClue reject(Long id, String verifyResult) {
        CaseClue clue = get(id);
        if (!"PENDING".equals(clue.getStatus())) throw new BizException(2021, "线索已办结");
        if (verifyResult == null || verifyResult.isBlank()) throw new BizException(2024, "不予立案须记录核查结果");
        clue.setStatus("REJECTED");
        clue.setVerifyResult(verifyResult);
        return clueRepository.save(clue);
    }

    /** 立案时由 CaseService 回调标记 */
    @Transactional
    public void markFiled(Long id, String verifyResult) {
        // 原子占用：读已提交下"先查 PENDING 再改"会让同一线索被两个并发立案同时通过，生成两个案件
        int n = jdbc.update("""
                update case_clue set status = 'FILED',
                       verify_result = coalesce(?, verify_result)
                where id = ? and status = 'PENDING'""",
                verifyResult == null || verifyResult.isBlank() ? null : verifyResult, id);
        if (n == 0) {
            get(id);  // 不存在则抛 2025，存在则说明已办结
            throw new BizException(2021, "线索已办结，不可重复立案");
        }
    }

    public CaseClue get(Long id) {
        return clueRepository.findById(id).orElseThrow(() -> new BizException(2025, "线索不存在"));
    }
}
