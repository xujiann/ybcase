package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Authentication state must be read after the same user-row lock as administrator updates. */
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class AuthSessionConcurrencyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager tm;

    static final String OLD = "AuthOld2026!", NEW = "AuthNew2026!", RESET = "AdminReset2026!";
    record Actor(long id, String name, String token) {}
    record Outcome(int http, int code, JsonNode data) {}
    enum Revocation { DISABLED, PASSWORD_RESET, TOKEN_VERSION }
    enum PasswordEncoding { ASCII, UTF8 }

    @BeforeEach void isolate() {
        assertTrue(jdbc.queryForObject("select current_database()",String.class).startsWith("ybcase_"));
        SecurityContextHolder.clearContext();
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();}
    Actor actor(String role) {
        var user = new SysUser();user.setUsername("auth_"+UUID.randomUUID().toString().replace("-",""));
        user.setRealName("认证并发夹具");user.setPassword(passwords.encode(OLD));user.setEnabled(true);
        user.setPhone("TEST-ONLY");user.setMustChangePassword(false);
        if(role!=null)user.getRoles().add(roles.findByCode(role).orElseThrow());
        user=users.saveAndFlush(user);
        return new Actor(user.getId(),user.getUsername(),jwt.issue(user.getUsername(),user.getTokenVersion()));
    }
    Outcome request(MockHttpServletRequestBuilder request,Object body) throws Exception {
        if(body!=null)request.contentType("application/json").content(mapper.writeValueAsBytes(body));
        var response=mvc.perform(request).andReturn().getResponse();
        assertTrue(response.getStatus()<500,"Authentication returned HTTP 500");
        JsonNode result=response.getContentAsByteArray().length==0?mapper.createObjectNode():mapper.readTree(response.getContentAsString());
        return new Outcome(response.getStatus(),result.has("code")?result.path("code").asInt():response.getStatus(),result.path("data"));
    }
    Outcome login(Actor actor,String password) throws Exception {
        return request(post("/api/auth/login"),Map.of("username",actor.name(),"password",password));
    }
    Outcome change(Actor actor,String old,String next) throws Exception {
        return request(post("/api/auth/change-password").servletPath("/api/auth/change-password").header("Authorization","Bearer "+actor.token()),
                Map.of("oldPassword",old,"newPassword",next));
    }
    Map<String,Object> row(Actor actor) {return jdbc.queryForMap("select * from sys_user where id=?",actor.id());}
    void failed(Actor actor,int count) {jdbc.update("update sys_user set failed_attempts=? where id=?",count,actor.id());}
    void awaitBlocked(int blocker,int expected) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<until){
            int n=jdbc.queryForObject("""
                    with recursive blocked(pid) as (
                      select pid from pg_locks where not granted and ?=any(pg_blocking_pids(pid))
                      union select l.pid from pg_locks l join blocked b on b.pid=any(pg_blocking_pids(l.pid)) where not l.granted
                    ) select count(distinct pid) from blocked""",Integer.class,blocker);
            if(n>=expected)return;Thread.sleep(25);
        }
        fail("Authentication request did not reach the held user row");
    }
    Outcome race(Actor actor,Callable<Outcome> call,Runnable edit) throws Exception {
        var pool=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<Outcome>>();
        try{
            new TransactionTemplate(tm).executeWithoutResult(status->{
                jdbc.queryForList("select id from sys_user where id=? for update",actor.id());
                int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.set(pool.submit(call));
                try{awaitBlocked(blocker,1);}catch(Exception e){throw new RuntimeException(e);}
                edit.run();
            });
            return pending.get().get(25,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    void adminReset(Actor actor) {
        jdbc.update("update sys_user set password=?,password_updated_at=now(),must_change_password=true,token_version=token_version+1,row_version=row_version+1 where id=?",
                passwords.encode(RESET),actor.id());
    }
    void profileEdit(Actor actor) {
        jdbc.update("update sys_user set real_name='管理员更新后的姓名',phone='NEW-PHONE',row_version=row_version+1 where id=?",actor.id());
        jdbc.update("delete from sys_user_role where user_id=?",actor.id());
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='LEGAL'",actor.id());
    }
    @Test void fiveConcurrentFailuresAreCountedAndLockTheAccount() throws Exception {
        var actor=actor("HANDLER");var pool=Executors.newFixedThreadPool(5);var pending=new ArrayList<Future<Outcome>>();
        try{
            new TransactionTemplate(tm).executeWithoutResult(status->{
                jdbc.queryForList("select id from sys_user where id=? for update",actor.id());
                int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                for(int i=0;i<5;i++)pending.add(pool.submit(()->login(actor,"wrong-password")));
                try{awaitBlocked(blocker,5);}catch(Exception e){throw new RuntimeException(e);}
            });
            for(var request:pending)assertEquals(1001,request.get(25,TimeUnit.SECONDS).code());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
        var state=row(actor);assertEquals(0,state.get("failed_attempts"));
        assertTrue(((java.sql.Timestamp)state.get("locked_until")).toInstant().isAfter(Instant.now()));
        assertEquals(0,state.get("token_version"));assertTrue(passwords.matches(OLD,(String)state.get("password")));
        assertEquals(1002,login(actor,OLD).code());
    }
    @Test void concurrentPasswordChangesRejectTheSecondCapturedSession() throws Exception {
        var actor=actor("HANDLER");var pool=Executors.newFixedThreadPool(2);var pending=new ArrayList<Future<Outcome>>();
        try{
            new TransactionTemplate(tm).executeWithoutResult(status->{
                jdbc.queryForList("select id from sys_user where id=? for update",actor.id());
                int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.add(pool.submit(()->change(actor,OLD,NEW)));pending.add(pool.submit(()->change(actor,OLD,RESET)));
                try{awaitBlocked(blocker,2);}catch(Exception e){throw new RuntimeException(e);}
            });
            var codes=new ArrayList<Integer>();for(var request:pending)codes.add(request.get(25,TimeUnit.SECONDS).code());
            codes.sort(Integer::compareTo);assertEquals(List.of(0,403),codes);
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
        var state=row(actor);assertEquals(1,state.get("token_version"));assertEquals(Boolean.FALSE,state.get("must_change_password"));
        assertTrue(passwords.matches(NEW,(String)state.get("password"))||passwords.matches(RESET,(String)state.get("password")));
        assertEquals(401,request(get("/api/auth/me").header("Authorization","Bearer "+actor.token()),null).http());
    }
    @ParameterizedTest @EnumSource(Revocation.class)
    void passwordChangeRechecksTheCapturedSessionAfterRowWait(Revocation change) throws Exception {
        var actor=actor("HANDLER");var expected=new AtomicReference<Map<String,Object>>();
        var result=race(actor,()->change(actor,OLD,NEW),()->{
            switch(change){
                case DISABLED->jdbc.update("update sys_user set enabled=false,token_version=token_version+1,row_version=row_version+1 where id=?",actor.id());
                case PASSWORD_RESET->adminReset(actor);
                case TOKEN_VERSION->jdbc.update("update sys_user set token_version=token_version+1,row_version=row_version+1 where id=?",actor.id());
            }
            expected.set(row(actor));
        });
        assertEquals(403,result.code());assertEquals(expected.get(),row(actor));
    }
    @Test void successfulLoginAfterProfileWaitPreservesCurrentRolesAndIssuesUsableToken() throws Exception {
        var actor=actor("HANDLER");failed(actor,1);
        var outcome=race(actor,()->login(actor,OLD),()->profileEdit(actor));
        assertEquals(0,outcome.code());var state=row(actor);
        assertEquals("管理员更新后的姓名",state.get("real_name"));assertEquals("NEW-PHONE",state.get("phone"));
        assertEquals(0,state.get("failed_attempts"));assertEquals(0,state.get("token_version"));
        var me=request(get("/api/auth/me").header("Authorization","Bearer "+outcome.data().path("token").asText()),null);
        assertEquals(0,me.code());assertEquals("LEGAL",me.data().path("roles").get(0).asText());
    }
    @Test void loginUsingPasswordResetDuringRowWaitCountsTheNowInvalidCredential() throws Exception {
        var actor=actor("HANDLER");failed(actor,1);
        var result=race(actor,()->login(actor,OLD),()->adminReset(actor));
        assertEquals(1001,result.code());assertTrue(result.data().isNull());var state=row(actor);
        assertTrue(passwords.matches(RESET,(String)state.get("password")));assertEquals(1,state.get("token_version"));
        assertEquals(Boolean.TRUE,state.get("must_change_password"));assertEquals(2,state.get("failed_attempts"));
    }
    @Test void failedLoginWhileAnAdministratorResetsPasswordKeepsThatReset() throws Exception {
        var actor=actor("HANDLER");
        var result=race(actor,()->login(actor,"wrong-password"),()->adminReset(actor));
        assertEquals(1001,result.code());var state=row(actor);
        assertTrue(passwords.matches(RESET,(String)state.get("password")));assertEquals(1,state.get("token_version"));
        assertEquals(Boolean.TRUE,state.get("must_change_password"));assertEquals(1,state.get("failed_attempts"));
    }
    @Test void successfulLoginCannotClearAConcurrentDisableOrReturnAToken() throws Exception {
        var actor=actor("HANDLER");failed(actor,1);
        var result=race(actor,()->login(actor,OLD),()->jdbc.update("update sys_user set enabled=false,token_version=token_version+1,row_version=row_version+1 where id=?",actor.id()));
        assertEquals(1005,result.code());assertTrue(result.data().isNull());var state=row(actor);
        assertEquals(Boolean.FALSE,state.get("enabled"));assertEquals(1,state.get("token_version"));assertEquals(1,state.get("failed_attempts"));
    }
    @Test void selfServicePasswordChangeStillWorksAfterAnOrdinaryRoleAndProfileEdit() throws Exception {
        var actor=actor("HANDLER");var outcome=race(actor,()->change(actor,OLD,NEW),()->profileEdit(actor));
        assertEquals(0,outcome.code());var state=row(actor);
        assertEquals("管理员更新后的姓名",state.get("real_name"));assertEquals("NEW-PHONE",state.get("phone"));
        assertTrue(passwords.matches(NEW,(String)state.get("password")));assertEquals(1,state.get("token_version"));
        assertEquals(List.of("LEGAL"),jdbc.queryForList("select r.code from sys_role r join sys_user_role ur on ur.role_id=r.id where ur.user_id=?",String.class,actor.id()));
    }
    @Test void rolelessEnabledAccountRetainsSelfServicePasswordChange() throws Exception {
        var actor=actor(null);assertEquals(0,change(actor,OLD,NEW).code());assertEquals(1,row(actor).get("token_version"));
        var fresh=login(actor,NEW);assertEquals(0,fresh.code());
        assertEquals(0,request(get("/api/auth/me").header("Authorization","Bearer "+fresh.data().path("token").asText()),null).code());
    }
    @Test void currentPasswordChangeRevokesOldTokenAndClearsInitialPasswordFlag() throws Exception {
        var actor=actor("HANDLER");jdbc.update("update sys_user set must_change_password=true where id=?",actor.id());
        assertEquals(0,change(actor,OLD,NEW).code());var state=row(actor);
        assertEquals(Boolean.FALSE,state.get("must_change_password"));assertEquals(1,state.get("token_version"));
        assertEquals(401,request(get("/api/auth/me").header("Authorization","Bearer "+actor.token()),null).http());
        var fresh=login(actor,NEW);assertEquals(0,fresh.code());assertFalse(fresh.data().path("mustChangePassword").asBoolean());
        assertEquals(1,JwtService.tokenVersionOf(jwt.parse(fresh.data().path("token").asText())));
    }
    @Test void activeLockKeepsTheExisting1002ContractWithoutMutatingCounters() throws Exception {
        var actor=actor("HANDLER");jdbc.update("update sys_user set locked_until=now()+interval '5 minutes',failed_attempts=3 where id=?",actor.id());
        var before=row(actor);assertEquals(1002,login(actor,OLD).code());assertEquals(before,row(actor));
    }
    @Test void expiredLockAndSuccessfulLoginClearOnlyTheExistingLoginCounters() throws Exception {
        var actor=actor("HANDLER");jdbc.update("update sys_user set locked_until=?,failed_attempts=3 where id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(1)),actor.id());
        var before=row(actor);assertEquals(0,login(actor,OLD).code());var after=row(actor);
        assertEquals(0,after.get("failed_attempts"));assertNull(after.get("locked_until"));
        for(String key:List.of("password","token_version","must_change_password","real_name","phone","enabled"))assertEquals(before.get(key),after.get(key),key);
    }
    @Test void incorrectOldPasswordAndWeakNewPasswordLeaveAllAccountFieldsUnchanged() throws Exception {
        var actor=actor("HANDLER");var before=row(actor);
        assertEquals(1003,change(actor,"wrong-password",NEW).code());assertEquals(before,row(actor));
        assertEquals(1004,change(actor,OLD,"weak").code());assertEquals(before,row(actor));
    }
    @Test void disabledAndUnknownLoginsRetainBusinessErrorsAndDoNotCreateAccounts() throws Exception {
        var actor=actor("HANDLER");jdbc.update("update sys_user set enabled=false where id=?",actor.id());
        var before=row(actor);assertEquals(1005,login(actor,OLD).code());assertEquals(before,row(actor));
        int count=jdbc.queryForObject("select count(*) from sys_user",Integer.class);
        assertEquals(1001,request(post("/api/auth/login"),Map.of("username","missing_"+UUID.randomUUID(),"password",OLD)).code());
        assertEquals(count,jdbc.queryForObject("select count(*) from sys_user",Integer.class));
    }
    @Test void bcryptRuntimeBoundaryObservation() {
        var inputs=new LinkedHashMap<String,String>();
        inputs.put("ascii72","A1"+"a".repeat(70));inputs.put("ascii73","A1"+"a".repeat(71));
        inputs.put("utf72","Aa1"+"界".repeat(23));inputs.put("utf73","Aa1"+"界".repeat(23)+"x");
        var accepted=new LinkedHashMap<String,Boolean>();
        inputs.forEach((name,value)->{
            try{String hash=passwords.encode(value);accepted.put(name,passwords.matches(value,hash));}
            catch(IllegalArgumentException unsupported){accepted.put(name,false);}
        });
        assertTrue(accepted.get("ascii72"));assertTrue(accepted.get("utf72"));
        assertFalse(accepted.get("ascii73"));assertFalse(accepted.get("utf73"));
        System.out.println("AUTH_BCRYPT_BOUNDARY "+accepted);
    }
    String boundaryPassword(PasswordEncoding encoding,int bytes) {
        String value=encoding==PasswordEncoding.ASCII?"A1"+"x".repeat(bytes-2):"Aa1"+"界".repeat(23)+(bytes==73?"x":"");
        assertEquals(bytes,value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);return value;
    }
    Map<String,Object> accountBody(String username,String password) {
        return new LinkedHashMap<>(Map.of("username",username,"password",password,"realName","密码边界夹具","roleCodes",List.of("HANDLER")));
    }
    @ParameterizedTest @EnumSource(PasswordEncoding.class)
    void currentPasswordChangeAcceptsExactly72Utf8Bytes(PasswordEncoding encoding) throws Exception {
        var actor=actor("HANDLER");String value=boundaryPassword(encoding,72);
        assertEquals(0,change(actor,OLD,value).code());assertTrue(passwords.matches(value,(String)row(actor).get("password")));
        assertEquals(0,login(actor,value).code());
    }
    @ParameterizedTest @EnumSource(PasswordEncoding.class)
    void currentPasswordChangeRejects73Utf8BytesWithoutChangingAccount(PasswordEncoding encoding) throws Exception {
        var actor=actor("HANDLER");var before=row(actor);
        assertEquals(1004,change(actor,OLD,boundaryPassword(encoding,73)).code());assertEquals(before,row(actor));
    }
    @ParameterizedTest @EnumSource(PasswordEncoding.class)
    void administratorMaySetExactly72ByteInitialPassword(PasswordEncoding encoding) throws Exception {
        var admin=actor("ADMIN");String username="authnew_"+UUID.randomUUID().toString().replace("-",""),password=boundaryPassword(encoding,72);
        var result=request(post("/api/system/users").header("Authorization","Bearer "+admin.token()),accountBody(username,password));
        assertEquals(0,result.code());long id=result.data().path("id").asLong();
        var target=new Actor(id,username,jwt.issue(username,0));var state=row(target);
        assertTrue(passwords.matches(password,(String)state.get("password")));assertEquals(Boolean.TRUE,state.get("must_change_password"));
        assertEquals(0,login(target,password).code());
    }
    @ParameterizedTest @EnumSource(PasswordEncoding.class)
    void administratorCannotCreateAccountWithAnUnencodable73BytePassword(PasswordEncoding encoding) throws Exception {
        var admin=actor("ADMIN");String username="authnew_"+UUID.randomUUID().toString().replace("-","");
        var result=request(post("/api/system/users").header("Authorization","Bearer "+admin.token()),accountBody(username,boundaryPassword(encoding,73)));
        assertEquals(1102,result.code());assertEquals(0,jdbc.queryForObject("select count(*) from sys_user where username=?",Integer.class,username));
    }
    @ParameterizedTest @EnumSource(PasswordEncoding.class)
    void administratorMayResetToAnExactly72BytePassword(PasswordEncoding encoding) throws Exception {
        var admin=actor("ADMIN");var target=actor("HANDLER");String password=boundaryPassword(encoding,72);
        var result=request(put("/api/system/users/{id}",target.id()).header("Authorization","Bearer "+admin.token()),accountBody(target.name(),password));
        assertEquals(0,result.code());var state=row(target);
        assertTrue(passwords.matches(password,(String)state.get("password")));assertEquals(1,state.get("token_version"));
        assertEquals(Boolean.TRUE,state.get("must_change_password"));assertEquals(0,login(target,password).code());
    }
    @ParameterizedTest @EnumSource(PasswordEncoding.class)
    void administratorResetRejects73BytesBeforeApplyingOtherAccountFields(PasswordEncoding encoding) throws Exception {
        var admin=actor("ADMIN");var target=actor("HANDLER");var before=row(target);
        var body=accountBody(target.name(),boundaryPassword(encoding,73));body.put("realName","不应变更的姓名");body.put("roleCodes",List.of("LEGAL"));
        var result=request(put("/api/system/users/{id}",target.id()).header("Authorization","Bearer "+admin.token()),body);
        assertEquals(1102,result.code());assertEquals(before,row(target));
        assertEquals(List.of("HANDLER"),jdbc.queryForList("select r.code from sys_role r join sys_user_role ur on ur.role_id=r.id where ur.user_id=?",String.class,target.id()));
    }
}
