package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.common.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AgencyAgreementService {
    private static final String RECEIVER_SCOPE = """
             and exists (select 1 from sys_dept d where d.id = a.agency_dept_id
                         and d.org_kind = 'AGENCY' and d.enabled = true)
             and exists (select 1 from case_file c where c.id = a.case_id and c.party_type = 'PROVIDER')
            """;

    private final JdbcTemplate jdbc;
    private final MaterialAccess materialAccess;
    private final AgencyInterviewService interviews;

    public record Department(long id, String name) {}
    public record Handoff(long id, String caseNo, String institutionName, String action,
                          LocalDate sentAt, String note, String status, int cycle, LocalDate dueAt,
                          LocalDate receivedAt, LocalDate returnedAt, String returnReason, LocalDate remindedAt,
                          LocalDate repliedAt, String result, String resultType, BigDecimal recoveredAmount,
                          LocalDate effectiveAt, LocalDate endAt, String reference, Long interviewId,
                          LocalDate autoRemindedAt) {}
    public record Reply(Integer cycle, String result, String resultType, BigDecimal recoveredAmount,
                        LocalDate effectiveAt, LocalDate endAt, String reference,
                        AgencyInterviewService.ActualInterview interview) {}
    public record Reassignment(Integer cycle, Long agencyDeptId, LocalDate dueAt, String reason) {}
    public record Event(int cycle, String type, String department, String actor, String detail,
                        LocalDate dueAt, OffsetDateTime createdAt) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Department> departments(String username) {
        var actor = materialAccess.readActor(username);
        if (actor.roles().stream().noneMatch(Set.of("HANDLER", "LEADER", "ADMIN")::contains))
            throw new BizException(403, "当前岗位无权移交协议处理");
        return jdbc.query("""
                select id, name from sys_dept where org_kind = 'AGENCY' and enabled = true order by sort_no, id
                """, (rs, row) -> new Department(rs.getLong("id"), rs.getString("name")));
    }

    /** The caller has already locked the case and authorized an administrative writer. */
    public void dispatch(long caseId, String action, long deptId, LocalDate sentAt, LocalDate dueAt,
                         String note, String username) {
        String department = validateDestination(caseId, deptId);
        requireDueDate(dueAt, sentAt);
        if (note != null && (note.length() > 255 || note.indexOf(0) >= 0))
            throw new BizException(2073, "移交说明长度不得超过255字");
        long id = jdbc.queryForObject("""
                insert into agreement_action (case_id, action, org, sent_at, note, agency_dept_id,
                    agency_status, agency_cycle, agency_due_at)
                values (?, ?, ?, ?, ?, ?, 'PENDING', 1, ?) returning id
                """, Long.class, caseId, action, department, sentAt, note, deptId, dueAt);
        recordEvent(id, "DISPATCHED", username, null);
    }

    private String validateDestination(long caseId, long deptId) {
        var names = jdbc.queryForList("""
                select d.name from sys_dept d
                where d.id = ? and d.org_kind = 'AGENCY' and d.enabled = true
                  and exists (select 1 from case_file c join case_decision decision on decision.case_id = c.id
                              where c.id = ? and c.party_type = 'PROVIDER')
                """, String.class, deptId, caseId);
        if (names.isEmpty())
            throw new BizException(2073, "请选择已启用的经办部门，并确认定点医药机构案件已有处理决定");
        return names.getFirst();
    }

    /** Case lock and scope are held by the administrative controller. */
    public void reassign(long id, Reassignment request, String username) {
        int cycle = requireCycle(request.cycle());
        Long dept = request.agencyDeptId();
        if (dept == null || dept <= 0) throw new BizException(2073, "请选择经办部门");
        requireDueDate(request.dueAt(), LocalDate.now());
        String reason = requiredText(request.reason(), 1000, "重新分派原因");
        long caseId = jdbc.queryForObject("select case_id from agreement_action where id = ?", Long.class, id);
        String department = validateDestination(caseId, dept);
        int changed = jdbc.update("""
                update agreement_action set agency_dept_id = ?, org = ?, agency_status = 'PENDING',
                    agency_cycle = agency_cycle + 1, agency_due_at = ?, agency_received_at = null,
                    agency_received_by = null, agency_returned_at = null, agency_return_reason = null,
                    agency_reminded_at = null, agency_auto_reminded_at = null
                where id = ? and agency_cycle = ? and agency_status in ('PENDING', 'RETURNED')
                """, dept, department, request.dueAt(), id, cycle);
        if (changed == 0) throw new BizException(2073, "仅待接收或已退回事项可重新分派，请刷新后重试");
        recordEvent(id, "REASSIGNED", username, reason);
    }

    /** A reminder is visible in the receiving department inbox, at most once per dispatch per day. */
    public void remind(long id, Integer dispatchCycle, String username) {
        LocalDate today = LocalDate.now();
        int changed = jdbc.update("""
                update agreement_action set agency_reminded_at = ?
                where id = ? and agency_cycle = ? and agency_status in ('PENDING', 'ACCEPTED')
                  and (agency_reminded_at is null or agency_reminded_at < ?)
                """, today, id, requireCycle(dispatchCycle), today);
        if (changed == 0) throw new BizException(2073, "事项已变更、非待办状态或今日已催办");
        recordEvent(id, "REMINDED", username, null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Event> history(long id, String username) {
        var cases = jdbc.queryForList("select case_id from agreement_action where id = ?", Long.class, id);
        if (cases.isEmpty()) throw new BizException(2073, "台账不存在");
        materialAccess.requireCaseReader(cases.getFirst(), username);
        return jdbc.query("""
                select agency_cycle, event_type, department_name, actor_username, detail, due_at, created_at
                from agency_agreement_event where agreement_id = ? order by id
                """, (rs, row) -> new Event(rs.getInt("agency_cycle"), rs.getString("event_type"),
                rs.getString("department_name"), rs.getString("actor_username"), rs.getString("detail"),
                rs.getObject("due_at", LocalDate.class), rs.getObject("created_at", OffsetDateTime.class)), id);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Handoff> inbox(String username) {
        var actor = materialAccess.readActor(username);
        long deptId = requireAgencyDepartment(actor);
        return jdbc.query("""
                select a.id, c.case_no, c.party_name, a.action, a.sent_at, a.note, a.replied_at, a.result,
                       a.agency_status, a.agency_cycle, a.agency_due_at, a.agency_received_at,
                       a.agency_returned_at, a.agency_return_reason, a.agency_reminded_at, a.agency_result_type,
                       a.agency_recovered_amount, a.agency_result_effective_at, a.agency_result_end_at,
                       a.agency_result_reference, a.agency_auto_reminded_at,
                       (select i.id from department_interview i where i.source_agreement_id=a.id) as interview_id
                from agreement_action a join case_file c on c.id = a.case_id
                where a.agency_dept_id = ? and c.party_type = 'PROVIDER'
                order by (a.agency_status not in ('PENDING', 'ACCEPTED')), a.agency_due_at nulls last,
                         a.sent_at desc, a.id desc
                """, (rs, row) -> new Handoff(rs.getLong("id"), rs.getString("case_no"),
                rs.getString("party_name"), rs.getString("action"), rs.getObject("sent_at", LocalDate.class),
                rs.getString("note"), rs.getString("agency_status"), rs.getInt("agency_cycle"),
                rs.getObject("agency_due_at", LocalDate.class), rs.getObject("agency_received_at", LocalDate.class),
                rs.getObject("agency_returned_at", LocalDate.class), rs.getString("agency_return_reason"),
                rs.getObject("agency_reminded_at", LocalDate.class), rs.getObject("replied_at", LocalDate.class),
                rs.getString("result"), rs.getString("agency_result_type"), rs.getBigDecimal("agency_recovered_amount"),
                rs.getObject("agency_result_effective_at", LocalDate.class), rs.getObject("agency_result_end_at", LocalDate.class),
                rs.getString("agency_result_reference"), rs.getObject("interview_id", Long.class),
                rs.getObject("agency_auto_reminded_at", LocalDate.class)), deptId);
    }

    @Transactional
    public void receive(long id, Integer dispatchCycle, String username) {
        var actor = materialAccess.lockActor(username, false);
        long deptId = requireAgencyDepartment(actor);
        int changed = jdbc.update("""
                update agreement_action a set agency_status = 'ACCEPTED', agency_received_at = ?, agency_received_by = ?
                where a.id = ? and a.agency_dept_id = ? and a.agency_cycle = ? and a.agency_status = 'PENDING'
                """ + RECEIVER_SCOPE, LocalDate.now(), actor.id(), id, deptId, requireCycle(dispatchCycle));
        checkChanged(changed);
        recordEvent(id, "ACCEPTED", username, null);
    }

    @Transactional
    public void returnToSender(long id, Integer dispatchCycle, String input, String username) {
        var actor = materialAccess.lockActor(username, false);
        long deptId = requireAgencyDepartment(actor);
        String reason = requiredText(input, 1000, "退回原因");
        int changed = jdbc.update("""
                update agreement_action a set agency_status = 'RETURNED', agency_returned_at = ?, agency_return_reason = ?
                where a.id = ? and a.agency_dept_id = ? and a.agency_cycle = ? and a.agency_status in ('PENDING', 'ACCEPTED')
                """ + RECEIVER_SCOPE, LocalDate.now(), reason, id, deptId, requireCycle(dispatchCycle));
        checkChanged(changed);
        recordEvent(id, "RETURNED", username, reason);
    }

    @Transactional
    public void reply(long id, Reply reply, String username) {
        var actor = materialAccess.lockActor(username, false);
        long deptId = requireAgencyDepartment(actor);
        int cycle = requireCycle(reply.cycle());
        var actions = jdbc.queryForList("""
                select action from agreement_action where id = ? and agency_dept_id = ? and agency_cycle = ?
                    and agency_status = 'ACCEPTED' for update
                """, String.class, id, deptId, cycle);
        checkChanged(actions.size());
        String result = requiredText(reply.result(), 4000, "办理结果");
        if (reply.interview() != null && !("INTERVIEW".equals(actions.getFirst())
                && "COMPLETED".equals(reply.resultType())))
            throw new BizException(2192, "实际约谈资料仅用于已落实的约谈事项");
        BigDecimal amount = validateResult(actions.getFirst(), reply);
        String reference = reply.reference() == null || reply.reference().isBlank()
                ? null : requiredText(reply.reference(), 128, "回执文号");
        int changed = jdbc.update("""
                update agreement_action a set agency_status = 'COMPLETED', replied_at = ?, result = ?,
                    agency_replied_by = ?, agency_result_type = ?, agency_recovered_amount = ?,
                    agency_result_effective_at = ?, agency_result_end_at = ?, agency_result_reference = ?
                where a.id = ? and a.agency_dept_id = ? and a.agency_cycle = ? and a.agency_status = 'ACCEPTED'
                """ + RECEIVER_SCOPE, LocalDate.now(), result, actor.id(), reply.resultType(), amount, reply.effectiveAt(),
                reply.endAt(), reference, id, deptId, cycle);
        checkChanged(changed);
        recordEvent(id, "COMPLETED", username, null);
        if (reply.interview() != null) interviews.registerLocked(id, cycle, reply.interview(), actor);
    }

    private static BigDecimal validateResult(String action, Reply reply) {
        boolean applied = "COMPLETED".equals(reply.resultType());
        if (!applied && !"NOT_APPLICABLE".equals(reply.resultType()))
            throw new BizException(2073, "请选择已落实或未落实");
        boolean recover = applied && "RECOVER".equals(action);
        boolean suspension = applied && "SUSPEND_AGREEMENT".equals(action);
        boolean hasEffectiveDate = suspension || applied && "TERMINATE_AGREEMENT".equals(action);
        if (hasEffectiveDate && (reply.effectiveAt() == null || reply.effectiveAt().getYear() < 1900
                || reply.effectiveAt().isAfter(LocalDate.now())))
            throw new BizException(2073, "请填写已生效的协议处理日期");
        if (suspension && (reply.endAt() == null || reply.endAt().isBefore(reply.effectiveAt())
                || reply.endAt().getYear() > 9999))
            throw new BizException(2073, "暂停截止日期不得早于暂停生效日期");
        if ((!recover && reply.recoveredAmount() != null) || (!hasEffectiveDate && reply.effectiveAt() != null)
                || (!suspension && reply.endAt() != null))
            throw new BizException(2073, "结构化结果须与办理结果和处理类型一致");
        return recover ? Money.positive(reply.recoveredAmount(), "实际追回金额") : null;
    }

    private void recordEvent(long id, String type, String username, String detail) {
        jdbc.update("""
                insert into agency_agreement_event (agreement_id, agency_cycle, event_type, agency_dept_id,
                    department_name, actor_username, detail, due_at)
                select id, agency_cycle, ?, agency_dept_id, org, ?, ?, agency_due_at
                from agreement_action where id = ?
                """, type, username, detail, id);
    }

    private static void checkChanged(int count) {
        if (count == 0) throw new BizException(2073, "事项不在本部门办理范围内或状态已变更，请刷新");
    }

    private static int requireCycle(Integer cycle) {
        if (cycle == null || cycle <= 0) throw new BizException(2073, "请刷新事项后再办理");
        return cycle;
    }

    private static void requireDueDate(LocalDate dueAt, LocalDate earliest) {
        if (dueAt == null || dueAt.isBefore(earliest) || dueAt.getYear() > 9999)
            throw new BizException(2073, "请选择不早于分派日期的办理期限");
    }

    private static String requiredText(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf(0) >= 0)
            throw new BizException(2073, "请填写" + label + "，长度不超过" + max + "字");
        return value.strip();
    }

    private long requireAgencyDepartment(WorkflowUserAccess.User actor) {
        if (!actor.roles().equals(Set.of("AGENCY")) || actor.deptId() == null)
            throw new BizException(403, "须使用已归类的经办协议处理账号");
        boolean enabled = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from sys_user u join sys_dept d on d.id = u.dept_id
                               where u.id = ? and d.id = ? and u.account_category = 'AGENCY'
                                 and d.org_kind = 'AGENCY' and d.enabled = true)
                """, Boolean.class, actor.id(), actor.deptId()));
        if (!enabled) throw new BizException(403, "账号须归属已启用的经办部门");
        return actor.deptId();
    }
}
