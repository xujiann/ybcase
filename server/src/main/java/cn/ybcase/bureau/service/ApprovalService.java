package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private final ClueService clueService;
    private final MessageService messageService;
    private final JdbcTemplate jdbc;
    private final SourceAttachmentAccess sourceAttachmentAccess;
    private final WorkflowUserAccess workflowUsers;
    private final EnforcerRegistryAccess enforcerRegistry;
    private final MaterialAccess materialAccess;
    // 用 Spring 全局 ObjectMapper（含 JavaTime 模块，LocalDate 反序列化正确）
    private final ObjectMapper objectMapper;

    private static final Map<String, String> KIND_NAMES = Map.of(
            "FILE_CASE", "立案", "EXTEND", "延期", "SUSPEND", "中止", "TERMINATE", "终止", "DEFER", "暂缓分期", "CLUE_EXTEND", "线索延期");

    private static final List<String> KINDS = List.of("FILE_CASE", "EXTEND", "SUSPEND", "TERMINATE", "DEFER", "CLUE_EXTEND");

    // Older filing rows may contain only a payload source. Guard both casts because payload is a text column.
    private static final String FILING_SOURCE = """
            left join lateral (select case when a.kind='FILE_CASE' and pg_input_is_valid(a.payload,'jsonb')
                then a.payload::jsonb end as document) filing_document on true
            left join lateral (select filing_document.document->'clueId' as field,
                split_part(filing_document.document->>'clueId','.',1) as integral_text) source_value on true
            left join lateral (select case when jsonb_typeof(source_value.field)='number'
                and source_value.field::text ~ '^[1-9][0-9]*([.]0+)?$'
                and pg_input_is_valid(source_value.integral_text,'bigint')
                then source_value.integral_text::bigint end as id) payload_source on true
            left join lateral (select coalesce(a.clue_id,payload_source.id) as clue_id,
                (a.kind<>'FILE_CASE' or ((a.payload is null or jsonb_typeof(filing_document.document)='object')
                    and (source_value.field is null or source_value.field='null'::jsonb
                        or (payload_source.id is not null and (a.clue_id is null or a.clue_id=payload_source.id)))))
                as verified) source_binding on true
            """;

    public record ApplyReq(String kind, Long clueId, Long caseId, Map<String, Object> payload, String reason,
                           String requestKey, Long previousApprovalId) {
        public ApplyReq(String kind, Long clueId, Long caseId, Map<String, Object> payload, String reason) {
            this(kind, clueId, caseId, payload, reason, null, null);
        }
    }

    @Transactional
    public long apply(ApplyReq req, String applicant, boolean privileged) {
        if (req.kind() == null || !KINDS.contains(req.kind())) throw new BizException(2071, "审批类型须为 立案/延期/中止/终止/暂缓 之一");
        requireBinding(req.kind(), req.caseId(), req.clueId());
        if (req.reason() == null || req.reason().isBlank()) throw new BizException(2071, "须填写申请理由");
        boolean filing = "FILE_CASE".equals(req.kind());
        if (req.requestKey() != null && !req.requestKey().matches("[A-Za-z0-9_-]{16,80}"))
            throw new BizException(2071, "审批请求号格式无效");
        if (req.previousApprovalId() != null && (!filing || req.requestKey() == null || req.previousApprovalId() < 1))
            throw new BizException(2071, "请从本人已驳回的立案申请重新提交");
        Map<String, Object> submittedPayload = req.payload() == null ? null : new java.util.LinkedHashMap<>(req.payload());
        Long filingClueId = req.clueId();
        if (filing) {
            if (req.payload() == null || req.payload().get("partyName") == null)
                throw new BizException(2071, "立案申请须附立案审批表内容（当事人/案由/办案人员）");
            CaseService.JurisdictionReq jurisdiction;
            try {
                jurisdiction = objectMapper.convertValue(req.payload().get("jurisdiction"), CaseService.JurisdictionReq.class);
            } catch (IllegalArgumentException e) {
                throw new BizException(2145, "管辖事实格式无效");
            }
            submittedPayload.put("jurisdiction", CaseService.normalizeJurisdiction(jurisdiction));
            CaseService.InstitutionReq institution;
            try {
                institution = objectMapper.convertValue(req.payload().get("institution"), CaseService.InstitutionReq.class);
            } catch (IllegalArgumentException e) {
                throw new BizException(2026, "机构信息格式无效");
            }
            String partyType = java.util.Objects.toString(req.payload().get("partyType"), "");
            if ("PROVIDER".equals(partyType) && institution == null
                    && Boolean.TRUE.equals(req.payload().get("reportingDetailsRequired")))
                throw new BizException(2026, "请填写机构编码、性质和等级");
            submittedPayload.put("institution", CaseService.normalizeInstitution(partyType, institution));
            filingClueId = filingClueId(filingClueId, submittedPayload);
            if (filingClueId != null) {
                jdbc.queryForList("select id from case_clue where id=? for update", filingClueId);
                submittedPayload.put("clueId", filingClueId);
            }
            if (req.requestKey() != null) enforcerRegistry.lockRegistry();
        } else if ("CLUE_EXTEND".equals(req.kind())) {
            jdbc.queryForList("select id from case_clue where id=? for update", req.clueId());
        } else {
            CaseGuards.lock(jdbc, req.caseId());
        }
        // Never trust a caller-provided privileged flag after waiting on a business source.
        WorkflowUserAccess.User actor = materialAccess.lockActor(applicant, true);
        if (actor.district() && actor.deptId() == null)
            throw new BizException(2080, "区市县账号尚未关联机构");
        String payload = toJson(submittedPayload);
        if (req.requestKey() != null) {
            var existing = jdbc.queryForList("select * from biz_approval where applicant=? and request_key=?",
                    applicant, req.requestKey());
            if (!existing.isEmpty()) {
                var row = existing.getFirst();
                if (!sameSubmission(row, req, filingClueId, payload)
                        || !Objects.equals(number(row.get("applicant_dept_id")), actor.deptId()))
                    throw new BizException(2071, "同一审批请求号的申请内容或机构已变化");
                if (filingClueId != null) sourceAttachmentAccess.assertRead("CLUE", filingClueId, applicant);
                if (req.caseId() != null) materialAccess.assertCaseScope(req.caseId(), actor);
                return number(row.get("id"));
            }
        }
        if (filing && filingClueId != null)
            sourceAttachmentAccess.clueForFiling(filingClueId, applicant, false);
        else if ("CLUE_EXTEND".equals(req.kind())) {
            sourceAttachmentAccess.lockClueForHandling(req.clueId(), applicant);
            if (!"PENDING".equals(clueService.get(req.clueId()).getStatus())) throw new BizException(2021, "线索已办结");
        }
        if (req.caseId() != null) {
            materialAccess.assertCaseScope(req.caseId(), actor);
            CaseGuards.lockWritable(jdbc, req.caseId());
        }
        if (filing && filingClueId != null) {
            var existing = jdbc.queryForList("select a.* from biz_approval a " + FILING_SOURCE + """
                    where a.kind='FILE_CASE' and a.status='PENDING' and (a.clue_id=? or
                        (a.clue_id is null and source_binding.verified and source_binding.clue_id=?)) order by a.id
                    """, filingClueId, filingClueId);
            if (!existing.isEmpty()) {
                var row = existing.getFirst();
                if (existing.size() == 1 && req.requestKey() == null && applicant.equals(row.get("applicant"))
                        && sameSubmission(row, req, filingClueId, payload))
                    return number(row.get("id"));
                throw new BizException(2071, "此线索已有待审立案申请，请先处理原申请");
            }
        }
        if (req.previousApprovalId() != null) {
            var previous = jdbc.queryForList("""
                    select clue_id, payload, applicant_dept_id from biz_approval
                    where id=? and applicant=? and kind='FILE_CASE' and status='REJECTED'
                    """, req.previousApprovalId(), applicant);
            if (previous.isEmpty()
                    || !Objects.equals(filingClueId(number(previous.getFirst().get("clue_id")),
                        fromJson((String) previous.getFirst().get("payload"))), filingClueId)
                    || !Objects.equals(number(previous.getFirst().get("applicant_dept_id")), actor.deptId())
                    || Boolean.TRUE.equals(jdbc.queryForObject(
                        "select exists(select 1 from biz_approval where previous_approval_id=?)",
                        Boolean.class, req.previousApprovalId())))
                throw new BizException(2071, "原立案申请不可续填，请核对处理状态和来源");
        }
        if (filing && req.requestKey() != null) {
            CaseService.CaseCreateReq filingRequest;
            try {
                filingRequest = objectMapper.convertValue(submittedPayload, CaseService.CaseCreateReq.class);
            } catch (IllegalArgumentException e) {
                throw new BizException(2071, "立案审批材料格式无效");
            }
            caseService.preflightFilingApplication(filingRequest, actor);
        }
        Long id = jdbc.queryForObject("""
                insert into biz_approval
                    (kind, clue_id, case_id, payload, reason, applicant, applicant_dept_id, request_key, previous_approval_id)
                values (?,?,?,?,?,?,?,?,?) returning id""", Long.class,
                req.kind(), filingClueId, req.caseId(), payload, req.reason(), applicant, actor.deptId(),
                req.requestKey(), req.previousApprovalId());
        messageService.sendApprovalRequest(id,
                "新审批单：" + KIND_NAMES.getOrDefault(req.kind(), req.kind()) + "（" + applicant + "）",
                req.reason());
        return id;
    }

    private boolean sameSubmission(Map<String, Object> row, ApplyReq request, Long clueId, String payload) {
        return request.kind().equals(row.get("kind"))
                && Objects.equals(clueId, number(row.get("clue_id")))
                && ("FILE_CASE".equals(request.kind()) || Objects.equals(request.caseId(), number(row.get("case_id"))))
                && Objects.equals(request.previousApprovalId(), number(row.get("previous_approval_id")))
                && request.reason().equals(row.get("reason"))
                && fromJson(payload).equals(fromJson((String) row.get("payload")));
    }

    private static Long number(Object value) { return value instanceof Number n ? n.longValue() : null; }

    /** 负责人裁决：批准即执行对应动作（同事务，失败则整单回滚） */
    @Transactional
    public Map<String, Object> decide(long approvalId, boolean approve, String opinion, String approver) {
        if (opinion == null || opinion.isBlank()) throw new BizException(2071, "须填写审批意见或驳回理由");
        // Conditional claim is retained: only one concurrent decision may execute this application.
        int claimed = jdbc.update("""
                update biz_approval set status = ?, approver = ?, decided_at = now(), opinion = ?
                where id = ? and status = 'PENDING'""",
                approve ? "APPROVED" : "REJECTED", approver, opinion, approvalId);
        if (claimed == 0) throw new BizException(2072, "审批单不存在或已办结");
        var a = jdbc.queryForList("select * from biz_approval where id = ?", approvalId).get(0);
        Object executed = null;
        Long linkedCaseId = a.get("case_id") == null ? null : ((Number) a.get("case_id")).longValue();
        if (approve) {
            String kind = (String) a.get("kind");
            Long clueId = a.get("clue_id") == null ? null : ((Number) a.get("clue_id")).longValue();
            requireBinding(kind, linkedCaseId, clueId);
            if ("FILE_CASE".equals(kind)) {
                clueId = filingClueId(clueId, fromJson((String) a.get("payload")));
                if (clueId != null) {
                    jdbc.queryForList("select id from case_clue where id=? for update", clueId);
                    sourceAttachmentAccess.lockInspectionsForAdoption(clueId);
                }
                enforcerRegistry.lockRegistry();
            } else if ("CLUE_EXTEND".equals(kind)) {
                jdbc.queryForList("select id from case_clue where id=? for update", clueId);
            } else {
                CaseGuards.lock(jdbc, linkedCaseId);
            }
            String applicant = (String) a.get("applicant");
            // Business source -> all users in ID order. Never user -> existing case (transfer takes case -> user).
            var actors = workflowUsers.lockUsers(applicant, approver);
            var actor = workflowUsers.requireFiling(actors.get(applicant));
            var decider = workflowUsers.requireApprover(actors.get(approver));
            materialAccess.lockActor(approver, false);
            assertApprovalDepartment(approvalId, decider);
            if ("FILE_CASE".equals(kind) && a.get("applicant_dept_id") instanceof Number originalDept
                    && !java.util.Objects.equals(originalDept.longValue(), actor.deptId()))
                throw new BizException(2080, "申请人所属机构已变化，请驳回并由当前机构重新提交");
            if (linkedCaseId != null) {
                materialAccess.assertCaseScope(linkedCaseId, actor);
                CaseGuards.lockWritable(jdbc, linkedCaseId);
            }
            if ("FILE_CASE".equals(kind) && clueId != null)
                sourceAttachmentAccess.clueForFiling(clueId, applicant, false);
            else if ("CLUE_EXTEND".equals(kind))
                sourceAttachmentAccess.lockClueForHandling(clueId, applicant);
            executed = execute(kind, a, approver);
            if (executed instanceof cn.ybcase.bureau.entity.CaseFile created && linkedCaseId == null) {
                linkedCaseId = created.getId();
                jdbc.update("update biz_approval set case_id = ? where id = ?", linkedCaseId, approvalId);
            }
        } else {
            // A stale/disabled applicant or malformed historical payload must remain rejectable.
            if (linkedCaseId != null)
                jdbc.queryForList("select id from case_file where id=? for update", linkedCaseId);
            var actors = workflowUsers.lockUsers(approver, (String) a.get("applicant"));
            var decider = workflowUsers.requireApprover(actors.get(approver));
            materialAccess.lockActor(approver, false);
            assertApprovalDepartment(approvalId, decider);
        }
        messageService.send((String) a.get("applicant"), "APPROVAL",
                "审批单 #" + approvalId + "（" + KIND_NAMES.getOrDefault((String) a.get("kind"), "") + "）"
                        + (approve ? "已批准" : "已驳回"),
                opinion, linkedCaseId == null ? "/case/approvals?mine=1&focus=" + approvalId : "/case/detail/" + linkedCaseId, null);
        return Map.of("approved", approve, "result", executed == null ? Map.of() : executed);
    }

    private void assertApprovalDepartment(long approvalId, WorkflowUserAccess.User approver) {
        if (!approver.district()) return;
        var scope = sourceAttachmentAccess.approvalDepartmentPredicate("a", approver.username());
        var args = new java.util.ArrayList<Object>();
        args.add(approvalId);
        args.addAll(scope.args());
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from biz_approval a where a.id=? and " + scope.sql() + ")",
                Boolean.class, args.toArray())))
            throw new BizException(2080, "审批单不在当前区市县机构范围内");
    }

    /** 负责人直接操作原接口时补记单据（申请即批准） */
    @Transactional
    public void recordDirect(String kind, Long caseId, String reason, String operator) {
        Map<String, Object> snapshot = "FILE_CASE".equals(kind)
                ? Map.of("jurisdiction", caseService.jurisdictionSnapshot(caseId)) : null;
        jdbc.update("""
                insert into biz_approval (kind, case_id, reason, applicant, status, approver, decided_at, opinion, payload, applicant_dept_id)
                values (?,?,?,?, 'APPROVED', ?, now(), '负责人直接批准',?,
                        (select dept_id from sys_user where username=?))""",
                kind, caseId, reason == null ? "（直接操作）" : reason, operator, operator, toJson(snapshot), operator);
    }

    @Transactional
    public void recordClueDirect(Long clueId, String reason, String operator) {
        jdbc.update("""
                insert into biz_approval(kind, clue_id, reason, applicant, status, approver, decided_at, opinion, applicant_dept_id)
                values ('CLUE_EXTEND',?,?,?,'APPROVED',?,now(),'主要负责人对应岗位直接批准',
                        (select dept_id from sys_user where username=?))""",
                clueId, reason, operator, operator, operator);
    }

    /** applicant 非空时只看本人提交的（非负责人/管理员视角） */
    public List<Map<String, Object>> pending(String applicant) {
        return pending(applicant, applicant == null);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> pendingFor(String username) {
        return pending(username, materialAccess.readActor(username).privileged());
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> pendingFor(String username, Long focusId) {
        var actor = approvalReader(username, focusId);
        return pending(username, actor.privileged(), focusId);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> mine(String username, Long focusId) {
        approvalReader(username, focusId);
        return applications(username, false, focusId, true);
    }

    private WorkflowUserAccess.User approvalReader(String username, Long focusId) {
        if (focusId != null && focusId < 1) throw new BizException(2071, "待办来源编号须为正整数");
        var actor = materialAccess.readActor(username);
        if (actor.roles().contains("AGENCY") || actor.roles().contains("INTEGRATION")
                || actor.roles().stream().noneMatch(java.util.Set.of("HANDLER","LEGAL","LEADER","ADMIN")::contains))
            throw new BizException(403, "当前岗位无权查看审批");
        return actor;
    }

    private List<Map<String, Object>> pending(String applicant, boolean canDecide) {
        return pending(applicant, canDecide, null);
    }

    private List<Map<String, Object>> pending(String applicant, boolean canDecide, Long focusId) {
        return applications(applicant, canDecide, focusId, false);
    }

    private List<Map<String, Object>> applications(String applicant, boolean canDecide, Long focusId, boolean history) {
        String base = """
                select a.*, cf.case_no, cf.name as case_name, c.clue_no,
                    exists(select 1 from biz_approval replacement where replacement.previous_approval_id=a.id) as resubmitted
                from biz_approval a
                """ + FILING_SOURCE + """
                left join case_file cf on cf.id = a.case_id
                left join case_clue c on c.id = source_binding.clue_id and source_binding.verified
                where """ + (history ? " true" : " a.status = 'PENDING'");
        if (applicant == null) return jdbc.queryForList(base + " order by a.id");
        String focus = focusId == null ? "" : " and a.id=?";
        if (canDecide) {
            var scope = sourceAttachmentAccess.approvalDepartmentPredicate("a", applicant);
            var args = new java.util.ArrayList<Object>(scope.args());
            if (focusId != null) args.add(focusId);
            return jdbc.queryForList(base + " and " + scope.sql() + focus + " order by a.id", args.toArray());
        }
        var caseScope = sourceAttachmentAccess.caseReadPredicate("a.case_id", applicant);
        var clueScope = sourceAttachmentAccess.clueReadPredicate("source_binding.clue_id", applicant);
        var args = new java.util.ArrayList<Object>(); args.add(applicant);
        var departmentScope = sourceAttachmentAccess.approvalDepartmentPredicate("a", applicant);
        args.addAll(caseScope.args()); args.addAll(clueScope.args()); args.addAll(departmentScope.args());
        if (focusId != null) args.add(focusId);
        return jdbc.queryForList(base + " and a.applicant=? and (a.case_id is null or " + caseScope.sql()
                + ") and source_binding.verified and (source_binding.clue_id is null or " + clueScope.sql() + ") and "
                + departmentScope.sql() + focus + (history ? " order by a.id desc limit 100" : " order by a.id"), args.toArray());
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String, Object>> byCase(Long caseId) {
        materialAccess.requireCurrentCaseReader(caseId);
        return jdbc.queryForList("select * from biz_approval where case_id = ? order by id desc", caseId);
    }

    private Object execute(String kind, Map<String, Object> a, String approver) {
        Long caseId = a.get("case_id") == null ? null : ((Number) a.get("case_id")).longValue();
        Long clueId = a.get("clue_id") == null ? null : ((Number) a.get("clue_id")).longValue();
        requireBinding(kind, caseId, clueId);
        Map<String, Object> p = fromJson((String) a.get("payload"));
        String reason = (String) a.get("reason");
        return switch (kind) {
            case "CLUE_EXTEND" -> clueService.extend(((Number) a.get("clue_id")).longValue(), reason, approver);
            case "FILE_CASE" -> {
                Map<String, Object> filingPayload = new java.util.LinkedHashMap<>(p);
                Long resolvedClueId = filingClueId(clueId, p);
                if (resolvedClueId != null) filingPayload.put("clueId", resolvedClueId);
                CaseService.CaseCreateReq req;
                try { req = objectMapper.convertValue(filingPayload, CaseService.CaseCreateReq.class); }
                catch (IllegalArgumentException e) { throw new BizException(2071, "立案审批材料格式无效，请驳回后重新提交"); }
                yield caseService.create(req, (String) a.get("applicant"));
            }
            // getOrDefault 对"键存在但值为 null"返回 null（不是默认值）→ NPE → 500，
            // 而这张单据此后每次批准都 500，永远办不掉
            case "EXTEND" -> {
                Object days = p.get("days");
                if (!(days instanceof Number n))
                    throw new BizException(2071, "延期申请未填写延长天数，无法执行；请驳回后重新提交");
                int exactDays;
                try { exactDays = new java.math.BigDecimal(n.toString()).intValueExact(); }
                catch (ArithmeticException | NumberFormatException e) {
                    throw new BizException(2071, "延期天数须为有效整数，请驳回后重新提交");
                }
                yield caseService.extend(caseId, exactDays, reason);
            }
            case "SUSPEND" -> caseService.suspend(caseId, reason);
            case "TERMINATE" -> caseService.terminate(caseId, reason);
            case "DEFER" -> executionService.approveDefer(caseId);
            default -> throw new BizException(2071, "未知审批类型");
        };
    }

    private Long filingClueId(Long clueId, Map<String, Object> payload) {
        Object raw = payload.get("clueId");
        Long payloadClueId = null;
        if (raw != null) {
            if (!(raw instanceof Number number) || raw instanceof Double || raw instanceof Float)
                throw new BizException(2146, "立案申请的线索编号无效");
            try { payloadClueId = new java.math.BigDecimal(number.toString()).longValueExact(); }
            catch (ArithmeticException | NumberFormatException e) { throw new BizException(2146, "立案申请的线索编号无效"); }
            if (payloadClueId < 1) throw new BizException(2146, "立案申请的线索编号无效");
        }
        if (clueId != null && payloadClueId != null && !clueId.equals(payloadClueId))
            throw new BizException(2146, "立案申请关联线索与审批材料中的线索不一致");
        return clueId == null ? payloadClueId : clueId;
    }

    /** Pending application bindings describe their source, never an already-created filing result. */
    private static void requireBinding(String kind, Long caseId, Long clueId) {
        if ("FILE_CASE".equals(kind)) {
            if (caseId != null) throw new BizException(2071, "立案申请不得关联已有案件；请驳回后重新提交");
        } else if ("CLUE_EXTEND".equals(kind)) {
            if (clueId == null || caseId != null) throw new BizException(2071, "线索延期须仅关联线索");
        } else if (kind != null && KINDS.contains(kind)) {
            if (caseId == null || clueId != null) throw new BizException(2071, "案件审批须仅关联案件");
        } else {
            throw new BizException(2071, "未知审批类型");
        }
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
        if (s == null) return Map.of();
        Object value;
        try {
            value = objectMapper.readerFor(Object.class)
                    .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(s);
        }
        catch (Exception e) { throw new BizException(2072, "审批单参数解析失败"); }
        if (!(value instanceof Map<?, ?>)) throw new BizException(2071, "审批单参数须为对象，请驳回后重新提交");
        return (Map<String, Object>) value;
    }
}
