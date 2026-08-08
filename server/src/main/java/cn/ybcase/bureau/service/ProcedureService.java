package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseClue;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseClueRepository;
import cn.ybcase.bureau.repository.CaseFileRepository;
import cn.ybcase.bureau.repository.CaseNoticeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** 三期程序完备：听证子流程、移送、协查、简易备案、执法证校验、电子送达确认 */
@Service
@RequiredArgsConstructor
public class ProcedureService {

    private final CaseFileRepository caseRepository;
    private final CaseClueRepository clueRepository;
    private final CaseNoticeRepository noticeRepository;
    private final BureauConfig config;
    private final JdbcTemplate jdbc;

    // ---------- 执法证台账（第16条） ----------

    /** 立案/添加办案人员时校验执法证（参数开关） */
    public void validateEnforcer(String name, String certNo) {
        if (!config.bool("enforcer_cert_check", true)) return;
        var rows = jdbc.queryForList(
                "select cert_expire_at, enabled, name from enforcer where cert_no = ?", certNo);
        if (rows.isEmpty())
            throw new BizException(2055, "执法证号 " + certNo + " 不在执法证台账内，请先登记（第16条执法资格要求）");
        var r = rows.get(0);
        if (!Boolean.TRUE.equals(r.get("enabled")))
            throw new BizException(2055, "执法证 " + certNo + " 已停用");
        if (!name.equals(r.get("name")))
            throw new BizException(2055, "执法证 " + certNo + " 与姓名不符（台账：" + r.get("name") + "）");
        // 台账允许有效期为空（历史数据/录入遗漏）：此处直接 toLocalDate 会 NPE→500，且立案事务回滚而看不到原因
        if (r.get("cert_expire_at") == null)
            throw new BizException(2055, "执法证 " + certNo + " 未登记有效期，请先在执法证台账补录（第16条）");
        LocalDate expire = ((java.sql.Date) r.get("cert_expire_at")).toLocalDate();
        if (expire.isBefore(LocalDate.now()))
            throw new BizException(2055, "执法证 " + certNo + " 已于 " + expire + " 过期");
    }

    // ---------- 听证子流程（辽46-50条） ----------

    public record HearingScheduleReq(LocalDate announcedAt, LocalDate noticeSentAt, LocalDate scheduledAt,
                                     String host, String hostDept, String recorder) {}

    @Transactional
    public void scheduleHearing(Long caseId, HearingScheduleReq req) {
        CaseFile c = caseFile(caseId);
        var notice = noticeRepository.findTopByCaseIdOrderByIdDesc(caseId)
                .orElseThrow(() -> new BizException(2039, "尚未作出处罚告知"));
        if (!Boolean.TRUE.equals(notice.getHearingRequested()))
            throw new BizException(2039, "当事人未申请听证");
        // 辽48条：听证主持人与办案人员应属于不同部门（不得为本案办案人员）
        List<Map<String, Object>> officers = jdbc.queryForList(
                "select name from case_officer where case_id = ?", caseId);
        if (officers.stream().anyMatch(o -> o.get("name").equals(req.host())))
            throw new BizException(2056, "听证主持人不得为本案办案人员（辽48条）");
        // 辽47条：举行听证7日前书面通知当事人（参数化）
        int ahead = config.intVal("hearing_notice_ahead_days", 7);
        LocalDate noticeSent = req.noticeSentAt() != null ? req.noticeSentAt() : LocalDate.now();
        if (ChronoUnit.DAYS.between(noticeSent, req.scheduledAt()) < ahead)
            throw new BizException(2057, "听证应在通知送达后至少 " + ahead + " 日举行（辽47条）");
        jdbc.update("""
                insert into case_hearing (case_id, announced_at, notice_sent_at, scheduled_at, host, host_dept, recorder)
                values (?,?,?,?,?,?,?)""",
                caseId, req.announcedAt(), noticeSent, req.scheduledAt(),
                req.host(), req.hostDept(), req.recorder());
    }

    @Transactional
    public void holdHearing(Long caseId, Long hearingId, String record) {
        if (record == null || record.isBlank()) throw new BizException(2057, "须制作听证笔录（辽50条）");
        int n = jdbc.update("""
                update case_hearing set held_at = ?, record = ?, status = 'HELD'
                where id = ? and case_id = ? and status = 'SCHEDULED'""",
                LocalDate.now(), record, hearingId, caseId);
        if (n == 0) throw new BizException(2057, "听证不存在或状态不允许");
        // 同步告知记录（听证已举行→决定前审查材料含听证报告）
        noticeRepository.findTopByCaseIdOrderByIdDesc(caseId).ifPresent(no -> {
            no.setHearingHeldAt(LocalDate.now());
            noticeRepository.save(no);
        });
    }

    @Transactional
    public void hearingOpinion(Long caseId, Long hearingId, String opinion) {
        if (opinion == null || opinion.isBlank()) throw new BizException(2057, "听证意见不能为空");
        int n = jdbc.update("""
                update case_hearing set opinion = ?, opinion_at = ?, status = 'OPINION_DONE'
                where id = ? and case_id = ? and status = 'HELD'""",
                opinion, LocalDate.now(), hearingId, caseId);
        if (n == 0) throw new BizException(2057, "听证不存在或尚未举行");
    }

    // ---------- 简易程序备案（第51条） ----------

    @Transactional
    public CaseFile summaryRecord(Long caseId) {
        CaseFile c = caseFile(caseId);
        if (!"SUMMARY".equals(c.getProcedureType())) throw new BizException(2058, "仅简易程序案件需备案（第51条）");
        if (c.getDecidedAt() == null) throw new BizException(2058, "尚未作出当场处罚决定");
        // 幂等：重复调用会把备案日期刷成当天，掩盖"是否两日内备案"的监督证据
        if (c.getSummaryRecordAt() != null)
            throw new BizException(2058, "该案已于 " + c.getSummaryRecordAt() + " 备案，不可重复备案");
        c.setSummaryRecordAt(LocalDate.now());
        return caseRepository.save(c);
    }

    // ---------- 移送台账（第10-12条/行刑衔接） ----------

    public record TransferReq(Long clueId, Long caseId, String direction, String targetOrg,
                              String kind, String reason, LocalDate sentAt, String note) {}

    @Transactional
    public void addTransfer(TransferReq req) {
        if (!List.of("OUT", "IN").contains(req.direction()))
            throw new BizException(2059, "方向须为 OUT/IN");
        if (!List.of("ADMIN", "JUDICIAL", "MSA").contains(req.kind()))
            throw new BizException(2059, "移送类型须为 其他行政机关/司法机关/医保部门间");
        if (req.reason() == null || req.reason().isBlank()) throw new BizException(2059, "须填写移送理由");
        if (req.clueId() == null && req.caseId() == null) throw new BizException(2059, "须关联线索或案件");
        jdbc.update("""
                insert into case_transfer (clue_id, case_id, direction, target_org, kind, reason, sent_at, note)
                values (?,?,?,?,?,?,?,?)""",
                req.clueId(), req.caseId(), req.direction(), req.targetOrg(), req.kind(), req.reason(),
                req.sentAt() != null ? req.sentAt() : LocalDate.now(), req.note());
        // 线索整体移送：办结（第12条移送后不再自行处理）
        if ("OUT".equals(req.direction()) && req.clueId() != null) {
            CaseClue clue = clueRepository.findById(req.clueId())
                    .orElseThrow(() -> new BizException(2025, "线索不存在"));
            if ("PENDING".equals(clue.getStatus())) {
                clue.setStatus("TRANSFERRED");
                clue.setVerifyResult("移送：" + req.targetOrg() + "（" + req.reason() + "）");
                clueRepository.save(clue);
            }
        }
    }

    @Transactional
    public void confirmTransfer(Long transferId) {
        int n = jdbc.update("update case_transfer set confirmed_at = ? where id = ? and confirmed_at is null",
                LocalDate.now(), transferId);
        if (n == 0) throw new BizException(2059, "移送记录不存在或已确认");
    }

    // ---------- 协查台账（第34条/辽12-14条） ----------

    public record AssistReq(String direction, String org, String content, LocalDate sentAt) {}

    @Transactional
    public void addAssist(Long caseId, AssistReq req) {
        caseFile(caseId);
        if (!List.of("OUT", "IN").contains(req.direction())) throw new BizException(2060, "方向须为 OUT/IN");
        if (req.content() == null || req.content().isBlank()) throw new BizException(2060, "须填写协查事项");
        LocalDate sent = req.sentAt() != null ? req.sentAt() : LocalDate.now();
        jdbc.update("""
                insert into case_assist (case_id, direction, org, content, sent_at, due_at)
                values (?,?,?,?,?,?)""",
                caseId, req.direction(), req.org(), req.content(), sent,
                sent.plusDays(config.intVal("assist_due_days", 15)));
    }

    @Transactional
    public void replyAssist(Long caseId, Long assistId, String result, boolean refused, String refuseReason) {
        if (refused && (refuseReason == null || refuseReason.isBlank()))
            throw new BizException(2060, "拒绝协助应当将理由告知请求机关（辽13条）");
        int n = jdbc.update("""
                update case_assist set replied_at = ?, result = ?, refused = ?, refuse_reason = ?
                where id = ? and case_id = ? and replied_at is null""",
                LocalDate.now(), result, refused, refuseReason, assistId, caseId);
        if (n == 0) throw new BizException(2060, "协查记录不存在或已办结");
    }

    // ---------- 电子送达确认（第59条） ----------

    @Transactional
    /** 登记电子送达确认书（第59条）：这是电子送达的唯一闸门，须留下确认书本身的痕迹 */
    public CaseFile eDeliveryConsent(Long caseId, String receiver, String channel, String docNo) {
        CaseFile c = caseFile(caseId);
        if (receiver == null || receiver.isBlank())
            throw new BizException(2054, "须填写签署电子送达确认书的当事人/受送达人");
        if (channel == null || channel.isBlank())
            throw new BizException(2054, "须填写当事人确认的电子送达地址（手机号/邮箱等）");
        c.setEDeliveryConsent(true);
        caseRepository.save(c);
        jdbc.update("""
                insert into case_document (case_id, doc_type, title, content, made_at, maker, signed)
                values (?, 'E_DELIVERY_CONSENT', ?, ?, ?, ?, true)""",
                caseId, "电子送达确认书", "受送达人：" + receiver + "；确认的电子送达地址：" + channel
                        + (docNo == null || docNo.isBlank() ? "" : "；确认书编号：" + docNo),
                LocalDate.now(), receiver);
        return c;
    }

    public List<Map<String, Object>> hearings(Long caseId) {
        return jdbc.queryForList("select * from case_hearing where case_id = ? order by id", caseId);
    }

    public List<Map<String, Object>> assists(Long caseId) {
        return jdbc.queryForList("select * from case_assist where case_id = ? order by id", caseId);
    }

    private CaseFile caseFile(Long caseId) {
        return caseRepository.findById(caseId).orElseThrow(() -> new BizException(2043, "案件不存在"));
    }
}
