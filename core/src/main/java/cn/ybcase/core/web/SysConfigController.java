package cn.ybcase.core.web;

import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/** 机构参数：公开配置供登录页/单据读取，修改仅管理员 */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class SysConfigController {

    private final JdbcTemplate jdbc;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final cn.ybcase.core.security.AdminWriteAccess adminWrites;

    /** 公开配置白名单：仅登录页所需的机构信息，其余参数一律须登录（防口径参数匿名泄露） */
    private static final java.util.Set<String> PUBLIC_KEYS =
            java.util.Set.of("org_name", "org_short", "contact_phone", "hospital_name", "hospital_short");

    @GetMapping("/public")
    public R<Map<String, String>> publicConfig() {
        var m = new LinkedHashMap<String, String>();
        jdbc.queryForList("select cfg_key, cfg_value from sys_config").forEach(row -> {
            String key = (String) row.get("cfg_key");
            if (PUBLIC_KEYS.contains(key)) m.put(key, (String) row.get("cfg_value"));
        });
        return R.ok(m);
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasRole('ADMIN')")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> update(@PathVariable String key, @RequestParam String value,
                          org.springframework.security.core.Authentication auth) {
        // 期限/阈值类参数一改即对全部在办案件生效，审计只记 method/path 不含 query 里的值：
        // 旧值→新值→操作人须专表留痕（同一事务），否则复议时"当时按 90 还是 60 天算"无从回答
        var old = jdbc.queryForList("select cfg_value from sys_config where cfg_key = ? for update", key);
        if (old.isEmpty()) return R.fail(1401, "配置项不存在");
        adminWrites.requireCurrentAdmin();
        if (value == null || value.indexOf('\0') >= 0 || value.codePointCount(0, value.length()) > 255)
            return R.fail(1402, "参数值不能含空字符且不能超过255字");
        String oldValue = (String) old.get(0).get("cfg_value");
        if (java.util.Objects.equals(oldValue, value)) return R.ok();   // 同值保存不算变更，不留空历史
        jdbc.update("update sys_config set cfg_value = ?, updated_at = now() where cfg_key = ?", value, key);
        jdbc.update("insert into sys_config_history (cfg_key, old_value, new_value, changed_by) values (?,?,?,?)",
                key, oldValue, value, auth.getName());
        // 参数缓存失效放在提交之后：方法已是事务，若在提交前失效，并发请求会把旧值重新装回缓存
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() {
                        events.publishEvent(new cn.ybcase.core.common.ConfigChangedEvent(key));
                    }
                });
        return R.ok();
    }

    /** 参数变更历史（最近 100 条，新在前） */
    @GetMapping("/{key}/history")
    @PreAuthorize("hasRole('ADMIN')")
    public R<java.util.List<Map<String, Object>>> history(@PathVariable String key) {
        return R.ok(jdbc.queryForList("""
                select id, cfg_key, old_value, new_value, changed_by, changed_at
                from sys_config_history where cfg_key = ? order by changed_at desc, id desc limit 100""", key));
    }
}
