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
    private static final String PENALTY_SOURCE = """
             from (select cast(? as date) as cutoff) effective_period
             cross join lateral case_effective_decisions(effective_period.cutoff) d join case_file cf on cf.id = d.case_id
             left join lateral case_effective_reporting_facts(effective_period.cutoff) rf on rf.case_id = cf.id
            """;
    private static final String PENALTY_POPULATION = """
            d.decision_type = 'PUNISH' and cf.party_type in ('PROVIDER', 'INDIVIDUAL')
            and d.decided_at between t.starts and t.ends
            """;
    private static final String PARTY_CODE = """
            case when cf.party_type = 'INDIVIDUAL' then rf.party_credit_no else rf.institution_code end
            """;
    private static final String PENALTY_BASIS = """
            case when coalesce(d.penalty_basis,'') ~ '[^[:space:]]' then d.penalty_basis else rf.penalty_basis end
            """;
    private static final String MISSING_PENALTIES = """
            with period as (select cast(? as date) as starts, cast(? as date) as ends),
            checked as (
                select cf.id, cf.case_no, cf.party_name, d.decided_at, array_remove(array[
                    case when coalesce((%s), '') !~ '[^[:space:]]' then
                        case when cf.party_type = 'PROVIDER' then '机构编码' else '参保人证件号码' end end,
                    case when cf.party_type = 'PROVIDER' and rf.institution_ownership is null then '机构性质' end,
                    case when cf.party_type = 'PROVIDER' and rf.institution_grade is null then '机构等级' end,
                    case when rf.confirmed_violation_start is null or rf.confirmed_violation_end is null then '违法发生期间' end,
                    case when coalesce(rf.confirmed_violation_facts, '') !~ '[^[:space:]]' then '认定违法事实' end,
                    case when rf.confirmed_violation_amount is null then '认定违法违规金额' end,
                    case when coalesce((%s), '') !~ '[^[:space:]]' then '处罚依据' end
                ], null) as missing_fields
                %s cross join period t where %s
            )
            """.formatted(PARTY_CODE, PENALTY_BASIS, PENALTY_SOURCE, PENALTY_POPULATION);
    private final JdbcTemplate jdbc;
    private final MaterialAccess materialAccess;

    public record MissingPenalty(String caseNo, String partyName, LocalDate decidedAt, List<String> missingFields) {}

    public record MissingPenalties(List<MissingPenalty> rows, long total, LocalDate start, LocalDate end, LocalDate cutoff) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, timeout = 12)
    public MissingPenalties missingFields(int year, LocalDate start, LocalDate end, LocalDate cutoff, int page, int size) {
        requireReportReader();
        var period = ReportExport.AnnualPeriod.of(year, start, end, cutoff, LocalDate.now());
        if (page < 1 || size < 1 || size > 100)
            throw new BizException(2100, "页码须大于零，每页须为1至100条");

        String missingOnly = " from checked where cardinality(missing_fields) > 0 ";
        long total = jdbc.queryForObject(MISSING_PENALTIES + "select count(*)" + missingOnly,
                Long.class, period.start(), period.end(), period.cutoff());
        long offset = (page - 1L) * size;
        List<MissingPenalty> rows = offset >= total ? List.of() : jdbc.query(
                MISSING_PENALTIES + "select case_no, party_name, decided_at, missing_fields" + missingOnly
                        + " order by decided_at, id limit ? offset ?",
                (rs, index) -> {
                    var fields = rs.getArray("missing_fields");
                    try {
                        return new MissingPenalty(rs.getString("case_no"), rs.getString("party_name"),
                                rs.getObject("decided_at", LocalDate.class), List.of((String[]) fields.getArray()));
                    } finally {
                        fields.free();
                    }
                }, period.start(), period.end(), period.cutoff(), size, offset);
        return new MissingPenalties(rows, total, period.start(), period.end(), period.cutoff());
    }

    private void requireReportReader() {
        var actor = materialAccess.requireCurrentReadRoles("LEADER", "LEGAL", "ADMIN");
        if (actor == null || !"BUREAU".equals(actor.accountCategory()))
            throw new BizException(403, "全局行政处罚报表由局机关授权岗位查看和导出");
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReportExport export(Integer year, LocalDate start, LocalDate end, LocalDate cutoff) {
        requireReportReader();
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
        List<ReportExport.Line> lines = new ArrayList<>();
        ReportExport.query(jdbc, """
                with period as (select cast(? as date) as starts, cast(? as date) as ends, cast(? as date) as cutoff,
                                       cast(? as boolean) as current_state, cast(? as boolean) as legacy)
                select cf.id as report_case_id, cf.case_no, d.decided_at, cf.filed_at, cf.party_name, coalesce(rf.has_approved_supplement, false) as supplemental,
                       %s as party_code,
                       case rf.institution_ownership when 'PUBLIC' then '公立' when 'SOCIAL' then '社会办' end as ownership,
                       case rf.institution_grade when 'TERTIARY' then '三级' when 'SECONDARY' then '二级'
                            when 'PRIMARY' then '一级' when 'UNGRADED' then '未定级' end as grade,
                       rf.confirmed_violation_start, rf.confirmed_violation_end,
                       rf.confirmed_violation_facts, rf.confirmed_violation_amount,
                       %s as penalty_basis, d.content, d.other_measures,
                       d.fine_amount, d.recoup_amount, d.confiscate_amount,
                       coalesce(p.fine_paid, 0) as fine_paid, coalesce(p.recoup_paid, 0) as recoup_paid,
                       coalesce(p.late_fee_paid, 0) as late_fee_paid,
                       coalesce(p.positive_collected,0) as positive_collected,
                       coalesce(p.reversed_refunded,0) as reversed_refunded,
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
                       case when exists (
                                select 1 from case_court_round cr join case_continuation_request cq on cq.id=cr.request_id
                                where cr.case_id=cf.id and cr.applied_at<=t.cutoff and cq.status='APPROVED'
                                  and cq.reviewed_at<=least(current_timestamp,statement_timestamp())
                            ) then true
                            when t.legacy then cf.court_enforce_applied
                            when cf.court_applied_at is not null then cf.court_applied_at <= t.cutoff
                            when not cf.court_enforce_applied then false
                            when t.current_state then cf.court_enforce_applied end as enforced,
                       d.publish_withdrawn and (d.withdrawn_at at time zone 'Asia/Shanghai')::date <= t.cutoff as withdrawn
                %s cross join period t
                left join lateral (
                    select sum(amount) filter (where kind = 'FINE') as fine_paid,
                           sum(amount) filter (where kind = 'RECOUP') as recoup_paid,
                           sum(amount) filter (where kind = 'LATE_FEE') as late_fee_paid,
                           sum(amount) filter (where amount>0) as positive_collected,
                           -sum(amount) filter (where amount<0) as reversed_refunded
                    from case_effective_executions(?) where case_id = cf.id and kind in ('FINE', 'RECOUP', 'LATE_FEE')
                         and paid_at <= t.cutoff
                ) p on true
                where %s
                order by d.decided_at, cf.id
                """.formatted(PARTY_CODE, PENALTY_BASIS, PENALTY_SOURCE, PENALTY_POPULATION), rs -> {
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
            Object[] row = cells.toArray();
            budget.add(sheet, row);
            lines.add(ReportExport.Line.of("CASE", rs.getLong("report_case_id"), rs.getString("case_no"),
                    annual || !includeFiling ? 0 : 1, sheet.size() + 3, row));
            if (annual) reconciliation.record(fine, paid, unpaid, recoup, recoupPaid,
                    rs.getBigDecimal("late_fee_paid"), rs.getBigDecimal("positive_collected"), rs.getBigDecimal("reversed_refunded"),
                    partyCode, facts, rs.getBoolean("withdrawn"), rs.getBoolean("supplemental"));
        }, period.start(), period.end(), period.cutoff(), period.cutoff().equals(today), !annual, period.cutoff(), period.cutoff());

        byte[] workbook = annual
                ? PenaltyReportWorkbook.write("administrative-penalties-annual.xlsx",
                    List.of(new PenaltyReportWorkbook.Sheet(current, 'S', Map.of("A1", year + "年行政处罚情况统计表（内部核对）"),
                            year + "年")),
                    reconciliation.rows(period, budget.rowCount()))
                : PenaltyReportWorkbook.write(historical, current);
        return new ReportExport(workbook, new ReportExport.Audit(ReportExport.Kind.PENALTIES,
                period.start(), period.end(), period.cutoff(), budget.rowCount()), lines);
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
        private BigDecimal positiveCollected = BigDecimal.ZERO, reversedRefunded = BigDecimal.ZERO;
        private int missingCode, missingFacts, withdrawn, supplemented;

        void record(BigDecimal fine, BigDecimal finePaid, BigDecimal unpaid, BigDecimal recoup, BigDecimal recoupPaid,
                    BigDecimal lateFee, BigDecimal positiveCollected, BigDecimal reversedRefunded,
                    String partyCode, String facts, boolean withdrawn, boolean supplemental) {
            this.fine = this.fine.add(fine);
            this.finePaid = this.finePaid.add(finePaid);
            this.unpaid = this.unpaid.add(unpaid);
            this.recoup = this.recoup.add(recoup);
            this.recoupPaid = this.recoupPaid.add(recoupPaid);
            this.lateFee = this.lateFee.add(lateFee);
            this.positiveCollected = this.positiveCollected.add(positiveCollected);
            this.reversedRefunded = this.reversedRefunded.add(reversedRefunded);
            if (partyCode == null || partyCode.isBlank()) missingCode++;
            if (facts == null || facts.isBlank()) missingFacts++;
            if (withdrawn) this.withdrawn++;
            if (supplemental) this.supplemented++;
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
                    new Object[]{"本表三类款项正向收缴合计（元）", positiveCollected},
                    new Object[]{"本表三类款项冲正／退付合计（元）", reversedRefunded},
                    new Object[]{"本表三类款项净入账合计（元）", positiveCollected.subtract(reversedRefunded)},
                    new Object[]{"决定退回基金（元）", recoup},
                    new Object[]{"已退回基金（元）", recoupPaid},
                    new Object[]{"缺当事人编码记录数", missingCode},
                    new Object[]{"缺确认违法事实记录数", missingFacts},
                    new Object[]{"截至日已撤回公示记录数", withdrawn},
                    new Object[]{"撤回口径", "撤回的是对外公示，处罚决定仍列入台账。"},
                    new Object[]{"空值口径", "空值表示未登记、不适用或历史状态不可核实。历史救济状态缺少快照，留空。"},
                    new Object[]{"历史更正", "当事人、机构与决定字段按当前登记资料列示。"},
                    new Object[]{"有已审核补录台账的案件数", supplemented},
                    new Object[]{"报表补录", "历史缺项使用已批准的报表专用补录值，来源及审核记录见报表补录台账。"},
                    new Object[]{"金额口径", "按业务日期与生成时已批准的信息取数。已缴金额为含已批准冲正、退付的净额，罚款本金、加处罚款与退回基金分别核算。"});
        }
    }
}
