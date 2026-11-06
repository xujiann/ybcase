package cn.ybcase.bureau;

import org.springframework.jdbc.core.JdbcTemplate;

/** Database fixtures for service tests; production filing always requires a real enabled account. */
final class FixtureUsers {
    private FixtureUsers() {}
    static void ensure(JdbcTemplate jdbc, String username, String role) {
        jdbc.update("insert into sys_user(username,password,real_name) values(?,?,?) on conflict(username) do nothing",
                username, "unused-service-fixture", "服务测试 " + username);
        jdbc.update("insert into sys_user_role(user_id,role_id) select u.id,r.id from sys_user u cross join sys_role r"
                + " where u.username=? and r.code=? on conflict do nothing", username, role);
    }
}
