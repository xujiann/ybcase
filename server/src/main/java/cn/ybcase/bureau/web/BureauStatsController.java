package cn.ybcase.bureau.web;

import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 统计分析与督办看板（执法公示与全过程监督支撑） */
@RestController
@RequestMapping("/api/bureau/stats")
@RequiredArgsConstructor
public class BureauStatsController {

    private final JdbcTemplate jdbc;
    private final cn.ybcase.bureau.service.BureauConfig cfg;

    /** 总览：案件分状态计数、罚没/追回金额合计、线索转化 */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("clueTotal", one("select count(*) from case_clue"));
        m.put("cluePending", one("select count(*) from case_clue where status = 'PENDING'"));
        m.put("clueFiled", one("select count(*) from case_clue where status = 'FILED'"));
        m.put("caseTotal", one("select count(*) from case_file"));
        m.put("caseByStatus", jdbc.queryForList(
                "select status, count(*) as cnt from case_file group by status order by status"));
        m.put("fineDecided", one("select coalesce(sum(fine_amount),0) from case_decision"));
        m.put("recoupDecided", one("select coalesce(sum(recoup_amount),0) from case_decision"));
        m.put("confiscateDecided", one("select coalesce(sum(confiscate_amount),0) from case_decision"));
        m.put("fineCollected", one("select coalesce(sum(amount),0) from case_execution where kind in ('FINE','LATE_FEE','CONFISCATE')"));
        m.put("recoupCollected", one("select coalesce(sum(amount),0) from case_execution where kind = 'RECOUP'"));
        m.put("byPartyType", jdbc.queryForList(
                "select party_type, count(*) as cnt from case_file group by party_type order by cnt desc"));
        m.put("byCause", jdbc.queryForList("""
                select cc.category, count(*) as cnt from case_file cf
                join case_cause cc on cc.id = cf.cause_id group by cc.category order by cnt desc limit 10"""));
        m.put("monthlyFiled", jdbc.queryForList("""
                select to_char(filed_at, 'YYYY-MM') as ym, count(*) as cnt from case_file
                where filed_at >= current_date - interval '12 months' group by 1 order by 1"""));
        return R.ok(m);
    }

    /**
     * 督办看板：各环节超期/临期预警（第14/40/45/59条时限）。
     * 逐条列出全局案号、案件名称（含当事人）与金额，绕过 case_view_scope=SELF，
     * 故限监督岗位；办案员看本人在办事项走 /bureau/my/workbench。
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('LEADER','LEGAL','ADMIN')")
    @GetMapping("/supervision")
    public R<Map<String, Object>> supervision() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        // 线索核查超期（第14条）
        m.put("clueOverdue", jdbc.queryForList("""
                select id, clue_no, suspect_name, received_at, deadline_at, extended
                from case_clue where status = 'PENDING' and deadline_at < current_date order by deadline_at"""));
        // 办案期限：临期(10日内)与超期（第45条，含扣除期间顺延）
        m.put("caseNearDeadline", jdbc.queryForList("""
                select cf.id, cf.case_no, cf.name, cf.status, cf.filed_at,
                       cf.deadline_at + coalesce((select sum(e.end_at - e.start_at)::int from case_period_exclusion e
                                                  where e.case_id = cf.id and e.end_at is not null), 0) as effective_deadline
                from case_file cf
                where cf.status in ('INVESTIGATING','REPORTED','NOTIFIED')
                  and cf.deadline_at + coalesce((select sum(e.end_at - e.start_at)::int from case_period_exclusion e
                                                 where e.case_id = cf.id and e.end_at is not null), 0)
                      < current_date + 10
                order by effective_deadline"""));
        // 法制审核超期（第40条：10个工作日）
        m.put("reviewOverdue", jdbc.queryForList("""
                select r.id, r.case_id, cf.case_no, r.submitted_at, r.deadline_at
                from case_review r join case_file cf on cf.id = r.case_id
                where r.reviewed_at is null and r.deadline_at < current_date order by r.deadline_at"""));
        // 送达超期（第59条：决定后7个工作日，按节假日表精确计算，不再用自然日近似）
        int deliverDays = cfg.intVal("delivery_days", 7);
        m.put("deliveryOverdue", jdbc.queryForList("""
                select id, case_no, name, decided_at from case_file
                where status = 'DECIDED' and decided_at is not null order by decided_at""").stream()
                .filter(r -> cfg.plusWorkdays(((java.sql.Date) r.get("decided_at")).toLocalDate(),
                        deliverDays).isBefore(java.time.LocalDate.now()))
                .toList());
        // 先行登记保存超期未处理（第26条：7个工作日）
        m.put("holdOverdue", jdbc.queryForList("""
                select ev.id, ev.case_id, cf.case_no, ev.name, ev.hold_expire_at
                from case_evidence ev join case_file cf on cf.id = ev.case_id
                where ev.register_hold = true and ev.hold_expire_at < current_date order by ev.hold_expire_at"""));
        // 重大处罚决定未报政府备案（辽54条：较大数额罚款按单位档阈值近似）
        m.put("govRecordMissing", jdbc.queryForList("""
                select d.case_id, cf.case_no, cf.name, d.fine_amount, d.decided_at
                from case_decision d join case_file cf on cf.id = d.case_id
                where d.decision_type = 'PUNISH' and d.gov_record_no is null
                  and d.fine_amount >= coalesce((select cfg_value::numeric from sys_config
                                                 where cfg_key = 'meeting_required_fine_org'), 100000)
                order by d.decided_at"""));
        // 分期缴纳到期未缴（第54条）
        m.put("installmentOverdue", jdbc.queryForList("""
                select i.id, i.case_id, cf.case_no, i.seq, i.due_at, i.amount
                from case_installment i join case_file cf on cf.id = i.case_id
                where i.paid_at is null and i.due_at < current_date order by i.due_at"""));
        // 听证意见超期（辽50条：听证结束2日内提出意见）
        m.put("hearingOpinionOverdue", jdbc.queryForList("""
                select h.id, h.case_id, cf.case_no, h.held_at
                from case_hearing h join case_file cf on cf.id = h.case_id
                where h.status = 'HELD' and h.held_at + ?::int < current_date order by h.held_at""",
                cfg.intVal("hearing_opinion_days", 2)));
        // 简易程序备案超期（第51条：决定后7个工作日，节假日表精确计算）
        int recordDays = cfg.intVal("summary_record_days", 7);
        m.put("summaryRecordOverdue", jdbc.queryForList("""
                select id, case_no, name, decided_at from case_file
                where procedure_type = 'SUMMARY' and decided_at is not null and summary_record_at is null
                order by decided_at""").stream()
                .filter(r -> cfg.plusWorkdays(((java.sql.Date) r.get("decided_at")).toLocalDate(),
                        recordDays).isBefore(java.time.LocalDate.now()))
                .toList());
        // 协查超期（第34条：15日内完成）
        m.put("assistOverdue", jdbc.queryForList("""
                select a.id, a.case_id, cf.case_no, a.org, a.due_at
                from case_assist a join case_file cf on cf.id = a.case_id
                where a.replied_at is null and a.due_at < current_date order by a.due_at"""));
        // 责令改正逾期未报告（限期跟踪）
        m.put("correctOverdue", jdbc.queryForList("""
                select d.id, d.case_id, cf.case_no, d.title, d.due_at
                from case_document d join case_file cf on cf.id = d.case_id
                where d.doc_type = 'ORDER_CORRECT' and d.due_at is not null and d.due_at < current_date
                  and cf.status not in ('CLOSED','TERMINATED') order by d.due_at"""));
        // 处罚决定公开超期（辽56条：作出决定7日内公开，参数化）
        m.put("publishOverdue", jdbc.queryForList("""
                select cf.id, cf.case_no, cf.name, d.decided_at
                from case_decision d join case_file cf on cf.id = d.case_id
                where d.decision_type = 'PUNISH' and d.published = false
                  and d.decided_at + ?::int < current_date order by d.decided_at""",
                publishDays()));
        // ---- 执行阶段（第52-55条 / 行政强制法53-54条）----
        // 此前督办看板止于"送达"：决定书送达那天起系统就不再计时，缴款逾期、该催告、
        // 强执申请期将满全靠办案人自己记台账。三个参数与 ExecutionService 同源，不另起口径。
        int payDays = cfg.intVal("payment_days", 15);
        int urgeDays = cfg.intVal("court_urge_days", 10);
        // 未缴清判定与 close() 的 fullyExecuted 一致：三类款项各自累计均达决定额才算缴清
        String unpaid = """
                and (coalesce((select sum(e.amount) from case_execution e
                               where e.case_id = cf.id and e.kind = 'FINE'), 0) < d.fine_amount
                  or coalesce((select sum(e.amount) from case_execution e
                               where e.case_id = cf.id and e.kind = 'RECOUP'), 0) < d.recoup_amount
                  or coalesce((select sum(e.amount) from case_execution e
                               where e.case_id = cf.id and e.kind = 'CONFISCATE'), 0) < d.confiscate_amount)""";
        // 缴款期届满仍未缴清（第53条）
        m.put("paymentOverdue", jdbc.queryForList("""
                select cf.id, cf.case_no, cf.name, cf.delivered_at,
                       cf.delivered_at + ?::int as pay_deadline,
                       d.fine_amount, d.recoup_amount, d.confiscate_amount,
                       coalesce((select sum(e.amount) from case_execution e
                                 where e.case_id = cf.id and e.kind in ('FINE','RECOUP','CONFISCATE')), 0) as paid
                from case_file cf join case_decision d on d.case_id = cf.id
                where cf.status = 'DELIVERED' and cf.delivered_at is not null
                  and cf.delivered_at + ?::int < current_date
                  and cf.court_enforce_applied = false
                """ + unpaid + """
                order by cf.delivered_at""", payDays, payDays));
        // 缴款期已届满未缴清但尚未催告——催告是申请强执的法定前置（行政强制法54条）
        m.put("urgeLetterMissing", jdbc.queryForList("""
                select cf.id, cf.case_no, cf.name, cf.delivered_at + ?::int as pay_deadline
                from case_file cf join case_decision d on d.case_id = cf.id
                where cf.status = 'DELIVERED' and cf.delivered_at is not null
                  and cf.delivered_at + ?::int < current_date
                  and cf.court_enforce_applied = false
                  and not exists (select 1 from case_document doc
                                  where doc.case_id = cf.id and doc.doc_type = 'URGE_LETTER')
                """ + unpaid + """
                order by cf.delivered_at""", payDays, payDays));
        // 强执申请期将满/已过（行政强制法53条：缴款期满起3个月内申请，逾期即失权）
        // 这是全流程唯一"错过即作废且标的最大"的期限，故不止报超期，提前 30 日即入清单
        m.put("courtEnforceExpiring", jdbc.queryForList("""
                select cf.id, cf.case_no, cf.name,
                       cf.delivered_at + ?::int as pay_deadline,
                       (cf.delivered_at + ?::int) + interval '3 month' as apply_deadline,
                       ((cf.delivered_at + ?::int) + interval '3 month')::date - current_date as days_left,
                       (select max(doc.made_at) from case_document doc
                        where doc.case_id = cf.id and doc.doc_type = 'URGE_LETTER') as urged_at
                from case_file cf join case_decision d on d.case_id = cf.id
                where cf.status = 'DELIVERED' and cf.delivered_at is not null
                  and cf.court_enforce_applied = false
                  and ((cf.delivered_at + ?::int) + interval '3 month')::date < current_date + 30
                """ + unpaid + """
                order by apply_deadline""", payDays, payDays, payDays, payDays));
        // 封存到期（第31条）
        m.put("sealExpiring", jdbc.queryForList("""
                select ev.id, ev.case_id, cf.case_no, ev.name, ev.seal_expire_at, ev.seal_extended
                from case_evidence ev join case_file cf on cf.id = ev.case_id
                where ev.sealed = true and ev.seal_expire_at < current_date + 5 order by ev.seal_expire_at"""));
        return R.ok(m);
    }

    private Object one(String sql) {
        return jdbc.queryForObject(sql, Object.class);
    }

    private int publishDays() {
        var rows = jdbc.queryForList("select cfg_value from sys_config where cfg_key = 'decision_publish_days'");
        try {
            return rows.isEmpty() ? 7 : Integer.parseInt(((String) rows.get(0).get("cfg_value")).trim());
        } catch (NumberFormatException e) {
            return 7;
        }
    }
}
