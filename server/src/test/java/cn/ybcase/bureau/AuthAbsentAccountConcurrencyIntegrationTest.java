package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Pause only after a real absent-account SELECT, then create/reset through normal HTTP. */
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class AuthAbsentAccountConcurrencyIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtService jwt;
    @MockitoSpyBean JdbcTemplate jdbc;
    static final String PASSWORD="AbsentRace2026!", OTHER="OtherInitial2026!";
    static final String ACCOUNT_LOCK_SQL="select id from sys_user where username=? for update";
    enum Creation { CREATED, RESET, DISABLED, WRONG_PASSWORD }
    record Outcome(int http,int code,JsonNode data) {}
    volatile Pause pause;
    static class Pause {
        final CountDownLatch reached=new CountDownLatch(1),resume=new CountDownLatch(1);
        final AtomicBoolean claimed=new AtomicBoolean();
    }
    @BeforeEach void isolate() {
        assertTrue(jdbc.queryForObject("select current_database()",String.class).startsWith("ybcase_authowner_"));
        SecurityContextHolder.clearContext();
        doAnswer(invocation->{
            Object result=invocation.callRealMethod();Pause current=pause;
            if(current!=null&&current.claimed.compareAndSet(false,true)){
                assertTrue(((List<?>)result).isEmpty(),"The first account lookup must really be absent");
                current.reached.countDown();
                assertTrue(current.resume.await(20,TimeUnit.SECONDS),"Absent-account request was not released");
            }
            return result;
        }).when(jdbc).queryForList(eq(ACCOUNT_LOCK_SQL),any(Object[].class));
    }
    @AfterEach void clear() {
        if(pause!=null)pause.resume.countDown();pause=null;SecurityContextHolder.clearContext();
    }
    Outcome call(MockHttpServletRequestBuilder request,Object body) throws Exception {
        if(body!=null)request.contentType("application/json").content(mapper.writeValueAsBytes(body));
        var response=mvc.perform(request).andReturn().getResponse();
        assertTrue(response.getStatus()<500,"Unexpected authentication HTTP failure");
        var value=mapper.readTree(response.getContentAsString());
        return new Outcome(response.getStatus(),value.path("code").asInt(),value.path("data"));
    }
    String adminToken() {
        var user=new SysUser();user.setUsername("absadmin_"+UUID.randomUUID().toString().replace("-",""));
        user.setRealName("新增账号交错管理员");user.setPassword(passwords.encode(PASSWORD));user.setMustChangePassword(false);
        user.getRoles().add(roles.findByCode("ADMIN").orElseThrow());user=users.saveAndFlush(user);
        return jwt.issue(user.getUsername(),user.getTokenVersion());
    }
    Outcome login(String username,String password) throws Exception {
        return call(post("/api/auth/login").servletPath("/api/auth/login"),Map.of("username",username,"password",password));
    }
    Map<String,Object> account(String username,String password) {
        return Map.of("username",username,"password",password,"realName","新增账号竞态夹具","roleCodes",List.of("HANDLER"));
    }
    @ParameterizedTest @EnumSource(Creation.class)
    void initiallyAbsentLoginCannotAuthenticateAnUnlockedAccountCreatedDuringTheRequest(Creation creation) throws Exception {
        String admin=adminToken(),username="absrace_"+UUID.randomUUID().toString().replace("-","");
        assertEquals(0,jdbc.queryForObject("select count(*) from sys_user where username=?",Integer.class,username));
        var pool=Executors.newSingleThreadExecutor();pause=new Pause();Future<Outcome> request=null;
        try{
            request=pool.submit(()->login(username,PASSWORD));
            assertTrue(pause.reached.await(20,TimeUnit.SECONDS),"Login did not observe the absent username");
            String initial=creation==Creation.RESET||creation==Creation.WRONG_PASSWORD?OTHER:PASSWORD;
            var created=call(post("/api/system/users").servletPath("/api/system/users").header("Authorization","Bearer "+admin),account(username,initial));
            assertEquals(0,created.code());long id=created.data().path("id").asLong();
            if(creation==Creation.RESET)
                assertEquals(0,call(put("/api/system/users/{id}",id).servletPath("/api/system/users/"+id).header("Authorization","Bearer "+admin),account(username,PASSWORD)).code());
            else if(creation==Creation.DISABLED)
                assertEquals(0,call(put("/api/system/users/{id}/enabled",id).servletPath("/api/system/users/"+id+"/enabled").param("enabled","false")
                        .header("Authorization","Bearer "+admin),null).code());
            var before=jdbc.queryForMap("select * from sys_user where id=?",id);
            pause.resume.countDown();var first=request.get(25,TimeUnit.SECONDS);
            assertEquals(1001,first.code(),"An initially missing username must not later issue an unlocked account token");
            assertTrue(first.data().isNull());
            assertEquals(before,jdbc.queryForMap("select * from sys_user where id=?",id),"The old request must not reset or increment the new account counters");
            var next=login(username,creation==Creation.WRONG_PASSWORD?OTHER:PASSWORD);
            if(creation==Creation.DISABLED){assertEquals(1005,next.code());assertTrue(next.data().isNull());}
            else{
                assertEquals(0,next.code());assertTrue(next.data().path("mustChangePassword").asBoolean());
                int expectedVersion=creation==Creation.RESET?1:0;
                assertEquals(expectedVersion,JwtService.tokenVersionOf(jwt.parse(next.data().path("token").asText())));
                var denied=call(get("/api/system/depts").servletPath("/api/system/depts")
                        .header("Authorization","Bearer "+next.data().path("token").asText()),null);
                assertEquals(1006,denied.code(),"The subsequent new-account session must still require its first password change");
            }
        }finally{
            pause.resume.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));
        }
    }
}
