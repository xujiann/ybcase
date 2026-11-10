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
import java.util.Set;

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
    private final ReviewMaterialService reviewMaterials;
    private final CaseNoticeRepository noticeRepository;
    private final CaseDecisionRepository decisionRepository;
    private final ClueService clueService;
    private final JdbcTemplate jdbc;
    private final BureauConfig config;
    private final DocumentService documentService;
    private final ProcedureService procedureService;
    private final BizSeqService seqService;
    private final ExecutionService executionService;
    private final ExecutionDeadlines executionDeadlines;
    private final SourceAttachmentAccess sourceAttachmentAccess;
    private final WorkflowUserAccess workflowUsers;
    private final EnforcerRegistryAccess enforcerRegistry;
    private final MaterialAccess materialAccess;
    private final MessageService messageService;
    private final CaseFactService factHistory;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final jakarta.persistence.EntityManager entityManager;

    // ---------- 立案 ----------

    public record OfficerReq(String name, String certNo, String duty) {}

    public record JurisdictionReq(String type, String basis, String violationPlace, String treatmentPlace,
                                  String insuredPlace, String designatedNo, String disputeNote) {}
    public record JurisdictionUpdateReq(JurisdictionReq jurisdiction, String reason) {}

    public static final Map<String, String> JURISDICTION_TYPE_LABELS = Map.of(
            "PENDING", "待补录", "LOCAL", "本辖区事实", "TRANSFERRED", "移送受理",
            "DESIGNATED", "指定管辖", "OTHER", "其他依据");

    /** Normalize once at submission and again at approval; no inferred jurisdiction or default legal conclusion. */
    public static JurisdictionReq normalizeJurisdiction(JurisdictionReq req) {
        if (req == null) return new JurisdictionReq("PENDING", null, null, null, null, null, null);
        String type = jurisdictionText(req.type(), 24, "管辖分类");
        type = type == null ? "PENDING" : type.toUpperCase(java.util.Locale.ROOT);
        if (!JURISDICTION_TYPE_LABELS.containsKey(type)) throw new BizException(2145, "管辖分类无效");
        String basis = jurisdictionText(req.basis(), 2000, "管辖依据");
        String violation = jurisdictionText(req.violationPlace(), 255, "违法行为地");
        String treatment = jurisdictionText(req.treatmentPlace(), 255, "就医地");
        String insured = jurisdictionText(req.insuredPlace(), 255, "参保地");
        String designated = jurisdictionText(req.designatedNo(), 128, "指定管辖文号");
        String dispute = jurisdictionText(req.disputeNote(), 2000, "管辖争议及处理情况");
        if (!"PENDING".equals(type) && basis == null) throw new BizException(2145, "选择管辖分类后须填写管辖依据");
        if ("LOCAL".equals(type) && violation == null && treatment == null && insured == null)
            throw new BizException(2145, "本辖区事实须至少记录违法行为地、就医地或参保地之一");
        if ("DESIGNATED".equals(type) && designated == null) throw new BizException(2145, "指定管辖须填写指定管辖文号");
        return new JurisdictionReq(type, basis, violation, treatment, insured, designated, dispute);
    }

    private static String jurisdictionText(String value, int max, String label) {
        if (value == null) return null;
        if (value.length() > max) throw new BizException(2145, label + "不能超过" + max + "字");
        return value.isBlank() ? null : value.trim();
    }

    private static JurisdictionReq jurisdictionFacts(CaseFile c) {
        return new JurisdictionReq(c.getJurisdictionType(), c.getJurisdictionBasis(), c.getViolationPlace(),
                c.getTreatmentPlace(), c.getInsuredPlace(), c.getDesignatedNo(), c.getJurisdictionDisputeNote());
    }

    public JurisdictionReq jurisdictionSnapshot(Long caseId) {
        return jurisdictionFacts(get(caseId));
    }

    private static void applyJurisdiction(CaseFile c, JurisdictionReq j) {
        c.setJurisdictionType(j.type());
        c.setJurisdictionBasis(j.basis());
        c.setViolationPlace(j.violationPlace());
        c.setTreatmentPlace(j.treatmentPlace());
        c.setInsuredPlace(j.insuredPlace());
        c.setDesignatedNo(j.designatedNo());
        c.setJurisdictionDisputeNote(j.disputeNote());
    }

    public record InstitutionReq(String code, String ownership, String grade, String kind) {
        public InstitutionReq(String code, String ownership, String grade) {
            this(code, ownership, grade, null);
        }
    }

    static InstitutionReq normalizeInstitution(String partyType, InstitutionReq institution) {
        if (institution == null) return null;
        if (!"PROVIDER".equals(partyType))
            throw new BizException(2026, "仅定点医药机构可以填写机构信息");
        if (!validReportText(institution.code(), 64)
                || !Set.of("PUBLIC", "SOCIAL").contains(java.util.Objects.toString(institution.ownership(), ""))
                || !Set.of("TERTIARY", "SECONDARY", "PRIMARY", "UNGRADED").contains(java.util.Objects.toString(institution.grade(), "")))
            throw new BizException(2026, "请填写有效的机构编码、性质和等级");
        if (institution.kind() != null && !Set.of("MEDICAL", "SINGLE_PHARMACY", "CHAIN_PHARMACY").contains(institution.kind()))
            throw new BizException(2026, "机构类型无效");
        return new InstitutionReq(institution.code().strip(), institution.ownership(), institution.grade(), institution.kind());
    }

    private static boolean validReportText(String text, int max) {
        return text != null && !text.isBlank() && text.length() <= max && text.indexOf(0) < 0;
    }

    public record CaseCreateReq(Long clueId, Long causeId, String procedureType,
                                String partyName, String partyType, String partyCreditNo,
                                String partyAddress, String partyLegalRep, String partyContact,
                                String summary, BigDecimal amountInvolved,
                                List<OfficerReq> officers, String clueVerifyResult,
                                Long enforceItemId, LocalDate violationEndDate, Boolean healthHarm,
                                JurisdictionReq jurisdiction, InstitutionReq institution,
                                Boolean reportingDetailsRequired) {
        public CaseCreateReq(Long clueId, Long causeId, String procedureType, String partyName, String partyType,
                             String partyCreditNo, String partyAddress, String partyLegalRep, String partyContact,
                             String summary, BigDecimal amountInvolved, List<OfficerReq> officers, String clueVerifyResult,
                             Long enforceItemId, LocalDate violationEndDate, Boolean healthHarm, JurisdictionReq jurisdiction) {
            this(clueId, causeId, procedureType, partyName, partyType, partyCreditNo, partyAddress, partyLegalRep,
                    partyContact, summary, amountInvolved, officers, clueVerifyResult, enforceItemId, violationEndDate,
                    healthHarm, jurisdiction, null, null);
        }
        /** Existing Java callers and API payloads remain compatible. */
        public CaseCreateReq(Long clueId, Long causeId, String procedureType, String partyName, String partyType,
                             String partyCreditNo, String partyAddress, String partyLegalRep, String partyContact,
                             String summary, BigDecimal amountInvolved, List<OfficerReq> officers, String clueVerifyResult,
                             Long enforceItemId, LocalDate violationEndDate, Boolean healthHarm) {
            this(clueId, causeId, procedureType, partyName, partyType, partyCreditNo, partyAddress, partyLegalRep,
                    partyContact, summary, amountInvolved, officers, clueVerifyResult, enforceItemId, violationEndDate,
                    healthHarm, null);
        }
    }

    private CaseCause validateFilingBasics(CaseCreateReq req) {
        // 按执法证号去重计数：重复填同一人不算两人（第16条）
        if (req.officers() == null
                || req.officers().stream().anyMatch(o -> o == null || o.name() == null || o.name().isBlank()
                    || o.certNo() == null || o.certNo().isBlank())
                || req.officers().stream().map(OfficerReq::certNo).map(String::trim).distinct().count() < 2)
            throw new BizException(2002, "执法人员不得少于两人（第16条），且须为不同执法证号");
        if (req.procedureType() != null && !List.of("NORMAL", "SUMMARY").contains(req.procedureType()))
            throw new BizException(2026, "程序类型须为普通程序或简易程序");
        return requireCauseMatch(req.causeId(), req.partyType());
    }

    void preflightFilingApplication(CaseCreateReq req, WorkflowUserAccess.User applicant) {
        if (req.partyName() == null || req.partyName().isBlank())
            throw new BizException(2026, "请填写当事人");
        validateFilingBasics(req);
        requireInstitutionForFiling(req, applicant, normalizeInstitution(req.partyType(), req.institution()));
        if (req.clueId() != null) requireFilingClueDepartment(req.clueId(), applicant);
        validateFilingDate(req, LocalDate.now());
        cn.ybcase.bureau.common.Money.nonNegative(req.amountInvolved(), "涉案金额");
        procedureService.validateAndResolveEnforcers(req.officers());
    }

    @Transactional
    public CaseFile create(CaseCreateReq req, String username) {
        CaseCause cause = validateFilingBasics(req);
        JurisdictionReq jurisdiction = normalizeJurisdiction(req.jurisdiction());
        InstitutionReq institution = normalizeInstitution(req.partyType(), req.institution());
        // Source before actors: the same order is used by approval and existing case transfers.
        if (req.clueId() != null) {
            jdbc.queryForList("select id from case_clue where id=? for update", req.clueId());
            sourceAttachmentAccess.lockInspectionsForAdoption(req.clueId());
        }
        enforcerRegistry.lockRegistry();
        // Direct filing and approval execution both have a responsible-role HTTP actor.
        // Approval execution may create a case for a different applicant, so acquire both users together.
        var filingActors = workflowUsers.lockUsers(username, currentPrincipalName());
        WorkflowUserAccess.User owner = workflowUsers.requireFiling(username == null ? null : filingActors.get(username));
        var filingApprover = materialAccess.requireCurrentRoles("LEADER", "ADMIN");
        if ((owner.district() && owner.deptId() == null)
                || (filingApprover != null && filingApprover.district()
                    && (filingApprover.deptId() == null || !filingApprover.deptId().equals(owner.deptId()))))
            throw new BizException(2080, "立案申请不在当前区市县机构范围内");
        // Recheck scope after any actor-lock wait: previous roles must not grant current access.
        if (req.clueId() != null) {
            sourceAttachmentAccess.clueForFiling(req.clueId(), username, false);
            requireFilingClueDepartment(req.clueId(), owner);
        }

        CaseFile c = new CaseFile();
        applyJurisdiction(c, jurisdiction);
        LocalDate today = LocalDate.now();
        validateFilingDate(req, today);
        if (req.violationEndDate() != null) {
            c.setViolationEndDate(req.violationEndDate());
            c.setHealthHarm(Boolean.TRUE.equals(req.healthHarm()));
        }
        c.setEnforceItemId(req.enforceItemId());
        c.setCaseNo(cfg("case_no_prefix", "医保案") + "〔" + today.getYear() + "〕"
                + seqService.next("CASE", today.getYear()) + "号");
        c.setName(investigatingName(req.partyName(), cause));
        c.setClueId(req.clueId());
        c.setCauseId(req.causeId());
        c.setProcedureType(req.procedureType() == null ? "NORMAL" : req.procedureType());
        c.setPartyName(req.partyName());
        c.setPartyType(req.partyType());
        c.setPartyCreditNo(req.partyCreditNo());
        if (institution != null) {
            c.setInstitutionCode(institution.code());
            c.setInstitutionOwnership(institution.ownership());
            c.setInstitutionGrade(institution.grade());
            c.setInstitutionKind(institution.kind());
        }
        c.setPartyAddress(req.partyAddress());
        c.setPartyLegalRep(req.partyLegalRep());
        c.setPartyContact(req.partyContact());
        c.setSummary(req.summary());
        c.setAmountInvolved(cn.ybcase.bureau.common.Money.nonNegative(req.amountInvolved(), "涉案金额"));
        c.setFiledAt(today);
        c.setDeadlineAt(today.plusDays(config.intVal("case_deadline_days", 90)));  // 第45条：立案之日起九十日（可配）
        c.setCreatedBy(username);
        c.setOwnerUser(username);  // 承办人=立案申请人（可移交）
        c.setOwnerDeptId(owner.deptId());
        c.setReportingDetailsRequired(requireInstitutionForFiling(req, owner, institution));
        var officerUsers = procedureService.validateAndResolveEnforcers(req.officers());
        caseRepository.save(c);

        for (OfficerReq o : req.officers()) {
            jdbc.update("insert into case_officer (case_id, name, cert_no, duty, user_id) values (?,?,?,?,?)",
                    c.getId(), o.name(), o.certNo(), o.duty() == null ? "MEMBER" : o.duty(),
                    officerUsers.get(o.certNo()));
        }
        if (req.clueId() != null) {
            clueService.markFiled(req.clueId(), req.clueVerifyResult());
            sourceAttachmentAccess.adoptClue(req.clueId(), c.getId());
        }
        return c;
    }

    private void requireFilingClueDepartment(Long clueId, WorkflowUserAccess.User applicant) {
        var department = jdbc.queryForObject("select owner_dept_id from case_clue where id=?", Long.class, clueId);
        if (department != null && applicant.accountCategory() != null && !department.equals(applicant.deptId()))
            throw new BizException(2080, "跨机构线索请完成机构交接后再立案");
    }

    private static boolean requireInstitutionForFiling(CaseCreateReq req, WorkflowUserAccess.User applicant,
                                                       InstitutionReq institution) {
        boolean required = Boolean.TRUE.equals(req.reportingDetailsRequired()) || applicant.accountCategory() != null;
        if (required && "PROVIDER".equals(req.partyType()) && institution == null)
            throw new BizException(2026, "请填写机构编码、性质和等级");
        return required;
    }

    private void validateFilingDate(CaseCreateReq req, LocalDate today) {
        if (req.violationEndDate() == null && config.bool("violation_end_date_required", false))
            throw new BizException(2051, "须填写违法行为终了日期，用于追责时效判定（第6条）；确无法确定的，请由管理员关闭该校验开关");
        if (req.violationEndDate() != null) {
            int years = Boolean.TRUE.equals(req.healthHarm())
                    ? config.intVal("liability_years_health", 5) : config.intVal("liability_years", 2);
            if (req.violationEndDate().plusYears(years).isBefore(today))
                throw new BizException(2051, "违法行为终了已超过" + years + "年追责时效，不再给予行政处罚（第6条）；如有中断事由请核实终了日期");
        }
    }

    /** 案由主体与当事人类别一致性（2001）：立案与案由更正共用同一口径 */
    private CaseCause requireCauseMatch(Long causeId, String partyType) {
        if (causeId == null) throw new BizException(2026, "请选择案由");
        CaseCause cause = causeRepository.findById(causeId)
                .orElseThrow(() -> new BizException(2026, "案由不存在"));
        if (!cause.getSubjectType().equals(partyType))
            throw new BizException(2001, "当事人类别与案由违法主体类别不一致（苏医保督〔2024〕1号）");
        return cause;
    }

    /** 调查阶段案件名称：违法主体+涉嫌+案由+案（决定后去"涉嫌"，另有路径） */
    private static String investigatingName(String partyName, CaseCause cause) {
        return partyName + "涉嫌" + cause.getCategory() + "案";
    }

    // ---------- 案件要素更正（留痕，局令第4/35条） ----------

    /** 更正记录 field 列 → 中文名（大事记与前端共用同一份） */
    public static final Map<String, String> CORRECTION_FIELD_LABELS = Map.ofEntries(
            Map.entry("partyName", "当事人名称"), Map.entry("partyCreditNo", "证照号"), Map.entry("partyAddress", "地址"),
            Map.entry("partyLegalRep", "法定代表人"), Map.entry("partyContact", "联系方式"), Map.entry("summary", "案情摘要"),
            Map.entry("amountInvolved", "涉案金额"), Map.entry("causeId", "案由"), Map.entry("name", "案件名称"),
            Map.entry("institutionCode", "机构编码"), Map.entry("institutionOwnership", "机构性质"),
            Map.entry("institutionGrade", "机构等级"), Map.entry("institutionKind", "机构类型"),
            Map.entry("jurisdictionType", "管辖分类"), Map.entry("jurisdictionBasis", "管辖依据"),
            Map.entry("violationPlace", "违法行为地"), Map.entry("treatmentPlace", "就医地"), Map.entry("insuredPlace", "参保地"),
            Map.entry("designatedNo", "指定管辖文号"), Map.entry("jurisdictionDisputeNote", "管辖争议及处理情况"));

    @Transactional
    public CaseFile updateJurisdiction(Long caseId, JurisdictionUpdateReq req, String operator) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        CaseFile c = get(caseId);
        if (!List.of("INVESTIGATING", "REPORTED", "NOTIFIED").contains(c.getStatus()))
            throw new BizException(2145, "仅在办案件可补录或更正管辖事实；已决定案件须按相应程序办理");
        if (req == null || req.jurisdiction() == null) throw new BizException(2145, "须提供管辖事实");
        String reason = jurisdictionText(req.reason(), 1000, "补录/更正理由");
        if (reason == null) throw new BizException(2145, "须填写管辖事实补录/更正理由");
        JurisdictionReq next = normalizeJurisdiction(req.jurisdiction());
        JurisdictionReq old = jurisdictionFacts(c);
        String[][] fields = {
                {"jurisdictionType", old.type(), next.type()}, {"jurisdictionBasis", old.basis(), next.basis()},
                {"violationPlace", old.violationPlace(), next.violationPlace()}, {"treatmentPlace", old.treatmentPlace(), next.treatmentPlace()},
                {"insuredPlace", old.insuredPlace(), next.insuredPlace()}, {"designatedNo", old.designatedNo(), next.designatedNo()},
                {"jurisdictionDisputeNote", old.disputeNote(), next.disputeNote()}};
        List<String[]> changes = java.util.Arrays.stream(fields)
                .filter(f -> !java.util.Objects.equals(f[1], f[2])).toList();
        if (changes.isEmpty()) throw new BizException(2122, "没有任何管辖事实发生变化，无需更正");
        String batch = java.util.UUID.randomUUID().toString().replace("-", "");
        for (String[] f : changes)
            jdbc.update("insert into case_correction(case_id,batch_no,field,old_value,new_value,reason,operator) values(?,?,?,?,?,?,?)",
                    caseId, batch, f[0], f[1], f[2], reason, operator);
        applyJurisdiction(c, next);
        // Approved filing payloads are immutable snapshots; corrections only change current facts and append history.
        return caseRepository.save(c);
    }

    private List<Map<String, Object>> jurisdictionHistory(Long id) {
        return jdbc.queryForList("""
                select * from case_correction where case_id = ? and field in
                ('jurisdictionType','jurisdictionBasis','violationPlace','treatmentPlace','insuredPlace','designatedNo','jurisdictionDisputeNote')
                order by id desc""", id);
    }

    private static Map<String, Object> jurisdictionMaterial(String key, String label, boolean present, String message) {
        return Map.of("key", key, "label", label, "present", present, "message", message);
    }

    private static List<Map<String, Object>> jurisdictionMaterials(JurisdictionReq j) {
        List<Map<String, Object>> items = new java.util.ArrayList<>();
        boolean pending = "PENDING".equals(j.type());
        items.add(jurisdictionMaterial("type", "管辖事实分类", !pending, pending ? "待补录管辖事实，请核实后完善" : "已记录事实分类，请核实相关依据"));
        items.add(jurisdictionMaterial("basis", "管辖依据说明", j.basis() != null, "请记录管辖依据，并注明相关材料或文号"));
        if ("LOCAL".equals(j.type()) || pending)
            items.add(jurisdictionMaterial("places", "违法行为地/就医地/参保地", j.violationPlace() != null || j.treatmentPlace() != null || j.insuredPlace() != null,
                    "至少记录与本案相关的一处地点；地点本身不自动确定管辖权"));
        if ("DESIGNATED".equals(j.type()))
            items.add(jurisdictionMaterial("designatedNo", "指定管辖文号", j.designatedNo() != null, "核对指定管辖文件及文号"));
        if ("TRANSFERRED".equals(j.type()))
            items.add(jurisdictionMaterial("transferBasis", "移送受理情况说明", j.basis() != null, "请在依据中记录移送来源、相关文号及受理情况"));
        if (j.disputeNote() != null)
            items.add(jurisdictionMaterial("disputeNote", "管辖争议及处理记录", true, "已记录争议与处理情况，按实际程序核实"));
        return items;
    }

    @Transactional
    public CaseFile convertToOrdinary(Long caseId, String reason, String username) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseRoles(caseId, "LEADER", "ADMIN");
        CaseFile c = get(caseId);
        String explanation = CaseFactService.text(reason, 512, "转普通程序原因");
        if (!"INVESTIGATING".equals(c.getStatus()) || !"SUMMARY".equals(c.getProcedureType())
                || c.getDecidedAt() != null || decisionRepository.findByCaseId(caseId).isPresent())
            throw new BizException(2003, "仅尚未作出决定且处于调查中的简易程序案件可转为普通程序");
        String batch = java.util.UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                insert into case_correction (case_id,batch_no,field,old_value,new_value,reason,operator)
                values (?,?,'procedureType','SUMMARY','NORMAL',?,?)
                """, caseId, batch, explanation, username);
        c.setProcedureType("NORMAL");
        return caseRepository.save(c);
    }

    /** 字段为空 = 不更正；传空串 = 清空（partyName 除外，不允许清空） */
    public record CorrectionReq(String partyName, String partyCreditNo, String partyAddress,
                                String partyLegalRep, String partyContact, String summary,
                                BigDecimal amountInvolved, Long causeId, String reason, InstitutionReq institution,String sourceReference) {
        public CorrectionReq(String partyName,String partyCreditNo,String partyAddress,String partyLegalRep,
                             String partyContact,String summary,BigDecimal amountInvolved,Long causeId,String reason) {
            this(partyName,partyCreditNo,partyAddress,partyLegalRep,partyContact,summary,amountInvolved,causeId,reason,null,null);
        }
    }

    /**
     * 立案后要素录错的系统内更正通道：此前只能直连库改且不留痕（审计只记 method/path）。
     * 仅调查阶段（INVESTIGATING/REPORTED）可改——已告知/已决定的案件，当事人已依据告知内容行使权利，
     * 要素变动须走更正决定书路径重新履行程序，而不是悄悄改底表。
     */
    @Transactional
    public CaseFile correctPartyInfo(Long caseId, CorrectionReq req, String operator) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        CaseFile c = get(caseId);
        if (!"INVESTIGATING".equals(c.getStatus()) && !"REPORTED".equals(c.getStatus()))
            throw new BizException(2120, "案件当前状态（" + c.getStatus()
                    + "）不允许更正要素：已告知/已决定的案件请通过更正决定书路径处理");
        if (req.reason() == null || req.reason().isBlank())
            throw new BizException(2121, "须填写更正理由");
        var previousFacts = factHistory.snapshot(caseId);

        // 顺序即记录顺序；name 派生自 partyName/案由，放最后
        List<String[]> changes = new java.util.ArrayList<>();
        String partyName = c.getPartyName();
        if (req.partyName() != null) {
            if (req.partyName().isBlank()) throw new BizException(2123, "当事人名称不能为空");
            partyName = diffText(changes, "partyName", c.getPartyName(), req.partyName());
            c.setPartyName(partyName);
        }
        c.setPartyCreditNo(diffText(changes, "partyCreditNo", c.getPartyCreditNo(), req.partyCreditNo()));
        c.setPartyAddress(diffText(changes, "partyAddress", c.getPartyAddress(), req.partyAddress()));
        c.setPartyLegalRep(diffText(changes, "partyLegalRep", c.getPartyLegalRep(), req.partyLegalRep()));
        c.setPartyContact(diffText(changes, "partyContact", c.getPartyContact(), req.partyContact()));
        c.setSummary(diffText(changes, "summary", c.getSummary(), req.summary()));
        if (req.amountInvolved() != null) {
            if (req.amountInvolved().signum() < 0) throw new BizException(2123, "涉案金额不能为负数");
            // 列是 numeric(14,2)：不先归一到 2 位小数，2500.505 会留下"2500.51→2500.505"的更正记录而库里仍是 2500.51，
            // 留痕与事实不符且每次重提都算"有变化"
            BigDecimal amt = req.amountInvolved().setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal old = nz(c.getAmountInvolved());
            if (old.compareTo(amt) != 0) {
                changes.add(new String[]{"amountInvolved", old.toPlainString(), amt.toPlainString()});
                c.setAmountInvolved(amt);
            }
        }
        CaseCause cause = causeRepository.findById(c.getCauseId()).orElse(null);
        if (req.institution() != null) {
            CaseFactService.text(req.reason(),2000,"机构更正理由");
            CaseFactService.text(req.sourceReference(),1000,"机构更正材料出处");
            InstitutionReq next=normalizeInstitution(c.getPartyType(),req.institution());
            c.setInstitutionCode(diffText(changes,"institutionCode",c.getInstitutionCode(),next.code()));
            c.setInstitutionOwnership(diffText(changes,"institutionOwnership",c.getInstitutionOwnership(),next.ownership()));
            c.setInstitutionGrade(diffText(changes,"institutionGrade",c.getInstitutionGrade(),next.grade()));
            if(next.kind()!=null) c.setInstitutionKind(diffText(changes,"institutionKind",c.getInstitutionKind(),next.kind()));
        }
        if (req.causeId() != null && !req.causeId().equals(c.getCauseId())) {
            CaseCause next = requireCauseMatch(req.causeId(), c.getPartyType());
            changes.add(new String[]{"causeId", causeText(cause), causeText(next)});
            c.setCauseId(next.getId());
            cause = next;
        }
        if (changes.isEmpty()) throw new BizException(2122, "没有任何要素发生变化，无需更正");
        if (cause != null) {
            String name = investigatingName(partyName, cause);
            if (!name.equals(c.getName())) {
                changes.add(new String[]{"name", c.getName(), name});
                c.setName(name);
            }
        }

        String batchNo = java.util.UUID.randomUUID().toString().replace("-", "");
        for (String[] ch : changes)
            jdbc.update("insert into case_correction (case_id, batch_no, field, old_value, new_value, reason, operator)"
                    + " values (?,?,?,?,?,?,?)", caseId, batchNo, ch[0], ch[1], ch[2], req.reason().trim(), operator);
        CaseFile saved=caseRepository.save(c);
        if(req.institution()!=null) {
            entityManager.flush();
            factHistory.record(caseId,"INSTITUTION_CORRECTION",null,null,LocalDate.now(),factHistory.snapshot(caseId),
                    previousFacts,req.reason().trim(),req.sourceReference().strip(),operator);
        }
        return saved;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> corrections(Long caseId) {
        materialAccess.requireCurrentCaseReader(caseId);
        get(caseId);
        return jdbc.queryForList("select * from case_correction where case_id = ? order by id desc", caseId);
    }

    /** 文本要素比对：null 表示未传（保持原值），空串表示清空；变化则记一行并返回新值 */
    private static String diffText(List<String[]> changes, String field, String oldVal, String reqVal) {
        if (reqVal == null) return oldVal;
        String next = reqVal.trim().isEmpty() ? null : reqVal.trim();
        if (!java.util.Objects.equals(oldVal, next)) changes.add(new String[]{field, oldVal, next});
        return next;
    }

    private static String causeText(CaseCause cause) {
        return cause == null ? null : cause.getItemNo() + ". " + cause.getCategory() + "——" + cause.getDescription();
    }

    // ---------- 办案人员 ----------

    @Transactional
    public void addOfficer(Long caseId, OfficerReq req) {
        CaseGuards.lockWritable(jdbc, caseId);
        requireActive(get(caseId));
        if (req == null || req.name() == null || req.name().isBlank() || req.certNo() == null || req.certNo().isBlank())
            throw new BizException(2002, "办案人员须填写姓名和执法证号");
        enforcerRegistry.lockRegistry();
        // Certificate paths share registry -> actors -> certificate rows with creation/review.
        materialAccess.requireCurrentCaseWriter(caseId);
        var officerUsers = procedureService.validateAndResolveEnforcers(List.of(req));
        Integer dup = jdbc.queryForObject(
                "select count(*) from case_officer where case_id = ? and cert_no = ? and avoided = false",
                Integer.class, caseId, req.certNo());
        if (dup != null && dup > 0) throw new BizException(2002, "该执法证号已在本案办案人员名单中");
        jdbc.update("insert into case_officer (case_id, name, cert_no, duty, user_id) values (?,?,?,?,?)",
                caseId, req.name(), req.certNo(), req.duty() == null ? "MEMBER" : req.duty(),
                officerUsers.get(req.certNo()));
    }

    /** 回避（第5条）：主动回避或当事人申请，分级批准；回避后在册执法人员仍不得少于两人 */
    @Transactional
    public void avoidOfficer(Long caseId, Long officerId, String reason, String applicant, String decidedBy) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
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
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        CaseFile c = get(caseId);
        CaseGuards.requireNotArchived(c);
        if (req == null) throw new BizException(2100, "请填写文书材料");
        documentText(req.docType(), "文书类型", 32, true);
        documentText(req.title(), "文书标题", 255, true);
        documentText(req.content(), "文书正文", Integer.MAX_VALUE, true);
        documentText(req.maker(), "制作人", 64, false);
        documentText(req.note(), "文书备注", 255, false);
        jdbc.update("""
                insert into case_document (case_id, doc_type, title, content, made_at, maker, signed, note, due_at)
                values (?,?,?,?,?,?,?,?,?)""",
                caseId, req.docType(), req.title(), req.content(),
                req.madeAt() != null ? req.madeAt() : LocalDate.now(),
                req.maker(), Boolean.TRUE.equals(req.signed()), req.note(), req.dueAt());
    }

    private static void documentText(String value, String label, int max, boolean required) {
        if (required && (value == null || value.isBlank())) throw new BizException(2100, "请填写" + label);
        if (value != null && value.indexOf('\0') >= 0) throw new BizException(2100, label + "含无效字符");
        if (value != null && value.codePointCount(0, value.length()) > max)
            throw new BizException(2100, label + "不能超过" + max + "个字符");
    }

    // ---------- 期限扣除（第45条） ----------

    public record ExclusionReq(String reason, LocalDate startAt, LocalDate endAt, String note) {}

    /** 为进行中的扣除事由登记结束日（收口开放区间）。上一轮把结束日限定为不晚于今天后，
     *  办案人被引向"留空、事后补登"，但此前既无补登接口、开放行也不计入期限——鉴定期扣除会整段丢失 */
    @Transactional
    public void endExclusion(Long caseId, Long exclusionId, LocalDate endAt) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        CaseFile c = get(caseId);
        var rows = jdbc.queryForList(
                "select start_at, end_at from case_period_exclusion where id = ? and case_id = ?", exclusionId, caseId);
        if (rows.isEmpty()) throw new BizException(2032, "扣除记录不存在");
        if (rows.get(0).get("end_at") != null) throw new BizException(2032, "该扣除事由已登记结束日期");
        if (endAt == null) throw new BizException(2032, "须填写结束日期");
        LocalDate start = ((java.sql.Date) rows.get(0).get("start_at")).toLocalDate();
        if (endAt.isBefore(start)) throw new BizException(2032, "结束日期不得早于起始日期（" + start + "）");
        if (endAt.isAfter(LocalDate.now())) throw new BizException(2032, "结束日期不得晚于今天");
        if (endAt.isAfter(exclusionCutoff(c)))
            throw new BizException(2032, "结束日期不得晚于调查办理结束日期（" + exclusionCutoff(c) + "）");
        long span = ChronoUnit.DAYS.between(start, endAt);
        int max = config.intVal("exclusion_max_days", 180);
        if (span > max) throw new BizException(2032, "扣除天数（" + span + "日）超过上限 " + max + " 日");
        Integer overlap = jdbc.queryForObject("""
                select count(*) from case_period_exclusion
                where case_id = ? and id <> ? and coalesce(end_at, current_date) > ? and start_at < ?""",
                Integer.class, caseId, exclusionId, start, endAt);
        if (overlap != null && overlap > 0)
            throw new BizException(2032, "结束日期与其他已登记扣除区间重叠，请核实实际起止日期");
        jdbc.update("update case_period_exclusion set end_at = ? where id = ?", endAt, exclusionId);
    }

    @Transactional
    public void addExclusion(Long caseId, ExclusionReq req) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
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
            // 与起始日对称的上界：扣除的是"已经发生"的期间。此前不限未来，
            // 填一个远期结束日即可一次性扣掉上限天数（默认180日），等于绕开"延期须负责人批准"
            if (req.endAt().isAfter(LocalDate.now()))
                throw new BizException(2032, "结束日期不得晚于今天；事由尚未结束的请留空结束日期，结束后再补登");
            long span = ChronoUnit.DAYS.between(req.startAt(), req.endAt());
            int max = config.intVal("exclusion_max_days", 180);
            if (span > max)
                throw new BizException(2032, "单次不计入期限的天数（" + span + "日）超过上限 " + max + " 日，请核实起止日期");
        }
        // 区间重叠会被重复扣除，等于凭空延长期限
        Integer overlap = jdbc.queryForObject("""
                select count(*) from case_period_exclusion
                where case_id = ? and (end_at is null or (end_at > ? and start_at < ?))""",
                Integer.class, caseId, req.startAt(), req.endAt() == null ? LocalDate.now() : req.endAt());
        if (overlap != null && overlap > 0)
            throw new BizException(2032, "该时间段与已登记的不计入期限区间重叠，不得重复扣除");
        jdbc.update("insert into case_period_exclusion (case_id, reason, start_at, end_at, note) values (?,?,?,?,?)",
                caseId, req.reason(), req.startAt(), req.endAt(), req.note());
    }

    // ---------- 中止 / 恢复 / 终止 ----------

    @Transactional
    public CaseFile suspend(Long caseId, String reason) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseRoles(caseId, "LEADER", "ADMIN");
        CaseFile c = get(caseId);
        if (!"INVESTIGATING".equals(c.getStatus())) throw new BizException(2033, "仅调查中的案件可中止（第42条）");
        if (reason == null || reason.isBlank()) throw new BizException(2033, "中止须载明法定情形并经负责人批准（第42条）");
        requireExclusionsClosed(caseId);
        c.setStatus("SUSPENDED");
        c.setSuspendReason(reason);
        c.setSuspendedAt(LocalDate.now());
        return caseRepository.save(c);
    }

    /** 中止原因消除后立即恢复调查；中止期间顺延办案期限 */
    @Transactional
    public CaseFile resume(Long caseId) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseRoles(caseId, "LEADER", "ADMIN");
        CaseFile c = get(caseId);
        if (!"SUSPENDED".equals(c.getStatus())) throw new BizException(2034, "案件不在中止状态");
        long suspendedDays = suspensionAdditionalDays(caseId, c.getSuspendedAt(), LocalDate.now());
        c.setDeadlineAt(c.getDeadlineAt().plusDays(suspendedDays));
        c.setStatus("INVESTIGATING");
        c.setSuspendReason(null);
        c.setSuspendedAt(null);
        return caseRepository.save(c);
    }

    @Transactional
    public CaseFile terminate(Long caseId, String reason) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseRoles(caseId, "LEADER", "ADMIN");
        CaseFile c = get(caseId);
        if (List.of("DECIDED", "DELIVERED", "CLOSED", "TERMINATED").contains(c.getStatus()))
            throw new BizException(2035, "已决定/已结案的案件不可终止调查（第47条）");
        if (reason == null || reason.isBlank()) throw new BizException(2035, "终止须载明法定情形并经负责人批准（第47条）");
        requireExclusionsClosed(caseId);
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
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseRoles(caseId, "LEADER", "ADMIN");
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

    public record ViolationFinding(LocalDate start, LocalDate end, String facts, BigDecimal amount) {}
    public record ReportReq(String content, ViolationFinding finding) {}

    private static boolean validFinding(ViolationFinding finding) {
        if (finding.start() == null || finding.end() == null || finding.start().isAfter(finding.end())
                || finding.end().isAfter(LocalDate.now()) || !validReportText(finding.facts(), 12000)
                || finding.amount() == null) return false;
        try {
            cn.ybcase.bureau.common.Money.nonNegative(finding.amount(), "认定违法违规金额");
            return true;
        } catch (BizException e) {
            return false;
        }
    }

    private static void applyFinding(CaseFile c, ViolationFinding finding) {
        c.setConfirmedViolationStart(finding.start());
        c.setConfirmedViolationEnd(finding.end());
        c.setConfirmedViolationFacts(finding.facts().strip());
        c.setConfirmedViolationAmount(finding.amount());
    }

    @Transactional
    public CaseFile report(Long caseId, String reportContent, String maker) {
        return report(caseId, new ReportReq(reportContent, null), maker);
    }

    @Transactional
    public CaseFile report(Long caseId, ReportReq req, String maker) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        CaseFile c = get(caseId);
        if (!"INVESTIGATING".equals(c.getStatus())) throw new BizException(2004, "仅调查中的案件可出具调查终结报告");
        if ("SUMMARY".equals(c.getProcedureType())) throw new BizException(2004, "简易程序无需调查终结报告，可直接当场决定");
        var previousFacts=factHistory.snapshot(caseId);
        String reportContent = req.content();
        if (reportContent == null || reportContent.isBlank())
            throw new BizException(2004, "调查终结报告须包含当事人情况/案件来源/事实证据/性质/处理意见（第36条）");
        if (c.isReportingDetailsRequired() && req.finding() == null)
            throw new BizException(2004, "调查终结须确认违法发生期间、事实和金额");
        if (req.finding() != null) {
            if (!validFinding(req.finding()))
                throw new BizException(2004, "请填写有效的违法发生起止日期、认定事实和金额");
            applyFinding(c, req.finding());
            reportContent += "\n\n违法发生期间：" + c.getConfirmedViolationStart() + " 至 " + c.getConfirmedViolationEnd()
                    + "\n认定违法事实：" + c.getConfirmedViolationFacts()
                    + "\n认定违法违规金额：" + c.getConfirmedViolationAmount().toPlainString() + " 元";
        }
        addDocument(caseId, new DocumentReq("FINAL_REPORT", c.getCaseNo() + " 案件调查终结报告",
                reportContent, LocalDate.now(), maker, false, null, null));
        c.setStatus("REPORTED");
        c.setReportedAt(LocalDate.now());
        CaseFile saved=caseRepository.save(c);
        entityManager.flush();
        var facts=new java.util.LinkedHashMap<String,Object>(factHistory.snapshot(caseId));
        facts.put("reportContent",reportContent);
        factHistory.record(caseId,"INVESTIGATION_REPORT",null,null,LocalDate.now(),facts,previousFacts,
                "调查终结确认事实","本案调查终结报告",maker);
        return saved;
    }

    public record InvestigationReturnReq(String reason,String sourceReference) {}

    @Transactional
    public CaseFile returnForInvestigation(Long caseId,InvestigationReturnReq req,String username) {
        CaseGuards.lockWritable(jdbc,caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        var actor=materialAccess.readActor(username);
        CaseFile c=get(caseId);
        String reason=CaseFactService.text(req==null?null:req.reason(),2000,"补查理由");
        String reference=CaseFactService.text(req.sourceReference(),1000,"补查材料依据");
        if(!"NORMAL".equals(c.getProcedureType()) || !List.of("REPORTED","NOTIFIED").contains(c.getStatus())
                || decisionRepository.findByCaseId(caseId).isPresent())
            throw new BizException(2194,"仅尚未决定的普通程序已终结调查案件可退回补查");
        CaseReview review=reviewRepository.findTopByCaseIdOrderByIdDesc(caseId).orElse(null);
        if(review==null || review.getReviewedAt()==null || !Set.of("CONTINUE","CHANGE","CORRECT").contains(java.util.Objects.toString(review.getOpinionType(),""))
                || Boolean.TRUE.equals(review.getPassed()))
            throw new BizException(2194,"须依据最新已完成且要求继续调查、变更或纠正的法审意见办理补查");
        if(!jdbc.queryForList("select id from case_investigation_return where review_id=?",review.getId()).isEmpty())
            throw new BizException(2194,"该审核意见已办理补查，请完成新报告并重新送审");
        var before=factHistory.snapshot(caseId);
        var notice=noticeRepository.findTopByCaseIdOrderByIdDesc(caseId).orElse(null);
        jdbc.update("""
            insert into case_investigation_return(case_id,review_id,previous_status,previous_reported_at,prior_notice_id,
              reason,source_reference,actor_id,actor_username) values(?,?,?,?,?,?,?,?,?)
            """,caseId,review.getId(),c.getStatus(),c.getReportedAt(),notice==null?null:notice.getId(),reason,reference,actor.id(),username);
        factHistory.record(caseId,"INVESTIGATION_RETURN",null,review.getId(),LocalDate.now(),before,before,reason,reference,username);
        c.setStatus("INVESTIGATING");
        c.setReportedAt(null);
        return caseRepository.save(c);
    }

    // ---------- 法制审核（第37-40条） ----------

    @Transactional
    public CaseReview submitReview(Long caseId, String requiredReason) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
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
        var material = reviewMaterials.capture(caseId);
        r.setMaterialSnapshot(material.json());
        r.setMaterialFingerprint(material.fingerprint());
        return reviewRepository.save(r);
    }

    @Transactional
    public CaseReview doReview(Long reviewId, String username, String opinionType, String opinion) {
        CaseReview r = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new BizException(2037, "审核记录不存在"));
        CaseGuards.lockWritable(jdbc, r.getCaseId());
        if (jdbc.queryForObject("select reviewed_at is not null from case_review where id = ?", Boolean.class, reviewId))
            throw new BizException(2037, "该审核已办结");
        if (!List.of("REPORTED", "NOTIFIED").contains(get(r.getCaseId()).getStatus()))
            throw new BizException(2036, "仅决定前的案件可办理法制审核");
        Long latest = jdbc.queryForObject("select max(id) from case_review where case_id = ?", Long.class, r.getCaseId());
        if (!r.getId().equals(latest))
            throw new BizException(2150, "该审核申请已被新的送审申请替代，请办理最新申请");
        if (!reviewMaterials.matches(r))
            throw new BizException(2150, "送审材料已变更或历史审核未绑定材料，请重新提交法制审核");
        enforcerRegistry.lockRegistry();
        if (username == null || username.isBlank()) throw new BizException(2070, "审核账号不存在或已停用");
        var actor = workflowUsers.lockUsers(username, currentPrincipalName()).get(username);
        if (actor == null || !actor.enabled()) throw new BizException(2070, "审核账号不存在或已停用");
        Long actorId = actor.id();
        String reviewer = jdbc.queryForObject("select real_name from sys_user where id=?", String.class, actorId);
        var certificates = enforcerRegistry.lockReviewCertificates(actorId, r.getCaseId());
        if (!List.of("AGREE", "CONTINUE", "CHANGE", "CORRECT", "OTHER").contains(opinionType))
            throw new BizException(2037, "审核意见类型须为 同意/继续调查/变更/纠正/其他（第39条）");
        // 第37条：办案人员不得作为审核人员
        var officers = jdbc.queryForList("select cert_no, user_id from case_officer where case_id=?", r.getCaseId());
        boolean participant = officers.stream().anyMatch(o -> {
            var certificate = certificates.get((String) o.get("cert_no"));
            return o.get("user_id") instanceof Number number && actorId.longValue() == number.longValue()
                    || certificate != null && actorId.equals(certificate.userId());
        });
        if (participant)
            throw new BizException(2038, "同一案件的办案人员不得作为法制审核人员（第37条）");
        if (actor.roles().stream().noneMatch(java.util.Set.of("LEGAL", "ADMIN")::contains))
            throw new BizException(2070, "当前账号不具备法制审核岗位");
        // Keep established self-review/qualification codes; additionally reject a revoked HTTP session.
        materialAccess.requireCurrentCaseRoles(r.getCaseId(), "LEGAL", "ADMIN");
        // 辽41条：从事案件审核的人员应通过法律职业资格考试（参数开关，比对执法证台账）
        if (config.bool("review_legal_qualified_required", true)) {
            boolean qualified = certificates.values().stream().anyMatch(certificate -> actorId.equals(certificate.userId())
                    && certificate.enabled() && certificate.legalQualified() && certificate.expiresAt() != null
                    && !certificate.expiresAt().isBefore(LocalDate.now()));
            if (!qualified)
                throw new BizException(2070, "审核人 " + reviewer + " 未在台账中登记法律职业资格（辽41条）");
        }
        r.setReviewer(reviewer);
        r.setReviewerUserId(actorId);
        r.setOperator(username);
        r.setOpinionType(opinionType);
        r.setOpinion(opinion);
        r.setReviewedAt(LocalDate.now());
        r.setPassed("AGREE".equals(opinionType));
        return reviewRepository.save(r);
    }

    // ---------- 处罚告知/陈述申辩/听证（第41条） ----------

    /**
     * 处罚性金额＝罚款＋没收违法所得（参数 threshold_include_confiscate 可关）。
     * 没收违法所得与罚款同属行政处罚种类，只按罚款额比对"数额较大"，
     * 会让"罚款填 0 + 巨额没收"同时跳过听证告知、法制审核与集体讨论三道门槛。
     * 该口径最终由局方确定，故做成参数，默认计入。
     */
    BigDecimal punitiveAmount(BigDecimal fine, BigDecimal confiscate) {
        return config.bool("threshold_include_confiscate", true) ? nz(fine).add(nz(confiscate)) : nz(fine);
    }

    public record NoticeReq(String content, BigDecimal proposedFine, BigDecimal proposedRecoup, BigDecimal proposedConfiscate,
                            String changeReason, Boolean requireDelivery, Long previousNoticeId) {
        public NoticeReq(String content, BigDecimal proposedFine, BigDecimal proposedRecoup, BigDecimal proposedConfiscate,
                         String changeReason) {
            this(content, proposedFine, proposedRecoup, proposedConfiscate, changeReason, null, null);
        }
    }

    @Transactional
    public CaseNotice notify(Long caseId, NoticeReq req) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        CaseFile c = get(caseId);
        // REPORTED 首次告知；NOTIFIED 允许再次告知（辽52条：改变原认定的事实/证据/依据须重新履行告知程序）
        if (!List.of("REPORTED", "NOTIFIED").contains(c.getStatus()))
            throw new BizException(2004, "调查终结后方可作出处罚告知（第36/41条）");
        if (req == null || req.content() == null || req.content().isBlank())
            throw new BizException(2006, "请填写处罚告知内容");
        CaseNotice prev = noticeRepository.findTopByCaseIdOrderByIdDesc(caseId).orElse(null);
        if ((Boolean.TRUE.equals(req.requireDelivery()) || req.previousNoticeId() != null
                || prev != null && prev.isDeliveryRequired())
                && !java.util.Objects.equals(req.previousNoticeId(), prev == null ? null : prev.getId()))
            throw new BizException(2194, "告知版本已变化，请刷新后重新核对");
        BigDecimal fine = cn.ybcase.bureau.common.Money.nonNegative(req.proposedFine(), "拟罚款");
        BigDecimal recoup = cn.ybcase.bureau.common.Money.nonNegative(req.proposedRecoup(), "拟追回金额");
        BigDecimal confiscate = cn.ybcase.bureau.common.Money.nonNegative(req.proposedConfiscate(), "拟没收金额");
        boolean tracked = Boolean.TRUE.equals(req.requireDelivery()) || prev != null && prev.isDeliveryRequired()
                || config.bool("notice_delivery_required", false);
        if (prev != null) {
            var limit = noticeAmounts(caseId, prev);
            if ((tracked || req.previousNoticeId() != null) && !limit.permits(fine, recoup, confiscate))
                throw new BizException(2077, "再次告知的拟处罚金额不得超过" + limit.description());
            if (!tracked && req.previousNoticeId() == null
                    && (fine.compareTo(nz(prev.getProposedFine())) > 0
                        || recoup.compareTo(nz(prev.getProposedRecoup())) > 0
                        || confiscate.compareTo(nz(prev.getProposedConfiscate())) > 0)
                    && (req.changeReason() == null || req.changeReason().isBlank()))
                throw new BizException(2077, "再次告知的拟处罚金额高于前次，须载明改变原认定事实、证据或依据的理由（辽52条）；"
                        + "不得因当事人陈述申辩而加重处罚（第41条）");
        }
        CaseNotice n = new CaseNotice();
        // 再告知保留已提出的申请，听证笔录仍归属举行时的告知版本。
        if (prev != null && Boolean.TRUE.equals(prev.getHearingRequested())) n.setHearingRequested(true);
        n.setChangeReason(req.changeReason());
        n.setCaseId(caseId);
        n.setNotifiedAt(LocalDate.now());
        n.setContent(req.content());
        n.setProposedFine(fine);
        n.setProposedRecoup(recoup);
        int stmtDays = Math.max(0, config.intVal("statement_deadline_days", 3));
        n.setDeliveryRequired(tracked);
        if (tracked) {
            n.setStatementPeriodDays(stmtDays);
            n.setHearingPeriodDays(Math.max(0, config.intVal("hearing_request_days", 3)));
        } else if (stmtDays > 0) {
            n.setStatementDeadline(n.getNotifiedAt().plusDays(stmtDays));
        }
        // 拟罚款达到听证标准（按当事人类型分档，辽46条）：告知听证权利
        n.setProposedConfiscate(confiscate);
        n.setHearingEntitled(punitiveAmount(nz(req.proposedFine()), nz(req.proposedConfiscate()))
                .compareTo(config.byPartyType(c.getPartyType(),
                        "hearing_threshold_individual", "hearing_threshold_org", "100000")) >= 0);
        noticeRepository.save(n);
        c.setStatus("NOTIFIED");
        caseRepository.save(c);
        return n;
    }

    public record StatementReq(String statement, String statementReview,
                               Boolean hearingRequested, LocalDate hearingHeldAt, Boolean statementWaived,
                               Long noticeId, LocalDate statementReceivedAt, LocalDate hearingRequestedAt,
                               String hearingWithdrawalReference, String statementWaiverReference) {
        public StatementReq(String statement, String statementReview, Boolean hearingRequested,
                            LocalDate hearingHeldAt, Boolean statementWaived) {
            this(statement, statementReview, hearingRequested, hearingHeldAt, statementWaived, null, null, null, null, null);
        }
    }

    @Transactional
    public void recordNoticeDelivery(Long caseId, Long noticeId, String method, LocalDate deliveredAt, String receiver,
                                     String receiptNo, LocalDate signedAt, String note) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        if ("ELECTRONIC".equals(method)) throw new BizException(2041, "版本告知的电子送达须通过认证供应商回执登记");
        applyNoticeDelivery(caseId, noticeId, null, method, deliveredAt, receiver, receiptNo, signedAt, note);
    }

    // Called under the case lock by the paper path or an authenticated electronic callback.
    long applyNoticeDelivery(Long caseId, Long noticeId, Long documentId, String method, LocalDate deliveredAt,
                             String receiver, String receiptNo, LocalDate signedAt, String note) {
        CaseNotice notice = currentNotice(caseId);
        if (!notice.isDeliveryRequired() || !"NOTIFIED".equals(get(caseId).getStatus()))
            throw new BizException(2041, "本案当前告知不支持登记版本送达");
        ProcedureService.requireNoticeVersion(notice, noticeId);
        if (receiptNo == null || receiptNo.isBlank() || receiver == null || receiver.isBlank())
            throw new BizException(2041, "受送达人和实际回证编号必填");
        if (deliveredAt == null || deliveredAt.isBefore(notice.getNotifiedAt()) || deliveredAt.isAfter(LocalDate.now())
                || signedAt != null && (signedAt.isBefore(notice.getNotifiedAt()) || signedAt.isAfter(LocalDate.now())))
            throw new BizException(2041, "实际送达日期及回证签收日期须在告知登记日至今天之间");
        var existing = jdbc.queryForList("""
                select id, document_id, method, delivered_at, receiver, receipt_no, receipt_signed_at, note
                from case_delivery where notice_id=?""", noticeId);
        if (!existing.isEmpty()) {
            var receipt = existing.getFirst();
            if (java.util.Objects.equals(documentId, receipt.get("document_id"))
                    && java.util.Objects.equals(method, receipt.get("method"))
                    && java.util.Objects.equals(java.sql.Date.valueOf(deliveredAt), receipt.get("delivered_at"))
                    && java.util.Objects.equals(receiver, receipt.get("receiver"))
                    && java.util.Objects.equals(receiptNo, receipt.get("receipt_no"))
                    && java.util.Objects.equals(signedAt == null ? null : java.sql.Date.valueOf(signedAt), receipt.get("receipt_signed_at"))
                    && java.util.Objects.equals(note, receipt.get("note"))) return ((Number) receipt.get("id")).longValue();
            throw new BizException(2041, "本版本已有实际回证，请核对原记录");
        }
        long id = jdbc.queryForObject("""
                insert into case_delivery(case_id, method, delivered_at, receiver, note, receipt_no,
                                          receipt_signed_at, doc_kind, notice_id, document_id)
                values (?,?,?,?,?,?,?,'NOTICE',?,?) returning id""",
                Long.class, caseId, method, deliveredAt, receiver, note, receiptNo, signedAt, noticeId, documentId);
        notice.setStatementDeadline(procedureDeadline(deliveredAt, notice.getStatementPeriodDays()));
        notice.setHearingRequestDeadline(procedureDeadline(deliveredAt, notice.getHearingPeriodDays()));
        noticeRepository.save(notice);
        return id;
    }

    private record NoticeAmounts(BigDecimal fine, BigDecimal recoup, BigDecimal confiscate) {
        boolean permits(BigDecimal proposedFine, BigDecimal proposedRecoup, BigDecimal proposedConfiscate) {
            return proposedFine.compareTo(fine) <= 0 && proposedRecoup.compareTo(recoup) <= 0
                    && proposedConfiscate.compareTo(confiscate) <= 0;
        }

        String description() {
            return "历次最低告知金额（罚款" + fine + "元／追回" + recoup + "元／没收" + confiscate + "元）";
        }
    }

    private NoticeAmounts noticeAmounts(Long caseId, CaseNotice current) {
        var notices = noticeRepository.findByCaseIdOrderByIdDesc(caseId);
        return new NoticeAmounts(
                notices.stream().map(CaseNotice::getProposedFine).filter(java.util.Objects::nonNull)
                        .min(BigDecimal::compareTo).orElse(nz(current.getProposedFine())),
                notices.stream().map(CaseNotice::getProposedRecoup).filter(java.util.Objects::nonNull)
                        .min(BigDecimal::compareTo).orElse(nz(current.getProposedRecoup())),
                notices.stream().map(n -> nz(n.getProposedConfiscate())).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO));
    }

    private LocalDate procedureDeadline(LocalDate effectiveDate, Integer periodDays) {
        return periodDays == null || periodDays == 0 ? null : effectiveDate.plusDays(periodDays);
    }

    private CaseNotice currentNotice(Long caseId) {
        return noticeRepository.findTopByCaseIdOrderByIdDesc(caseId)
                .orElseThrow(() -> new BizException(2006, "尚未作出处罚告知"));
    }

    @Transactional
    public CaseNotice recordStatement(Long caseId, StatementReq req) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        CaseFile c = get(caseId);
        CaseGuards.requireNotArchived(c);
        CaseNotice n = currentNotice(caseId);
        ProcedureService.requireNoticeVersion(n, req.noticeId());
        LocalDate deliveredAt = null;
        if (n.isDeliveryRequired()) {
            if (!"NOTIFIED".equals(c.getStatus())) throw new BizException(2194, "当前阶段不能更改告知程序记录");
            deliveredAt = procedureService.requireNoticeDelivery(n);
        }
        if (req.statement() != null && req.statement().isBlank())
            throw new BizException(2100, "陈述申辩内容不能为空白");
        LocalDate statementDate = n.isDeliveryRequired() && req.statement() != null
                ? actualResponseDate(req.statementReceivedAt(), deliveredAt, "陈述申辩收到日期") : LocalDate.now();
        if (req.statement() != null && n.getStatementDeadline() != null
                && statementDate.isAfter(n.getStatementDeadline()))
            throw new BizException(2046, "已超过陈述申辩期限（" + n.getStatementDeadline()
                    + "），依法视为放弃陈述申辩权（辽44条）；如需继续，请按放弃留痕处理");
        if (req.statement() != null) {
            n.setStatement(req.statement());
            if (n.isDeliveryRequired()) n.setStatementReceivedAt(statementDate);
        }
        if (req.statementReview() != null) n.setStatementReview(req.statementReview());
        // 当事人明确放弃陈述申辩：留痕后方可在期限届满前决定（第41条）
        if (Boolean.TRUE.equals(req.statementWaived()) && !Boolean.TRUE.equals(n.getStatementWaived())) {
            if (n.isDeliveryRequired()) recordNoticeEvent(n, "STATEMENT_WAIVER", req.statementWaiverReference(), "陈述申辩放弃凭据");
            n.setStatementWaived(true);
        }
        if (req.hearingRequested() != null) {
            if (Boolean.TRUE.equals(req.hearingRequested()) && !Boolean.TRUE.equals(n.getHearingEntitled()))
                throw new BizException(2039, "该案未达听证标准，无听证权利告知记录");
            if (Boolean.TRUE.equals(req.hearingRequested()) && !Boolean.TRUE.equals(n.getHearingRequested())) {
                LocalDate requestDate = n.isDeliveryRequired()
                        ? actualResponseDate(req.hearingRequestedAt(), deliveredAt, "听证申请收到日期") : LocalDate.now();
                LocalDate deadline = n.isDeliveryRequired() ? n.getHearingRequestDeadline()
                        : procedureDeadline(n.getNotifiedAt(), Math.max(0, config.intVal("hearing_request_days", 3)));
                if (deadline != null && requestDate.isAfter(deadline))
                    throw new BizException(2046, "听证申请已超过期限（" + deadline + "）");
                if (n.isDeliveryRequired()) n.setHearingRequestedAt(requestDate);
            }
            if (n.isDeliveryRequired() && !req.hearingRequested() && Boolean.TRUE.equals(n.getHearingRequested()))
                recordNoticeEvent(n, "HEARING_WITHDRAWAL", req.hearingWithdrawalReference(), "听证申请撤回凭据");
            n.setHearingRequested(req.hearingRequested());
        }
        if (req.hearingHeldAt() != null) {
            throw new BizException(2141, "听证举行日期须通过听证流程登记，不能在陈述申辩中直接填写");
        }
        return noticeRepository.save(n);
    }

    private LocalDate actualResponseDate(LocalDate date, LocalDate deliveredAt, String label) {
        if (date == null || date.isBefore(deliveredAt) || date.isAfter(LocalDate.now()))
            throw new BizException(2046, label + "须在本次告知实际送达日至今天之间");
        return date;
    }

    private void recordNoticeEvent(CaseNotice notice, String type, String reference, String label) {
        if (reference == null || reference.isBlank() || reference.length() > 500 || reference.indexOf('\0') >= 0)
            throw new BizException(2100, "请填写" + label + "，最多500字");
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        jdbc.update("""
                insert into case_notice_procedure_event(case_id,notice_id,event_type,reference,recorded_by)
                values (?,?,?,?,?)""", notice.getCaseId(), notice.getId(), type, reference.trim(),
                auth == null ? null : auth.getName());
    }

    // ---------- 集体讨论（第44条） ----------

    public record MeetingReq(LocalDate heldAt, String attendees, String record, String conclusion) {}

    @Transactional
    public void addMeeting(Long caseId, MeetingReq req) {
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        CaseGuards.requireNotArchived(get(caseId));
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
                              String mitigation, String discretionReason, String penaltyBasis,
                              ViolationFinding finding) {
        public DecisionReq(String decisionType, BigDecimal fineAmount, BigDecimal recoupAmount,
                           BigDecimal confiscateAmount, String otherMeasures, String content,
                           String mitigation, String discretionReason) {
            this(decisionType, fineAmount, recoupAmount, confiscateAmount, otherMeasures, content,
                    mitigation, discretionReason, null, null);
        }
    }

    public record CloseReq(String closeReport) {}
    public record PreflightReq(String action, DecisionReq decision, CloseReq close) {}
    public record PreflightItem(String key, String label, boolean passed, String message, String role) {}
    public record PreflightResult(String action, boolean ready, List<PreflightItem> items) {}

    private record CheckedItem(PreflightItem item, int code, String failureMessage) {}

    /** The same read-only checks drive previews and locked writes; no trial writes or number allocation. */
    private static final class Checklist {
        private final java.util.ArrayList<CheckedItem> checks = new java.util.ArrayList<>();

        void check(String key, String label, boolean passed, int code, String message, String role) {
            check(key, label, passed, code, message, role, message);
        }

        void check(String key, String label, boolean passed, int code, String message, String role, String failureMessage) {
            checks.add(new CheckedItem(new PreflightItem(key, label, passed,
                    passed ? "已满足" : message, role), code, failureMessage));
        }

        BigDecimal money(String key, String label, BigDecimal amount) {
            try {
                BigDecimal value = cn.ybcase.bureau.common.Money.nonNegative(amount, label);
                check(key, label, true, 2140, "", "HANDLER");
                return value;
            } catch (BizException e) {
                check(key, label, false, e.code, e.getMessage(), "HANDLER");
                return null;
            }
        }

        void requireReady() {
            for (CheckedItem c : checks) {
                if (!c.item().passed()) throw new BizException(c.code(), c.failureMessage());
            }
        }

        PreflightResult result(String action) {
            return new PreflightResult(action, checks.stream().allMatch(c -> c.item().passed()),
                    checks.stream().map(CheckedItem::item).toList());
        }
    }

    private record DecisionChecks(Checklist checklist, BigDecimal fine, BigDecimal recoup, BigDecimal confiscate) {}

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public PreflightResult preflight(Long caseId, PreflightReq req) {
        materialAccess.requireCurrentCaseReader(caseId);
        if (req == null || !("DECIDE".equals(req.action()) || "CLOSE".equals(req.action())))
            throw new BizException(2144, "前置检查操作须为 DECIDE 或 CLOSE");
        CaseFile c = get(caseId);
        return "DECIDE".equals(req.action()) ? decisionChecks(c, req.decision()).checklist().result("DECIDE")
                : closeChecks(c, req.close()).result("CLOSE");
    }

    private DecisionChecks decisionChecks(CaseFile c, DecisionReq req) {
        Long caseId = c.getId();
        Checklist out = new Checklist();
        out.check("writable", "案卷尚未封卷", !"CLOSED".equals(c.getStatus()) && c.getArchiveNo() == null,
                2031, "案件已立卷归档，不可再增删卷内材料", "LEADER");
        out.check("no_decision", "尚未作出处理决定", decisionRepository.findByCaseId(caseId).isEmpty(),
                2040, "该案已作出处理决定", "LEADER");
        out.check("exclusions_closed", "期限扣除事由已登记结束日期", exclusionsClosed(caseId),
                2032, "仍有未结束的期限扣除事由或专家评审，请先登记结束日期", "HANDLER");
        if (req == null) {
            out.check("decision_draft", "处理决定草稿", false, 2144, "请填写拟作出的处理决定", "HANDLER");
            req = new DecisionReq(null, null, null, null, null, null, null, null);
        }
        boolean validType = req.decisionType() != null && List.of("PUNISH", "NO_PUNISH", "NOT_ESTABLISHED",
                "TRANSFER_ADMIN", "TRANSFER_JUDICIAL").contains(req.decisionType());
        out.check("decision_type", "法定决定类型", validType, 2040, "决定类型须为第43条规定的五种之一", "HANDLER");
        BigDecimal fine = out.money("fine_amount", "罚款金额", req.fineAmount());
        BigDecimal recoup = out.money("recoup_amount", "追回金额", req.recoupAmount());
        BigDecimal conf = out.money("confiscate_amount", "没收金额", req.confiscateAmount());
        boolean validAmounts = fine != null && recoup != null && conf != null;
        if (fine != null && conf != null)
            out.check("penalty_type", "决定类型与罚没金额一致", "PUNISH".equals(req.decisionType())
                            || fine.signum() == 0 && conf.signum() == 0,
                    2140, "非处罚决定不能确定罚款或没收违法所得", "HANDLER");
        boolean summary = "SUMMARY".equals(c.getProcedureType());
        if (req.penaltyBasis() != null || c.isReportingDetailsRequired()) {
            out.check("penalty_basis", "处罚依据", !"PUNISH".equals(req.decisionType())
                            || validReportText(req.penaltyBasis(), 4000),
                    2040, "请填写处罚依据，最多4000字", "LEADER");
        }
        if (req.finding() != null || summary && c.isReportingDetailsRequired() && "PUNISH".equals(req.decisionType())) {
            out.check("violation_finding", "确认违法事实", summary && "PUNISH".equals(req.decisionType())
                            && req.finding() != null && validFinding(req.finding()),
                    2040, "请在简易程序处罚决定中填写有效的违法发生起止日期、认定事实和金额", "LEADER");
        }
        if (summary) {
            out.check("decision_status", "简易程序决定阶段", "INVESTIGATING".equals(c.getStatus()),
                    2003, "简易程序应在调查中当场作出决定（第48条）", "HANDLER");
            BigDecimal limit = config.byPartyType(c.getPartyType(), "summary_fine_limit_individual", "summary_fine_limit_org",
                    "INDIVIDUAL".equals(c.getPartyType()) ? "200" : "3000");
            if (fine != null)
                out.check("summary_limit", "简易程序罚款限额", !"PUNISH".equals(req.decisionType()) || fine.compareTo(limit) <= 0,
                        2003, "简易程序罚款超限（当事人类别限额 " + limit + " 元），请转普通程序（第48条）", "HANDLER");
        } else {
            out.check("decision_status", "已告知并听取陈述申辩", "NOTIFIED".equals(c.getStatus()),
                    2006, "作出处罚决定前应当书面告知当事人并听取陈述申辩（第41条）", "HANDLER");
            CaseNotice notice = noticeRepository.findTopByCaseIdOrderByIdDesc(caseId).orElse(null);
            out.check("notice", "处罚告知记录", notice != null, 2006, "缺少处罚告知记录（第41条）", "HANDLER");
            var returns=jdbc.queryForList("select review_id,prior_notice_id from case_investigation_return where case_id=? order by id desc limit 1",caseId);
            boolean returned=!returns.isEmpty();
            if(returned) {
                var prior=returns.getFirst().get("prior_notice_id");
                out.check("reinvestigation_notice","补查后重新告知",notice!=null && (prior==null || notice.getId()>((Number)prior).longValue()),
                        2194,"补查后须重新履行告知程序，不能沿用补查前告知", "HANDLER");
            }
            LocalDate heldHearing = notice == null ? null : procedureService.heldHearingDate(notice);
            if (notice != null) {
                out.check("notice_delivery", "本次处罚告知已实际送达",
                        !notice.isDeliveryRequired() || procedureService.noticeDeliveredAt(notice) != null,
                        2006, "请先登记当前告知版本的实际送达回证", "HANDLER");
                var limit = noticeAmounts(caseId, notice);
                if (validAmounts)
                    out.check("no_aggravation", "不得高于历次最低告知金额", limit.permits(fine, recoup, conf),
                            2007, "决定金额不得高于" + limit.description() + "（第41条）", "HANDLER");
                out.check("requested_hearing", "已申请听证的真实听证笔录", !Boolean.TRUE.equals(notice.getHearingRequested()) || heldHearing != null,
                        2075, "当事人已申请听证，应当组织听证并制作笔录后方可作出决定（第41条）", "HANDLER");
            }
            Integer pending = jdbc.queryForObject("select count(*) from case_hearing where case_id = ? and status = 'SCHEDULED'", Integer.class, caseId);
            out.check("pending_hearing", "无尚未举行的排期听证", pending == null || pending == 0,
                    2075, "本案有已排期但尚未举行的听证，应当组织听证后方可作出决定（第41条）", "HANDLER");
            if (notice != null) {
                boolean waiting = "PUNISH".equals(req.decisionType()) && config.bool("statement_wait_required", true)
                        && notice.getStatementDeadline() != null && !LocalDate.now().isAfter(notice.getStatementDeadline())
                        && (notice.getStatement() == null || notice.getStatement().isBlank())
                        && !Boolean.TRUE.equals(notice.getStatementWaived()) && heldHearing == null;
                out.check("statement_period", "陈述申辩期限或已行使/放弃权利", !waiting, 2076,
                        "陈述申辩期至 " + notice.getStatementDeadline()
                                + " 届满前不得作出处罚决定（第41条）；当事人已陈述申辩或明确放弃的，请先录入陈述申辩记录", "HANDLER");
            }
            if (validAmounts && (returned || documentService.legalReviewRequired(caseId, fine, recoup, conf))) {
                CaseReview review = reviewRepository.findTopByCaseIdOrderByIdDesc(caseId).orElse(null);
                if(returned) out.check("reinvestigation_review","补查后新法审",review!=null && review.getId()>((Number)returns.getFirst().get("review_id")).longValue(),
                        2194,"补查后须重新提交法制审核，原意见保留历史", "LEGAL");
                out.check("legal_review", "法制审核通过", review != null && Boolean.TRUE.equals(review.getPassed()),
                        2005, "本案属法制审核范围，未经审核通过不得作出决定（第37条/辽40条）", "LEGAL");
                out.check("legal_review_materials", "法制审核覆盖当前材料", review != null && reviewMaterials.matches(review),
                        2150, "当前材料与最新送审版本不一致，或历史审核未绑定材料；请重新送审并通过后再作决定", "LEGAL");
            }
            if (config.bool("cross_exam_required", false) && "PUNISH".equals(req.decisionType())) {
                Integer unexamined = jdbc.queryForObject("select count(*) from case_evidence where case_id = ? and cross_exam_at is null", Integer.class, caseId);
                out.check("cross_exam", "证据已经当事人质证", unexamined == null || unexamined == 0, 2045,
                        "尚有 " + unexamined + " 份证据未经当事人质证，不能作为决定依据（辽24条）", "HANDLER");
            }
            if ("PUNISH".equals(req.decisionType())) {
                BigDecimal threshold = config.byPartyType(c.getPartyType(), "meeting_required_fine_individual", "meeting_required_fine_org", "100000");
                if (fine != null && conf != null)
                    out.check("collective_meeting", "较大数额罚没集体讨论并签字", punitiveAmount(fine, conf).compareTo(threshold) < 0 || hasValidMeeting(caseId),
                            2047, "较大数额罚款（≥" + threshold + "元）应当经负责人集体讨论决定，请先录入讨论记录（辽54条/局令44条）", "LEADER");
                out.check("discretion_reason", "裁量理由与考虑因素", !config.bool("discretion_reason_required", true)
                                || req.discretionReason() != null && !req.discretionReason().isBlank(),
                        2062, "处罚决定须说明裁量理由/考虑因素（辽44条），可先查看裁量基准建议", "HANDLER");
            }
            LocalDate deadline = c.getDeadlineAt() == null ? null : c.getDeadlineAt().plusDays(totalExclusionDays(caseId));
            out.check("decision_deadline", "尚在有效办案期限内", deadline != null && !LocalDate.now().isAfter(deadline),
                    2008, "已超办案期限（" + deadline + "），请先办理延期审批（第45条）", "LEADER");
        }
        out.check("decision_content", "处理决定正文", req.content() != null && !req.content().isBlank(),
                2144, "须填写处理决定正文", "HANDLER");
        return new DecisionChecks(out, fine, recoup, conf);
    }

    private Checklist closeChecks(CaseFile c, CloseReq req) {
        Checklist out = new Checklist();
        boolean terminated = "TERMINATED".equals(c.getStatus());
        boolean report = req != null && req.closeReport() != null && !req.closeReport().isBlank();
        LocalDate today = ExecutionDeadlines.today();
        var effective = terminated ? Map.<String,Object>of() : executionDeadlines.effectiveDecision(c.getId(),today);
        var executionWindow = executionDeadlines.window(c,effective,today);
        var delivery = executionWindow.delivery();
        out.check("writable", "尚未立卷归档", c.getArchiveNo() == null && !"CLOSED".equals(c.getStatus()), 2011,
                terminated ? "本案已立卷归档（案卷号 " + c.getArchiveNo() + "）" : "案件状态不满足结案条件", "LEADER");
        out.check("exclusions_closed", "期限扣除事由已登记结束日期", exclusionsClosed(c.getId()),
                2032, "仍有未结束的期限扣除事由或专家评审，请先登记结束日期再归档", "HANDLER");
        boolean outstandingContinuation = executionWindow.reliefPending()
                || Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from case_continuation_request where case_id=? and status='PENDING')",
                        Boolean.class,c.getId()));
        out.check("continuation_review", "救济结果及后续申请已完成复核", !outstandingContinuation,2193,
                "尚有待复核后续申请或未办结救济结果，请先办理后再确认结案", "LEGAL");
        if (!terminated) {
            CaseDecision d = decisionRepository.findByCaseId(c.getId()).orElse(null);
            out.check("decision", "已有处理决定", d != null, 2011, "无处理决定，不能结案", "LEADER");
            out.check("close_status", "已决定或已送达阶段", List.of("DECIDED", "DELIVERED").contains(c.getStatus()),
                    2011, "案件状态不满足结案条件", "HANDLER");
            if (d != null && "PUNISH".equals(effective.get("decision_type"))) {
                out.check("delivery", "当前有效处罚决定已送达", delivery != null,
                        2011, "当前有效处罚决定尚无生效送达记录，不能结案", "HANDLER");
                out.check("execution_or_court", "款项执行完毕或法院正式受理",
                        executionDeadlines.courtAccepted(c,effective,today)
                                || executionDeadlines.fullyExecuted(c.getId(),effective,today),
                        2011, "当前决定款项未执行完毕，且无对应法院正式受理回执，不能结案", "HANDLER");
            }
        }
        out.check("close_report", terminated ? "终止调查/移送归档报告" : "行政处罚结案报告", report, 2011,
                terminated ? "须填写终止调查/移送情况说明与归档报告（第56/57条）"
                        : "须填写行政处罚结案报告并经负责人批准（第56条）", "LEADER");
        if (config.bool("archive_completeness_required", false)) {
            List<String> missing = documentService.missingRequiredDocs(c.getId()).stream()
                    .filter(type -> !"DELIVERY_RECEIPT".equals(type) || delivery == null || delivery.id() == null)
                    .filter(type -> !report || !"CLOSE_REPORT".equals(type)).toList();
            String originalFailure = "案卷必备文书缺失：" + String.join("、", missing) + "（第57条文书齐全要求）";
            for (String type : missing) {
                String name = switch (type) {
                    case "FILING_APPROVAL" -> "立案审批表";
                    case "FINAL_REPORT" -> "调查终结报告";
                    case "NOTICE" -> "行政处罚告知书";
                    case "DECISION" -> "处理决定书";
                    case "DELIVERY_RECEIPT" -> "送达回证";
                    case "CLOSE_REPORT" -> "结案/归档报告";
                    case "LEGAL_OPINION" -> "法制审核意见书";
                    case "TERMINATE_DECISION" -> "终止调查决定书";
                    case "TRANSFER_LETTER" -> "案件移送函";
                    case "ACCEPT_RECEIPT" -> "受案回执";
                    default -> type;
                };
                String role = "LEGAL_OPINION".equals(type) ? "LEGAL"
                        : List.of("FILING_APPROVAL", "DECISION", "CLOSE_REPORT", "TERMINATE_DECISION").contains(type) ? "LEADER" : "HANDLER";
                out.check("document_" + type, name, false, 2052, "缺少" + name + "（第57条文书齐全要求）", role, originalFailure);
            }
            if (missing.isEmpty()) out.check("documents_complete", "案卷必备文书齐全", true, 2052, "", "HANDLER");
        }
        return out;
    }

    @Transactional
    public CaseDecision decide(Long caseId, DecisionReq req) {
        CaseGuards.lock(jdbc, caseId);
        materialAccess.requireCurrentCaseRoles(caseId, "LEADER", "ADMIN");
        CaseFile c = get(caseId);
        DecisionChecks checks = decisionChecks(c, req);
        checks.checklist().requireReady();
        BigDecimal fine = checks.fine();
        BigDecimal recoup = checks.recoup();
        BigDecimal confiscateAmount = checks.confiscate();

        CaseDecision d = new CaseDecision();
        d.setCaseId(caseId);
        d.setDecisionType(req.decisionType());
        if ("PUNISH".equals(req.decisionType())) {
            d.setDecisionNo(cfg("decision_no_prefix", "医保罚") + "〔" + LocalDate.now().getYear() + "〕"
                    + seqService.next("DECISION", LocalDate.now().getYear()) + "号");
        }
        d.setFineAmount(fine);
        d.setRecoupAmount(recoup);
        d.setConfiscateAmount(confiscateAmount);
        d.setOtherMeasures(req.otherMeasures());
        d.setContent(req.content());
        if ("PUNISH".equals(req.decisionType()) && req.penaltyBasis() != null)
            d.setPenaltyBasis(req.penaltyBasis().strip());
        if (req.finding() != null) applyFinding(c, req.finding());
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
        CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from case_effective_decisions(current_date,clock_timestamp()) where case_id=? and version_id is not null)",Boolean.class,caseId)))
            throw new BizException(2193,"本案已有当前生效的独立决定版本，不得继续登记原决定送达；请办理具体后续版本文书送达");
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
        CaseGuards.lock(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        if (effectiveCloseDecision(caseId).get("version_id") != null)
            throw new BizException(2193,"本案已有当前生效的独立决定版本，不得继续公开原决定；请人工核对结果版本及既有公示");
        entityManager.flush();
        CaseFile cf = get(caseId);
        entityManager.refresh(cf);
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2042, "案件无处理决定"));
        entityManager.refresh(d);
        // 辽56条：公开的是"行政处罚决定"，且应在决定书送达后（未送达即公开会侵害当事人陈述救济权）
        if (!"PUNISH".equals(d.getDecisionType()))
            throw new BizException(2042, "仅行政处罚决定需要依法公开（当前决定类型：" + d.getDecisionType() + "）");
        // 公告送达的 deliveredAt 记的是"公告期满之日"，是个未来日期；只判非空
        // 会让公告一登记就能立刻公开，比法定生效早整整一个公告期（默认60日）
        if (cf.getDeliveredAt() == null || cf.getDeliveredAt().isAfter(LocalDate.now()))
            throw new BizException(2042, "处罚决定书尚未送达"
                    + (cf.getDeliveredAt() == null ? "" : "（公告送达期满日 " + cf.getDeliveredAt() + "）")
                    + "，不得先行公开（辽56条）");
        // 幂等：重复调用会把公开日期刷成当天，把"7日内公开"的超期证据洗白（第56条监督链）
        if (Boolean.TRUE.equals(d.getPublished()) && d.getPublishedAt() != null)
            throw new BizException(2042, "该决定已于 " + d.getPublishedAt() + " 公开，不可重复登记");
        int updated = jdbc.update("update case_decision set published = true, published_at = ? where case_id = ? and published = false",
                LocalDate.now(), caseId);
        if (updated == 0) throw new BizException(2042, "该决定已公开，不可重复登记");
        entityManager.refresh(d);
        return d;
    }

    /** 重大处罚决定政府备案登记（辽54条） */
    @Transactional
    public CaseDecision govRecord(Long caseId, String recordNo) {
        CaseGuards.lock(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        entityManager.flush();
        CaseDecision d = decisionRepository.findByCaseId(caseId)
                .orElseThrow(() -> new BizException(2042, "案件无处理决定"));
        entityManager.refresh(d);
        if (recordNo == null || recordNo.isBlank()) throw new BizException(2042, "须填写备案文号");
        if (recordNo.trim().length() > 64) throw new BizException(2042, "备案文号不得超过64字");
        if (!"PUNISH".equals(d.getDecisionType())) throw new BizException(2042, "仅行政处罚决定可登记政府备案");
        int updated = jdbc.update("""
                update case_decision set gov_record_no = ?, gov_record_at = ?
                where case_id = ? and gov_record_no is null and gov_record_at is null""", recordNo.trim(), LocalDate.now(), caseId);
        if (updated == 0) throw new BizException(2042, "该决定已登记政府备案，不可覆盖原文号或日期");
        entityManager.refresh(d);
        return d;
    }

    // ---------- 结案（第56-57条） ----------

    /**
     * 终止调查/移送司法案件的立卷归档（第57条）：保留 TERMINATED 状态，只补案卷号与结案报告。
     * private 自调用方法标 @Transactional 无效，事务由调用方 close() 承担。
     */
    private CaseFile archiveTerminated(CaseFile c, String closeReport, String maker) {
        var d = decisionRepository.findByCaseId(c.getId()).orElse(null);
        String reason = d != null && String.valueOf(d.getDecisionType()).startsWith("TRANSFER")
                ? "JUDICIAL" : "TERMINATED";
        addDocument(c.getId(), new DocumentReq(
                "CLOSE_REPORT", c.getCaseNo() + " 归档报告", closeReport,
                LocalDate.now(), maker, false, null, null));
        jdbc.update("update case_evidence set sealed = false, register_hold = false where case_id = ?", c.getId());
        c.setClosedAt(LocalDate.now());
        c.setCloseReason(reason);
        c.setArchiveNo(c.getCaseNo() + "卷");
        return caseRepository.save(c);
    }

    @Transactional
    public CaseFile close(Long caseId, String closeReport, String maker) {
        CaseGuards.lock(jdbc, caseId);
        materialAccess.requireCurrentCaseRoles(caseId, "LEADER", "ADMIN");
        CaseFile c = get(caseId);
        Checklist checks = closeChecks(c, new CloseReq(closeReport));
        checks.requireReady();
        if ("TERMINATED".equals(c.getStatus())) return archiveTerminated(c, closeReport, maker);
        LocalDate today = ExecutionDeadlines.today();
        var effective = executionDeadlines.effectiveDecision(caseId,today);
        String closeReason = !"PUNISH".equals(effective.get("decision_type")) ? "NO_NEED"
                : executionDeadlines.courtAccepted(c,effective,today) ? "COURT" : "EXECUTED";
        addDocument(caseId, new DocumentReq("CLOSE_REPORT", c.getCaseNo() + " 结案报告",
                closeReport, LocalDate.now(), maker, false, null, null));

        // 结案即解除仍在生效的强制措施（第31条封存、第26条先行登记保存）：
        // updateSeal 限案件在办状态，进入 DECIDED 后就再也解不掉，
        // 结案时不解除的话，督办看板的"封存到期"会对已办结案件永久常亮
        jdbc.update("update case_evidence set sealed = false, register_hold = false where case_id = ?", caseId);
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
            "approvals|biz_approval|id desc",
            "corrections|case_correction|id desc");

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
        sb.append("(select coalesce(sum(greatest(0, coalesce(end_at, cast(? as date)) - start_at)), 0) from case_period_exclusion ")
          .append("where case_id = ?) as \"exclusionDays\"");
        return sb.toString();
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> detail(Long id) {
        materialAccess.requireCurrentCaseReader(id);
        CaseFile c = get(id);
        CaseCause cause = causeRepository.findById(c.getCauseId()).orElse(null);
        Object[] args = new Object[DETAIL_PARTS.size() + 3];
        java.util.Arrays.fill(args, id);
        args[args.length - 2] = exclusionCutoff(c);
        Map<String, Object> agg = jdbc.queryForMap(DETAIL_SQL, args);

        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("caseFile", c);
        m.put("jurisdiction", jurisdictionFacts(c));
        m.put("jurisdictionHistory", jurisdictionHistory(id));
        m.put("jurisdictionMaterials", jurisdictionMaterials(jurisdictionFacts(c)));
        m.put("cause", cause == null ? Map.of() : cause);
        for (String part : DETAIL_PARTS) m.put(part.split("\\|")[0], readJson(agg.get(part.split("\\|")[0])));
        m.put("documents", readJson(agg.get("documents")));
        m.put("reviews", reviewRepository.findByCaseIdOrderByIdDesc(id));
        m.put("reviewMaterialStatus", reviewMaterialStatus(id));
        m.put("notices", noticeRepository.findByCaseIdOrderByIdDesc(id));
        m.put("decision", decisionRepository.findByCaseId(id).map(Object.class::cast).orElse(Map.of()));
        long exclusionDays = agg.get("exclusionDays") == null ? 0 : ((Number) agg.get("exclusionDays")).longValue();
        m.put("effectiveDeadline", c.getDeadlineAt().plusDays(exclusionDays));
        m.put("executionDeadline", executionDeadlines.summary(c, LocalDate.now()));
        return m;
    }

    private Map<String, Object> reviewMaterialStatus(Long caseId) {
        var review = reviewRepository.findTopByCaseIdOrderByIdDesc(caseId).orElse(null);
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        boolean bound = review != null && review.getMaterialSnapshot() != null && review.getMaterialFingerprint() != null;
        boolean current = bound && reviewMaterials.matches(review);
        status.put("reviewId", review == null ? null : review.getId());
        status.put("fingerprint", review == null ? null : review.getMaterialFingerprint());
        status.put("bound", bound);
        status.put("current", current);
        status.put("reason", review == null ? "尚未提交法制审核" : !bound
                ? "历史审核未绑定送审材料；在办必审案件须重新送审" : !current
                ? "材料已变更，请重新送审；旧审核记录继续保留" : "当前材料与最新送审版本一致");
        return status;
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

    public boolean hasCaseScope(String username, boolean privileged) {
        return materialAccess.caseScope(username, privileged).restricted();
    }

    // 普通字符串拼接（文本块会剥前导空格导致 "?and" 语法错，医院项目同坑）
    // 关联键用账号 ID：早先按 real_name 匹配会让同名执法人员互相可见，改一次姓名即可绕过隔离
    /** 数据范围谓词（以 " and (" 开头，绑定表名 case_file 无别名）：供跨控制器的 SELF 收窄复用 */
    public static final String SCOPE_SQL =
            " and (case_file.owner_user = ? or exists (select 1 from case_officer o"
            + " join sys_user su on su.id = o.user_id"
            + " where o.case_id = case_file.id and o.avoided = false and su.username = ?)) ";

    /** 越权访问单案（2080） */
    public void assertInScope(Long caseId, String username, boolean privileged) {
        materialAccess.assertCaseScope(caseId, materialAccess.caseScope(username, privileged));
    }

    /** 分页+关键词检索（案号/案名/当事人），含范围过滤 */
    public Map<String, Object> pageList(String status, String q, int page, int size,
                                        String username, boolean privileged) {
        return pageList(status, q, null, page, size, username, privileged);
    }

    /** owner 非空时按承办人收窄（交接清单）；其余条件与旧签名完全一致 */
    public Map<String, Object> pageList(String status, String q, String owner, int page, int size,
                                        String username, boolean privileged) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) {
            where.append(" and status = ? ");
            args.add(status);
        }
        if (owner != null && !owner.isBlank()) {
            where.append(" and owner_user = ? ");
            args.add(owner.trim());
        }
        if (q != null && !q.isBlank()) {
            where.append(" and (case_no ilike ? or name ilike ? or party_name ilike ?) ");
            String like = "%" + q.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        var scope = materialAccess.caseScope(username, privileged);
        where.append(scope.sql());
        args.addAll(scope.args());
        Long total = jdbc.queryForObject("select count(*) from case_file" + where, Long.class, args.toArray());
        args.add(size);
        args.add((long) (Math.max(page, 1) - 1) * size);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from case_file" + where + " order by id desc limit ? offset ?", args.toArray());
        return Map.of("total", total == null ? 0 : total, "page", page, "size", size, "rows", rows);
    }

    /** 案件移交（承办人变更，负责人操作，留消息） */
    @Transactional
    public CaseFile transferOwner(Long caseId, String newOwner, String operator) {
        CaseGuards.lockWritable(jdbc, caseId);
        CaseFile c = get(caseId);
        // Lock operator and receiver together in ID order, including a distinct authenticated domain caller.
        // Separate operator->receiver locks could deadlock reciprocal transfers in different cases.
        var actors = workflowUsers.lockUsers(operator, newOwner, currentPrincipalName());
        var current = materialAccess.requireCurrentCaseRoles(caseId, "LEADER", "ADMIN");
        var receiver = newOwner == null ? null : actors.get(newOwner);
        if (receiver == null || !receiver.enabled())
            throw new BizException(2081, "接收人账号不存在、已停用或不具备接案角色");
        workflowUsers.assertDepartmentEnabled(receiver);
        if (receiver.roles().stream().noneMatch(cn.ybcase.bureau.spi.CaseOwnerRoles.RECEIVER_CODES::contains))
            throw new BizException(2081, "接收人账号不存在、已停用或不具备接案角色");
        var sender = current != null || operator == null ? current : actors.get(operator);
        if ((receiver.district() && receiver.deptId() == null)
                || (sender != null && sender.district()
                    && (sender.deptId() == null || !sender.deptId().equals(receiver.deptId()))))
            throw new BizException(2080, "区市县机构只能在本机构内移交案件");
        if (!java.util.Objects.equals(c.getOwnerDeptId(), receiver.deptId())
                && Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from sys_dept where id in (?,?) and org_kind in ('BUREAU','DISTRICT'))",
                        Boolean.class, c.getOwnerDeptId(), receiver.deptId())))
            throw new BizException(2080, "跨机构案件请通过机构交接办理");
        c.setOwnerUser(newOwner);
        c.setOwnerDeptId(receiver.deptId());
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
        var scope = materialAccess.caseScope(username, privileged);
        var args = new java.util.ArrayList<Object>(List.of(like, like, like));
        args.addAll(scope.args());
        return jdbc.queryForList(base + scope.sql() + " order by id desc limit 20", args.toArray());
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Map<String, Object> documentDetail(Long caseId, Long docId) {
        materialAccess.requireCurrentCaseReader(caseId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from case_document where id = ? and case_id = ?", docId, caseId);
        if (rows.isEmpty()) throw new BizException(2045, "文书不存在");
        return rows.get(0);
    }

    // ---------- 私有 ----------

    private String currentPrincipalName() {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal()) ? auth.getName() : null;
    }

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
                // 进行中的事由（end_at 为空）按截至今天计入：扣除的本就是"已经发生"的期间，
                // 与 addExclusion 的重叠判定（coalesce(end_at, current_date)）同口径
                "select start_at, coalesce(end_at, cast(? as date)) as end_at from case_period_exclusion where case_id = ?",
                exclusionCutoff(get(caseId)), caseId);
        return rows.stream().mapToLong(r -> ChronoUnit.DAYS.between(
                ((java.sql.Date) r.get("start_at")).toLocalDate(),
                ((java.sql.Date) r.get("end_at")).toLocalDate())).map(days -> Math.max(0, days)).sum();
    }

    /** Historic open rows stay intact; an investigation's clock stops at its first terminal action. */
    static LocalDate exclusionCutoff(CaseFile c) {
        return java.util.stream.Stream.of(LocalDate.now(), c.getDecidedAt(), c.getTerminatedAt(), c.getClosedAt())
                .filter(java.util.Objects::nonNull).min(LocalDate::compareTo).orElseThrow();
    }

    private boolean exclusionsClosed(Long caseId) {
        return jdbc.queryForObject("""
                select (select count(*) from case_period_exclusion where case_id = ? and end_at is null)
                     + (select count(*) from expert_review where case_id = ? and ended_at is null)""",
                Integer.class, caseId, caseId) == 0;
    }

    private void requireExclusionsClosed(Long caseId) {
        if (!exclusionsClosed(caseId))
            throw new BizException(2032, "仍有未结束的期限扣除事由或专家评审，请先登记结束日期");
    }

    /** Legacy suspended cases may already have exclusions; extend only days not counted there. */
    private long suspensionAdditionalDays(Long caseId, LocalDate start, LocalDate end) {
        long covered = 0;
        LocalDate cursor = start;
        for (var row : jdbc.queryForList("""
                select start_at, coalesce(end_at, cast(? as date)) as end_at
                from case_period_exclusion where case_id = ? order by start_at""", end, caseId)) {
            LocalDate left = ((java.sql.Date) row.get("start_at")).toLocalDate();
            LocalDate right = ((java.sql.Date) row.get("end_at")).toLocalDate();
            if (left.isBefore(cursor)) left = cursor;
            if (right.isAfter(end)) right = end;
            if (right.isAfter(left)) {
                covered += ChronoUnit.DAYS.between(left, right);
                cursor = right;
            }
        }
        return Math.max(0, ChronoUnit.DAYS.between(start, end) - covered);
    }

    /** 法定"负责人集体讨论"记录是否成立：须签字确认且参会人/结论有实质内容（第44/45条共用口径） */
    private boolean hasValidMeeting(Long caseId) {
        return !jdbc.queryForList("""
                select id from case_meeting
                where case_id = ? and sign_confirmed = true
                  and coalesce(btrim(attendees), '') <> ''
                  and coalesce(btrim(conclusion), '') <> ''""", caseId).isEmpty();
    }

    private Map<String,Object> effectiveCloseDecision(Long caseId) {
        return executionDeadlines.effectiveDecision(caseId,ExecutionDeadlines.today());
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
