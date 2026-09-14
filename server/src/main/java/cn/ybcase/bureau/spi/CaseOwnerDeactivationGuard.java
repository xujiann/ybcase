package cn.ybcase.bureau.spi;

import cn.ybcase.core.spi.UserDeactivationGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 停用守卫：承办人名下仍有在办案件（未办结/未终止）时拒绝停用，回带案号清单提示先移交 */
@Component
@RequiredArgsConstructor
public class CaseOwnerDeactivationGuard implements UserDeactivationGuard {

    private final JdbcTemplate jdbc;

    @Override
    public List<String> blockers(String username) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select case_no from case_file
                where owner_user = ? and status not in ('CLOSED','TERMINATED') order by id""", username);
        if (rows.isEmpty()) return List.of();
        List<String> nos = rows.stream().map(r -> (String) r.get("case_no")).toList();
        String shown = String.join("、", nos.subList(0, Math.min(5, nos.size()))) + (nos.size() > 5 ? "等" : "");
        return List.of("该账号名下尚有 " + nos.size() + " 件在办案件，请先移交：" + shown);
    }
}
