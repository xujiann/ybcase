package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service @RequiredArgsConstructor
public class BusinessStatisticsService {
    private static final String EFFECTIVE_DECISIONS = "case_effective_decisions((current_timestamp at time zone 'Asia/Shanghai')::date,current_timestamp)";
    private static final String EFFECTIVE_EXECUTIONS = "case_effective_executions((current_timestamp at time zone 'Asia/Shanghai')::date,current_timestamp)";
    private final JdbcTemplate jdbc;
    private final SourceAttachmentAccess access;
    private final MaterialAccess materialAccess;
    static final List<String> SUBMISSION_COLUMNS = List.of("月份", "立案数", "结案数", "决定罚款", "决定追回", "实际收缴");
    static final String SUBMISSION_RULE = "MONTHLY_BUSINESS_CUTOFF_V1";
    record MonthlySnapshot(ReportExport report, String scope) {}
    private String label(String user) {
        if (materialAccess.departmentScope(user).district()) return "当前区市县机构可见业务统计";
        return access.hasRole(user,"LEGAL","LEADER","ADMIN") ? "全局业务统计" : "当前可见业务统计（线索为共享台账）";
    }
    private SourceAttachmentAccess.ReadPredicate clueStatisticsScope(String user) {
        return materialAccess.departmentScope(user).district()
                ? access.clueReadPredicate("case_clue.id", user)
                : new SourceAttachmentAccess.ReadPredicate("true", List.of());
    }
    private List<Map<String,Object>> rows(String sql, SourceAttachmentAccess.ReadPredicate scope, Object... extra) {
        var args=new ArrayList<Object>(scope.args());args.addAll(List.of(extra));
        return jdbc.queryForList(sql,args.toArray());
    }
    private Object scalar(String sql, SourceAttachmentAccess.ReadPredicate scope, Object... extra) {
        return rows(sql,scope,extra).get(0).values().iterator().next();
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> overview(String user) {
        var scope=access.caseReadPredicate("cf.id",user);String cases=" from case_file cf where "+scope.sql();
        var out=new LinkedHashMap<String,Object>();out.put("scopeLabel",label(user));
        var clues = clueStatisticsScope(user);
        for(var item:Map.of("clueTotal","", "cluePending"," and status='PENDING'", "clueFiled"," and status='FILED'").entrySet())
            out.put(item.getKey(), scalar("select count(*) from case_clue where " + clues.sql() + item.getValue(), clues));
        out.put("caseTotal",scalar("select count(*)"+cases,scope));
        out.put("caseByStatus",rows("select cf.status,count(*) as cnt"+cases+" group by cf.status order by cf.status",scope));
        String decisions=" from "+EFFECTIVE_DECISIONS+" d join case_file cf on cf.id=d.case_id where "+scope.sql();
        for(var item:Map.of("fineDecided","fine_amount","recoupDecided","recoup_amount","confiscateDecided","confiscate_amount").entrySet())
            out.put(item.getKey(),scalar("select coalesce(sum(d."+item.getValue()+"),0)"+decisions,scope));
        String executions=" from "+EFFECTIVE_EXECUTIONS+" e join case_file cf on cf.id=e.case_id where "+scope.sql();
        out.put("fineCollected",scalar("select coalesce(sum(e.amount),0)"+executions+" and e.kind in ('FINE','LATE_FEE','CONFISCATE')",scope));
        out.put("recoupCollected",scalar("select coalesce(sum(e.amount),0)"+executions+" and e.kind='RECOUP'",scope));
        out.put("byPartyType",rows("select cf.party_type,count(*) as cnt"+cases+" group by cf.party_type order by cnt desc,cf.party_type",scope));
        out.put("byCause",rows("select cc.category,count(*) as cnt from case_file cf join case_cause cc on cc.id=cf.cause_id where "+scope.sql()+" group by cc.category order by cnt desc,cc.category limit 10",scope));
        out.put("monthlyFiled",rows("select to_char(cf.filed_at,'YYYY-MM') as ym,count(*) as cnt"+cases+" and cf.filed_at>=current_date-interval '12 months' group by 1 order by 1",scope));
        return out;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> report(int year,String user) {
        if(year<1||year>9998)throw new BizException(2100,"统计年度须在1至9998年之间");
        var caseScope=access.caseReadPredicate("cf.id",user);
        String cte="with visible_cases as (select cf.* from case_file cf where "+caseScope.sql()+"),"
                +" visible_decisions as (select d.* from "+EFFECTIVE_DECISIONS+" d join visible_cases cf on cf.id=d.case_id),"
                +" visible_executions as (select e.* from "+EFFECTIVE_EXECUTIONS+" e join visible_cases cf on cf.id=e.case_id) ";
        String monthly="""
                select m as month,
                (select count(*) from visible_cases where extract(year from filed_at)=? and extract(month from filed_at)=m) as filed,
                (select count(*) from visible_cases where extract(year from closed_at)=? and extract(month from closed_at)=m) as closed,
                (select coalesce(sum(fine_amount),0) from visible_decisions where extract(year from decided_at)=? and extract(month from decided_at)=m) as fine_decided,
                (select coalesce(sum(recoup_amount),0) from visible_decisions where extract(year from decided_at)=? and extract(month from decided_at)=m) as recoup_decided,
                (select coalesce(sum(amount),0) from visible_executions where extract(year from paid_at)=? and extract(month from paid_at)=m) as collected
                from generate_series(1,12) m order by m
                """;
        var months=rows(cte+monthly,caseScope,year,year,year,year,year);
        var totals=new LinkedHashMap<String,Object>();
        var clues = clueStatisticsScope(user);
        totals.put("clue_total",scalar("select count(*) from case_clue where " + clues.sql() + " and extract(year from received_at)=?",clues,year));
        for(String date:List.of("filed","closed"))totals.put(date+"_total",scalar("select count(*) from case_file cf where "+caseScope.sql()+" and extract(year from cf."+date+"_at)=?",caseScope,year));
        totals.put("hearing_total",scalar("select count(*) from case_hearing h join case_file cf on cf.id=h.case_id where "+caseScope.sql()+" and extract(year from h.held_at)=?",caseScope,year));
        var transfer=access.transferReadPredicate("t",user);
        totals.put("transfer_total",scalar("select count(*) from case_transfer t where "+transfer.sql()+" and extract(year from t.sent_at)=?",transfer,year));
        var inspection=access.inspectionReadPredicate("i",user);
        totals.put("inspection_total",scalar("select count(*) from inspection i where "+inspection.sql()+" and extract(year from i.done_at)=?",inspection,year));
        var reward=access.clueReadPredicate("r.clue_id",user);
        totals.put("reward_paid",scalar("select coalesce(sum(r.amount),0) from reward r where "+reward.sql()+" and extract(year from r.paid_at)=?",reward,year));
        return Map.of("year",year,"monthly",months,"totals",totals,"scopeLabel",label(user));
    }

    MonthlySnapshot monthlySnapshot(YearMonth month, LocalDate cutoff, OffsetDateTime knownAt, String user) {
        var scope = access.caseReadPredicate("cf.id", user);
        var start = month.atDay(1);
        var end = month.atEndOfMonth();
        var values = rows("""
            with visible_cases as (select cf.* from case_file cf where %s),
            visible_decisions as (select d.* from case_effective_decisions(?,?) d
                join visible_cases cf on cf.id=d.case_id where d.decided_at between ? and ?),
            visible_executions as (select e.* from case_effective_executions(?,?) e
                join visible_cases cf on cf.id=e.case_id where e.paid_at between ? and ?)
            select (select count(*) from visible_cases where filed_at between ? and ?) filed,
                   (select count(*) from visible_cases where closed_at between ? and ?) closed,
                   (select coalesce(sum(fine_amount),0) from visible_decisions) fine_decided,
                   (select coalesce(sum(recoup_amount),0) from visible_decisions) recoup_decided,
                   (select coalesce(sum(amount),0) from visible_executions) collected
            """.formatted(scope.sql()), scope, cutoff, knownAt, start, end, cutoff, knownAt, start, end,
                start, end, start, end).getFirst();
        String scopeLabel = label(user);
        Object[] cells = {month.toString(), values.get("filed"), values.get("closed"), values.get("fine_decided"),
                values.get("recoup_decided"), values.get("collected")};
        // The row starts at line 4 after scope, generation facts and the header.
        String csv = "\ufeff数据范围," + scopeLabel + "\r\n"
                + "业务截至日," + cutoff + ",取数时点," + knownAt + ",口径版本," + SUBMISSION_RULE + "\r\n"
                + String.join(",", SUBMISSION_COLUMNS) + "\r\n"
                + java.util.Arrays.stream(cells).map(v -> v instanceof BigDecimal amount ? amount.toPlainString() : v.toString())
                        .collect(java.util.stream.Collectors.joining(",")) + "\r\n";
        var line = ReportExport.Line.of("MONTH", month.getYear()*100L + month.getMonthValue(), month.toString(), 0, 4, cells);
        return new MonthlySnapshot(new ReportExport(csv.getBytes(StandardCharsets.UTF_8),
                new ReportExport.Audit(ReportExport.Kind.MONTHLY_SUBMISSION, start, end, cutoff, 1), List.of(line)), scopeLabel);
    }
}
