package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PenaltyReportService {
    private final JdbcTemplate jdbc;
    private final MaterialAccess materialAccess;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportExport export(Integer year, LocalDate start, LocalDate end, LocalDate cutoff) {
        var actor = materialAccess.requireCurrentReadRoles("LEADER", "LEGAL", "ADMIN");
        if (actor != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                "select account_category = 'BUREAU' from sys_user where id = ?", Boolean.class, actor.id())))
            throw new BizException(403, "全局行政处罚报表由局机关授权岗位导出");

        boolean annual = year != null;
        var today = LocalDate.now();
        if (!annual && (start != null || end != null || cutoff != null))
            throw new BizException(2100, "指定统计日期时须同时选择年度");
        var period = annual ? ReportExport.AnnualPeriod.of(year, start, end, cutoff, today)
                : new ReportExport.AnnualPeriod(LocalDate.of(2024, 1, 1), LocalDate.of(2026, 12, 31), today);
        List<Object[]> historical = new ArrayList<>();
        List<Object[]> current = new ArrayList<>();
        var budget = new ReportExport.Budget();
        var reconciliation = new Reconciliation();
        ReportExport.query(jdbc, """
                with period as (select cast(? as date) as starts, cast(? as date) as ends, cast(? as date) as cutoff,
                                       cast(? as boolean) as current_state, cast(? as boolean) as legacy)
                select d.decided_at, cf.filed_at, cf.party_name,
                       case when cf.party_type = 'INDIVIDUAL' then cf.party_credit_no
                            else cf.institution_code end as party_code,
                       case cf.institution_ownership when 'PUBLIC' then '公立' when 'SOCIAL' then '社会办' end as ownership,
                       case cf.institution_grade when 'TERTIARY' then '三级' when 'SECONDARY' then '二级'
                            when 'PRIMARY' then '一级' when 'UNGRADED' then '未定级' end as grade,
                       cf.confirmed_violation_start, cf.confirmed_violation_end,
                       cf.confirmed_violation_facts, cf.confirmed_violation_amount,
                       d.penalty_basis, d.content, d.other_measures,
                       d.fine_amount, d.recoup_amount, d.confiscate_amount,
                       coalesce(p.fine_paid, 0) as fine_paid, coalesce(p.recoup_paid, 0) as recoup_paid,
                       coalesce(p.late_fee_paid, 0) as late_fee_paid,
                       (t.current_state and cf.defer_approved) or exists (
                           select 1 from biz_approval a where a.case_id = cf.id and a.kind = 'DEFER'
                             and (t.legacy or (a.applied_at at time zone 'Asia/Shanghai')::date <= t.cutoff)
                       ) as defer_requested,
                       case when t.current_state and not cf.relief_pending and nullif(btrim(cf.relief_basis), '') is not null
                            then t.cutoff > cf.relief_deadline end as relief_expired,
                       exists (
                           select 1 from case_delivery del join case_document doc on doc.id = del.document_id
                           where del.case_id = cf.id and doc.case_id = cf.id and doc.doc_type = 'URGE_LETTER'
                                 and del.delivered_at is not null and (t.legacy or del.delivered_at <= t.cutoff)
                       ) as urged,
                       case when t.legacy then cf.court_enforce_applied
                            when cf.court_applied_at is not null then cf.court_applied_at <= t.cutoff
                            when not cf.court_enforce_applied then false
                            when t.current_state then cf.court_enforce_applied end as enforced,
                       d.publish_withdrawn and (d.withdrawn_at at time zone 'Asia/Shanghai')::date <= t.cutoff as withdrawn
                from case_decision d join case_file cf on cf.id = d.case_id cross join period t
                left join lateral (
                    select sum(amount) filter (where kind = 'FINE') as fine_paid,
                           sum(amount) filter (where kind = 'RECOUP') as recoup_paid,
                           sum(amount) filter (where kind = 'LATE_FEE') as late_fee_paid
                    from case_execution where case_id = cf.id and kind in ('FINE', 'RECOUP', 'LATE_FEE')
                         and paid_at <= t.cutoff
                ) p on true
                where d.decision_type = 'PUNISH' and cf.party_type in ('PROVIDER', 'INDIVIDUAL')
                  and d.decided_at between t.starts and t.ends
                order by d.decided_at, cf.id
                """, rs -> {
            LocalDate decided = rs.getObject("decided_at", LocalDate.class);
            boolean includeFiling = annual || decided.getYear() == 2026;
            List<Object[]> sheet = includeFiling ? current : historical;
            BigDecimal fine = rs.getBigDecimal("fine_amount");
            BigDecimal paid = rs.getBigDecimal("fine_paid");
            BigDecimal unpaid = fine.subtract(paid).max(BigDecimal.ZERO);
            BigDecimal recoup = rs.getBigDecimal("recoup_amount");
            BigDecimal recoupPaid = rs.getBigDecimal("recoup_paid");
            var partyCode = rs.getString("party_code");
            var facts = rs.getString("confirmed_violation_facts");
            Boolean expired = rs.getObject("relief_expired", Boolean.class);
            Boolean enforced = rs.getObject("enforced", Boolean.class);
            List<Object> cells = new ArrayList<>();
            cells.add(sheet.size() + 1);
            if (includeFiling) cells.add(rs.getObject("filed_at", LocalDate.class));
            cells.add(decided);
            LocalDate violationStart = rs.getObject("confirmed_violation_start", LocalDate.class);
            LocalDate violationEnd = rs.getObject("confirmed_violation_end", LocalDate.class);
            cells.add(violationStart == null || violationEnd == null ? null
                    : violationStart.equals(violationEnd) ? violationStart : violationStart + " 至 " + violationEnd);
            cells.add(partyCode);
            cells.add(rs.getString("party_name"));
            cells.add(rs.getString("ownership"));
            cells.add(rs.getString("grade"));
            cells.add(facts);
            cells.add(rs.getBigDecimal("confirmed_violation_amount"));
            cells.add(decisionText(rs.getString("penalty_basis"), rs.getString("content"), rs.getString("other_measures"),
                    rs.getBigDecimal("confiscate_amount")));
            cells.add(fine);
            cells.add(recoup.signum() > 0 ? yesNo(recoupPaid.compareTo(recoup) >= 0) : null);
            cells.add(fine.signum() > 0 ? yesNo(unpaid.signum() == 0) : null);
            boolean deferred = rs.getBoolean("defer_requested");
            cells.add(deferred ? "是（已缴 " + paid.toPlainString() + " 元）" : paid);
            cells.add(deferred ? "是（未缴 " + unpaid.toPlainString() + " 元）" : unpaid);
            cells.add(yesNo(expired));
            cells.add(yesNo(rs.getBoolean("urged")));
            cells.add(yesNo(enforced));
            budget.add(sheet, cells.toArray());
            if (annual) reconciliation.record(fine, paid, unpaid, recoup, recoupPaid,
                    rs.getBigDecimal("late_fee_paid"), partyCode, facts, rs.getBoolean("withdrawn"));
        }, period.start(), period.end(), period.cutoff(), period.cutoff().equals(today), !annual);

        byte[] workbook = annual
                ? PenaltyReportWorkbook.write("administrative-penalties-annual.xlsx",
                    List.of(new PenaltyReportWorkbook.Sheet(current, 'S', Map.of("A1", year + "年行政处罚情况统计表（内部核对）"),
                            year + "年")),
                    reconciliation.rows(period, budget.rowCount()))
                : PenaltyReportWorkbook.write(historical, current);
        return new ReportExport(workbook, new ReportExport.Audit(ReportExport.Kind.PENALTIES,
                period.start(), period.end(), period.cutoff(), budget.rowCount()));
    }

    private static String decisionText(String basis, String content, String measures, BigDecimal confiscated) {
        List<String> lines = new ArrayList<>();
        if (basis != null && !basis.isBlank()) lines.add("处罚依据：" + basis);
        if (content != null && !content.isBlank()) lines.add(content);
        if (measures != null && !measures.isBlank()) lines.add(measures);
        if (confiscated.signum() > 0) lines.add("没收违法所得 " + confiscated.toPlainString() + " 元");
        return String.join("\n", lines);
    }

    private static String yesNo(Boolean value) {
        return value == null ? null : value ? "是" : "否";
    }

    private static final class Reconciliation {
        private BigDecimal fine = BigDecimal.ZERO, finePaid = BigDecimal.ZERO, unpaid = BigDecimal.ZERO;
        private BigDecimal recoup = BigDecimal.ZERO, recoupPaid = BigDecimal.ZERO, lateFee = BigDecimal.ZERO;
        private int missingCode, missingFacts, withdrawn;

        void record(BigDecimal fine, BigDecimal finePaid, BigDecimal unpaid, BigDecimal recoup, BigDecimal recoupPaid,
                    BigDecimal lateFee, String partyCode, String facts, boolean withdrawn) {
            this.fine = this.fine.add(fine);
            this.finePaid = this.finePaid.add(finePaid);
            this.unpaid = this.unpaid.add(unpaid);
            this.recoup = this.recoup.add(recoup);
            this.recoupPaid = this.recoupPaid.add(recoupPaid);
            this.lateFee = this.lateFee.add(lateFee);
            if (partyCode == null || partyCode.isBlank()) missingCode++;
            if (facts == null || facts.isBlank()) missingFacts++;
            if (withdrawn) this.withdrawn++;
        }

        List<Object[]> rows(ReportExport.AnnualPeriod period, int count) {
            return List.of(
                    new Object[]{"模板说明", "内部核对版沿用既有年度字段，正式上报格式须由业务处室确认。"},
                    new Object[]{"决定日期区间", period.start() + " 至 " + period.end()},
                    new Object[]{"执行截至日", period.cutoff()},
                    new Object[]{"处罚记录数", count},
                    new Object[]{"罚款本金（元）", fine},
                    new Object[]{"已缴罚款本金（元）", finePaid},
                    new Object[]{"未缴罚款本金（元）", unpaid},
                    new Object[]{"已缴加处罚款（元）", lateFee},
                    new Object[]{"决定退回基金（元）", recoup},
                    new Object[]{"已退回基金（元）", recoupPaid},
                    new Object[]{"缺当事人编码记录数", missingCode},
                    new Object[]{"缺确认违法事实记录数", missingFacts},
                    new Object[]{"截至日已撤回公示记录数", withdrawn},
                    new Object[]{"撤回口径", "撤回的是对外公示，处罚决定仍列入台账。"},
                    new Object[]{"空值口径", "空值表示未登记、不适用或历史状态不可核实。历史救济状态缺少快照，留空。"},
                    new Object[]{"历史更正", "当事人、机构与决定字段按当前登记资料列示。"},
                    new Object[]{"金额口径", "仅计入截至日内登记的付款，罚款本金、加处罚款和退回基金分别核算。"});
        }
    }
}
