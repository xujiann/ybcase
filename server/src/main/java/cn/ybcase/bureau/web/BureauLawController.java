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

    @DeleteMapping("/holidays/{day}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> deleteHoliday(@PathVariable String day) {
        jdbc.update("delete from sys_holiday where day = ?", reqDate(Map.of("day", day), "day", "日期"));
        config.evictHolidays();
        return R.ok();
    }
}
