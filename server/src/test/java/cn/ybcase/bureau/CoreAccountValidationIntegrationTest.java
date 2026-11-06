package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CoreAccountValidationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder passwords;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    static final String PASSWORD="Validation2026!";
    record Actor(long id,String name,String token) {}
    Actor actor(String role) {
        var u=new SysUser();u.setUsername("cv_"+UUID.randomUUID().toString().replace("-",""));u.setRealName("校验测试");u.setPassword(passwords.encode(PASSWORD));
        u.getRoles().add(roles.findByCode(role).orElseThrow());u=users.save(u);
        return new Actor(u.getId(),u.getUsername(),jwt.issue(u.getUsername(),u.getTokenVersion()));
    }
    Map<String,Object> valid() {
        var p=new LinkedHashMap<String,Object>();p.put("username","cv_"+UUID.randomUUID().toString().replace("-",""));p.put("realName","有效姓名");p.put("password",PASSWORD);p.put("roleCodes",List.of("HANDLER"));return p;
    }
    JsonNode call(MockHttpServletRequestBuilder request,Actor actor,Object body) throws Exception {
        request.header("Authorization","Bearer "+actor.token());
        if(body!=null)request.contentType("application/json").content(mapper.writeValueAsString(body));
        var response=mvc.perform(request).andReturn().getResponse();
        assertTrue(response.getStatus()<500,response.getContentAsString());return mapper.readTree(response.getContentAsString());
    }
    @Test void invalidAccountFieldsCannotCreateBlankOrDatabaseOverflowAccounts() throws Exception {
        var admin=actor("ADMIN");
        for(var bad:List.of(Map.entry("username","  "),Map.entry("username","x".repeat(65)),Map.entry("realName","  "),Map.entry("realName","x".repeat(65)),Map.entry("title","x".repeat(33)),Map.entry("phone","x".repeat(33)))) {
            var p=valid();p.put(bad.getKey(),bad.getValue());
            var response=call(post("/api/system/users"),admin,p);assertEquals(1106,response.path("code").asInt(),response.toString());
            assertTrue(users.findByUsername((String)p.get("username")).isEmpty());
        }
    }
    @Test void invalidRoleMustNotPartiallyChangeIdentityPasswordOrExistingRoles() throws Exception {
        var admin=actor("ADMIN");var target=actor("HANDLER");var before=users.findById(target.id()).orElseThrow();String hash=before.getPassword();int version=before.getTokenVersion();
        var p=valid();p.put("username",target.name());p.put("realName","不得写入");p.put("roleCodes",List.of("LEGAL","MISSING_ROLE_20261022"));p.put("password","Replacement2026!");
        assertEquals(1106,call(put("/api/system/users/{id}",target.id()),admin,p).path("code").asInt());
        var after=users.findById(target.id()).orElseThrow();assertEquals("校验测试",after.getRealName());assertEquals(hash,after.getPassword());assertEquals(version,after.getTokenVersion());
        assertEquals(Set.of("HANDLER"),after.getRoles().stream().map(r->r.getCode()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(0,call(get("/api/auth/me"),target,null).path("code").asInt(),"failed administration must not revoke the existing token");
        var create=valid();create.put("roleCodes",List.of("MISSING_ROLE_20261022"));assertEquals(1106,call(post("/api/system/users"),admin,create).path("code").asInt());assertTrue(users.findByUsername((String)create.get("username")).isEmpty());
    }
    @Test void nullPasswordInputIsBusinessFailureAndDoesNotModifyPasswordOrToken() throws Exception {
        var user=actor("HANDLER");String before=users.findById(user.id()).orElseThrow().getPassword();
        for(String body:List.of("{\"oldPassword\":\""+PASSWORD+"\",\"newPassword\":null}","{\"oldPassword\":null,\"newPassword\":\"Replacement2026!\"}")) {
            var response=mvc.perform(post("/api/auth/change-password").header("Authorization","Bearer "+user.token()).contentType("application/json").content(body)).andReturn().getResponse();
            assertTrue(response.getStatus()<500,response.getContentAsString());assertNotEquals(0,mapper.readTree(response.getContentAsString()).path("code").asInt());
        }
        assertEquals(before,users.findById(user.id()).orElseThrow().getPassword());assertEquals(0,call(get("/api/auth/me"),user,null).path("code").asInt());
    }
    @Test void managementBoundariesAndResponsesKeepCredentialFieldsPrivate() throws Exception {
        var handler=actor("HANDLER");var admin=actor("ADMIN");
        for(String path:List.of("/api/system/users","/api/system/roles","/api/system/roles/menus","/api/audit/logs","/api/config/case_view_scope/history"))
            assertEquals(403,call(get(path),handler,null).path("code").asInt(),path);
        for(var response:List.of(call(get("/api/system/users"),admin,null),call(get("/api/auth/me"),handler,null))) {
            assertEquals(0,response.path("code").asInt());String json=response.toString();
            for(String key:List.of("password","tokenVersion","failedAttempts","rowVersion"))assertFalse(json.contains("\""+key+"\":"),key);
        }
        var failure=mvc.perform(post("/api/auth/login").contentType("application/json")
            .content(mapper.writeValueAsString(Map.of("username",handler.name(),"password","definitely-wrong"))))
            .andReturn().getResponse();
        assertEquals(1001,mapper.readTree(failure.getContentAsString()).path("code").asInt());
        String requestId=failure.getHeader("X-Request-Id");assertNotNull(requestId);
        var audit=jdbc.queryForMap("select username,http_status from sys_audit_log where request_id=? and path='/api/auth/login'",requestId);
        assertEquals(handler.name(),audit.get("username"));assertEquals(1001,((Number)audit.get("http_status")).intValue());
    }
    @Test void validAccountCreationStillWorksWithInitialPasswordAndExactRoles() throws Exception {
        var admin=actor("ADMIN");var p=valid();p.put("roleCodes",List.of("HANDLER","LEGAL"));
        var response=call(post("/api/system/users"),admin,p);assertEquals(0,response.path("code").asInt(),response.toString());
        var created=users.findByUsername((String)p.get("username")).orElseThrow();assertTrue(passwords.matches(PASSWORD,created.getPassword()));assertTrue(created.getMustChangePassword());
        assertEquals(Set.of("HANDLER","LEGAL"),created.getRoles().stream().map(r->r.getCode()).collect(java.util.stream.Collectors.toSet()));
    }
}
