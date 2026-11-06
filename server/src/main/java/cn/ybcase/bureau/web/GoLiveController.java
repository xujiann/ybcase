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
    private final cn.ybcase.bureau.service.BusinessStatisticsService statistics;
    private final cn.ybcase.bureau.service.MaterialAccess materialAccess;

    /** 智能监控疑点批量导入为线索（source=MONITOR） */
    @PostMapping("/clues/import")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    public R<Map<String, Object>> importClues(@RequestBody List<Map<String, String>> rows,
                                              Authentication auth) {
        return R.ok(clueService.importMonitorForCurrentActor(rows, auth.getName()));
    }

    /** 统计上报（月报/年报口径）：按月立案/结案/罚没/追回/听证/移送 */
    @GetMapping("/stats/report")
    public R<Map<String, Object>> report(@RequestParam int year, Authentication auth) {
        return R.ok(statistics.report(year, auth.getName()));
    }

    // 委托执法档案（第8条）
    @GetMapping("/delegates")
    public R<List<Map<String, Object>>> delegates() {
        return R.ok(jdbc.queryForList("select * from delegate_org order by id desc"));
    }

    @PostMapping("/delegates")
    @PreAuthorize("hasRole('ADMIN') or hasRole('LEADER')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> addDelegate(@RequestBody Map<String, Object> b, Authentication auth) {
        // The request's captured authorities can precede a concurrent role/enable/password change.
        // Delegations have no case/source row, so lock the actor before validating and inserting.
        var actor = materialAccess.lockActor(auth.getName(), false);
        if (!actor.privileged()) throw new BizException(403, "当前岗位无权登记委托执法档案");
        String name = delegateText(b, "name", "受托组织", 128, true);
        String agreement = delegateText(b, "agreementNo", "委托书文号", 64, true);
        String scope = delegateText(b, "scope", "委托事项与权限", 512, true);
        String note = delegateText(b, "note", "备注", 255, false);
        LocalDate start = delegateDate(b, "startAt", "委托开始日期", true);
        LocalDate end = delegateDate(b, "endAt", "委托结束日期", true);
        LocalDate published = delegateDate(b, "publishedAt", "公布日期", false);
        if (end.isBefore(start)) throw new BizException(2068, "委托结束日期不能早于开始日期");
        jdbc.update("""
                insert into delegate_org (name, agreement_no, scope, start_at, end_at, published_at, note)
                values (?,?,?,?,?,?,?)""", name, agreement, scope, start, end, published, note);
        return R.ok();
    }

    private static String delegateText(Map<String, Object> body, String key, String label, int max, boolean required) {
        Object value = body == null ? null : body.get(key);
        if (value == null && !required) return null;
        if (!(value instanceof String text) || (required && text.isBlank()))
            throw new BizException(2068, label + "须为" + (required ? "非空" : "") + "文本");
        if (text.indexOf('\0') >= 0 || text.codePointCount(0, text.length()) > max)
            throw new BizException(2068, label + "含无效字符或超过" + max + "字限制");
        return text;
    }

    private static LocalDate delegateDate(Map<String, Object> body, String key, String label, boolean required) {
        Object value = body == null ? null : body.get(key);
        if (value == null || "".equals(value)) {
            if (required) throw new BizException(2068, label + "必填");
            return null;
        }
        if (value instanceof String text && text.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
            try {
                LocalDate date = LocalDate.parse(text);
                if (date.getYear() > 0) return date;
            } catch (java.time.format.DateTimeParseException ignored) { }
        }
        throw new BizException(2068, label + "须为有效日期（yyyy-MM-dd）");
    }

    /** 集体讨论记录签字确认（局令44条） */
    @PostMapping("/cases/{caseId}/meetings/{meetingId}/sign")
    @PreAuthorize("hasAnyRole('HANDLER','LEADER','ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> signMeeting(@PathVariable Long caseId, @PathVariable Long meetingId,
                               @RequestBody Map<String, Object> body) {
        cn.ybcase.bureau.service.CaseGuards.lockWritable(jdbc, caseId);
        materialAccess.requireCurrentCaseWriter(caseId);
        Object value = body == null ? null : body.get("signNames");
        if (!(value instanceof String names) || names.isBlank())
            throw new BizException(2069, "须记录参加讨论人员签字确认名单");
        if (names.indexOf('\0') >= 0 || names.codePointCount(0, names.length()) > 255)
            throw new BizException(2069, "签字确认名单含无效字符或超过255字限制");
        int n = jdbc.update("""
                update case_meeting set sign_confirmed = true, sign_names = ?
                where id = ? and case_id = ? and sign_confirmed = false""", names, meetingId, caseId);
        if (n == 0) throw new BizException(2069, "讨论记录不存在或已完成签字确认");
        return R.ok();
    }
}
