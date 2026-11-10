package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ExecutionAlerts execution;
    private final ObjectMapper mapper;

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
        String ctes = "with visible_cases as (select c.* from case_file c where " + caseScope.sql() + "), ";
        boolean writer = actor.roles().stream().anyMatch(Set.of("HANDLER", "LEADER", "ADMIN")::contains);
        boolean reviewer = actor.roles().contains("LEGAL") || actor.roles().contains("LEADER");
        boolean bureauReviewer = reviewer && "BUREAU".equals(actor.accountCategory()) && actor.deptId() != null
                && Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from sys_dept where id=? and enabled and org_kind='BUREAU')
                    """, Boolean.class, actor.deptId()));
        if (writer) {
            String assignment = actor.privileged() ? "true" : """
                    (c.owner_user=? or exists (select 1 from case_officer o left join enforcer e on e.cert_no=o.cert_no
                      where o.case_id=c.id and coalesce(o.user_id,e.user_id)=? and not o.avoided))
                    """;
            List<Object> assignmentArgs = actor.privileged() ? List.of() : List.of(username, actor.id());
            var work = execution.work(ExecutionDeadlines.today(),caseScope.sql(),caseScope.args(),assignment,assignmentArgs);
            ctes += """
                    execution_tasks as (select * from jsonb_to_recordset(?::jsonb)
                      as w("caseId" bigint,"taskKey" text,title text,stage text,"dueAt" date,link text)),
                    """;
            try {
                arguments.add(mapper.writeValueAsString(work));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("Unable to encode current execution tasks", ex);
            }
            parts.add("""
                    select w."taskKey", 'CASE', c.id, null::int, c.case_no,
                           w.title, w.stage, w."dueAt", w.link
                    from execution_tasks w join visible_cases c on c.id=w."caseId"
                    """);
            arguments.addAll(assignmentArgs);
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
                      and not exists(select 1 from execution_tasks w where w."caseId"=c.id)
                      and
                    """ + assignment);

            var clueScope = sources.clueReadPredicate("cl.id", username);
            arguments.addAll(clueScope.args());
            parts.add("""
                    select 'CLUE:' || cl.id as task_key, 'CLUE' as source_type, cl.id as source_id,
                           null::int as cycle, cl.clue_no as source_no, '核查线索并决定后续处理' as title,
                           '待核查' as stage, cl.deadline_at as due_at,
                           '/case/clues?focus=' || cl.id as link
                    from case_clue cl where cl.status='PENDING'
                      and not exists(select 1 from case_file f where f.clue_id=cl.id)
                      and not exists(select 1 from department_handoff h where h.clue_id=cl.id and h.status='PENDING')
                      and not exists (
                         select 1 from department_handoff h where h.id=(
                            select max(a.id) from department_handoff a where a.clue_id=cl.id and a.status='ACCEPTED')
                           and h.to_dept_id=cl.owner_dept_id
                           and not exists(select 1 from clue_assignment ca where ca.handoff_id=h.id)
                      ) and
                    """ + clueScope.sql());

            var inspectionScope = sources.inspectionReadPredicate("i", username);
            arguments.addAll(inspectionScope.args());
            parts.add("""
                    select 'INSPECTION:' || i.id || case when i.done_at is null then ':CHECK' else ':CONVERT' end,
                           'INSPECTION', i.id, null::int, i.insp_no,
                           case when i.done_at is null then '办理检查并登记结果' else '核对检查结果并转线索' end,
                           case when i.done_at is null then '待检查' else '待转线索' end,
                           null::date, '/case/inspections?focus=' || i.id
                    from inspection i where i.clue_id is null
                      and (i.done_at is null or i.violation_found)
                      and not exists(select 1 from department_handoff h where h.inspection_id=i.id
                             and (h.status='PENDING' or (h.status='ACCEPTED' and i.assigned_user_id is null)))
                      and
                    """ + inspectionScope.sql());

            arguments.add(ExecutionDeadlines.today());
            arguments.addAll(assignmentArgs);
            parts.add("""
                    select 'REPORT_MISSING:' || c.id, 'REPORT_MISSING', c.id, null::int, c.case_no,
                           '核对报表缺项：' || array_to_string(issue.missing_fields, '、'),
                           '待补齐报表资料', null::date, '/case/detail/' || c.id || '#report-supplement'
                    from case_penalty_missing_fields(?) issue join visible_cases c on c.id=issue.case_id
                    where cardinality(issue.missing_fields)>0
                      and c.status in ('DECIDED','DELIVERED','CLOSED')
                      and not exists(select 1 from case_reporting_supplement_request s
                                     where s.case_id=c.id and s.status='PENDING')
                      and
                    """ + assignment);

            parts.add("""
                    select 'AGENCY_RETURN:' || a.id || ':' || a.agency_cycle, 'AGREEMENT', a.id,
                           a.agency_cycle, c.case_no, '经办事项已退回，请核对后重新分派', '经办已退回',
                           null::date, '/case/detail/' || c.id
                    from agreement_action a join visible_cases c on c.id=a.case_id
                    where a.agency_status='RETURNED'
                    """);
            if (!actor.privileged()) { arguments.add(username); arguments.add(actor.id()); }
            parts.add("""
                    select 'DELIVERY_EXCEPTION:' || e.id, 'DELIVERY_EXCEPTION', e.id, null::int, c.case_no,
                           '核对电子送达失败或超时并按当前规则处理', '电子送达异常', null::date,
                           '/case/detail/' || c.id
                    from electronic_delivery e join visible_cases c on c.id=e.case_id
                    where e.status in ('FAILED','TIMED_OUT') and e.superseded_at is null
                      and (e.supplement_document_id is not null or (c.archive_no is null and c.status<>'CLOSED')) and
                    """ + assignment);
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
        if (reviewer && actor.deptId()!=null && Set.of("BUREAU","DISTRICT").contains(
                actor.accountCategory()==null ? "" : actor.accountCategory())) {
            arguments.add(actor.id());
            arguments.add(bureauReviewer);
            parts.add("""
                    select 'CONTINUATION:' || r.id, 'CONTINUATION', r.id, null::int, c.case_no,
                           '复核救济、后续缴款或退款申请并保留原卷', '待后续事项复核', null::date,
                           '/case/detail/' || c.id
                    from case_continuation_request r join visible_cases c on c.id=r.case_id
                    where r.status='PENDING' and r.requested_by<>?
                      and (?::boolean or (r.request_type not in ('FACT_CORRECTION','COURT_RECEIPT_CORRECTION')
                         and not (r.request_type='REMEDY_RESULT' and exists (
                            select 1 from jsonb_each_text(r.payload) field
                            where field.key in ('institutionCode','institutionOwnership','institutionGrade','institutionKind',
                                'confirmedViolationStart','confirmedViolationEnd','confirmedViolationFacts','confirmedViolationAmount')
                              and coalesce(field.value,'')<>''))))
                    """);
        }

        var treasuryConditions = new ArrayList<String>();
        if (actor.roles().stream().anyMatch(Set.of("LEGAL","LEADER","ADMIN")::contains))
            treasuryConditions.add("i.review_status='OPEN'");
        if (writer) {
            treasuryConditions.add("(i.review_status='ASSIGNED' and i.assignee=?)");
            arguments.add(username);
        }
        if (actor.roles().contains("LEGAL") || actor.roles().contains("LEADER")) {
            treasuryConditions.add("(i.review_status='SUBMITTED' and i.investigated_by<>?)");
            arguments.add(username);
        }
        if (!treasuryConditions.isEmpty()) parts.add("""
                select 'TREASURY:' || i.id, 'TREASURY', i.id, null::int, c.case_no,
                       case i.review_status when 'OPEN' then '分派财政票据差异核查'
                         when 'ASSIGNED' then '核查财政票据差异并提交依据' else '复核财政票据差异核查结果' end,
                       case i.review_status when 'OPEN' then '待分派差异' when 'ASSIGNED' then '待差异核查' else '待差异复核' end,
                       null::date, '/system/integrations?treasuryItem=' || i.id
                from treasury_batch_item i join visible_cases c on c.id=i.case_id
                where i.result not in ('PENDING','MATCH') and (
                """ + String.join(" or ", treasuryConditions) + ")");
        if (actor.roles().contains("LEADER") && actor.accountCategory() != null && actor.deptId() != null) {
            arguments.add(actor.deptId());
            parts.add("""
                    select 'HANDOFF:' || h.id, 'HANDOFF', h.id, null::int, h.source_no,
                           '核对机构交接申请并接收或退回', '待接收', null::date,
                           '/case/department-handoffs?focus=' || h.id
                    from department_handoff h where h.status='PENDING' and h.to_dept_id=?
                    """);
            arguments.add(actor.deptId());
            arguments.add(actor.accountCategory());
            parts.add("""
                    select 'HANDOFF:' || h.id || ':ASSIGN', 'HANDOFF', h.id, null::int, h.source_no,
                           case when h.inspection_id is not null then '指派接收检查的承办人' else '指派接收线索的承办人' end,
                           '接收后待指派', null::date, '/case/department-handoffs?focus=' || h.id
                    from department_handoff h
                    join sys_dept d on d.id=h.to_dept_id and d.enabled
                    left join inspection i on i.id=h.inspection_id
                    left join case_clue cl on cl.id=h.clue_id
                    where h.status='ACCEPTED' and h.to_dept_id=? and d.org_kind=?
                      and ((i.id is not null and i.owner_dept_id=h.to_dept_id and i.clue_id is null
                            and (i.done_at is null or i.violation_found) and i.assigned_user_id is null
                            and h.id=(select max(a.id) from department_handoff a where a.inspection_id=i.id and a.status='ACCEPTED')
                            and not exists(select 1 from department_handoff p where p.inspection_id=i.id and p.status='PENDING'))
                        or (cl.id is not null and cl.owner_dept_id=h.to_dept_id and cl.status='PENDING'
                            and h.id=(select max(a.id) from department_handoff a where a.clue_id=cl.id and a.status='ACCEPTED')
                            and not exists(select 1 from case_file f where f.clue_id=cl.id)
                            and not exists(select 1 from department_handoff p where p.clue_id=cl.id and p.status='PENDING')
                            and not exists(select 1 from clue_assignment ca where ca.handoff_id=h.id)))
                    """);
        }
        if (bureauReviewer) {
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
            arguments.add(actor.id());
            arguments.add(actor.deptId());
            parts.add("""
                    select 'REPORT_VERSION:' || b.id, 'REPORT_VERSION', b.id, null::int,
                           '报表批次 #' || b.id, '核对冻结报表来源并确认版本', '待报表确认', null::date,
                           '/case/stats?reportBatch=' || b.id
                    from report_frozen_batch b where b.status='GENERATED' and b.generated_by<>?
                      and exists(select 1 from sys_dept where id=? and enabled and org_kind='BUREAU')
                    """);
            arguments.add(actor.id());
            arguments.add(actor.deptId());
            parts.add("""
                    select 'INTERVIEW_CHANGE:' || r.id, 'INTERVIEW_CHANGE', r.id, null::int,
                           '约谈 #' || r.interview_id, '核对实际约谈更正或误建作废申请', '待约谈复核', null::date,
                           '/case/interviews?interviewRequest=' || r.id
                    from interview_change_request r where r.status='PENDING' and r.requested_by<>?
                      and exists(select 1 from sys_dept where id=? and enabled and org_kind='BUREAU')
                    """);
            arguments.add(actor.id());
            arguments.add(actor.deptId());
            parts.add("""
                    select 'SUPPLEMENT_BUNDLE:' || b.id, 'SUPPLEMENT_BUNDLE', b.id, null::int, c.case_no,
                           '核对后续材料包', '待后续材料复核', null::date,
                           '/case/detail/' || c.id || '?supplementBundle=' || b.id
                    from case_supplement_bundle b join visible_cases c on c.id=b.case_id
                    where b.status='PENDING' and b.applicant_id<>?
                      and exists(select 1 from sys_dept where id=? and enabled and org_kind='BUREAU')
                    """);
        }
        return query(ctes, parts, arguments, page, size);
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
                """, """
                select 'INTERVIEW_REPLACEMENT:' || r.interview_id, 'INTERVIEW_REPLACEMENT', r.interview_id,
                       r.agency_cycle, c.case_no, '核对作废约谈并按实际情况申请替代', '待核对约谈替代',
                       null::date, '/agency/agreements?interview=' || r.interview_id
                from eligible_interview_replacement r join case_file c on c.id=r.case_id
                where r.dept_id=?
                """);
        return query("with ", parts, new ArrayList<>(List.of(actor.deptId(),actor.deptId())), page, size);
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
            String actionLabel = "进入办理";
            return new TaskRow(rs.getString("task_key"), rs.getString("source_type"), rs.getLong("source_id"),
                    rs.getObject("cycle", Integer.class), rs.getString("source_no"), rs.getString("title"),
                    rs.getString("stage"), due, late == null ? null : Math.max(late, 0), actionLabel, rs.getString("link"));
        }, paging.toArray());
        return new TaskPage(rows, total == null ? 0 : total, page, size, LocalDate.now());
    }
}
