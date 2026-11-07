package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.common.Money;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReportingSupplementService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MaterialAccess materials;
    private final WorkflowUserAccess workflowUsers;

    private enum Field {
        INSTITUTION_CODE("institutionCode", "institution_code", 64),
        INSTITUTION_OWNERSHIP("institutionOwnership", "institution_ownership", 16),
        INSTITUTION_GRADE("institutionGrade", "institution_grade", 16),
        PARTY_CREDIT_NO("partyCreditNo", "party_credit_no", 64),
        VIOLATION_START("confirmedViolationStart", "confirmed_violation_start", 0),
        VIOLATION_END("confirmedViolationEnd", "confirmed_violation_end", 0),
        VIOLATION_FACTS("confirmedViolationFacts", "confirmed_violation_facts", 12000),
        VIOLATION_AMOUNT("confirmedViolationAmount", "confirmed_violation_amount", 0),
        PENALTY_BASIS("penaltyBasis", "penalty_basis", 4000);

        final String key;
        final String column;
        final int maxLength;
        Field(String key, String column, int maxLength) {
            this.key = key;
            this.column = column;
            this.maxLength = maxLength;
        }
        boolean applies(String partyType) {
            if (this == PARTY_CREDIT_NO) return "INDIVIDUAL".equals(partyType);
            if (name().startsWith("INSTITUTION_")) return "PROVIDER".equals(partyType);
            return true;
        }
        static Field of(String key) {
            return Arrays.stream(values()).filter(field -> field.key.equals(key)).findFirst()
                    .orElseThrow(() -> invalid("包含不允许补录的字段"));
        }
    }

    private record Source(String status, String partyType, String decisionType, LocalDate decidedAt,
                          String sourceSnapshot, Map<String, Object> original, Map<String, Object> effective) {
        boolean eligible() {
            return List.of("DECIDED", "DELIVERED", "CLOSED").contains(status)
                    && "PUNISH".equals(decisionType) && Set.of("PROVIDER", "INDIVIDUAL").contains(partyType);
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> state(long caseId, String username) {
        var actor = materials.requireCaseReader(caseId, username);
        requireBusinessRole(actor);
        Source source = source(caseId);
        var history = jdbc.queryForList("""
                select r.*,c.case_no,c.party_name from case_reporting_supplement_request r
                join case_file c on c.id=r.case_id where r.case_id=? order by r.id desc
                """, caseId).stream().map(row -> history(row, actor)).toList();
        var missing = missing(source);
        boolean pending = history.stream().anyMatch(row -> "PENDING".equals(row.get("status")));
        var result = new LinkedHashMap<String, Object>();
        result.put("eligible", source.eligible());
        result.put("canRequest", source.eligible() && writer(actor) && !missing.isEmpty() && !pending);
        result.put("canReview", reviewer(actor));
        result.put("missingFields", missing);
        result.put("original", source.original());
        result.put("effective", source.effective());
        result.put("history", history);
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> pending(String username, int page, int size) {
        var actor = materials.readActor(username);
        requireReviewer(actor);
        if (page < 1 || size < 1 || size > 100) throw invalid("页码须大于零，每页须为1至100条");
        Long total = jdbc.queryForObject(
                "select count(*) from case_reporting_supplement_request where status='PENDING'", Long.class);
        var rows = jdbc.queryForList("""
                select r.*,c.case_no,c.party_name from case_reporting_supplement_request r
                join case_file c on c.id=r.case_id where r.status='PENDING'
                order by r.id desc limit ? offset ?
                """, size, (page - 1L) * size).stream().map(row -> history(row, actor)).toList();
        return Map.of("rows", rows, "total", total, "page", page, "size", size);
    }

    @Transactional
    public long request(long caseId, Map<String, Object> body, String username) {
        requireKeys(body, Set.of("reason", "sourceReference", "values"));
        String reason = text(body.get("reason"), 512, "补录原因");
        String sourceReference = text(body.get("sourceReference"), 1000, "来源依据");
        CaseGuards.lock(jdbc, caseId);
        var actor = materials.requireCaseWriter(caseId, username);
        Source source = source(caseId);
        if (!source.eligible()) throw invalid("仅已决定、已送达或已结案的处罚案件可以申请报表补录");
        Map<String, Object> values = proposed(body.get("values"), source);
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from case_reporting_supplement_request where case_id=? and status='PENDING')
                """, Boolean.class, caseId)))
            throw invalid("本案已有待审核的报表补录申请");
        return jdbc.queryForObject("""
                insert into case_reporting_supplement_request(case_id,requested_values,original_values,effective_values,
                    source_snapshot,reason,source_reference,requested_by,requested_username,requested_name,
                    requester_dept_id,requester_category)
                values(?,?::jsonb,?::jsonb,?::jsonb,?::jsonb,?,?,?,?,?,?,?) returning id
                """, Long.class, caseId, json(values), json(source.original()), json(source.effective()),
                source.sourceSnapshot(), reason, sourceReference, actor.id(), actor.username(), realName(actor.id()),
                actor.deptId(), actor.accountCategory());
    }

    @Transactional
    public void review(long caseId, long requestId, Map<String, Object> body, String username) {
        requireKeys(body, Set.of("approve", "opinion"));
        if (!(body.get("approve") instanceof Boolean approve)) throw invalid("请选择批准或驳回");
        String opinion = text(body.get("opinion"), 2000, "审核意见");
        CaseGuards.lock(jdbc, caseId);
        Map<String, Object> request = lockedRequest(caseId, requestId);
        // Department -> jointly ordered users matches institution administration's lock order.
        Long observedDepartment = reviewerDepartment(username);
        if (observedDepartment != null)
            jdbc.queryForList("select id from sys_dept where id=? for share", observedDepartment);
        var actors = workflowUsers.lockUsers(username, (String) request.get("requested_username"));
        var actor = materials.lockActor(username, false);
        if (!Objects.equals(actor.deptId(), observedDepartment)) throw invalid("审核人机构已变化，请刷新后重试");
        requireReviewer(actor);
        materials.assertCaseScope(caseId, actor);
        if (actor.id().equals(number(request.get("requested_by")))) throw forbidden("申请人不能审核本人的补录申请");
        requirePending(request);
        Map<String, Object> approved = null;
        if (approve) {
            Source current = source(caseId);
            var applicant = workflowUsers.requireFiling(actors.get(request.get("requested_username")));
            materials.assertCaseScope(caseId, applicant);
            if (!Objects.equals(applicant.deptId(), number(request.get("requester_dept_id")))
                    || !Objects.equals(applicant.accountCategory(), request.get("requester_category"))
                    || !current.eligible()
                    || !sameJson(current.sourceSnapshot(), request.get("source_snapshot"))
                    || !sameJson(json(current.effective()), request.get("effective_values")))
                throw invalid("来源资料或申请人归属已变化，请驳回后重新申请");
            Map<String, Object> values = proposed(object(request.get("requested_values")), current);
            approved = new LinkedHashMap<>(current.effective());
            approved.putAll(values);
            finish(requestId, "APPROVED", actor, opinion, approved);
            saveValues(caseId, requestId, values);
        } else {
            finish(requestId, "REJECTED", actor, opinion, null);
        }
    }

    @Transactional
    public void cancel(long caseId, long requestId, Map<String, Object> body, String username) {
        requireKeys(body, Set.of("opinion"));
        String opinion = text(body.get("opinion"), 2000, "撤回原因");
        CaseGuards.lock(jdbc, caseId);
        Map<String, Object> request = lockedRequest(caseId, requestId);
        var actor = materials.lockActor(username, false);
        materials.assertCaseScope(caseId, actor);
        requireBusinessRole(actor);
        if (!actor.id().equals(number(request.get("requested_by"))) && !actor.privileged())
            throw forbidden("仅申请人或本案负责人可以撤回");
        requirePending(request);
        finish(requestId, "CANCELLED", actor, opinion, null);
    }

    private Map<String, Object> proposed(Object raw, Source source) {
        if (!(raw instanceof Map<?, ?> input) || input.isEmpty()) throw invalid("请至少填写一项缺失字段");
        var values = new LinkedHashMap<String, Object>();
        for (var entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw invalid("补录字段无效");
            Field field = Field.of(key);
            if (!field.applies(source.partyType()) || !absent(source.effective().get(key)))
                throw invalid("只能补录本案尚缺失的适用字段");
            Object value = entry.getValue();
            switch (field) {
                case VIOLATION_START, VIOLATION_END -> {
                    String day = text(value, 10, field.key);
                    try {
                        if (!day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
                        LocalDate parsed = LocalDate.parse(day);
                        if (parsed.getYear() < 1) throw new IllegalArgumentException();
                        values.put(key, parsed);
                    } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
                        throw invalid("违法发生日期须为有效的YYYY-MM-DD");
                    }
                }
                case VIOLATION_AMOUNT -> {
                    if (!(value instanceof Number amount)) throw invalid("认定金额须为数字");
                    values.put(key, Money.nonNegative(new BigDecimal(amount.toString()), "认定金额"));
                }
                default -> values.put(key, text(value, field.maxLength, field.key));
            }
        }
        if (values.containsKey("institutionOwnership")
                && !Set.of("PUBLIC", "SOCIAL").contains(values.get("institutionOwnership")))
            throw invalid("机构性质须为PUBLIC或SOCIAL");
        if (values.containsKey("institutionGrade")
                && !Set.of("TERTIARY", "SECONDARY", "PRIMARY", "UNGRADED").contains(values.get("institutionGrade")))
            throw invalid("机构等级无效");
        if (values.containsKey("confirmedViolationStart") || values.containsKey("confirmedViolationEnd")) {
            var effective = new LinkedHashMap<>(source.effective());
            effective.putAll(values);
            Object start = effective.get("confirmedViolationStart"), end = effective.get("confirmedViolationEnd");
            if (start == null || end == null) throw invalid("请同时补齐违法发生起止日期");
            LocalDate from = LocalDate.parse(start.toString()), to = LocalDate.parse(end.toString());
            if (from.isAfter(to) || to.isAfter(source.decidedAt()))
                throw invalid("违法发生起止日期须有序且不得晚于决定日期");
        }
        return values;
    }

    private Source source(long caseId) {
        String original = Arrays.stream(Field.values()).map(field -> "'" + field.key + "',"
                + (field == Field.PENALTY_BASIS ? "d." : "c.") + field.column).collect(Collectors.joining(","));
        String effective = Arrays.stream(Field.values()).map(field -> "'" + field.key + "',rf."
                + field.column).collect(Collectors.joining(","));
        var rows = jdbc.queryForList("""
                select c.status,c.party_type,d.decision_type,d.decided_at,
                       jsonb_build_object('caseFile',to_jsonb(c),'decision',to_jsonb(d))::text as source_snapshot,
                       jsonb_build_object(%s)::text as original_values,
                       jsonb_build_object(%s)::text as effective_values
                from case_file c left join case_decision d on d.case_id=c.id
                join case_reporting_facts rf on rf.case_id=c.id where c.id=?
                """.formatted(original, effective), caseId);
        if (rows.isEmpty()) throw forbidden("案件不存在或不在当前范围内");
        var row = rows.getFirst();
        return new Source((String) row.get("status"), (String) row.get("party_type"), (String) row.get("decision_type"),
                row.get("decided_at") instanceof java.sql.Date day ? day.toLocalDate() : null,
                (String) row.get("source_snapshot"), object(row.get("original_values")), object(row.get("effective_values")));
    }

    private Map<String, Object> history(Map<String, Object> row, WorkflowUserAccess.User actor) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", row.get("id"));
        value.put("caseId", row.get("case_id"));
        value.put("caseNo", row.get("case_no"));
        value.put("partyName", row.get("party_name"));
        value.put("status", row.get("status"));
        value.put("reason", row.get("reason"));
        value.put("sourceReference", row.get("source_reference"));
        value.put("values", object(row.get("requested_values")));
        value.put("originalSnapshot", object(row.get("original_values")));
        value.put("effectiveSnapshot", object(row.get("effective_values")));
        value.put("approvedSnapshot", row.get("approved_values") == null ? null : object(row.get("approved_values")));
        value.put("requestedBy", row.get("requested_username"));
        value.put("requestedName", row.get("requested_name"));
        value.put("requestedAt", row.get("requested_at"));
        value.put("reviewedBy", row.get("reviewed_username"));
        value.put("reviewedName", row.get("reviewed_name"));
        value.put("reviewedAt", row.get("reviewed_at"));
        value.put("opinion", row.get("opinion"));
        boolean pending = "PENDING".equals(row.get("status"));
        boolean own = actor.id().equals(number(row.get("requested_by")));
        value.put("canReview", pending && !own && reviewer(actor));
        value.put("canCancel", pending && (own || actor.privileged()));
        return value;
    }

    private void saveValues(long caseId, long requestId, Map<String, Object> values) {
        String columns = Arrays.stream(Field.values()).map(field -> field.column).collect(Collectors.joining(","));
        String updates = Arrays.stream(Field.values()).map(field -> field.column
                + "=coalesce(case_reporting_supplement_values." + field.column + ",excluded." + field.column + ")")
                .collect(Collectors.joining(","));
        var args = new ArrayList<Object>();
        args.add(caseId);
        for (Field field : Field.values()) args.add(values.get(field.key));
        args.add(requestId);
        jdbc.update("insert into case_reporting_supplement_values(case_id," + columns
                + ",approved_request_id) values(?,?,?,?,?,?,?,?,?,?,?) on conflict(case_id) do update set "
                + updates + ",approved_request_id=excluded.approved_request_id", args.toArray());
    }

    private void finish(long id, String status, WorkflowUserAccess.User actor, String opinion, Map<String, Object> approved) {
        jdbc.update("""
                update case_reporting_supplement_request set status=?,reviewed_by=?,reviewed_username=?,reviewed_name=?,
                    reviewer_dept_id=?,reviewed_at=now(),opinion=?,approved_values=?::jsonb where id=?
                """, status, actor.id(), actor.username(), realName(actor.id()), actor.deptId(),
                opinion, approved == null ? null : json(approved), id);
    }

    private Map<String, Object> lockedRequest(long caseId, long requestId) {
        var rows = jdbc.queryForList("""
                select * from case_reporting_supplement_request where id=? and case_id=? for update
                """, requestId, caseId);
        if (rows.isEmpty()) throw forbidden("补录申请不存在或不在当前案件内");
        return rows.getFirst();
    }

    private List<String> missing(Source source) {
        return Arrays.stream(Field.values()).filter(field -> field.applies(source.partyType())
                && absent(source.effective().get(field.key))).map(field -> field.key).toList();
    }

    private boolean reviewer(WorkflowUserAccess.User actor) {
        return "BUREAU".equals(actor.accountCategory()) && actor.deptId() != null
                && (actor.roles().contains("LEGAL") || actor.roles().contains("LEADER"))
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from sys_dept where id=? and enabled and org_kind='BUREAU')
                    """, Boolean.class, actor.deptId()));
    }

    private void requireReviewer(WorkflowUserAccess.User actor) {
        if (!reviewer(actor)) throw forbidden("报表补录须由另一名局机关法制或负责人岗位审核");
    }

    private Long reviewerDepartment(String username) {
        var rows = jdbc.queryForList("select dept_id from sys_user where username=?", username);
        return rows.isEmpty() ? null : number(rows.getFirst().get("dept_id"));
    }

    private String realName(long actorId) {
        return jdbc.queryForObject("select real_name from sys_user where id=?", String.class, actorId);
    }

    private static boolean writer(WorkflowUserAccess.User actor) {
        return actor.roles().stream().anyMatch(Set.of("HANDLER", "LEADER", "ADMIN")::contains);
    }

    private static void requireBusinessRole(WorkflowUserAccess.User actor) {
        if (actor.roles().stream().noneMatch(Set.of("HANDLER", "LEADER", "LEGAL", "ADMIN")::contains))
            throw forbidden("当前岗位无权查看或处理报表补录");
    }

    private static boolean absent(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private static Long number(Object value) { return value instanceof Number n ? n.longValue() : null; }

    private static void requirePending(Map<String, Object> request) {
        if (!"PENDING".equals(request.get("status"))) throw invalid("补录申请已处理");
    }

    private static void requireKeys(Map<String, Object> body, Set<String> allowed) {
        if (body == null || body.keySet().stream().anyMatch(key -> !allowed.contains(key)))
            throw invalid("补录请求字段无效");
    }

    private static String text(Object value, int length, String label) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > length || text.indexOf('\0') >= 0)
            throw invalid(label + "须为不超过" + length + "字的非空文本");
        return text.strip();
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid("补录数据格式无效"); }
    }

    private Map<String, Object> object(Object value) {
        try { return mapper.readValue(value.toString(), new TypeReference<LinkedHashMap<String, Object>>() {}); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid("补录记录格式无效"); }
    }

    private boolean sameJson(String current, Object stored) {
        try { return mapper.readTree(current).equals(mapper.readTree(stored.toString())); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid("补录快照格式无效"); }
    }

    private static BizException invalid(String message) { return new BizException(2191, message); }
    private static BizException forbidden(String message) { return new BizException(403, message); }
}
