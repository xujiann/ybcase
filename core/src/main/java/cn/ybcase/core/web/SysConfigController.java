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
    public R<Void> update(@PathVariable String key, @RequestParam String value) {
        int n = jdbc.update("update sys_config set cfg_value = ?, updated_at = now() where cfg_key = ?", value, key);
        if (n == 0) return R.fail(1401, "配置项不存在");
        events.publishEvent(new cn.ybcase.core.common.ConfigChangedEvent(key));  // 参数缓存立即失效
        return R.ok();
    }
}
