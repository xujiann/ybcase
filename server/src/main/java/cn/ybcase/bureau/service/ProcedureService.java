package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseClue;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.entity.CaseNotice;
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
    private final SourceAttachmentAccess sourceAccess;
    private final EnforcerRegistryAccess enforcerRegistry;
    private final MaterialAccess materialAccess;
    private final jakarta.persistence.EntityManager entityManager;

    // ---------- 执法证台账（第16条） ----------

    /** Validation and authoritative account mapping come from one locked certificate snapshot. */
    @Transactional
    public Map<String, Long> validateAndResolveEnforcers(List<CaseService.OfficerReq> officers) {
        if (officers == null || officers.stream().anyMatch(o -> o == null || o.name() == null || o.name().isBlank()
                || o.certNo() == null || o.certNo().isBlank()))
            throw new BizException(2002, "办案人员须填写姓名和执法证号");
        var snapshots = enforcerRegistry.lockCertificates(officers.stream().map(CaseService.OfficerReq::certNo).toList());
        boolean check = config.bool("enforcer_cert_check", true);
        Map<String, Long> accounts = new java.util.LinkedHashMap<>();
        for (var officer : officers) {
            var snapshot = snapshots.get(officer.certNo());
            if (check) validateEnforcer(officer.name(), officer.certNo(), snapshot);
            // Turning qualification checks off does not allow mixing a later account mapping into this write.
            accounts.put(officer.certNo(), snapshot == null ? null : snapshot.userId());
        }
        return java.util.Collections.unmodifiableMap(accounts);
    }

    @Transactional
    public void validateEnforcer(String name, String certNo) {
        validateAndResolveEnforcers(List.of(new CaseService.OfficerReq(name, certNo, null)));
    }

    private void validateEnforcer(String name, String certNo, EnforcerRegistryAccess.Snapshot snapshot) {
        if (snapshot == null)
            throw new BizException(2055, "执法证号 " + certNo + " 不在执法证台账内，请先登记（第16条执法资格要求）");
        if (!snapshot.enabled()) throw new BizException(2055, "执法证 " + certNo + " 已停用");
        if (!name.equals(snapshot.name()))
            throw new BizException(2055, "执法证 " + certNo + " 与姓名不符（台账：" + snapshot.name() + "）");
        if (snapshot.expiresAt() == null)
            throw new BizException(2055, "执法证 " + certNo + " 未登记有效期，请先在执法证台账补录（第16条）");
        if (snapshot.expiresAt().isBefore(LocalDate.now()))
            throw new BizException(2055, "执法证 " + certNo + " 已于 " + snapshot.expiresAt() + " 过期");
    }

    @Transactional
    public void saveEnforcer(Map<String, Object> body) {
        if (body == null) throw new BizException(2100, "请填写执法证台账内容");
        String name = registryText(body.get("name"), "姓名", true);
        String certNo = registryText(body.get("certNo"), "执法证号", true);
        String dept = registryText(body.get("dept"), "部门", false);
        LocalDate expires = cn.ybcase.bureau.common.ReqValues.reqDate(body, "certExpireAt", "执法证有效期");
        boolean qualified = registryBoolean(body.get("legalQualified"), false, "法律职业资格");
        boolean enabled = registryBoolean(body.get("enabled"), true, "有效状态");
        boolean mappingSpecified = body.containsKey("userId");
        Long userId = registryUserId(body.get("userId"));
        enforcerRegistry.lockRegistry();
        // Take the FK target lock before the certificate row, including before UPSERT's implicit FK check.
        if (userId != null && jdbc.queryForList("select id from sys_user where id=? for key share", userId).isEmpty())
            throw new BizException(2100, "关联账号不存在，请刷新账号列表后重新选择");
        jdbc.update("""
                insert into enforcer (name, cert_no, dept, cert_expire_at, legal_qualified, enabled, user_id)
                values (?,?,?,?,?,?,?)
                on conflict (cert_no) do update set name=excluded.name, dept=excluded.dept,
                    cert_expire_at=excluded.cert_expire_at, legal_qualified=excluded.legal_qualified,
                    enabled=excluded.enabled,
                    user_id=case when ? then excluded.user_id else enforcer.user_id end""",
                name, certNo, dept, expires, qualified, enabled, userId, mappingSpecified);
    }

    private String registryText(Object value, String label, boolean required) {
        if (value == null && !required) return null;
        if (!(value instanceof String text) || (required && text.isBlank()) || text.length() > 64)
            throw new BizException(2100, label + (required ? "须为非空字符串且不能超过64字" : "须为字符串且不能超过64字"));
        return text;
    }

    private boolean registryBoolean(Object value, boolean defaultValue, String label) {
        if (value == null) return defaultValue;
        if (!(value instanceof Boolean flag)) throw new BizException(2100, label + "须为布尔值");
        return flag;
    }

    private Long registryUserId(Object value) {
        if (value == null || value instanceof String text && text.isBlank()) return null;
        try {
            long id;
            if (value instanceof Number number) id = new java.math.BigDecimal(number.toString()).longValueExact();
            else if (value instanceof String text) id = Long.parseLong(text.trim());
            else throw new IllegalArgumentException();
            if (id <= 0) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new BizException(2100, "关联账号编号须为有效整数");
        }
    }

    // ---------- 听证子流程（辽46-50条） ----------

    public LocalDate heldHearingDate(CaseNotice notice) {
        var date = jdbc.queryForObject("""
                select max(held_at) from case_hearing where case_id = ?
                and status in ('HELD','OPINION_DONE') and held_at <= current_date
                and coalesce(btrim(record),'') <> ''
                and (notice_id=? or (
                    not ? and notice_id is null and held_at>=?
                    and (select count(*) from case_notice where case_id=?)=1
                ))""", java.sql.Date.class, notice.getCaseId(), notice.getId(),
                notice.isDeliveryRequired(), notice.getNotifiedAt(), notice.getCaseId());
        return date == null ? null : date.toLocalDate();
    }

    LocalDate noticeDeliveredAt(CaseNotice notice) {
        var delivered = jdbc.queryForObject("""
                select (select delivered_at from case_delivery where notice_id=? and delivered_at<=current_date)
                """, java.sql.Date.class, notice.getId());
        return delivered == null ? null : delivered.toLocalDate();
    }

    LocalDate requireNoticeDelivery(CaseNotice notice) {
        LocalDate deliveredAt = noticeDeliveredAt(notice);
        if (deliveredAt == null) throw new BizException(2006, "请先登记当前告知版本的实际送达回证");
        return deliveredAt;
    }

    static void requireNoticeVersion(CaseNotice notice, Long noticeId) {
        if ((notice.isDeliveryRequired() || noticeId != null) && !notice.getId().equals(noticeId))
            throw new BizException(2194, "告知版本已变化，请刷新后重新核对");
    }

    public record HearingScheduleReq(LocalDate announcedAt, LocalDate noticeSentAt, LocalDate scheduledAt,
                                     String host, String hostDept, String recorder, Long noticeId) {
        public HearingScheduleReq(LocalDate announcedAt, LocalDate noticeSentAt, LocalDate scheduledAt,
                                  String host, String hostDept, String recorder) {
            this(announcedAt, noticeSentAt, scheduledAt, host, hostDept, recorder, null);
        }
    }

    @Transactional
    public void scheduleHearing(Long caseId, HearingScheduleReq req) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        if (req == null || req.scheduledAt() == null || req.host() == null || req.host().isBlank())
            throw new BizException(2057, "听证举行日期与主持人必填");
        CaseFile c = caseFile(caseId);
        var notice = noticeRepository.findTopByCaseIdOrderByIdDesc(caseId)
                .orElseThrow(() -> new BizException(2039, "尚未作出处罚告知"));
        requireNoticeVersion(notice, req.noticeId());
        if (!Boolean.TRUE.equals(notice.getHearingRequested()))
            throw new BizException(2039, "当事人未申请听证");
        // 辽48条：听证主持人与办案人员应属于不同部门（不得为本案办案人员）
        List<Map<String, Object>> officers = jdbc.queryForList(
                "select name from case_officer where case_id = ?", caseId);
        if (officers.stream().anyMatch(o -> ((String) o.get("name")).strip().equals(req.host().strip())))
            throw new BizException(2056, "听证主持人不得为本案办案人员（辽48条）");
        // 同案不得存在两条未举行的排期：decide() 见到任一 SCHEDULED 即拦截，
        // 重复排期会留下一条永远无法消化的旧排期，把案件锁死在"不能决定"
        Integer scheduled = jdbc.queryForObject(
                "select count(*) from case_hearing where case_id = ? and status = 'SCHEDULED'",
                Integer.class, caseId);
        if (scheduled != null && scheduled > 0)
            throw new BizException(2057, "本案已有未举行的听证排期，如需改期请先撤销原排期");
        // 辽47条：举行听证7日前书面通知当事人（参数化）
        int ahead = config.intVal("hearing_notice_ahead_days", 7);
        LocalDate noticeSent = req.noticeSentAt() != null ? req.noticeSentAt() : LocalDate.now();
        if (notice.isDeliveryRequired()) {
            if (!"NOTIFIED".equals(c.getStatus())) throw new BizException(2057, "当前阶段不能安排听证");
            LocalDate deliveredAt = requireNoticeDelivery(notice);
            LocalDate earliest = notice.getHearingRequestedAt() == null ? deliveredAt : notice.getHearingRequestedAt();
            if (req.noticeSentAt() == null || noticeSent.isBefore(earliest) || noticeSent.isAfter(LocalDate.now()))
                throw new BizException(2057, "请填写当前告知版本听证申请后已实际送达通知的日期");
        }
        if (ChronoUnit.DAYS.between(noticeSent, req.scheduledAt()) < ahead)
            throw new BizException(2057, "听证应在通知送达后至少 " + ahead + " 日举行（辽47条）");
        jdbc.update("""
                insert into case_hearing (case_id, announced_at, notice_sent_at, scheduled_at, host, host_dept, recorder, notice_id)
                values (?,?,?,?,?,?,?,?)""",
                caseId, req.announcedAt(), noticeSent, req.scheduledAt(),
                req.host(), req.hostDept(), req.recorder(), notice.getId());
    }

    /**
     * 撤销未举行的听证排期（改期/当事人撤回申请）。
     * decide() 对 SCHEDULED 听证是硬拦截，若无此通道，一条填错日期的排期会让案件永久无法作出决定，
     * 唯一"解法"将是对从未举行的听证编造笔录——比卡住更坏。撤销留痕、不删行。
     */
    @Transactional
    public void cancelHearing(Long caseId, Long hearingId, String reason) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        if (reason == null || reason.isBlank())
            throw new BizException(2057, "撤销听证排期须说明理由（排期属案卷记录，不得无痕撤销）");
        int n = jdbc.update("""
                update case_hearing
                set status = 'CANCELLED',
                    record = coalesce(record, '') || '【排期已撤销】' || ?
                where id = ? and case_id = ? and status = 'SCHEDULED'""",
                reason, hearingId, caseId);
        if (n == 0) throw new BizException(2057, "听证不存在，或已举行/已撤销（仅未举行的排期可撤销）");
    }

    @Transactional
    public void holdHearing(Long caseId, Long hearingId, String record) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        if (record == null || record.isBlank()) throw new BizException(2057, "须制作听证笔录（辽50条）");
        // 实际举行日同样受"通知后满 N 日"约束：此前只校验计划日，当天举行也能通过
        var hr = jdbc.queryForList(
                "select scheduled_at, notice_sent_at, notice_id from case_hearing where id = ? and case_id = ?",
                hearingId, caseId);
        if (hr.isEmpty()) throw new BizException(2057, "听证不存在");
        CaseNotice notice = noticeRepository.findTopByCaseIdOrderByIdDesc(caseId).orElse(null);
        Object hearingNoticeId = hr.getFirst().get("notice_id");
        if (notice != null && (hearingNoticeId == null
                ? notice.isDeliveryRequired() || noticeRepository.findByCaseIdOrderByIdDesc(caseId).size() != 1
                : !notice.getId().equals(((Number) hearingNoticeId).longValue())))
            throw new BizException(2057, "该排期不属于当前告知版本，请核查后撤销旧排期并重新安排");
        if (notice != null && notice.isDeliveryRequired()) {
            if (!"NOTIFIED".equals(caseFile(caseId).getStatus())) throw new BizException(2057, "当前阶段不能登记听证举行");
            requireNoticeDelivery(notice);
        }
        LocalDate today = LocalDate.now();
        Object schedRaw = hr.get(0).get("scheduled_at");
        if (schedRaw != null && today.isBefore(((java.sql.Date) schedRaw).toLocalDate()))
            throw new BizException(2057, "尚未到听证计划举行日（" + schedRaw + "），不得提前登记举行");
        Object sentRaw = hr.get(0).get("notice_sent_at");
        int aheadDays = config.intVal("hearing_notice_ahead_days", 7);
        if (sentRaw != null && today.isBefore(((java.sql.Date) sentRaw).toLocalDate().plusDays(aheadDays)))
            throw new BizException(2057, "听证举行日距通知送达不足 " + aheadDays + " 日（辽48条）");
        int n = jdbc.update("""
                update case_hearing set held_at = ?, record = ?, status = 'HELD'
                where id = ? and case_id = ? and status = 'SCHEDULED'""",
                LocalDate.now(), record, hearingId, caseId);
        if (n == 0) throw new BizException(2057, "听证不存在或状态不允许");
        // 同步告知记录（听证已举行→决定前审查材料含听证报告）
        if (notice != null) {
            notice.setHearingHeldAt(today);
            noticeRepository.save(notice);
        }
    }

    @Transactional
    public void hearingOpinion(Long caseId, Long hearingId, String opinion) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
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
        CaseGuards.lock(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        entityManager.flush();
        CaseFile c = caseFile(caseId);
        entityManager.refresh(c);
        if (!"SUMMARY".equals(c.getProcedureType())) throw new BizException(2058, "仅简易程序案件需备案（第51条）");
        if (c.getDecidedAt() == null) throw new BizException(2058, "尚未作出当场处罚决定");
        // 幂等：重复调用会把备案日期刷成当天，掩盖"是否两日内备案"的监督证据
        if (c.getSummaryRecordAt() != null)
            throw new BizException(2058, "该案已于 " + c.getSummaryRecordAt() + " 备案，不可重复备案");
        jdbc.update("update case_file set summary_record_at=? where id=? and summary_record_at is null", LocalDate.now(), caseId);
        entityManager.refresh(c);
        return c;
    }

    // ---------- 移送台账（第10-12条/行刑衔接） ----------

    public record TransferReq(Long clueId, Long caseId, String direction, String targetOrg,
                              String kind, String reason, LocalDate sentAt, String note) {}

    @Transactional
    public void addTransfer(TransferReq req, String username) {
        if (req.clueId() != null) {
            sourceAccess.lockClueForHandling(req.clueId(), username);
            sourceAccess.assertRead("CLUE", req.clueId(), username);
            lockClueCases(req.clueId());
        }
        lockTransferCase(req);
        var actor = materialAccess.lockActor(username, true);
        if (req.caseId() != null) materialAccess.assertCaseScope(req.caseId(), actor);
        if (req.clueId() != null) sourceAccess.assertRead("CLUE", req.clueId(), username);
        insertTransfer(req);
    }

    @Transactional
    public void addTransfer(TransferReq req) {
        lockTransferCase(req);
        if (req.caseId() != null) materialAccess.requireCurrentCaseWriter(req.caseId());
        insertTransfer(req);
    }

    private void lockTransferCase(TransferReq req) {
        if (req.caseId() != null) {
            CaseGuards.lockWritable(jdbc, req.caseId());
            if (req.clueId() != null && !req.clueId().equals(caseFile(req.caseId()).getClueId()))
                throw new BizException(2059, "移送关联线索与案件来源不一致");
        }
    }

    private void insertTransfer(TransferReq req) {
        if (req.direction() == null || !List.of("OUT", "IN").contains(req.direction()))
            throw new BizException(2059, "方向须为 OUT/IN");
        if (req.kind() == null || !List.of("ADMIN", "JUDICIAL", "MSA").contains(req.kind()))
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
    public void confirmTransfer(Long transferId, String username) {
        var rows = jdbc.queryForList("select clue_id, case_id from case_transfer where id=?", transferId);
        if (rows.isEmpty()) throw new BizException(2059, "移送记录不存在");
        if (rows.get(0).get("clue_id") == null && rows.get(0).get("case_id") == null)
            throw new BizException(2059, "移送记录缺少有效来源");
        if (rows.get(0).get("clue_id") instanceof Number clue) {
            sourceAccess.lockClueForHandling(clue.longValue(), username);
            sourceAccess.assertRead("CLUE", clue.longValue(), username);
            lockClueCases(clue.longValue());
        }
        Long caseId = rows.get(0).get("case_id") instanceof Number value ? value.longValue() : null;
        if (caseId != null) CaseGuards.lockWritable(jdbc, caseId);
        var actor = materialAccess.lockActor(username, true);
        if (caseId != null) materialAccess.assertCaseScope(caseId, actor);
        if (rows.get(0).get("clue_id") instanceof Number clue)
            sourceAccess.assertRead("CLUE", clue.longValue(), username);
        markTransferConfirmed(transferId);
    }

    @Transactional
    public void confirmTransfer(Long transferId) {
        var transfer = jdbc.queryForList("select case_id from case_transfer where id = ?",transferId);
        if (!transfer.isEmpty() && transfer.get(0).get("case_id") instanceof Number caseId) {
            CaseGuards.lockWritable(jdbc, caseId.longValue());
            materialAccess.requireCurrentCaseWriter(caseId.longValue());
        }
        markTransferConfirmed(transferId);
    }

    private void markTransferConfirmed(Long transferId) {
        int n = jdbc.update("update case_transfer set confirmed_at = ? where id = ? and confirmed_at is null",
                LocalDate.now(), transferId);
        if (n == 0) throw new BizException(2059, "移送记录不存在或已确认");
    }

    // ---------- 协查台账（第34条/辽12-14条） ----------

    public record AssistReq(String direction, String org, String content, LocalDate sentAt) {}

    @Transactional
    public void addAssist(Long caseId, AssistReq req) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        caseFile(caseId);
        if (req.direction() == null || !List.of("OUT", "IN").contains(req.direction())) throw new BizException(2060, "方向须为 OUT/IN");
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
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        if (!refused && (result == null || result.isBlank()))
            throw new BizException(2060, "须填写协查复函结果");
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
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        CaseFile c = caseFile(caseId);
        if (receiver == null || receiver.isBlank())
            throw new BizException(2054, "须填写签署电子送达确认书的当事人/受送达人");
        if (channel == null || channel.isBlank())
            throw new BizException(2054, "须填写当事人确认的电子送达地址（手机号/邮箱等）");
        if (receiver.indexOf('\0') >= 0 || receiver.codePointCount(0, receiver.length()) > 64
                || channel.indexOf('\0') >= 0 || channel.codePointCount(0, channel.length()) > 500
                || docNo != null && (docNo.indexOf('\0') >= 0 || docNo.codePointCount(0, docNo.length()) > 64))
            throw new BizException(2054, "受送达人限64字、同意地址限500字、确认书编号限64字，不能含空字符");
        c.setEDeliveryConsent(true);
        caseRepository.save(c);
        jdbc.update("update case_file set e_delivery_receiver=?,e_delivery_channel=? where id=?", receiver, channel, caseId);
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

    private void lockClueCases(Long clueId) {
        for (Long caseId : jdbc.queryForList("select id from case_file where clue_id=? order by id", Long.class, clueId))
            CaseGuards.lockWritable(jdbc, caseId);
    }

    private CaseFile caseFile(Long caseId) {
        return caseRepository.findById(caseId).orElseThrow(() -> new BizException(2043, "案件不存在"));
    }
}
