package cn.ybcase.bureau.web;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.ClueService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 五期：智能监控线索批量导入、统计上报、委托执法档案、集体讨论签名确认 */
@RestController
@RequestMapping("/api/bureau")
@RequiredArgsConstructor
public class GoLiveController {

    private final ClueService clueService;
    private final JdbcTemplate jdbc;

    /** 智能监控疑点批量导入为线索（source=MONITOR） */
    @PostMapping("/clues/import")
    public R<Map<String, Object>> importClues(@RequestBody List<Map<String, String>> rows,
                                              Authentication auth) {
        if (rows == null || rows.isEmpty()) throw new BizException(2067, "导入清单为空");
        if (rows.size() > 500) throw new BizException(2067, "单批最多导入500条");
        List<String> nos = new ArrayList<>();
        for (var r : rows) {
            var clue = clueService.create(new ClueService.ClueCreateReq(
                    "MONITOR", r.get("content"), r.get("suspectName"),
                    r.getOrDefault("suspectType", "PROVIDER"), LocalDate.now(),
                    r.get("handler")), auth.getName());
            nos.add(clue.getClueNo());
        }
        return R.ok(Map.of("imported", nos.size(), "clueNos", nos));
    }

    /** 统计上报（月报/年报口径）：按月立案/结案/罚没/追回/听证/移送 */
    @GetMapping("/stats/report")
    public R<Map<String, Object>> report(@RequestParam int year) {
        List<Map<String, Object>> monthly = jdbc.queryForList("""
                with months as (select generate_series(1,12) as m)
                select months.m as month,
                       (select count(*) from case_file cf where extract(year from cf.filed_at)=? and extract(month from cf.filed_at)=months.m) as filed,
                       (select count(*) from case_file cf where extract(year from cf.closed_at)=? and extract(month from cf.closed_at)=months.m) as closed,
                       (select coalesce(sum(d.fine_amount),0) from case_decision d where extract(year from d.decided_at)=? and extract(month from d.decided_at)=months.m) as fine_decided,
                       (select coalesce(sum(d.recoup_amount),0) from case_decision d where extract(year from d.decided_at)=? and extract(month from d.decided_at)=months.m) as recoup_decided,
                       (select coalesce(sum(e.amount),0) from case_execution e where extract(year from e.paid_at)=? and extract(month from e.paid_at)=months.m) as collected
                from months order by months.m""", year, year, year, year, year);
        Map<String, Object> totals = jdbc.queryForMap("""
                select
                  (select count(*) from case_clue where extract(year from received_at)=?) as clue_total,
                  (select count(*) from case_file where extract(year from filed_at)=?) as filed_total,
                  (select count(*) from case_file where extract(year from closed_at)=?) as closed_total,
                  (select count(*) from case_hearing where extract(year from held_at)=?) as hearing_total,
                  (select count(*) from case_transfer where extract(year from sent_at)=?) as transfer_total,
                  (select count(*) from inspection where extract(year from done_at)=?) as inspection_total,
                  (select coalesce(sum(amount),0) from reward where extract(year from paid_at)=?) as reward_paid""",
                year, year, year, year, year, year, year);
        return R.ok(Map.of("year", year, "monthly", monthly, "totals", totals));
    }

    // 委托执法档案（第8条）
    @GetMapping("/delegates")
    public R<List<Map<String, Object>>> delegates() {
        return R.ok(jdbc.queryForList("select * from delegate_org order by id desc"));
    }

    @PostMapping("/delegates")
    @PreAuthorize("hasRole('ADMIN') or hasRole('LEADER')")
    public R<Void> addDelegate(@RequestBody Map<String, String> b) {
        if (b.get("name") == null || b.get("agreementNo") == null || b.get("scope") == null)
            throw new BizException(2068, "受托组织/委托书文号/委托事项必填（第8条：委托书应载明事项权限期限）");
        jdbc.update("""
                insert into delegate_org (name, agreement_no, scope, start_at, end_at, published_at, note)
                values (?,?,?,?::date,?::date,?::date,?)""",
                b.get("name"), b.get("agreementNo"), b.get("scope"),
                b.get("startAt"), b.get("endAt"), b.get("publishedAt"), b.get("note"));
        return R.ok();
    }

    /** 集体讨论记录签字确认（局令44条） */
    @PostMapping("/cases/{caseId}/meetings/{meetingId}/sign")
    public R<Void> signMeeting(@PathVariable Long caseId, @PathVariable Long meetingId,
                               @RequestBody Map<String, String> body) {
        String names = body.get("signNames");
        if (names == null || names.isBlank()) throw new BizException(2069, "须记录参加讨论人员签字确认名单");
        int n = jdbc.update("""
                update case_meeting set sign_confirmed = true, sign_names = ?
                where id = ? and case_id = ?""", names, meetingId, caseId);
        if (n == 0) throw new BizException(2069, "讨论记录不存在");
        return R.ok();
    }
}
