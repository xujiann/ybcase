package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DalianOrganizationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;

    @Test
    void directorySeparatesEnforcementAndAgencyAccounts() throws Exception {
        // Verify the PPT's seeded directory, independently of departments created by other committed HTTP tests.
        assertEquals(10, jdbc.queryForObject("select count(*) from sys_dept where org_kind='BUREAU' and parent_id=(select id from sys_dept where code='DL_YB_OFFICES')", Integer.class));
        assertEquals(12, jdbc.queryForObject("select count(*) from sys_dept where org_kind='DISTRICT' and parent_id in(select id from sys_dept where code in('DL_YB_BRANCHES','DL_YB_URBAN'))", Integer.class));
        assertEquals(3, jdbc.queryForObject("select count(*) from sys_dept where org_kind='AGENCY' and parent_id=(select id from sys_dept where code='DL_YB_CENTER')", Integer.class));
        assertEquals(7, jdbc.queryForObject("""
                select count(*) from sys_dept child join sys_dept parent on child.parent_id=parent.id
                where parent.code='DL_YB_BRANCHES'
                """, Integer.class));
        assertEquals(5, jdbc.queryForObject("""
                select count(*) from sys_dept child join sys_dept parent on child.parent_id=parent.id
                where parent.code='DL_YB_URBAN'
                """, Integer.class));

        SysUser admin = new SysUser();
        admin.setUsername("directory_admin_" + System.nanoTime());
        admin.setRealName("目录测试管理员");
        admin.setPassword("unused");
        admin.getRoles().add(roles.findByCode("ADMIN").orElseThrow());
        admin = users.saveAndFlush(admin);
        String token = jwt.issue(admin.getUsername(), admin.getTokenVersion());

        assertEquals(1106, create(token, "DISTRICT", "DL_YB_BRANCHES", List.of("HANDLER")).path("code").asInt());
        assertEquals(1106, create(token, "BUREAU", "DL_BR_ZHUANGHE", List.of("LEGAL")).path("code").asInt());
        assertEquals(1106, create(token, "DISTRICT", "DL_BR_ZHUANGHE", List.of("ADMIN")).path("code").asInt());
        assertEquals(1106, create(token, "AGENCY", "DL_CENTER_CHECK_1", List.of("AGENCY", "HANDLER")).path("code").asInt());
        assertEquals(1106, create(token, "AGENCY", "DL_OFF_FUND_1", List.of("AGENCY")).path("code").asInt());
        assertEquals(0, create(token, "DISTRICT", "DL_BR_ZHUANGHE", List.of("HANDLER")).path("code").asInt());
        assertEquals(0, create(token, "BUREAU", "DL_OFF_LEGAL", List.of("LEGAL")).path("code").asInt());
        var agency = create(token, "AGENCY", "DL_CENTER_CHECK_1", List.of("AGENCY"));
        assertEquals(0, agency.path("code").asInt());
        String username = agency.path("data").path("username").asText();
        var account = users.findByUsername(username).orElseThrow();
        jdbc.update("update sys_user set must_change_password=false where id=?", account.getId());
        String agencyToken = jwt.issue(username, account.getTokenVersion());
        for (String path : List.of("/api/system/depts", "/api/bureau/cases", "/api/bureau/stats/overview")) {
            assertEquals(403, mvc.perform(get(path).header("Authorization", "Bearer " + agencyToken))
                    .andReturn().getResponse().getStatus(), path);
        }
        var me = mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + agencyToken))
                .andReturn().getResponse();
        assertEquals("AGENCY", mapper.readTree(me.getContentAsString()).path("data").path("accountCategory").asText());
    }

    private JsonNode create(String token, String category, String department, List<String> roleCodes) throws Exception {
        Long deptId = jdbc.queryForObject("select id from sys_dept where code=?", Long.class, department);
        var response = mvc.perform(post("/api/system/users").header("Authorization", "Bearer " + token)
                .contentType("application/json").content(mapper.writeValueAsString(Map.of(
                        "username", "directory_" + System.nanoTime(), "realName", "测试人员", "password", "Test-Directory-123",
                        "deptId", deptId, "accountCategory", category, "roleCodes", roleCodes))))
                .andReturn().getResponse();
        return mapper.readTree(response.getContentAsString());
    }
}
