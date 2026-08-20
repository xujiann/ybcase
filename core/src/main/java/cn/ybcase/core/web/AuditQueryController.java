package cn.ybcase.core.web;

import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 审计日志查询——审计属平台横切能力，自 server 下沉 core */
@RestController
@RequiredArgsConstructor
public class AuditQueryController {

    private final JdbcTemplate jdbc;

    /** 审计日志查询（sensitive=true 只看本域敏感操作：决定/执行/卷宗/附件/审计导出/举报人/账号权限） */
    @GetMapping("/api/audit/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public R<List<Map<String, Object>>> auditLogs(@RequestParam(required = false) String username,
                                                  @RequestParam(defaultValue = "false") boolean sensitive) {
        // 医保执法本域的敏感操作（原为医院项目遗留路径 refund/abx-privileges/insurance-catalog，
        // 本系统无对应接口，敏感筛选实际近乎失效）
        String sensitiveWhere = """
                (path like '%/decide%' or path like '%/executions%' or path like '%/close%'
                 or path like '%/archive-full%' or path like '%/attachments%'
                 or path like '%/audit/export%' or path like '%/rewards%'
                 or path like '%/publish%' or path like '%/screenshot%'
                 or path like '%/system/users%' or path like '%/roles%' or path like '%/menus%')
                """;
        StringBuilder sql = new StringBuilder("select * from sys_audit_log where 1=1 ");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (username != null) {
            sql.append(" and username = ? ");
            args.add(username);
        }
        if (sensitive) sql.append(" and ").append(sensitiveWhere);
        sql.append(" order by id desc limit 200");
        return R.ok(jdbc.queryForList(sql.toString(), args.toArray()));
    }
}
