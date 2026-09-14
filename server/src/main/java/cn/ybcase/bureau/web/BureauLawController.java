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
    public R<Void> addHoliday(@RequestBody Map<String, String> body) {
        // 先解析成 LocalDate：交给 ?::date 由数据库解析，格式错会变成 500
        jdbc.update("""
                insert into sys_holiday (day, kind, name) values (?, ?, ?)
                on conflict (day) do update set kind = excluded.kind, name = excluded.name""",
                reqDate(body, "day", "日期"), body.getOrDefault("kind", "HOLIDAY"), body.get("name"));
        config.evictHolidays();  // 工作日推算缓存须立即反映新节假日
        return R.ok();
    }

    /** 覆盖度自检：缺放假记录的年份（当年恒查，次年 11 月起查）；节假日页红条与督办看板共用 */
    @GetMapping("/holidays/coverage")
    public R<Map<String, Object>> holidayCoverage() {
        return R.ok(Map.of("missingYears", config.missingHolidayYears()));
    }

    /**
     * 按年批量录入：一年约 30 条放假 + 若干调休，逐日点选漏录概率高。
     * 每行 {day, kind, name}，全部校验通过才写库（一条格式错整批拒绝，避免录一半）。
     */
    @PostMapping("/holidays/batch")
    @PreAuthorize("hasRole('ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Map<String, Object>> batchHolidays(@RequestBody List<Map<String, String>> rows) {
        if (rows == null || rows.isEmpty()) throw new cn.ybcase.bureau.common.BizException(2100, "没有可导入的行");
        if (rows.size() > 400) throw new cn.ybcase.bureau.common.BizException(2100, "单次最多导入 400 行");
        List<Object[]> args = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> r = rows.get(i);
            java.time.LocalDate day = reqDate(r, "day", "第 " + (i + 1) + " 行日期");
            String kind = r.getOrDefault("kind", "HOLIDAY");
            if (!"HOLIDAY".equals(kind) && !"SHIFT_WORK".equals(kind))
                throw new cn.ybcase.bureau.common.BizException(2100,
                        "第 " + (i + 1) + " 行类型无效：" + kind + "（须为 HOLIDAY 或 SHIFT_WORK）");
            String name = r.get("name");
            if (name != null && name.length() > 64)
                throw new cn.ybcase.bureau.common.BizException(2100, "第 " + (i + 1) + " 行名称超过 64 字");
            args.add(new Object[]{day, kind, name});
        }
        jdbc.batchUpdate("""
                insert into sys_holiday (day, kind, name) values (?, ?, ?)
                on conflict (day) do update set kind = excluded.kind, name = excluded.name""", args);
        // 方法在事务内：提交前失效缓存会被并发的 plusWorkdays 用未提交的旧表重新装回（TTL 30s 内期限算错），
        // 与 SysConfigController.update 同一模式，改为提交后失效
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { config.evictHolidays(); }
                });
        return R.ok(Map.of("count", args.size(), "missingYears", config.missingHolidayYears()));
    }

    @DeleteMapping("/holidays/{day}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> deleteHoliday(@PathVariable String day) {
        jdbc.update("delete from sys_holiday where day = ?", reqDate(Map.of("day", day), "day", "日期"));
        config.evictHolidays();
        return R.ok();
    }
}
