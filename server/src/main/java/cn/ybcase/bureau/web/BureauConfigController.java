package cn.ybcase.bureau.web;

import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 参数设置：全量参数（含说明）仅管理员可读；修改走 core 的 PUT /api/config/{key} */
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class BureauConfigController {

    private final JdbcTemplate jdbc;

    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public R<List<Map<String, Object>>> all() {
        return R.ok(jdbc.queryForList(
                "select cfg_key as key, cfg_value as value, remark from sys_config order by cfg_key"));
    }
}
