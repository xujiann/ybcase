package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A live, scoped view of source work. Completing the source removes its task without a second task state. */
@Service
@RequiredArgsConstructor
public class NextStepTaskService {
    private final JdbcTemplate jdbc;
    private final MaterialAccess materials;
    private final SourceAttachmentAccess sources;

    public record TaskRow(String taskKey, String sourceType, long sourceId, Integer cycle,
                          String sourceNo, String title, String stage, LocalDate dueAt,
                          Integer overdueDays, String actionLabel, String link) {}
    public record TaskPage(List<TaskRow> rows, long total, int page, int size, LocalDate asOf) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskPage bureau(String username, int page, int size) {
        var actor = materials.readActor(username);
        if (actor.roles().contains("AGENCY")
                || actor.roles().stream().noneMatch(Set.of("HANDLER", "LEGAL", "LEADER", "ADMIN")::contains))
            throw new BizException(403, "当前岗位无权查看行政办案待办");
        var caseScope = sources.caseReadPredicate("c.id", username);
        var arguments = new ArrayList<Object>(caseScope.args());
        var parts = new ArrayList<String>();
        boolean writer = actor.roles().stream().anyMatch(Set.of("HANDLER", "LEADER", "ADMIN")::contains);
        if (writer) {
            String assignment = actor.privileged() ? "true" : """
                    (c.owner_user=? or exists (select 1 from case_officer o left join enforcer e on e.cert_no=o.cert_no
                      where o.case_id=c.id and coalesce(o.user_id,e.user_id)=? and not o.avoided))
                    """;
            if (!actor.privileged()) { arguments.add(username); arguments.add(actor.id()); }
            parts.add("""
                    select 'CASE:' || c.id || ':' || c.status as task_key, 'CASE' as source_type,
                           c.id as source_id, null::int as cycle, c.case_no as source_no,
                           case c.status
                            when 'INVESTIGATING' then case when c.procedure_type='SUMMARY'
                              then '准备简易程序处理决定' else '完成调查并提交调查终结报告' end
                            when 'REPORTED' then '核对法制审核及处罚告知事项'
                            when 'NOTIFIED' then '核对陈述申辩及处理决定条件'
                            when 'DECIDED' then '登记处理决定送达'
                            when 'DELIVERED' then '核对执行记录及结案条件'
                            when 'SUSPENDED' then '核实中止事由并按程序办理'
                            when 'TERMINATED' then '办理终止调查或移送案件归档' end as title,
                           case c.status when 'INVESTIGATING' then '调查中' when 'REPORTED' then '调查已终结'
                            when 'NOTIFIED' then '已告知' when 'DECIDED' then '已决定'
                            when 'DELIVERED' then '已送达' when 'SUSPENDED' then '中止调查'
                            when 'TERMINATED' then '待归档' end as stage,
                           case when c.status in ('INVESTIGATING','REPORTED','NOTIFIED') then
                             c.deadline_at + coalesce((select sum(coalesce(e.end_at,current_date)-e.start_at)::int
                                from case_period_exclusion e where e.case_id=c.id),0) end as due_at,
                           '/case/detail/' || c.id as link
                    from visible_cases c where c.archive_no is null
                      and c.status in ('INVESTIGATING','REPORTED','NOTIFIED','DECIDED','DELIVERED','SUSPENDED','TERMINATED')
                      and
                    """ + assignment);

            var clueScope = sources.clueReadPredicate("cl.id", username);
            arguments.addAll(clueScope.args());
            parts.add("""
                    select 'CLUE:' || cl.id as task_key, 'CLUE' as source_type, cl.id as source_id,
                           null::int as cycle, cl.clue_no as source_no, '核查线索并决定后续处理' as title,
                           '待核查' as stage, cl.deadline_at as due_at,
                           '/case/clues?focus=' || cl.id as link
                    from case_clue cl where cl.status='PENDING' and
                    """ + clueScope.sql());

            parts.add("""
                    select 'AGENCY_RETURN:' || a.id || ':' || a.agency_cycle, 'AGREEMENT', a.id,
                           a.agency_cycle, c.case_no, '经办事项已退回，请核对后重新分派', '经办已退回',
                           null::date, '/case/detail/' || c.id
                    from agreement_action a join visible_cases c on c.id=a.case_id
                    where a.agency_status='RETURNED'
                    """);
        }
        if (actor.privileged()) {
            var approvalScope = sources.approvalDepartmentPredicate("a", username);
            arguments.addAll(approvalScope.args());
            parts.add("""
                    select 'APPROVAL:' || a.id, 'APPROVAL', a.id, null::int,
                           coalesce(c.case_no, cl.clue_no, '#' || a.id),
                           '审批待裁决', '待审批', null::date, '/case/approvals?focus=' || a.id
                    from biz_approval a left join case_file c on c.id=a.case_id
                    left join case_clue cl on cl.id=a.clue_id
                    where a.status='PENDING' and
                    """ + approvalScope.sql());
        }
        if (actor.roles().contains("LEGAL") || actor.roles().contains("ADMIN")) {
            arguments.add(actor.id());
            arguments.add(actor.id());
            parts.add("""
                    select 'REVIEW:' || r.id, 'REVIEW', r.id, null::int, c.case_no,
                           '核对送审材料与审核资格并办理法制审核', '待法制审核', r.deadline_at,
                           '/case/detail/' || c.id
                    from case_review r join visible_cases c on c.id=r.case_id
                    where r.reviewed_at is null and c.archive_no is null
                      and c.status in ('REPORTED','NOTIFIED')
                      and not exists(select 1 from case_review later where later.case_id=r.case_id and later.id>r.id)
                      and not exists(select 1 from case_officer o left join enforcer e on e.cert_no=o.cert_no
                                     where o.case_id=c.id and (o.user_id=? or e.user_id=?))
                    """);
        }
        if (actor.roles().contains("LEADER") && actor.accountCategory() != null && actor.deptId() != null) {
            arguments.add(actor.deptId());
            parts.add("""
                    select 'HANDOFF:' || h.id, 'HANDOFF', h.id, null::int, h.source_no,
                           '核对机构交接申请并接收或退回', '待接收', null::date,
                           '/case/department-handoffs?focus=' || h.id
                    from department_handoff h where h.status='PENDING' and h.to_dept_id=?
                    """);
        }
        if ("BUREAU".equals(actor.accountCategory()) && actor.deptId() != null
                && (actor.roles().contains("LEGAL") || actor.roles().contains("LEADER"))) {
            arguments.add(actor.id());
            arguments.add(actor.deptId());
            parts.add("""
                    select 'REPORT_SUPPLEMENT:' || r.id, 'REPORT_SUPPLEMENT', r.id, null::int, c.case_no,
                           '审核历史报表字段补录申请', '待补录审核', null::date,
                           '/case/detail/' || c.id
                    from case_reporting_supplement_request r join visible_cases c on c.id=r.case_id
                    where r.status='PENDING' and r.requested_by<>?
                      and exists(select 1 from sys_dept where id=? and enabled and org_kind='BUREAU')
                    """);
        }
        return query("with visible_cases as (select c.* from case_file c where " + caseScope.sql() + "), ",
                parts, arguments, page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskPage agency(String username, int page, int size) {
        var actor = materials.readActor(username);
        if (!actor.roles().equals(Set.of("AGENCY")) || !"AGENCY".equals(actor.accountCategory()) || actor.deptId() == null
                || !Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from sys_dept where id=? and enabled and org_kind='AGENCY')
                    """, Boolean.class, actor.deptId())))
            throw new BizException(403, "须使用已归类且所属经办部门已启用的经办账号");
        List<String> parts = List.of("""
                select 'AGENCY:' || a.id || ':' || a.agency_cycle || ':' || a.agency_status as task_key,
                       'AGREEMENT' as source_type, a.id as source_id, a.agency_cycle as cycle, c.case_no as source_no,
                       case when a.agency_status='PENDING' then '接收协议处理事项'
                            when a.agency_status='ACCEPTED' then '办理并反馈协议处理结果'
                            else '登记已落实约谈的实际情况' end as title,
                       case when a.agency_status='PENDING' then '待接收'
                            when a.agency_status='ACCEPTED' then '待反馈' else '待登记实际约谈' end as stage,
                       case when a.agency_status in ('PENDING','ACCEPTED') then a.agency_due_at end as due_at,
                       '/agency/agreements?focus=' || a.id || '&cycle=' || a.agency_cycle as link
                from agreement_action a join case_file c on c.id=a.case_id
                where a.agency_dept_id=? and c.party_type='PROVIDER'
                  and (a.agency_status in ('PENDING','ACCEPTED') or (
                    a.agency_status='COMPLETED' and a.action='INTERVIEW' and (a.agency_result_type is null or a.agency_result_type='COMPLETED')
                    and not exists(select 1 from department_interview i where i.source_agreement_id=a.id)))
                """);
        return query("with ", parts, new ArrayList<>(List.of(actor.deptId())), page, size);
    }

    private TaskPage query(String prefix, List<String> parts, List<Object> arguments, int page, int size) {
        if (page < 1 || size < 1 || size > 100)
            throw new BizException(2192, "页码须大于零，每页须为1至100条");
        if (parts.isEmpty()) return new TaskPage(List.of(), 0, page, size, LocalDate.now());
        String cte = prefix + "tasks(task_key,source_type,source_id,cycle,source_no,title,stage,due_at,link) as (" + String.join(" union all ", parts) + ") ";
        Long total = jdbc.queryForObject(cte + "select count(*) from tasks", Long.class, arguments.toArray());
        var paging = new ArrayList<>(arguments);
        paging.add(size);
        paging.add((page - 1L) * size);
        var rows = jdbc.query(cte + """
                select task_key,source_type,source_id,cycle,source_no,title,stage,due_at,link
                from tasks order by due_at nulls last,task_key limit ? offset ?
                """, (rs, row) -> {
            LocalDate due = rs.getObject("due_at", LocalDate.class);
            Integer late = due == null ? null : (int) java.time.temporal.ChronoUnit.DAYS.between(due, LocalDate.now());
            String sourceType = rs.getString("source_type");
            String actionLabel = Set.of("CLUE", "APPROVAL", "HANDOFF").contains(sourceType) ? "查看列表" : "进入办理";
            return new TaskRow(rs.getString("task_key"), rs.getString("source_type"), rs.getLong("source_id"),
                    rs.getObject("cycle", Integer.class), rs.getString("source_no"), rs.getString("title"),
                    rs.getString("stage"), due, late == null ? null : Math.max(late, 0), actionLabel, rs.getString("link"));
        }, paging.toArray());
        return new TaskPage(rows, total == null ? 0 : total, page, size, LocalDate.now());
    }
}