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
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EnforcementWorkReportService {
    private final JdbcTemplate jdbc;
    private final MaterialAccess materialAccess;
    private final BureauConfig config;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportExport export(LocalDate start, LocalDate end, boolean reconcile) {
        var actor = materialAccess.requireCurrentReadRoles("LEADER", "LEGAL", "ADMIN");
        if (actor != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "select account_category = 'BUREAU' from sys_user where id = ?", Boolean.class, actor.id())))
            throw new BizException(403, "全局办理案件统计表由局机关授权岗位导出");
        LocalDate today = LocalDate.now();
        if (start == null || end == null || start.isAfter(end) || end.isAfter(today))
            throw new BizException(2100, "统计时间须为有效区间，截止日不得晚于今天");

        List<Object[]> rows = new ArrayList<>();
        var budget = new ReportExport.Budget();
        ReportExport.query(jdbc, """
                with period as (select cast(? as date) as starts, cast(? as date) as ends)
                select dept.name as department, officers.leads, c.party_name, officers.members,
                       case c.institution_kind when 'SINGLE_PHARMACY' then '单体药店'
                            when 'CHAIN_PHARMACY' then '连锁药店' when 'MEDICAL' then
                            case rf.institution_ownership when 'PUBLIC' then '公立医疗机构'
                                 when 'SOCIAL' then '非公立医疗机构' end end as institution_type,
                       case rf.institution_grade when 'TERTIARY' then '三级' when 'SECONDARY' then '二级'
                            when 'PRIMARY' then '一级' when 'UNGRADED' then '无等级' end as grade,
                       cl.source, cl.clue_no,
                       (c.closed_at <= p.ends or c.terminated_at <= p.ends) is true as complete,
                       case when d.decision_type = 'PUNISH' then true
                            when d.decision_type = 'NOT_ESTABLISHED' then false end as violation,
                       case when c.reported_at <= p.ends or d.id is not null then rf.confirmed_violation_facts end as facts,
                       case when d.decision_type = 'PUNISH' then concat_ws(E'\\n',
                           d.decision_no, nullif(rf.penalty_basis,''), nullif(d.content,''),
                           '罚款 ' || d.fine_amount || ' 元，责令退回基金 ' || d.recoup_amount || ' 元',
                           nullif(d.other_measures,'')) end as penalty,
                       agreement.results, agreement.termination,
                       case when c.closed_at <= p.ends or c.terminated_at <= p.ends or d.id is not null then null
                            when c.reported_at <= p.ends then '调查已终结，尚未作出处理决定'
                            when c.status = 'SUSPENDED' and c.suspended_at <= p.ends then '中止调查'
                            when assist.pending is not null then '调查中，待' || assist.pending || '复函'
                            else '已立案，正在调查' end as pending_state,
                       transfers.details, transfers.judgments,
                       concat_ws(E'\\n', '案号：' || c.case_no, transfers.notes,
                                 '日常检查：' || inspections.ordinary) as remarks,
                       inspections.national, inspections.provincial, inspections.municipal
                from case_file c cross join period p
                left join case_reporting_facts rf on rf.case_id = c.id
                left join sys_dept dept on dept.id = c.owner_dept_id
                left join case_clue cl on cl.id = c.clue_id
                left join case_decision d on d.case_id = c.id and d.decided_at <= p.ends
                left join lateral (
                    select string_agg(name, '、' order by id) filter (where duty = 'LEAD') as leads,
                           string_agg(name, '、' order by id) filter (where duty <> 'LEAD') as members
                    from case_officer where case_id = c.id and not avoided
                ) officers on true
                left join lateral (
                    select string_agg(org || '：' ||
                               case when agency_result_type is null then result
                                    else concat_ws('，', nullif(result, ''),
                                         case agency_result_type when 'COMPLETED' then '已落实' else '未落实' end,
                                         '实际追回 ' || agency_recovered_amount || ' 元',
                                         '生效日 ' || agency_result_effective_at,
                                         '暂停截止日 ' || agency_result_end_at,
                                         '回执文号 ' || nullif(agency_result_reference, '')) end,
                               E'\\n' order by replied_at, id) as results,
                           string_agg(org || '：' || result, E'\\n' order by replied_at, id)
                               filter (where action = 'TERMINATE_AGREEMENT' and (agency_result_type is null
                                   or (agency_result_type = 'COMPLETED'
                                       and coalesce(agency_result_effective_at, replied_at) <= p.ends))) as termination,
                           bool_or(replied_at between p.starts and p.ends) as activity_in_period
                    from agreement_action where case_id = c.id and replied_at <= p.ends
                ) agreement on true
                left join lateral (
                    select string_agg(distinct org, '、' order by org) as pending
                    from case_assist where case_id = c.id and direction = 'OUT' and sent_at <= p.ends
                         and (replied_at is null or replied_at > p.ends)
                ) assist on true
                left join lateral (
                    select string_agg(concat_ws(' ', sent_at::text, target_org, reason), E'\\n' order by sent_at, id) as details,
                           string_agg(concat_ws(' ', judgment_at::text, judgment_no, judgment_result),
                                      E'\\n' order by judgment_at, id)
                               filter (where judgment_at <= p.ends) as judgments,
                           string_agg(nullif(note, ''), E'\\n' order by sent_at, id) as notes,
                           bool_or(sent_at between p.starts and p.ends
                                or judgment_at between p.starts and p.ends) as activity_in_period
                    from case_transfer t left join case_transfer_judgment j on j.transfer_id = t.id
                    where direction = 'OUT' and sent_at <= p.ends
                      and (case_id = c.id or (case_id is null and clue_id = c.clue_id))
                ) transfers on true
                left join lateral (
                    select string_agg(concat_ws(' ', done_at::text, insp_no), E'\\n' order by done_at, id)
                               filter (where task_level is null and object_type = 'PROVIDER') as ordinary,
                           string_agg(concat_ws(' ', done_at::text, insp_no), E'\\n' order by done_at, id)
                               filter (where task_level = 'NATIONAL') as national,
                           string_agg(concat_ws(' ', done_at::text, insp_no), E'\\n' order by done_at, id)
                               filter (where task_level = 'PROVINCIAL') as provincial,
                           string_agg(concat_ws(' ', case when task_level = 'CITY_CROSS_CHECK' then '市级交叉检查' end,
                                               done_at::text, insp_no), E'\\n' order by done_at, id)
                               filter (where task_level in ('MUNICIPAL', 'CITY_CROSS_CHECK')) as municipal
                    from inspection where clue_id = c.clue_id and done_at between p.starts and p.ends
                ) inspections on true
                where c.party_type = 'PROVIDER' and c.filed_at <= p.ends
                  and (least(c.closed_at, c.terminated_at, p.ends) >= p.starts
                       or agreement.activity_in_period or transfers.activity_in_period
                       or inspections.ordinary is not null or inspections.national is not null or inspections.provincial is not null
                       or inspections.municipal is not null)
                order by dept.name nulls last, c.filed_at, c.id
                """, rs -> {
            Boolean violation = rs.getObject("violation", Boolean.class);
            budget.add(rows, new Object[]{rows.size() + 1, rs.getString("department"), rs.getString("leads"),
                    rs.getString("party_name"), rs.getString("institution_type"), rs.getString("grade"),
                    rs.getString("members"), clueSource(rs.getString("source"), rs.getString("clue_no")), rs.getBoolean("complete") ? "是" : "否",
                    violation == null ? null : violation ? "是" : "否",
                    rs.getString("facts"), rs.getString("penalty"), rs.getString("results"),
                    rs.getString("pending_state"), rs.getString("details"), rs.getString("termination"),
                    rs.getString("judgments"), rs.getString("national"), rs.getString("provincial"), rs.getString("municipal"),
                    rs.getString("remarks")});
        }, start, end);
        int caseCount = rows.size();
        ReportExport.query(jdbc, """
                select dept.name as department, i.object_name, i.officers, i.result, i.violation_found,
                       i.insp_no, i.done_at, i.task_level
                from inspection i
                left join sys_dept dept on dept.id = i.owner_dept_id
                where i.object_type = 'PROVIDER' and i.done_at between ? and ?
                  and not exists (select 1 from case_file c where c.clue_id = i.clue_id
                                  and c.party_type = 'PROVIDER' and c.filed_at <= ?)
                order by dept.name nulls last, i.done_at, i.id
                """, rs -> {
            String activity = rs.getString("done_at") + " " + rs.getString("insp_no");
            String level = rs.getString("task_level");
            boolean crossCheck = "CITY_CROSS_CHECK".equals(level);
            boolean violation = rs.getBoolean("violation_found");
            budget.add(rows, new Object[]{rows.size() + 1, rs.getString("department"), null, rs.getString("object_name"),
                    null, null, null, (crossCheck ? "市级交叉检查 " : "行政检查 ") + rs.getString("insp_no"), "是", violation ? "是" : "否",
                    violation ? rs.getString("result") : null, null, null, null, null, null, null,
                    "NATIONAL".equals(level) ? activity : null, "PROVINCIAL".equals(level) ? activity : null,
                    crossCheck ? "市级交叉检查 " + activity : "MUNICIPAL".equals(level) ? activity : null,
                    "检查人员：" + rs.getString("officers")});
        }, start, end, end);
        var sheet = new PenaltyReportWorkbook.Sheet(rows, 'U', Map.of(
                "A2", "报送部门：" + config.str("org_name", "医疗保障局"),
                "G2", "统计时间：" + start + " 至 " + end,
                "R2", "报送时间：" + today));
        List<Object[]> notes = reconcile ? List.of(
                new Object[]{"核对项目", "口径及数量"},
                new Object[]{"统计期间", start + " 至 " + end + "（含首尾）"},
                new Object[]{"合计数据行", rows.size()},
                new Object[]{"案件数据行", caseCount},
                new Object[]{"独立检查数据行", rows.size() - caseCount},
                new Object[]{"归属部门待核", rows.stream().filter(row -> row[1] == null).count()},
                new Object[]{"案件纳入范围", "机构案件在区间内办理，或有关协议、移送、检查活动在区间内发生。关联检查随案件列示。"},
                new Object[]{"协议处理", "实际追回金额、协议生效日、暂停截止日和回执文号取已登记的结构化办结结果。"},
                new Object[]{"报表补录", "机构性质、等级、认定事实及处罚依据的历史缺项使用已批准的报表专用补录值，来源见报表补录台账。"},
                new Object[]{"协议解除", "结构化办结以解除生效时间为准。历史文本复函按既有规则列示，解除结果需核对原件。"},
                new Object[]{"历史口径", "状态、决定、协助和移送按已登记日期判断，部门名称和办案人员采用当前登记资料。"}
        ) : List.of();
        return new ReportExport(PenaltyReportWorkbook.write("enforcement-work.xlsx", List.of(sheet), notes),
                new ReportExport.Audit(ReportExport.Kind.ENFORCEMENT_WORK, start, end, end, rows.size()));
    }

    private static String clueSource(String source, String clueNo) {
        if (source == null) return null;
        String label = switch (source) {
            case "COMPLAINT" -> "投诉举报";
            case "INSPECTION" -> "监督检查";
            case "TRANSFER" -> "部门移送";
            case "ASSIGNED" -> "上级交办";
            case "MONITOR" -> "智能监控";
            case "TRACE_CODE" -> "药品追溯码线索";
            default -> source;
        };
        return label + " " + clueNo;
    }
}
