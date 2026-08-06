package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.common.Workdays;
import cn.ybcase.bureau.entity.*;
import cn.ybcase.bureau.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * 案件办理核心服务。法定规则硬校验（错误码 2xxx），依据《医疗保障行政处罚程序暂行规定》（国家医保局令第4号）：
 * 2001 案由主体不符 | 2002 执法人员不足两人（第16条） | 2003 简易程序限额（第48条）
 * 2004 调查未终结 | 2005 未经法制审核不得决定（第37条） | 2006 决定前未告知（第41条）
 * 2007 不得因陈述申辩加重处罚（第41条） | 2008 超办案期限（第45条） | 2009 延期超上限（第45条）
 * 2010 当场收缴限额（第52条） | 2011 结案条件不满足（第56条） | 2012 加处罚款超本金（第55条）
 */
@Service
@RequiredArgsConstructor
public class CaseService {

    private final CaseFileRepository caseRepository;
    private final CaseCauseRepository causeRepository;
    private final CaseReviewRepository reviewRepository;
    private final CaseNoticeRepository noticeRepository;
    private final CaseDecisionRepository decisionRepository;
    private final ClueService clueService;
    private final JdbcTemplate jdbc;
    private final BureauConfig config;
    private final DocumentService documentService;
    private final ProcedureService procedureService;
    private final BizSeqService seqService;
    private final ExecutionService executionService;

    // ---------- 立案 ----------

    public record OfficerReq(String name, String certNo, String duty) {}

    public record CaseCreateReq(Long clueId, Long causeId, String procedureType,
                                String partyName, String partyType, String partyCreditNo,
                                String partyAddress, String partyLegalRep, String partyContact,
                                String summary, BigDecimal amountInvolved,
                                List<OfficerReq> officers, String clueVerifyResult,
                                Long enforceItemId, LocalDate violationEndDate, Boolean healthHarm) {}

    @Transactional
    public CaseFile create(CaseCreateReq req, String username) {
        if (req.officers() == null || req.officers().size() < 2)
            throw new BizException(2002, "执法人员不得少于两人（第16条）");
        CaseCause cause = causeRepository.findById(req.causeId())
                .orElseThrow(() -> new BizException(2026, "案由不存在"));
        if (!cause.getSubjectType().equals(req.partyType()))
            throw new BizException(2001, "当事人类别与案由违法主体类别不一致（苏医保督〔2024〕1号）");

        CaseFile c = new CaseFile();
        LocalDate today = LocalDate.now();
        // 第6条：追责时效——违法行为终了起2年（涉生命健康且有危害后果5年）未被发现不再处罚
        if (req.violationEndDate() != null) {
            int years = Boolean.TRUE.equals(req.healthHarm())
                    ? config.intVal("liability_years_health", 5) : config.intVal("liability_years", 2);
            if (req.violationEndDate().plusYears(years).isBefore(today))
                throw new BizException(2051, "违法行为终了已超过" + years + "年追责时效，不再给予行政处罚（第6条）；如有中断事由请核实终了日期");
            c.setViolationEndDate(req.violationEndDate());
            c.setHealthHarm(Boolean.TRUE.equals(req.healthHarm()));
        }
        c.setEnforceItemId(req.enforceItemId());
        c.setCaseNo(cfg("case_no_prefix", "医保案") + "〔" + today.getYear() + "〕"
                + seqService.next("CASE", today.getYear()) + "号");
        // 调查阶段案件名称：违法主体+涉嫌+案由+案
        c.setName(req.partyName() + "涉嫌" + cause.getCategory() + "案");
        c.setClueId(req.clueId());
        c.setCauseId(req.causeId());
        c.setProcedureType(req.procedureType() == null ? "NORMAL" : req.procedureType());
        c.setPartyName(req.partyName());
        c.setPartyType(req.partyType());
        c.setPartyCreditNo(req.partyCreditNo());
        c.setPartyAddress(req.partyAddress());
        c.setPartyLegalRep(req.partyLegalRep());
        c.setPartyContact(req.partyContact());
        c.setSummary(req.summary());
        c.setAmountInvolved(req.amountInvolved() == null ? BigDecimal.ZERO : req.amountInvolved());
        c.setFiledAt(today);
        c.setDeadlineAt(today.plusDays(config.intVal("case_deadline_days", 90)));  // 第45条：立案之日起九十日（可配）
        c.setCreatedBy(username);
        caseRepository.save(c);

        for (OfficerReq o : req.officers()) {
            procedureService.validateEnforcer(o.name(), o.certNo());  // 第16条：执法资格台账校验
            jdbc.update("insert into case_officer (case_id, name, cert_no, duty) values (?,?,?,?)",
                    c.getId(), o.name(), o.certNo(), o.duty() == null ? "MEMBER" : o.duty());
        }
        if (req.clueId() != null) clueService.markFiled(req.clueId(), req.clueVerifyResult());
        return c;
    }

    // ---------- 办案人员 ----------

    @Transactional
    public void addOfficer(Long caseId, OfficerReq req) {
        requireActive(get(caseId));
        procedureService.validateEnforcer(req.name(), req.certNo());
        jdbc.update("insert into case_officer (case_id, name, cert_no, duty) values (?,?,?,?)",
                caseId, req.name(), req.certNo(), req.duty() == null ? "MEMBER" : req.duty());
    }

    /** 回避（第5条）：主动回避或当事人申请，分级批准；回避后在册执法人员仍不得少于两人 */
    @Transactional
    public void avoidOfficer(Long caseId, Long officerId, String reason, String applicant, String decidedBy) {
        requireActive(get(caseId));
        Integer active = jdbc.queryForObject(
                "select count(*) from case_officer where case_id = ? and avoided = false and id <> ?",
                Integer.class, caseId, officerId);
        if (active == null || active < 2)
            throw new BizException(2002, "回避后执法人员将少于两人，请先补充办案人员（第16条）");
        if ("PARTY".equals(applicant) && (decidedBy == null || decidedBy.isBlank()))
            throw new BizException(2061, "当事人申请回避须经负责人审查决定并记录批准人（第5条）");
        jdbc.update("""
                update case_officer set avoided = true, avoid_reason = ?, avoid_applicant = ?, avoid_decided_by = ?
                where id = ? and case_id = ?""",
                reason, applicant == null ? "SELF" : applicant, decidedBy, officerId, caseId);
    }

    // ---------- 文书 ----------

    public record DocumentReq(String docType, String title, String content, LocalDate madeAt,
                              String maker, Boolean signed, String note, LocalDate dueAt) {}

    @Transactional
    public void addDocument(Long caseId, DocumentReq req) {
        CaseFile c = get(caseId);
        if ("CLOSED".equals(c.getStatus())) throw new BizException(2031, "案件已结案归档，不可新增文书");
        jdbc.update("""
                insert into case_document (case_id, doc_type, title, content, made_at, maker, signed, note, due_at)
                values (?,?,?,?,?,?,?,?,?)""",
                caseId, req.docType(), req.title(), req.content(),
                req.madeAt() != null ? req.madeAt() : LocalDate.now(),
                req.maker(), Boolean.TRUE.equals(req.signed()), req.note(), req.dueAt());
    }

    // ---------- 期限扣除（第45条） ----------

    public record ExclusionReq(String reason, LocalDate startAt, LocalDate endAt, String note) {}

    @Transactional
    public void addExclusion(Long caseId, ExclusionReq req) {
        requireActive(get(caseId));
        if (!List.of("TEST", "APPRAISE", "HEARING", "ANNOUNCE", "EXPERT").contains(req.reason()))
            throw new BizException(2032, "不计入期限的情形限于检测检验/鉴定/听证/公告/专家评审（第45条）");
        jdbc.update("insert into case_period_exclusion (case_id, reason, start_at, end_at, note) values (?,?,?,?,?)",
                caseId, req.reason(), req.startAt(), req.endAt(), req.note());
    }

    // ---------- 中止 / 恢复 / 终止 ----------

    @Transactional
    public CaseFile suspend(Long caseId, String reason) {
        CaseFile c = get(caseId);
        if (!"INVESTIGATING".equals(c.getStatus())) throw new BizException(2033, "仅调查中的案件可中止（第42条）");
        if (reason == null || reason.isBlank()) throw new BizException(2033, "中止须载明法定情形并经负责人批准（第42条）");
        c.setStatus("SUSPENDED");
        c.setSuspendReason(reason);
        c.setSuspendedAt(LocalDate.now());
        return caseRepository.save(c);
    }

    /** 中止原因消除后立即恢复调查；中止期间顺延办案期限 */
    @Transactional
    public CaseFile resume(Long caseId) {
        CaseFile c = get(caseId);
        if (!"SUSPENDED".equals(c.getStatus())) throw new BizException(2034, "案件不在中止状态");
        long suspendedDays = ChronoUnit.DAYS.between(c.getSuspendedAt(), LocalDate.now());
        c.setDeadlineAt(c.getDeadlineAt().plusDays(suspendedDays));
        c.setStatus("INVESTIGATING");
        c.setSuspendReason(null);
        c.setSuspendedAt(null);
        return caseRepository.save(c);
    }

    @Transactional
    public CaseFile terminate(Long caseId, String reason) {
        CaseFile c = get(caseId);
        if (List.of("DECIDED", "DELIVERED", "CLOSED", "TERMINATED").contains(c.getStatus()))
            throw new BizException(2035, "已决定/已结案的案件不可终止调查（第47条）");
        if (reason == null || reason.isBlank()) throw new BizException(2035, "终止须载明法定情形并经负责人批准（第47条）");
        // 第47条：终止调查的，已采取的强制措施应当同时解除
        jdbc.update("update case_evidence set sealed = false, register_hold = false where case_id = ?", caseId);
        c.setStatus("TERMINATED");
        c.setTerminateReason(reason);
        c.setTerminatedAt(LocalDate.now());
        return caseRepository.save(c);
    }

    // ---------- 延期（第45条：+30；集体讨论再延最长+60） ----------

    @Transactional
    public CaseFile extend(Long caseId, int days, String reason) {
        CaseFile c = get(caseId);
        requireActive(c);
        if (days <= 0) throw new BizException(2009, "延长天数须为正数");
        int firstMax = config.intVal("extension_first_max", 30);
        int totalMax = config.intVal("extension_total_max", 90);
        int already = c.getExtensionDays();
        if (already == 0) {
            if (days > firstMax) throw new BizException(2009, "首次延期经负责人批准最长" + firstMax + "日（第45条）");
        } else {
            if (already + days > totalMax) throw new BizException(2009, "继续延期须集体讨论且累计不超过" + totalMax + "日（第45条）");
            boolean hasMeeting = !jdbc.queryForList("select id from case_meeting where case_id = ?", caseId).isEmpty();
            if (!hasMeeting) throw new BizException(2009, "继续延期应当由负责人集体讨论决定，请先录入集体讨论记录（第45条）");
        }
        if (reason == null || reason.isBlank()) throw new BizException(2009, "延期须说明理由");
        c.setExtensionDays(already + days);
        c.setDeadlineAt(c.getDeadlineAt().plusDays(days));
        return caseRepository.save(c);
    }

    // ---------- 调查终结（第36条） ----------

    @Transactional
    public CaseFile report(Long caseId, String reportContent, String maker) {
        CaseFile c = get(caseId);
        if (!"INVESTIGATING".equals(c.getStatus())) throw new BizException(2004, "仅调查中的案件可出具调查终结报告");
        if ("SUMMARY".equals(c.getProcedureType())) throw new BizException(2004, "简易程序无需调查终结报告，可直接当场决定");
        if (reportContent == null || reportContent.isBlank())
            throw new BizException(2004, "调查终结报告须包含当事人情况/案件来源/事实证据/性质/处理意见（第36条）");
        addDocument(caseId, new DocumentReq("FINAL_REPORT", c.getCaseNo() + " 案件调查终结报告",
                reportContent, LocalDate.now(), maker, false, null, null));
        c.setStatus("REPORTED");
        c.setReportedAt(LocalDate.now());
        return caseRepository.save(c);
    }

    // ---------- 法制审核（第37-40条） ----------

    @Transactional
    public CaseReview submitReview(Long caseId, String requiredReason) {
        CaseFile c = get(caseId);
        if (!List.of("REPORTED", "NOTIFIED").contains(c.getStatus()))
            throw new BizException(2036, "法制审核在调查终结后、作出决定前进行（第37条）");
        CaseReview r = new CaseReview();
        r.setCaseId(caseId);
        r.setRequiredReason(requiredReason);
        r.setSubmittedAt(LocalDate.now());
        // 国家第40条：10个工作日；辽41条：7日+3——参数化
        r.setDeadlineAt(config.plusByUnit(LocalDate.now(),
                config.intVal("legal_review_days", 10), "legal_review_day_unit"));
        return reviewRepository.save(r);
    }

    @Transactional
    public CaseReview doReview(Long reviewId, String reviewer, String opinionType, String opinion) {
        CaseReview r = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BizException(2037, "审核记录不存在"));
        if (r.getReviewedAt() != null) throw new BizException(2037, "该审核已办结");
        if (!List.of("AGREE", "CONTINUE", "CHANGE", "CORRECT", "OTHER").contains(opinionType))
            throw new BizException(2037, "审核意见类型须为 同意/继续调查/变更/纠正/其他（第39条）");
        // 第37条：办案人员不得作为审核人员
        List<Map<String, Object>> officers = jdbc.queryForList(
                "select name from case_officer where case_id = ?", r.getCaseId());
        if (officers.stream().anyMatch(o -> o.get("name").equals(reviewer)))
            throw new BizException(2038, "同一案件的办案人员不得作为法制审核人员（第37条）");
        // 辽41条：从事案件审核的人员应通过法律职业资格考试（参数开关，比对执法证台账）
        if (config.bool("review_legal_qualified_required", true)) {
            Integer qualified = jdbc.queryForObject(
                    "select count(*) from enforcer where name = ? and legal_qualified = true and enabled = true",
                    Integer.class, reviewer);
            if (qualified == null || qualified == 0)
                throw new BizException(2070, "审核人 " + reviewer + " 未在台账中登记法律职业资格（辽41条）");
        }
        r.setReviewer(reviewer);
        r.setOpinionType(opinionType);
        r.setOpinion(opinion);
        r.setReviewedAt(LocalDate.now());
        r.setPassed("AGREE".equals(opinionType));
        return reviewRepository.save(r);
    }

    // ---------- 处罚告知/陈述申辩/听证（第41条） ----------

    public record NoticeReq(String content, BigDecimal proposedFine, BigDecimal proposedRecoup) {}

    @Transactional
    public CaseNotice notify(Long caseId, NoticeReq req) {
        CaseFile c = get(caseId);
        // REPORTED 首次告知；NOTIFIED 允许再次告知（辽52条：改变原认定的事实/证据/依据须重新履行告知程序）
        if (!List.of("REPORTED", "NOTIFIED").contains(c.getStatus()))
            throw new BizException(2004, "调查终结后方可作出处罚告知（第36/41条）");
        CaseNotice n = new CaseNotice();
        n.setCaseId(caseId);
        n.setNotifiedAt(LocalDate.now());
        n.setContent(req.content());
        n.setProposedFine(nz(req.proposedFine()));
        n.setProposedRecoup(nz(req.proposedRecoup()));
        // 陈述申辩期限（辽44条：告知须载明期限与逾期后果；0=不启用）
        int stmtDays = config.intVal("statement_deadline_days", 3);
        if (stmtDays > 0) n.setStatementDeadline(LocalDate.now().plusDays(stmtDays));
        // 拟罚款达到听证标准（按当事人类型分档，辽46条）：告知听证权利
        n.setHearingEntitled(nz(req.proposedFine()).compareTo(config.byPartyType(c.getPartyType(),
                "hearing_threshold_individual", "hearing_threshold_org", "100000")) >= 0);
        noticeRepository.save(n);
        c.setStatus("NOTIFIED");
        caseRepository.save(c);
        return n;
    }

    public record StatementReq(String statement, String statementReview,
                               Boolean hearingRequested, LocalDate hearingHeldAt) {}

    @Transactional
    public CaseNotice recordStatement(Long caseId, StatementReq req) {
        CaseNotice n = noticeRepository.findTopByCaseIdOrderByIdDesc(caseId)
                .orElseThrow(() -> new BizException(2006, "尚未作出处罚告知"));
        // 辽44条：期限内未行使陈述权、申辩权的，视为放弃
        if (req.statement() != null && n.getStatementDeadline() != null
                && LocalDate.now().isAfter(n.getStatementDeadline()))
            throw new BizException(2046, "已超过陈述申辩期限（" + n.getStatementDeadline() + "），视为放弃权利（辽44条）");
        if (req.statement() != null) n.setStatement(req.statement());
        if (req.statementReview() != null) n.setStatementReview(req.statementReview());
        if (req.hearingRequested() != null) {
            if (Boolean.TRUE.equals(req.hearingRequested()) && !Boolean.TRUE.equals(n.getHearingEntitled()))
                throw new BizException(2039, "该案未达听证标准，无听证权利告知记录");
            // 辽46条：听证申请应当自告知之日起3日内提出（参数化）
            int reqDays = config.intVal("hearing_request_days", 3);
            if (Boolean.TRUE.equals(req.hearingRequested()) && reqDays > 0
                    && LocalDate.now().isAfter(n.getNotifiedAt().plusDays(reqDays)))
                throw new BizException(2046, "听证申请已超过期限（告知起" + reqDays + "日内）");
            n.setHearingRequested(req.hearingRequested());
        }
        if (req.hearingHeldAt() != null) {
            if (!Boolean.TRUE.equals(n.getHearingRequested()))
                throw new BizException(2039, "当事人未申请听证");
            n.setHearingHeldAt(req.hearingHeldAt());
        }
        return noticeRepository.save(n);
    }

    // ---------- 集体讨论（第44条） ----------

    public record MeetingReq(LocalDate heldAt, String attendees, String record, String conclusion) {}

    @Transactional
    public void addMeeting(Long caseId, MeetingReq req) {
        get(caseId);
        jdbc.update("insert into case_meeting (case_id, held_at, attendees, record, conclusion) values (?,?,?,?,?)",
                caseId, req.heldAt() != null ? req.heldAt() : LocalDate.now(),
                req.attendees(), req.record(), req.conclusion());
    }

    // ---------- 处理决定（第43-45条） ----------

    public record DecisionReq(String decisionType, BigDecimal fineAmount, BigDecimal recoupAmount,
                              BigDecimal confiscateAmount, String otherMeasures, String content,
                              String mitigation, String discretionReason) {}

    @Transactional
    public CaseDecision decide(Long caseId, DecisionReq req) {
        CaseFile c = get(caseId);
        if (decisionRepository.findByCaseId(caseId).isPresent())
            throw new BizException(2040, "该案已作出处理决定");
        if (!List.of("PUNISH", "NO_PUNISH", "NOT_ESTABLISHED", "TRANSFER_ADMIN", "TRANSFER_JUDICIAL")
                .contains(req.decisionType()))
            throw new BizException(2040, "决定类型须为第43条规定的五种之一");

        BigDecimal fine = nz(req.fineAmount());
        BigDecimal recoup = nz(req.recoupAmount());
        boolean summary = "SUMMARY".equals(c.getProcedureType());

        if (summary) {
            if (!"INVESTIGATING".equals(c.getStatus()))
                throw new BizException(2003, "简易程序应在调查中当场作出决定（第48条）");
            // 第48条：公民/单位限额（参数化：新处罚法200/3000，辽宁旧口径50/1000）
            BigDecimal limit = config.byPartyType(c.getPartyType(),
                    "summary_fine_limit_individual", "summary_fine_limit_org",
                    "INDIVIDUAL".equals(c.getPartyType()) ? "200" : "3000");
            if ("PUNISH".equals(req.decisionType()) && fine.compareTo(limit) > 0)
                throw new BizException(2003, "简易程序罚款超限（当事人类别限额 " + limit + " 元），请转普通程序（第48条）");
        } else {
            if (!"NOTIFIED".equals(c.getStatus()))
                throw new BizException(2006, "作出处罚决定前应当书面告知当事人并听取陈述申辩（第41条）");
            CaseNotice notice = noticeRepository.findTopByCaseIdOrderByIdDesc(caseId)
                    .orElseThrow(() -> new BizException(2006, "缺少处罚告知记录（第41条）"));
            // 第41条：不得因陈述、申辩或申请听证而加重处罚
            if (fine.compareTo(notice.getProposedFine()) > 0 || recoup.compareTo(notice.getProposedRecoup()) > 0)
                throw new BizException(2007, "决定金额不得高于告知金额——不得因陈述申辩而加重处罚（第41条）");
            // 法制审核：THRESHOLD=数额较大或经听证必审（国家37条）；ALL=全案必审（辽40条）
            boolean needReview = "ALL".equalsIgnoreCase(config.str("legal_review_mode", "THRESHOLD"))
                    || fine.compareTo(cfgDecimal("legal_review_fine_threshold")) >= 0
                    || notice.getHearingHeldAt() != null;
            if (needReview) {
                CaseReview review = reviewRepository.findTopByCaseIdOrderByIdDesc(caseId).orElse(null);
                if (review == null || !Boolean.TRUE.equals(review.getPassed()))
                    throw new BizException(2005, "本案属法制审核范围，未经审核通过不得作出决定（第37条/辽40条）");
            }
            // 辽24条：未经当事人质证（发表意见）的证据不能作为处罚决定依据（参数开关）
            if (config.bool("cross_exam_required", false) && "PUNISH".equals(req.decisionType())) {
                Integer unexamined = jdbc.queryForObject(
                        "select count(*) from case_evidence where case_id = ? and cross_exam_at is null",
                        Integer.class, caseId);
                if (unexamined != null && unexamined > 0)
                    throw new BizException(2045, "尚有 " + unexamined + " 份证据未经当事人质证，不能作为决定依据（辽24条）");
            }
            // 辽54条：较大数额罚款必须经负责人集体讨论决定（按当事人类型分档）
            if ("PUNISH".equals(req.decisionType())) {
                BigDecimal meetingThreshold = config.byPartyType(c.getPartyType(),
                        "meeting_required_fine_individual", "meeting_required_fine_org", "100000");
                boolean hasMeeting = !jdbc.queryForList("select id from case_meeting where case_id = ?", caseId).isEmpty();
                if (fine.compareTo(meetingThreshold) >= 0 && !hasMeeting)
                    throw new BizException(2047, "较大数额罚款（≥" + meetingThreshold + "元）应当经负责人集体讨论决定，请先录入讨论记录（辽54条/局令44条）");
            }
            // 辽44条：裁量性处罚决定应说明裁量考虑因素（参数开关）
            if ("PUNISH".equals(req.decisionType()) && config.bool("discretion_reason_required", true)
                    && (req.discretionReason() == null || req.discretionReason().isBlank()))
                throw new BizException(2062, "处罚决定须说明裁量理由/考虑因素（辽44条），可先查看裁量基准建议");
            // 第45条：办案期限（含批准延长），扣除期间不计入
            LocalDate effectiveDeadline = c.getDeadlineAt().plusDays(totalExclusionDays(caseId));
            if (LocalDate.now().isAfter(effectiveDeadline))
                throw new BizException(2008, "已超办案期限（" + effectiveDeadline + "），请先办理延期审批（第45条）");
        }

        CaseDecision d = new CaseDecision();
        d.setCaseId(caseId);
        d.setDecisionType(req.decisionType());
        if ("PUNISH".equals(req.decisionType())) {
            d.setDecisionNo(cfg("decision_no_prefix", "医保罚") + "〔" + LocalDate.now().getYear() + "〕"
                    + seqService.next("DECISION", LocalDate.now().getYear()) + "号");
        }
        d.setFineAmount(fine);
        d.setRecoupAmount(recoup);
        d.setConfiscateAmount(nz(req.confiscateAmount()));
        d.setOtherMeasures(req.otherMeasures());
        d.setContent(req.content());
        d.setMitigation(req.mitigation());
        d.setDiscretionReason(req.discretionReason());
        d.setDecidedAt(LocalDate.now());
        decisionRepository.save(d);

        c.setDecidedAt(LocalDate.now());
        c.setName(c.getName().replace("涉嫌", ""));  // 苏医保督〔2024〕1号：决定后案件名称去"涉嫌"
        if ("TRANSFER_JUDICIAL".equals(req.decisionType())) {
            // 第47条：移送司法机关追究刑事责任的，终止调查并解除强制措施
            jdbc.update("update case_evidence set sealed = false, register_hold = false where case_id = ?", caseId);
            c.setStatus("TERMINATED");
            c.setTerminateReason("移送司法机关追究刑事责任（第43/47条联动）");
            c.setTerminatedAt(LocalDate.now());
        } else {
            c.setStatus("DECIDED");
        }
        caseRepository.save(c);
        return d;
    }

    // ---------- 送达（第59条） ----------

    public record DeliveryReq(String method, LocalDate deliveredAt, String receiver, String note,
                              String receiptNo, LocalDate receiptSignedAt) {}

    @Transactional
    public CaseFile deliver(Long caseId, DeliveryReq req) {
        CaseFile c = get(caseId);
        if (!"DECIDED".equals(c.getStatus())) throw new BizException(2041, "仅已决定的案件可登记送达（第59条）");
        if (!List.of("DIRECT", "MAIL", "LEFT", "ELECTRONIC", "ANNOUNCE").contains(req.method()))
            throw new BizException(2041, "送达方式须为 直接/邮寄/留置/电子/公告 之一");
        // 辽61条：行政处罚决定书不得电子送达（国家局令59条允许但须签确认书——参数开关）
        if ("ELECTRONIC".equals(req.method()) && !config.bool("delivery_electronic_decision_allowed", true))
            throw new BizException(2049, "当前规则下处罚决定书不得电子送达（辽61条），请改用直接/邮寄/留置/公告送达");
        // 第59条：电子送达以当事人同意并签订确认书为前提
        if ("ELECTRONIC".equals(req.method()) && !Boolean.TRUE.equals(c.getEDeliveryConsent()))
            throw new BizException(2054, "当事人未签订电子送达确认书，不得电子送达（第59条）");
        // 辽58条：送达回证签收日期为送达日期；辽60条：公告送达自公告之日起满60日视为送达
        LocalDate deliveredAt;
        if ("ANNOUNCE".equals(req.method())) {
            LocalDate announceDate = req.deliveredAt() != null ? req.deliveredAt() : LocalDate.now();
            deliveredAt = announceDate.plusDays(config.intVal("announce_deliver_days", 60));
        } else {
            deliveredAt = req.receiptSignedAt() != null ? req.receiptSignedAt()
                    : (req.deliveredAt() != null ? req.deliveredAt() : LocalDate.now());
        }
        jdbc.update("""
                insert into case_delivery (case_id, method, delivered_at, receiver, note, receipt_no, receipt_signed_at)
                values (?,?,?,?,?,?,?)""",
                caseId, req.method(), deliveredAt, req.receiver(), req.note(),
                req.receiptNo(), req.receiptSignedAt());
        c.setStatus("DELIVERED");
        c.setDeliveredAt(deliveredAt);
        return caseRepository.save(c);
    }

    // ---------- 执行（第52-55条） ----------

    /** 处罚决定公开（局令46条；辽56条：作出决定7日内公开） */
    @Transactional
    public CaseDecision publish(Long caseId) {
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2042, "案件无处理决定"));
        d.setPublished(true);
        d.setPublishedAt(LocalDate.now());
        return decisionRepository.save(d);
    }

    /** 重大处罚决定政府备案登记（辽54条） */
    @Transactional
    public CaseDecision govRecord(Long caseId, String recordNo) {
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2042, "案件无处理决定"));
        if (recordNo == null || recordNo.isBlank()) throw new BizException(2042, "须填写备案文号");
        d.setGovRecordNo(recordNo);
        d.setGovRecordAt(LocalDate.now());
        return decisionRepository.save(d);
    }

    // ---------- 结案（第56-57条） ----------

    @Transactional
    public CaseFile close(Long caseId, String closeReport, String maker) {
        CaseFile c = get(caseId);
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2011, "无处理决定，不能结案"));
        if (!List.of("DECIDED", "DELIVERED").contains(c.getStatus()))
            throw new BizException(2011, "案件状态不满足结案条件");

        String closeReason;
        if ("PUNISH".equals(d.getDecisionType())) {
            if (!"DELIVERED".equals(c.getStatus()))
                throw new BizException(2011, "处罚决定书未送达，不能结案（第59条）");
            if (Boolean.TRUE.equals(c.getCourtEnforceApplied())) {
                closeReason = "COURT";     // 第56条(二)：申请法院强制执行且受理
            } else if (fullyExecuted(caseId, d)) {
                closeReason = "EXECUTED";  // 第56条(一)：执行完毕
            } else {
                throw new BizException(2011, "罚没款项/退回基金未执行完毕，且未申请法院强制执行，不能结案（第56条）");
            }
        } else {
            closeReason = "NO_NEED";       // 第56条(三)：不予处罚等无须执行
        }

        if (closeReport == null || closeReport.isBlank())
            throw new BizException(2011, "须填写行政处罚结案报告并经负责人批准（第56条）");
        addDocument(caseId, new DocumentReq("CLOSE_REPORT", c.getCaseNo() + " 结案报告",
                closeReport, LocalDate.now(), maker, false, null, null));
        // 第57条：一案一卷、文书齐全（参数开启时强制校验必备文书）
        if (config.bool("archive_completeness_required", false)) {
            List<String> missing = documentService.missingRequiredDocs(caseId);
            if (!missing.isEmpty())
                throw new BizException(2052, "案卷必备文书缺失：" + String.join("、", missing) + "（第57条文书齐全要求）");
        }

        c.setStatus("CLOSED");
        c.setClosedAt(LocalDate.now());
        c.setCloseReason(closeReason);
        c.setArchiveNo(c.getCaseNo() + "卷");  // 第57条：一案一卷
        return caseRepository.save(c);
    }

    // ---------- 查询 ----------

    public CaseFile get(Long id) {
        return caseRepository.findById(id).orElseThrow(() -> new BizException(2043, "案件不存在"));
    }

    public Map<String, Object> detail(Long id) {
        CaseFile c = get(id);
        CaseCause cause = causeRepository.findById(c.getCauseId()).orElse(null);
        return Map.ofEntries(
                Map.entry("caseFile", c),
                Map.entry("cause", cause == null ? Map.of() : cause),
                Map.entry("officers", jdbc.queryForList("select * from case_officer where case_id = ? order by id", id)),
                Map.entry("evidences", jdbc.queryForList("select * from case_evidence where case_id = ? order by id", id)),
                Map.entry("documents", jdbc.queryForList(
                        "select id, doc_type, title, made_at, maker, signed, note from case_document where case_id = ? order by id", id)),
                Map.entry("exclusions", jdbc.queryForList("select * from case_period_exclusion where case_id = ? order by id", id)),
                Map.entry("reviews", reviewRepository.findByCaseIdOrderByIdDesc(id)),
                Map.entry("notices", noticeRepository.findByCaseIdOrderByIdDesc(id)),
                Map.entry("meetings", jdbc.queryForList("select * from case_meeting where case_id = ? order by id", id)),
                Map.entry("decision", decisionRepository.findByCaseId(id).map(Object.class::cast).orElse(Map.of())),
                Map.entry("expertReviews", jdbc.queryForList("select * from expert_review where case_id = ? order by id", id)),
                Map.entry("hearings", jdbc.queryForList("select * from case_hearing where case_id = ? order by id", id)),
                Map.entry("assists", jdbc.queryForList("select * from case_assist where case_id = ? order by id", id)),
                Map.entry("deliveries", jdbc.queryForList("select * from case_delivery where case_id = ? order by id", id)),
                Map.entry("executions", jdbc.queryForList("select * from case_execution where case_id = ? order by id", id)),
                Map.entry("effectiveDeadline", c.getDeadlineAt().plusDays(totalExclusionDays(id))));
    }

    public Map<String, Object> documentDetail(Long caseId, Long docId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from case_document where id = ? and case_id = ?", docId, caseId);
        if (rows.isEmpty()) throw new BizException(2045, "文书不存在");
        return rows.get(0);
    }

    // ---------- 私有 ----------

    private void requireActive(CaseFile c) {
        if (!"INVESTIGATING".equals(c.getStatus()) && !"REPORTED".equals(c.getStatus())
                && !"NOTIFIED".equals(c.getStatus()))
            throw new BizException(2044, "案件当前状态（" + c.getStatus() + "）不允许该操作");
    }

    private long totalExclusionDays(Long caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select start_at, end_at from case_period_exclusion where case_id = ? and end_at is not null", caseId);
        return rows.stream().mapToLong(r -> ChronoUnit.DAYS.between(
                ((java.sql.Date) r.get("start_at")).toLocalDate(),
                ((java.sql.Date) r.get("end_at")).toLocalDate())).sum();
    }

    private boolean fullyExecuted(Long caseId, CaseDecision d) {
        return executionService.sum(caseId, "FINE").compareTo(d.getFineAmount()) >= 0
                && executionService.sum(caseId, "RECOUP").compareTo(d.getRecoupAmount()) >= 0
                && executionService.sum(caseId, "CONFISCATE").compareTo(d.getConfiscateAmount()) >= 0;
    }

    private String cfg(String key, String def) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select cfg_value from sys_config where cfg_key = ?", key);
        return rows.isEmpty() ? def : (String) rows.get(0).get("cfg_value");
    }

    private BigDecimal cfgDecimal(String key) {
        return new BigDecimal(cfg(key, "100000"));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
