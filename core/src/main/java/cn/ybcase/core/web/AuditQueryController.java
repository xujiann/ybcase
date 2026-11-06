package cn.ybcase.core.web;

import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 审计日志查询——审计属平台横切能力，自 server 下沉 core */
@RestController
@RequiredArgsConstructor
public class AuditQueryController {

    private final JdbcTemplate jdbc;
    private final cn.ybcase.core.security.AdminReadAccess adminReads;

    private static final int MAX_SIZE = 500;

    /**
     * 审计日志查询（sensitive=true 只看本域敏感操作：决定/执行/卷宗/附件/审计导出/举报人/账号权限）。
     * 不传 page/size 时保持旧契约（数组、最近 200 条，E2E 依赖）；传了任一分页参数则返回 {total,page,size,rows}。
     * from/to 为 yyyy-MM-dd 闭区间（按 created_at，to 取次日 0 点做 <）；path 模糊、requestId 精确。
     */
    @GetMapping("/api/audit/logs")
    @PreAuthorize("hasRole('ADMIN')")
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public R<Object> auditLogs(@RequestParam(required = false) String username,
                               @RequestParam(defaultValue = "false") boolean sensitive,
                               @RequestParam(required = false) String from,
                               @RequestParam(required = false) String to,
                               @RequestParam(required = false) String path,
                               @RequestParam(required = false) String requestId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size) {
        adminReads.requireCurrentAdmin();
        // 医保执法本域的敏感操作（原为医院项目遗留路径 refund/abx-privileges/insurance-catalog，
        // 本系统无对应接口，敏感筛选实际近乎失效）
        String sensitiveWhere = """
                (path like '%/decide%' or path like '%/executions%' or path like '%/close%'
                 or path like '%/archive-full%' or path like '%/attachments%'
                 or path like '%/audit/export%' or path like '%/rewards%'
                 or path like '%/publish%' or path like '%/screenshot%'
                 or path like '%/system/users%' or path like '%/roles%' or path like '%/menus%')
                """;
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> args = new ArrayList<>();
        if (username != null && !username.isBlank()) {
            where.append(" and username = ? ");
            args.add(username);
        }
        if (sensitive) where.append(" and ").append(sensitiveWhere);
        if (from != null && !from.isBlank()) {
            LocalDate d = parseDay(from);
            if (d == null) return R.fail(2139, "from 须为 yyyy-MM-dd");
            where.append(" and created_at >= ? ");
            args.add(d);
        }
        if (to != null && !to.isBlank()) {
            LocalDate d = parseDay(to);
            if (d == null) return R.fail(2139, "to 须为 yyyy-MM-dd");
            where.append(" and created_at < ? ");
            args.add(d.plusDays(1));   // 闭区间：to 当天整天都算
        }
        if (path != null && !path.isBlank()) {
            where.append(" and path ilike ? ");
            args.add("%" + path + "%");
        }
        if (requestId != null && !requestId.isBlank()) {
            where.append(" and request_id = ? ");
            args.add(requestId);
        }

        if (page == null && size == null) {
            // 旧契约：数组 + 最近 200 条
            return R.ok(jdbc.queryForList("select * from sys_audit_log" + where + " order by id desc limit 200",
                    args.toArray()));
        }
        int p = page == null || page < 1 ? 1 : page;
        int s = size == null || size < 1 ? 50 : Math.min(size, MAX_SIZE);
        Long total = jdbc.queryForObject("select count(*) from sys_audit_log" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) (p - 1) * s);
        var rows = jdbc.queryForList("select * from sys_audit_log" + where + " order by id desc limit ? offset ?",
                pageArgs.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total == null ? 0 : total);
        out.put("page", p);
        out.put("size", s);
        out.put("rows", rows);
        return R.ok(out);
    }

    private static LocalDate parseDay(String v) {
        if(v==null || !v.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return null;
        try {
            LocalDate date=LocalDate.parse(v);
            return date.getYear()>0?date:null;
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
