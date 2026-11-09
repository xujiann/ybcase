package cn.ybcase.bureau.web;

import cn.ybcase.core.common.R;
import static cn.ybcase.bureau.common.ReqValues.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 执法事项目录（2024年版清单）+ 法律依据库 + 节假日维护 */
@RestController
@RequestMapping("/api/bureau")
@RequiredArgsConstructor
public class BureauLawController {

    private final JdbcTemplate jdbc;
    private final cn.ybcase.bureau.service.BureauConfig config;
    private final cn.ybcase.bureau.service.MaterialAccess materialAccess;
    private final cn.ybcase.bureau.service.WorkdayDeadlinePreview deadlinePreview;
    private static final long HOLIDAY_WRITE_LOCK = 2026102801L;

    @GetMapping("/enforce-items")
    public R<List<Map<String, Object>>> enforceItems() {
        return R.ok(jdbc.queryForList("select * from law_enforce_item where enabled = true order by seq_no"));
    }

    @GetMapping("/law-basis")
    public R<List<Map<String, Object>>> lawBasis() {
        return R.ok(jdbc.queryForList("select * from law_basis order by id"));
    }

    @GetMapping("/holidays")
    public R<List<Map<String, Object>>> holidays(@RequestParam(required = false) Integer year) {
        if (year == null) return R.ok(jdbc.queryForList("select * from sys_holiday order by day"));
        return R.ok(jdbc.queryForList(
                "select * from sys_holiday where extract(year from day) = ? order by day", year));
    }

    @PostMapping("/holidays")
    @PreAuthorize("hasRole('ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> addHoliday(@RequestBody Map<String, Object> body) {
        Object[] row = holidayRow(body, "");
        lockHolidayWriter();
        jdbc.update("""
                insert into sys_holiday (day, kind, name) values (?, ?, ?)
                on conflict (day) do update set kind = excluded.kind, name = excluded.name""",
                row);
        invalidateHolidaysAfterCommit();
        return R.ok();
    }

    /** 覆盖度自检：缺放假记录的年份（当年恒查，次年 11 月起查）；节假日页红条与督办看板共用 */
    @GetMapping("/holidays/coverage")
    public R<Map<String, Object>> holidayCoverage() {
        return R.ok(Map.of("missingYears", config.missingHolidayYears()));
    }

    @GetMapping("/holidays/deadline-preview")
    @PreAuthorize("hasRole('ADMIN')")
    public R<cn.ybcase.bureau.service.WorkdayDeadlinePreview.Page> deadlinePreview(
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "25") int size) {
        return R.ok(deadlinePreview.preview(page, size));
    }

    /**
     * 按年批量录入：一年约 30 条放假 + 若干调休，逐日点选漏录概率高。
     * 每行 {day, kind, name}，全部校验通过才写库（一条格式错整批拒绝，避免录一半）。
     */
    @PostMapping("/holidays/batch")
    @PreAuthorize("hasRole('ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Map<String, Object>> batchHolidays(@RequestBody List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) throw new cn.ybcase.bureau.common.BizException(2100, "没有可导入的行");
        if (rows.size() > 400) throw new cn.ybcase.bureau.common.BizException(2100, "单次最多导入 400 行");
        List<Object[]> args = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            args.add(holidayRow(rows.get(i), "第 " + (i + 1) + " 行"));
        }
        lockHolidayWriter();
        jdbc.batchUpdate("""
                insert into sys_holiday (day, kind, name) values (?, ?, ?)
                on conflict (day) do update set kind = excluded.kind, name = excluded.name""", args);
        // 方法在事务内：提交前失效缓存会被并发的 plusWorkdays 用未提交的旧表重新装回（TTL 30s 内期限算错），
        // 与 SysConfigController.update 同一模式，改为提交后失效
        invalidateHolidaysAfterCommit();
        return R.ok(Map.of("count", args.size(), "missingYears", config.missingHolidayYears()));
    }

    @DeleteMapping("/holidays/{day}")
    @PreAuthorize("hasRole('ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> deleteHoliday(@PathVariable String day) {
        Object date = holidayRow(Map.of("day", day), "")[0];
        lockHolidayWriter();
        jdbc.update("delete from sys_holiday where day = ?", date);
        invalidateHolidaysAfterCommit();
        return R.ok();
    }

    private void lockHolidayWriter() {
        // All three maintenance paths share this gate, including inserts for absent dates.
        // Acquire it before the actor lock so queued requests recheck their live identity.
        jdbc.execute("select pg_advisory_xact_lock(" + HOLIDAY_WRITE_LOCK + ")");
        materialAccess.requireCurrentRoles("ADMIN");
    }

    private Object[] holidayRow(Map<String, Object> row, String label) {
        if (row == null || !(row.get("day") instanceof String value)
                || !value.trim().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new cn.ybcase.bureau.common.BizException(2100, label + "日期格式须为 yyyy-MM-dd");
        var day = reqDate(row, "day", label + "日期");
        if (day.getYear() < 1)
            throw new cn.ybcase.bureau.common.BizException(2100, label + "日期年份须大于0");
        Object kind = row.containsKey("kind") ? row.get("kind") : "HOLIDAY";
        if (!(kind instanceof String) || !("HOLIDAY".equals(kind) || "SHIFT_WORK".equals(kind)))
            throw new cn.ybcase.bureau.common.BizException(2100, label + "类型须为 HOLIDAY 或 SHIFT_WORK");
        Object name = row.get("name");
        if (name != null && (!(name instanceof String text) || text.indexOf('\0') >= 0
                || text.codePointCount(0, text.length()) > 64))
            throw new cn.ybcase.bureau.common.BizException(2100, label + "名称须为不超过64字的文本");
        return new Object[]{day, kind, name};
    }

    private void invalidateHolidaysAfterCommit() {
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { config.evictHolidays(); }
                });
    }
}
