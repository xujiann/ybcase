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
    private final MessageService messageService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

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
        // 按执法证号去重计数：重复填同一人不算两人（第16条）
        if (req.officers() == null
                || req.officers().stream().map(OfficerReq::certNo).filter(java.util.Objects::nonNull)
                       .map(String::trim).distinct().count() < 2)
            throw new BizException(2002, "执法人员不得少于两人（第16条），且须为不同执法证号");
        CaseCause cause = causeRepository.findById(req.causeId())
                .orElseThrow(() -> new BizException(2026, "案由不存在"));
        if (!cause.getSubjectType().equals(req.partyType()))
            throw new BizException(2001, "当事人类别与案由违法主体类别不一致（苏医保督〔2024〕1号）");

        CaseFile c = new CaseFile();
        LocalDate today = LocalDate.now();
        // 第6条：追责时效——违法行为终了起2年（涉生命健康且有危害后果5年）未被发现不再处罚
        // 不填终了日即完全绕过时效检查，故提供强制开关（默认开启，历史数据迁移期可关）
        if (req.violationEndDate() == null && config.bool("violation_end_date_required", false))
            throw new BizException(2051, "须填写违法行为终了日期，用于追责时效判定（第6条）；确无法确定的，请由管理员关闭该校验开关");
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
        c.setOwnerUser(username);  // 承办人=立案申请人（可移交）
        var deptRows = jdbc.queryForList("select dept_id from sys_user where username = ?", username);
        if (!deptRows.isEmpty() && deptRows.get(0).get("dept_id") != null)
            c.setOwnerDeptId(((Number) deptRows.get(0).get("dept_id")).longValue());
        caseRepository.save(c);

        for (OfficerReq o : req.officers()) {
            procedureService.validateEnforcer(o.name(), o.certNo());  // 第16条：执法资格台账校验
            jdbc.update("insert into case_officer (case_id, name, cert_no, duty, user_id) values (?,?,?,?,?)",
                    c.getId(), o.name(), o.certNo(), o.duty() == null ? "MEMBER" : o.duty(),
                    resolveOfficerUserId(o.name(), o.certNo()));
        }
        if (req.clueId() != null) clueService.markFiled(req.clueId(), req.clueVerifyResult());
        return c;
    }

    // ---------- 办案人员 ----------

    @Transactional
    public void addOfficer(Long caseId, OfficerReq req) {
        requireActive(get(caseId));
        procedureService.validateEnforcer(req.name(), req.certNo());
        Integer dup = jdbc.queryForObject(
                "select count(*) from case_officer where case_id = ? and cert_no = ? and avoided = false",
                Integer.class, caseId, req.certNo());
        if (dup != null && dup > 0) throw new BizException(2002, "该执法证号已在本案办案人员名单中");
        jdbc.update("insert into case_officer (case_id, name, cert_no, duty, user_id) values (?,?,?,?,?)",
                caseId, req.name(), req.certNo(), req.duty() == null ? "MEMBER" : req.duty(),
                resolveOfficerUserId(req.name(), req.certNo()));
    }

    /**
     * 办案人员 → 系统账号：只认执法证台账里维护的权威映射（按证号）。
     * 不按姓名兜底——"重名唯一"只保证 sys_user 内不重名，会把另一岗位的同名账号误绑，
     * 凭空给它本案数据权限。解析不到就留空，由管理员在执法证台账补映射（数据范围从严）。
     */
    private Long resolveOfficerUserId(String name, String certNo) {
        var byCert = jdbc.queryForList(
                "select user_id from enforcer where cert_no = ? and user_id is not null", certNo);
        return byCert.isEmpty() ? null : ((Number) byCert.get(0).get("user_id")).longValue();
    }

    /** 回避（第5条）：主动回避或当事人申请，分级批准；回避后在册执法人员仍不得少于两人 */
    @Transactional
    public void avoidOfficer(Long caseId, Long officerId, String reason, String applicant, String decidedBy) {
        requireActive(get(caseId));
        Integer active = jdbc.queryForObject(
                "select count(distinct cert_no) from case_officer where case_id = ? and avoided = false and id <> ?",
                Integer.class, caseId, officerId);
        if (active == null || active < 2)
            throw new BizException(2002, "回避后执法人员将少于两人，请先补充办案人员（第16条）");
        // 第5条分级批准：当事人申请回避由本机关负责人审查决定，须记录批准人；
        // 主办（LEAD）人员回避影响办案组织，同样须经批准——主动回避（SELF）报告即可，不强求批准人
        if ("PARTY".equals(applicant) && (decidedBy == null || decidedBy.isBlank()))
            throw new BizException(2061, "当事人申请回避须经负责人审查决定并记录批准人（第5条）");
        var offRow = jdbc.queryForList("select duty from case_officer where id = ? and case_id = ?", officerId, caseId);
        if (!offRow.isEmpty() && "LEAD".equals(offRow.get(0).get("duty"))
                && (decidedBy == null || decidedBy.isBlank()))
            throw new BizException(2061, "主办人员回避须经负责人批准并记录批准人（第5条）");
        if (reason == null || reason.isBlank())
            throw new BizException(2061, "回避须载明事由（第5条）");
        int n = jdbc.update("""
                update case_officer set avoided = true, avoid_reason = ?, avoid_applicant = ?, avoid_decided_by = ?
                where id = ? and case_id = ? and avoided = false""",
                reason, applicant == null ? "SELF" : applicant, decidedBy, officerId, caseId);
        if (n == 0) throw new BizException(2061, "办案人员不存在于本案或已回避");
    }

    // ---------- 文书 ----------

    public record DocumentReq(String docType, String title, String content, LocalDate madeAt,
                              String maker, Boolean signed, String note, LocalDate dueAt) {}

    @Transactional
    public void addDocument(Long caseId, DocumentReq req) {
        CaseFile c = get(caseId);
        // 判"已封卷"用案卷号而非状态：终止调查/移送司法的案件归档后状态仍是 TERMINATED，
        // 只认 CLOSED 会让这批已立卷的案件还能继续往卷里塞文书
        if ("CLOSED".equals(c.getStatus()) || c.getArchiveNo() != null)
            throw new BizException(2031, "案件已立卷归档，不可新增文书");
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
        CaseFile c = get(caseId);
        requireActive(c);
        if (!List.of("TEST", "APPRAISE", "HEARING", "ANNOUNCE", "EXPERT").contains(req.reason()))
            throw new BizException(2032, "不计入期限的情形限于检测检验/鉴定/听证/公告/专家评审（第45条）");
        // 期限扣除会直接推后办案期限，是绕开"延期须负责人批准且累计≤上限"的口子——须严格校验区间
        if (req.startAt() == null) throw new BizException(2032, "须填写不计入期限的起始日期");
        if (req.startAt().isBefore(c.getFiledAt()))
            throw new BizException(2032, "起始日期不得早于立案日期（" + c.getFiledAt() + "）");
        if (req.startAt().isAfter(LocalDate.now()))
            throw new BizException(2032, "起始日期不得晚于今天");
        if (req.endAt() != null) {
            if (req.endAt().isBefore(req.startAt()))
                throw new BizException(2032, "结束日期不得早于起始日期");
            long span = ChronoUnit.DAYS.between(req.startAt(), req.endAt()) + 1;
            int max = config.intVal("exclusion_max_days", 180);
            if (span > max)
                throw new BizException(2032, "单次不计入期限的天数（" + span + "日）超过上限 " + max + " 日，请核实起止日期");
        }
        // 区间重叠会被重复扣除，等于凭空延长期限
        Integer overlap = jdbc.queryForObject("""
                select count(*) from case_period_exclusion
                where case_id = ? and coalesce(end_at, current_date) >= ? and start_at <= ?""",
                Integer.class, caseId, req.startAt(), req.endAt() == null ? LocalDate.now() : req.endAt());
        if (overlap != null && overlap > 0)
            throw new BizException(2032, "该时间段与已登记的不计入期限区间重叠，不得重复扣除");
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
            // 与 decide() 的 2047 同口径：只判"有行"会让一条未签字确认的记录就放行继续延期，
            // 同一份法定的集体讨论记录不能在两处有两套有效性标准
            if (!hasValidMeeting(caseId))
                throw new BizException(2009, "继续延期应当由负责人集体讨论决定，请先录入并签字确认集体讨论记录（第45条）");
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

    public record NoticeReq(String content, BigDecimal proposedFine, BigDecimal proposedRecoup,
                            String changeReason) {}

    @Transactional
    public CaseNotice notify(Long caseId, NoticeReq req) {
        CaseFile c = get(caseId);
        // REPORTED 首次告知；NOTIFIED 允许再次告知（辽52条：改变原认定的事实/证据/依据须重新履行告知程序）
        if (!List.of("REPORTED", "NOTIFIED").contains(c.getStatus()))
            throw new BizException(2004, "调查终结后方可作出处罚告知（第36/41条）");
        // 再次告知且金额高于此前：须载明改变原认定事实/证据/依据的理由，避免借重新告知规避"不得因申辩加重"
        CaseNotice prev = noticeRepository.findTopByCaseIdOrderByIdDesc(caseId).orElse(null);
        if (prev != null
                && (nz(req.proposedFine()).compareTo(nz(prev.getProposedFine())) > 0
                    || nz(req.proposedRecoup()).compareTo(nz(prev.getProposedRecoup())) > 0)
                && (req.changeReason() == null || req.changeReason().isBlank()))
            throw new BizException(2077, "再次告知的拟处罚金额高于前次，须载明改变原认定事实、证据或依据的理由（辽52条）；"
                    + "不得因当事人陈述申辩而加重处罚（第41条）");
        CaseNotice n = new CaseNotice();
        // 听证申请随案而非随单：再次告知不得清空前次的听证申请，
        // 否则当事人申请听证后只要以同额再告知一次，2075 守卫即失效
        if (prev != null && Boolean.TRUE.equals(prev.getHearingRequested())) {
            n.setHearingRequested(true);
            n.setHearingHeldAt(prev.getHearingHeldAt());
        }
        n.setChangeReason(req.changeReason());
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
                               Boolean hearingRequested, LocalDate hearingHeldAt,
                               Boolean statementWaived) {}

    @Transactional
    public CaseNotice recordStatement(Long caseId, StatementReq req) {
        CaseNotice n = noticeRepository.findTopByCaseIdOrderByIdDesc(caseId)
                .orElseThrow(() -> new BizException(2006, "尚未作出处罚告知"));
        // 辽44条：期限内未行使陈述权、申辩权的，视为放弃
        if (req.statement() != null && n.getStatementDeadline() != null
                && LocalDate.now().isAfter(n.getStatementDeadline()))
            throw new BizException(2046, "已超过陈述申辩期限（" + n.getStatementDeadline()
                    + "），依法视为放弃陈述申辩权（辽44条）；如需继续，请按放弃留痕处理");
        if (req.statement() != null) n.setStatement(req.statement());
        if (req.statementReview() != null) n.setStatementReview(req.statementReview());
        // 当事人明确放弃陈述申辩：留痕后方可在期限届满前决定（第41条）
        if (Boolean.TRUE.equals(req.statementWaived())) n.setStatementWaived(true);
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
        if (req.attendees() == null || req.attendees().isBlank())
            throw new BizException(2047, "须记录集体讨论参加人员（第44条）");
        if (req.conclusion() == null || req.conclusion().isBlank())
            throw new BizException(2047, "须记录集体讨论结论（第44条）");
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
            // 第41条：不得因陈述、申辩或申请听证而加重处罚。
            // 比对全部告知中的最低拟处罚额：仅比最新一条会被"再告知一个更高金额"绕过（辽52条的重新告知
            // 只允许在事实/证据/依据改变时进行，notify 已要求载明变更理由并重新起算陈述申辩期）。
            List<CaseNotice> allNotices = noticeRepository.findByCaseIdOrderByIdDesc(caseId);
            BigDecimal minFine = allNotices.stream()
                    .map(CaseNotice::getProposedFine).filter(java.util.Objects::nonNull)
                    .min(BigDecimal::compareTo).orElse(notice.getProposedFine());
            BigDecimal minRecoup = allNotices.stream()
                    .map(CaseNotice::getProposedRecoup).filter(java.util.Objects::nonNull)
                    .min(BigDecimal::compareTo).orElse(notice.getProposedRecoup());
            if (fine.compareTo(minFine) > 0 || recoup.compareTo(minRecoup) > 0)
                throw new BizException(2007, "决定金额不得高于告知金额（已告知最低 罚款" + minFine + "元/追回"
                        + minRecoup + "元）——不得因陈述申辩而加重处罚（第41条）");
            // 第41条：当事人申请听证的，应当组织听证后再决定
            if (Boolean.TRUE.equals(notice.getHearingRequested()) && notice.getHearingHeldAt() == null)
                throw new BizException(2075, "当事人已申请听证，应当组织听证并制作笔录后方可作出决定（第41条）");
            // 已排期未举行的听证同样拦截：notice 上的标记可能因再次告知而遗漏
            Integer pendingHearing = jdbc.queryForObject(
                    "select count(*) from case_hearing where case_id = ? and status = 'SCHEDULED'",
                    Integer.class, caseId);
            if (pendingHearing != null && pendingHearing > 0)
                throw new BizException(2075, "本案有已排期但尚未举行的听证，应当组织听证后方可作出决定（第41条）");
            // 陈述申辩期届满前不得决定；当事人已陈述申辩或书面放弃的除外（参数开关）
            // 已举行听证的，当事人已充分陈述申辩，不再受该期限约束
            if ("PUNISH".equals(req.decisionType()) && config.bool("statement_wait_required", true)
                    && notice.getStatementDeadline() != null
                    // 含尾：届满日当天当事人仍可陈述申辩（recordStatement 用 isAfter 受理），
                    // 这里若用 isBefore 就会出现"同一天既可申辩又可下决定"，先决定即剥夺申辩权
                    && !LocalDate.now().isAfter(notice.getStatementDeadline())
                    && notice.getStatement() == null && !Boolean.TRUE.equals(notice.getStatementWaived())
                    && notice.getHearingHeldAt() == null)
                throw new BizException(2076, "陈述申辩期至 " + notice.getStatementDeadline()
                        + " 届满前不得作出处罚决定（第41条）；当事人已陈述申辩或明确放弃的，请先录入陈述申辩记录");
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
                // 空壳讨论记录不算数：须有实质内容且经签字确认（第44条集体讨论决定）
                if (fine.compareTo(meetingThreshold) >= 0 && !hasValidMeeting(caseId))
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
        CaseFile cf = get(caseId);
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2042, "案件无处理决定"));
        // 辽56条：公开的是"行政处罚决定"，且应在决定书送达后（未送达即公开会侵害当事人陈述救济权）
        if (!"PUNISH".equals(d.getDecisionType()))
            throw new BizException(2042, "仅行政处罚决定需要依法公开（当前决定类型：" + d.getDecisionType() + "）");
        if (cf.getDeliveredAt() == null)
            throw new BizException(2042, "处罚决定书尚未送达，不得先行公开（辽56条）");
        // 幂等：重复调用会把公开日期刷成当天，把"7日内公开"的超期证据洗白（第56条监督链）
        if (Boolean.TRUE.equals(d.getPublished()) && d.getPublishedAt() != null)
            throw new BizException(2042, "该决定已于 " + d.getPublishedAt() + " 公开，不可重复登记");
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

    /**
     * 终止调查/移送司法案件的立卷归档（第57条）：保留 TERMINATED 状态，只补案卷号与结案报告。
     * private 自调用方法标 @Transactional 无效，事务由调用方 close() 承担。
     */
    private CaseFile archiveTerminated(CaseFile c, String closeReport, String maker) {
        if (c.getArchiveNo() != null)
            throw new BizException(2011, "本案已立卷归档（案卷号 " + c.getArchiveNo() + "）");
        if (closeReport == null || closeReport.isBlank())
            throw new BizException(2011, "须填写终止调查/移送情况说明与归档报告（第56/57条）");
        var d = decisionRepository.findByCaseId(c.getId()).orElse(null);
        String reason = d != null && String.valueOf(d.getDecisionType()).startsWith("TRANSFER")
                ? "JUDICIAL" : "TERMINATED";
        addDocument(c.getId(), new DocumentReq(
                "CLOSE_REPORT", c.getCaseNo() + " 归档报告", closeReport,
                LocalDate.now(), maker, false, null, null));
        if (config.bool("archive_completeness_required", false)) {
            List<String> missing = documentService.missingRequiredDocs(c.getId());
            if (!missing.isEmpty())
                throw new BizException(2052, "案卷必备文书缺失：" + String.join("、", missing) + "（第57条文书齐全要求）");
        }
        c.setClosedAt(LocalDate.now());
        c.setCloseReason(reason);
        c.setArchiveNo(c.getCaseNo() + "卷");
        return caseRepository.save(c);
    }

    @Transactional
    public CaseFile close(Long caseId, String closeReport, String maker) {
        CaseFile c = get(caseId);
        // 终止调查（第47条五类情形）与移送司法的案件同样要立卷归档（第57条一案一卷），
        // 但它们既没有处罚决定、也不在 DECIDED/DELIVERED 状态——此前被"无处理决定"与
        // 状态白名单双重拦死，拿不到案卷号、产不出结案报告，只能线下手工立卷。
        // 归档不改变 TERMINATED 状态（终止是案件的真实出口，不应被改写成"已结案"），
        // 以 archive_no 是否已生成作为"是否已归档"的标志。
        if ("TERMINATED".equals(c.getStatus())) return archiveTerminated(c, closeReport, maker);

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

    /**
     * 案件详情。十余张子表原先逐张查（每张一次往返），改为一条 SQL 用 json_agg 聚合：
     * 往返 17 次 → 5 次（实体类仍走 JPA，其 JSON 字段名是驼峰，前端契约不能变）。
     * 子表输出保持 select * 的蛇形字段名。注意：timestamptz 列（如 biz_approval.decided_at）
     * 经 json_agg 会带会话时区偏移（"+08:00"），与仍走 queryForList 的 /approvals/pending 的
     * UTC 归一格式不同；前端目前只读 approvals[].status，若后续要读这些时间列须自行归一。
     */
    private static final List<String> DETAIL_PARTS = List.of(
            "officers|case_officer|id",
            "evidences|case_evidence|id",
            "exclusions|case_period_exclusion|id",
            "meetings|case_meeting|id",
            "expertReviews|expert_review|id",
            "agreementActions|agreement_action|id",
            "hearings|case_hearing|id",
            "assists|case_assist|id",
            "deliveries|case_delivery|id",
            "executions|case_execution|id",
            "approvals|biz_approval|id desc");

    private static final String DETAIL_SQL = buildDetailSql();

    private static String buildDetailSql() {
        StringBuilder sb = new StringBuilder("select ");
        for (String part : DETAIL_PARTS) {
            String[] p = part.split("\\|");
            sb.append("(select coalesce(json_agg(t order by t.").append(p[2])
              .append("), '[]'::json) from ").append(p[1])
              .append(" t where t.case_id = ?) as \"").append(p[0]).append("\", ");
        }
        // 文书只取列表所需字段（正文另有详情接口，避免把全部全文一次拉回）
        sb.append("(select coalesce(json_agg(t order by t.id), '[]'::json) from ")
          .append("(select id, doc_type, title, made_at, maker, signed, note from case_document ")
          .append("where case_id = ?) t) as \"documents\", ");
        sb.append("(select coalesce(sum(end_at - start_at), 0) from case_period_exclusion ")
          .append("where case_id = ? and end_at is not null) as \"exclusionDays\"");
        return sb.toString();
    }

    public Map<String, Object> detail(Long id) {
        CaseFile c = get(id);
        CaseCause cause = causeRepository.findById(c.getCauseId()).orElse(null);
        Object[] args = new Object[DETAIL_PARTS.size() + 2];
        java.util.Arrays.fill(args, id);
        Map<String, Object> agg = jdbc.queryForMap(DETAIL_SQL, args);

        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("caseFile", c);
        m.put("cause", cause == null ? Map.of() : cause);
        for (String part : DETAIL_PARTS) m.put(part.split("\\|")[0], readJson(agg.get(part.split("\\|")[0])));
        m.put("documents", readJson(agg.get("documents")));
        m.put("reviews", reviewRepository.findByCaseIdOrderByIdDesc(id));
        m.put("notices", noticeRepository.findByCaseIdOrderByIdDesc(id));
        m.put("decision", decisionRepository.findByCaseId(id).map(Object.class::cast).orElse(Map.of()));
        long exclusionDays = agg.get("exclusionDays") == null ? 0 : ((Number) agg.get("exclusionDays")).longValue();
        m.put("effectiveDeadline", c.getDeadlineAt().plusDays(exclusionDays));
        return m;
    }

    private List<Map<String, Object>> readJson(Object pgJson) {
        if (pgJson == null) return List.of();
        try {
            return objectMapper.readValue(String.valueOf(pgJson), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception e) {
            throw new BizException(2043, "案件详情数据解析失败");
        }
    }

    // ---------- 数据级权限（v1.2）：case_view_scope=SELF 时办案员仅见本人承办/参办案件 ----------

    /** 负责人/法制/管理员始终全局；办案员按参数收窄 */
    public boolean scopedSelf(boolean privileged) {
        return !privileged && "SELF".equalsIgnoreCase(config.str("case_view_scope", "ALL"));
    }

    // 普通字符串拼接（文本块会剥前导空格导致 "?and" 语法错，医院项目同坑）
    // 关联键用账号 ID：早先按 real_name 匹配会让同名执法人员互相可见，改一次姓名即可绕过隔离
    private static final String SCOPE_SQL =
            " and (case_file.owner_user = ? or exists (select 1 from case_officer o"
            + " join sys_user su on su.id = o.user_id"
            + " where o.case_id = case_file.id and o.avoided = false and su.username = ?)) ";

    /** 越权访问单案（2080） */
    public void assertInScope(Long caseId, String username, boolean privileged) {
        if (!scopedSelf(privileged)) return;
        Integer hit = jdbc.queryForObject(
                "select count(*) from case_file where id = ?" + SCOPE_SQL,
                Integer.class, caseId, username, username);
        if (hit == null || hit == 0)
            throw new BizException(2080, "该案件不在您的数据查看范围内（承办/参办范围，case_view_scope=SELF）");
    }

    /** 分页+关键词检索（案号/案名/当事人），含范围过滤 */
    public Map<String, Object> pageList(String status, String q, int page, int size,
                                        String username, boolean privileged) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" and status = ? ");
            args.add(status);
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (case_no ilike ? or name ilike ? or party_name ilike ?) ");
            String like = "%" + q.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (scopedSelf(privileged)) {
            where.append(SCOPE_SQL);
            args.add(username); args.add(username);
        }
        Long total = jdbc.queryForObject("select count(*) from case_file" + where, Long.class, args.toArray());
        args.add(size);
        args.add((Math.max(page, 1) - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from case_file" + where + " order by id desc limit ? offset ?", args.toArray());
        return Map.of("total", total == null ? 0 : total, "page", page, "size", size, "rows", rows);
    }

    /** 案件移交（承办人变更，负责人操作，留消息） */
    @Transactional
    public CaseFile transferOwner(Long caseId, String newOwner, String operator) {
        CaseFile c = get(caseId);
        Integer exists = jdbc.queryForObject(
                "select count(*) from sys_user where username = ? and enabled = true", Integer.class, newOwner);
        if (exists == null || exists == 0) throw new BizException(2081, "接收人账号不存在或已停用");
        c.setOwnerUser(newOwner);
        var deptRows = jdbc.queryForList("select dept_id from sys_user where username = ?", newOwner);
        if (!deptRows.isEmpty() && deptRows.get(0).get("dept_id") != null)
            c.setOwnerDeptId(((Number) deptRows.get(0).get("dept_id")).longValue());
        CaseFile saved = caseRepository.save(c);
        messageService.send(newOwner, "SYSTEM",
                "案件移交：" + c.getCaseNo() + " 已移交给您承办",
                operator + " 操作移交", "/case/detail/" + caseId, null);
        return saved;
    }

    /** 全局搜索（案件部分），SELF 范围时同样收窄 */
    public List<Map<String, Object>> searchCases(String like, String username, boolean privileged) {
        String base = "select id, case_no, name, party_name, status, filed_at from case_file"
                + " where (case_no ilike ? or name ilike ? or party_name ilike ?)";
        if (scopedSelf(privileged)) {
            return jdbc.queryForList(base + SCOPE_SQL + " order by id desc limit 20",
                    like, like, like, username, username);
        }
        return jdbc.queryForList(base + " order by id desc limit 20", like, like, like);
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

    /**
     * 不计入办案期限的天数。口径：end - start（与 resume() 的中止顺延、MessageService 的提醒查询一致），
     * 即"停表"模型，同日起止记 0 日。若当地要求含头含尾（同日记 1 日），三处须一并改。
     */
    private long totalExclusionDays(Long caseId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select start_at, end_at from case_period_exclusion where case_id = ? and end_at is not null", caseId);
        return rows.stream().mapToLong(r -> ChronoUnit.DAYS.between(
                ((java.sql.Date) r.get("start_at")).toLocalDate(),
                ((java.sql.Date) r.get("end_at")).toLocalDate())).sum();
    }

    /** 法定"负责人集体讨论"记录是否成立：须签字确认且参会人/结论有实质内容（第44/45条共用口径） */
    private boolean hasValidMeeting(Long caseId) {
        return !jdbc.queryForList("""
                select id from case_meeting
                where case_id = ? and sign_confirmed = true
                  and coalesce(btrim(attendees), '') <> ''
                  and coalesce(btrim(conclusion), '') <> ''""", caseId).isEmpty();
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
