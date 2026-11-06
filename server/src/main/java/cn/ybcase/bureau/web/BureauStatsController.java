package cn.ybcase.bureau.web;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.ReportExport;
import cn.ybcase.core.common.R;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** 统计分析与督办看板（执法公示与全过程监督支撑） */
@RestController
@RequestMapping("/api/bureau/stats")
@RequiredArgsConstructor
public class BureauStatsController {
    private final Semaphore reportSlots = new Semaphore(2);

    private final JdbcTemplate jdbc;
    private final cn.ybcase.bureau.service.BusinessStatisticsService statistics;
    private final cn.ybcase.bureau.service.BureauConfig cfg;
    private final cn.ybcase.bureau.service.ExecutionAlerts executionAlerts;
    private final cn.ybcase.bureau.service.MaterialAccess materialAccess;

    private final cn.ybcase.bureau.service.PenaltyReportService penaltyReport;
    private final cn.ybcase.bureau.service.EnforcementWorkReportService enforcementWorkReport;
    private final cn.ybcase.bureau.service.DepartmentInterviewService departmentInterviews;

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('LEADER','LEGAL','ADMIN')")
    @GetMapping("/interviews/download")
    public org.springframework.http.ResponseEntity<byte[]> downloadInterviews(
            @RequestParam int year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutoff,
            @RequestParam(defaultValue = "false") boolean reconcile,
            org.springframework.security.core.Authentication auth, HttpServletRequest request) {
        return downloadWorkbook(request, year + "年医保部门约谈定点医药机构台账.xlsx",
                () -> departmentInterviews.export(year, start, cutoff, reconcile, auth.getName()));
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('LEADER','LEGAL','ADMIN')")
    @GetMapping("/enforcement-work/download")
    public org.springframework.http.ResponseEntity<byte[]> downloadEnforcementWork(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            @RequestParam(defaultValue = "false") boolean reconcile, HttpServletRequest request) {
        return downloadWorkbook(request, "基金监管部门执法人员办理案件情况统计表.xlsx",
                () -> enforcementWorkReport.export(start, end, reconcile));
    }

    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('LEADER','LEGAL','ADMIN')")
    @GetMapping("/penalties/download")
    public org.springframework.http.ResponseEntity<byte[]> downloadPenalties(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate cutoff,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end,
            HttpServletRequest request) {
        String filename = year == null ? "2024-2026年行政处罚情况统计表.xlsx"
                : year + "年行政处罚情况统计表（内部核对）.xlsx";
        return downloadWorkbook(request, filename, () -> penaltyReport.export(year, start, end, cutoff));
    }

    private org.springframework.http.ResponseEntity<byte[]> downloadWorkbook(
            HttpServletRequest request, String name, Supplier<ReportExport> generate) {
        if (!reportSlots.tryAcquire()) throw new BizException(2100, "报表导出繁忙，请稍后重试");
        ReportExport report;
        try {
            report = generate.get();
        } finally {
            reportSlots.release();
        }
        request.setAttribute(ReportExport.AUDIT_ATTRIBUTE, report.audit());
        var disposition = org.springframework.http.ContentDisposition.attachment()
                .filename(name, java.nio.charset.StandardCharsets.UTF_8)
                .build();
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(report.workbook());
    }

    /** 总览：案件分状态计数、罚没/追回金额合计、线索转化 */
    @GetMapping("/overview")
    public R<Map<String, Object>> overview(org.springframework.security.core.Authentication auth) {
        return R.ok(statistics.overview(auth.getName()));
    }

    /**
     * 督办看板：各环节超期/临期预警（第14/40/45/59条时限）。
     * 逐条列出全局案号、案件名称（含当事人）与金额，绕过 case_view_scope=SELF，
     * 故限监督岗位；办案员看本人在办事项走 /bureau/my/workbench。
     */
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('LEADER','LEGAL','ADMIN')")
    @GetMapping("/supervision")
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public R<Map<String, Object>> supervision() {
        var actor = materialAccess.requireCurrentReadRoles("LEADER", "LEGAL", "ADMIN");
        if (actor != null && Boolean.TRUE.equals(jdbc.queryForObject(
                "select account_category = 'DISTRICT' from sys_user where id = ?", Boolean.class, actor.id())))
            throw new cn.ybcase.bureau.common.BizException(403, "全市督办看板由局机关授权岗位查看");
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        // 每类只返回最紧急的前 N 条：各查询本就按紧急度 ORDER BY，截断保留的正是最该看的。
        // 此前 16 类全部无上界，实测 3102 案件时响应 933KB、合计 6434 行，
        // 前端普通 el-table 全量渲染出 46296 个 DOM 节点、约 17 秒才可用，且随案件量线性恶化。
        final int top = cfg.intVal("supervision_top_n", 100);
        java.util.Map<String, Boolean> truncated = new java.util.LinkedHashMap<>();
        java.util.function.BiConsumer<String, List<Map<String, Object>>> put = (k, v) -> {
            if (v.size() > top) { truncated.put(k, true); m.put(k, new java.util.ArrayList<>(v.subList(0, top))); }
            else m.put(k, v);
        };
        // 线索核查超期（第14条）
        put.accept("clueOverdue", jdbc.queryForList("""
                select id, clue_no, suspect_name, received_at, deadline_at, extended
                from case_clue where status = 'PENDING' and deadline_at < current_date order by deadline_at"""));
        // 办案期限：临期(10日内)与超期（第45条，含扣除期间顺延）
        put.accept("caseNearDeadline", jdbc.queryForList("""
                select cf.id, cf.case_no, cf.name, cf.status, cf.filed_at,
                       cf.deadline_at + coalesce((select sum(coalesce(e.end_at, current_date) - e.start_at)::int from case_period_exclusion e
                                                  where e.case_id = cf.id), 0) as effective_deadline
                from case_file cf
                where cf.status in ('INVESTIGATING','REPORTED','NOTIFIED')
                  and cf.deadline_at + coalesce((select sum(coalesce(e.end_at, current_date) - e.start_at)::int from case_period_exclusion e
                                                 where e.case_id = cf.id), 0)
                      < current_date + 10
                order by effective_deadline"""));
        // 法制审核超期（第40条：10个工作日）
        put.accept("reviewOverdue", jdbc.queryForList("""
                select r.id, r.case_id, cf.case_no, r.submitted_at, r.deadline_at
                from case_review r join case_file cf on cf.id = r.case_id
                where r.reviewed_at is null and r.deadline_at < current_date order by r.deadline_at"""));
        // 送达超期（第59条：决定后7个工作日，按节假日表精确计算，不再用自然日近似）
        int deliverDays = cfg.intVal("delivery_days", 7);
        put.accept("deliveryOverdue", jdbc.queryForList("""
                select id, case_no, name, decided_at from case_file
                where status = 'DECIDED' and decided_at is not null order by decided_at""").stream()
                .filter(r -> cfg.plusWorkdays(((java.sql.Date) r.get("decided_at")).toLocalDate(),
                        deliverDays).isBefore(java.time.LocalDate.now()))
                .toList());
        // 先行登记保存超期未处理（第26条：7个工作日）
        put.accept("holdOverdue", jdbc.queryForList("""
                select ev.id, ev.case_id, cf.case_no, ev.name, ev.hold_expire_at
                from case_evidence ev join case_file cf on cf.id = ev.case_id
                where ev.register_hold = true and ev.hold_expire_at < current_date order by ev.hold_expire_at"""));
        // 重大处罚决定未报政府备案（辽54条：较大数额罚款按单位档阈值近似）
        put.accept("govRecordMissing", jdbc.queryForList("""
                select d.case_id, cf.case_no, cf.name, d.fine_amount, d.decided_at
                from case_decision d join case_file cf on cf.id = d.case_id
                where d.decision_type = 'PUNISH' and d.gov_record_no is null
                  and d.fine_amount >= coalesce((select cfg_value::numeric from sys_config
                                                 where cfg_key = 'meeting_required_fine_org'), 100000)
                order by d.decided_at"""));
        // 分期缴纳到期未缴（第54条）
        put.accept("installmentOverdue", jdbc.queryForList("""
                select i.id, i.case_id, cf.case_no, i.seq, i.due_at, i.amount
                from case_installment i join case_file cf on cf.id = i.case_id
                where i.paid_at is null and i.due_at < current_date order by i.due_at"""));
        // 听证意见超期（辽50条：听证结束2日内提出意见）
        put.accept("hearingOpinionOverdue", jdbc.queryForList("""
                select h.id, h.case_id, cf.case_no, h.held_at
                from case_hearing h join case_file cf on cf.id = h.case_id
                where h.status = 'HELD' and h.held_at + ?::int < current_date order by h.held_at""",
                cfg.intVal("hearing_opinion_days", 2)));
        // 简易程序备案超期（第51条：决定后7个工作日，节假日表精确计算）
        int recordDays = cfg.intVal("summary_record_days", 7);
        put.accept("summaryRecordOverdue", jdbc.queryForList("""
                select id, case_no, name, decided_at from case_file
                where procedure_type = 'SUMMARY' and decided_at is not null and summary_record_at is null
                order by decided_at""").stream()
                .filter(r -> cfg.plusWorkdays(((java.sql.Date) r.get("decided_at")).toLocalDate(),
                        recordDays).isBefore(java.time.LocalDate.now()))
                .toList());
        // 协查超期（第34条：15日内完成）
        put.accept("assistOverdue", jdbc.queryForList("""
                select a.id, a.case_id, cf.case_no, a.org, a.due_at
                from case_assist a join case_file cf on cf.id = a.case_id
                where a.replied_at is null and a.due_at < current_date order by a.due_at"""));
        // 责令改正逾期未报告（限期跟踪）
        put.accept("correctOverdue", jdbc.queryForList("""
                select d.id, d.case_id, cf.case_no, d.title, d.due_at
                from case_document d join case_file cf on cf.id = d.case_id
                where d.doc_type = 'ORDER_CORRECT' and d.due_at is not null and d.due_at < current_date
                  and cf.status not in ('CLOSED','TERMINATED') order by d.due_at"""));
        // 处罚决定公开超期（辽56条：作出决定7日内公开，参数化）
        put.accept("publishOverdue", jdbc.queryForList("""
                select cf.id, cf.case_no, cf.name, d.decided_at
                from case_decision d join case_file cf on cf.id = d.case_id
                where d.decision_type = 'PUNISH' and d.published = false
                  and d.decided_at + ?::int < current_date order by d.decided_at""",
                publishDays()));
        // ---- 执行阶段（第52-55条 / 行政强制法53-54条）----
        // 此前督办看板止于"送达"：决定书送达那天起系统就不再计时，缴款逾期、该催告、
        // 强执申请期将满全靠办案人自己记台账。三个参数与 ExecutionService 同源，不另起口径。
        java.time.LocalDate today = java.time.LocalDate.now();
        var executionRows = executionAlerts.rows(today);
        var overdue = executionRows.stream().filter(r -> r.get("pay_deadline") != null
                && cn.ybcase.bureau.service.ExecutionAlerts.date(r.get("pay_deadline")).isBefore(today)).toList();
        put.accept("paymentOverdue", overdue);
        put.accept("urgeLetterMissing", overdue.stream().filter(r -> r.get("urged_at") == null).toList());
        put.accept("courtEnforceExpiring", executionRows.stream().filter(r -> r.get("apply_deadline") != null
                && !cn.ybcase.bureau.service.ExecutionAlerts.date(r.get("apply_deadline")).isAfter(today.plusDays(30))).toList());
        put.accept("executionReliefUnconfirmed", executionRows.stream()
                .filter(r -> "UNCONFIRMED".equals(r.get("application_state"))).toList());
        // 封存到期（第31条）
        put.accept("sealExpiring", jdbc.queryForList("""
                select ev.id, ev.case_id, cf.case_no, ev.name, ev.seal_expire_at, ev.seal_extended
                from case_evidence ev join case_file cf on cf.id = ev.case_id
                where ev.sealed = true and ev.seal_expire_at < current_date + 5 order by ev.seal_expire_at"""));
        // 执法证 30 日内到期/已过期（第16条）：证只在立案/加人那一刻校验，到期前无人知晓，
        // 办案员上午还在办案、下午立案被 2055 硬拦才发现；顺带给出其名下在办件数（按证号关联办案人员）
        put.accept("enforcerCertExpiring", jdbc.queryForList("""
                select e.id, e.name, e.cert_no, e.dept, e.cert_expire_at,
                       (e.cert_expire_at < current_date) as expired,
                       (select count(*) from case_officer o join case_file cf on cf.id = o.case_id
                         where o.cert_no = e.cert_no and o.avoided = false
                           and cf.status not in ('CLOSED','TERMINATED')) as active_cases
                from enforcer e
                where e.enabled = true and e.cert_expire_at < current_date + 30
                order by e.cert_expire_at, e.id"""));
        // 节假日表缺年：不是案件预警，是"以上所有工作日口径的预警本身可能算错"的前提告警，
        // 故单独一个键（年份列表），前端在看板顶部挂红条
        m.put("holidayMissing", cfg.missingHolidayYears());
        // 前端据此提示"共 N 条，仅显示最紧急的前 N 条"
        m.put("truncated", truncated);
        m.put("topN", top);
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
