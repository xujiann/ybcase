package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.AuthUser;
import cn.ybcase.core.security.JwtService;
import cn.ybcase.core.web.SysUserController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Existing master-data writes must use current administrators and preserve whole-record consistency. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MasterDataConcurrencyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager tm;
    @Autowired SysUserController userController;

    record Actor(long id,String name,String token,int version) {}
    record Operation(Supplier<MockHttpServletRequestBuilder> request,Object body,Supplier<Object> snapshot) {}
    @BeforeEach void isolated() {
        assertTrue(jdbc.queryForObject("select current_database()",String.class).startsWith("ybcase_"),"Original database is forbidden");
        SecurityContextHolder.clearContext();
    }
    @AfterEach void logout() {SecurityContextHolder.clearContext();}
    String unique() {return UUID.randomUUID().toString().replace("-","");}
    Actor actor(String role) {
        SysUser user=new SysUser();user.setUsername("master_"+unique());user.setRealName("主数据回归");user.setPassword("not-a-login-password");
        if(role!=null)user.getRoles().add(roles.findByCode(role).orElseThrow());
        user=users.saveAndFlush(user);
        return new Actor(user.getId(),user.getUsername(),jwt.issue(user.getUsername(),user.getTokenVersion()),user.getTokenVersion());
    }
    Map<String,Object> deptBody(String code) {
        return new LinkedHashMap<>(Map.of("name","科室原名称","code",code,"type","ADMIN","sortNo",1));
    }
    long dept(String code) {
        return jdbc.queryForObject("insert into sys_dept(name,code,type,sort_no) values ('科室原名称',?,'ADMIN',1) returning id",Long.class,code);
    }
    long role() {
        String suffix=unique();
        return jdbc.queryForObject("insert into sys_role(name,code) values (?,?) returning id",Long.class,"回归角色"+suffix,"M_"+suffix.substring(0,20));
    }
    long menu(int offset) {return jdbc.queryForObject("select id from sys_menu order by id offset ? limit 1",Long.class,offset);}
    List<Map<String,Object>> row(String table,String column,Object value) {
        return jdbc.queryForList("select * from "+table+" where "+column+"=? order by 1",value);
    }
    Map<String,Object> userBody(Actor target) {
        return new LinkedHashMap<>(Map.of("username",target.name(),"realName","更新后姓名","roleCodes",List.of("HANDLER")));
    }
    JsonNode call(Actor actor,MockHttpServletRequestBuilder request,Object body) throws Exception {
        request.header("Authorization","Bearer "+actor.token());
        if(body!=null)request.contentType("application/json").content(mapper.writeValueAsString(body));
        var response=mvc.perform(request).andReturn().getResponse();
        assertTrue(response.getStatus()<500,response.getContentAsString());
        return mapper.readTree(response.getContentAsString());
    }
    int code(Actor actor,Operation operation) throws Exception {return call(actor,operation.request().get(),operation.body()).path("code").asInt();}
    Operation operation(String name) {
        String marker=unique();
        return switch(name) {
            case "dept-create" -> new Operation(()->post("/api/system/depts"),deptBody(marker),()->row("sys_dept","code",marker));
            case "dept-update" -> {
                long id=dept(marker);var body=deptBody(marker);body.put("name","更新后科室");
                yield new Operation(()->put("/api/system/depts/{id}",id),body,()->row("sys_dept","id",id));
            }
            case "dept-enabled" -> {
                long id=dept(marker);
                yield new Operation(()->put("/api/system/depts/{id}/enabled",id).param("enabled","false"),null,()->row("sys_dept","id",id));
            }
            case "config" -> {
                jdbc.update("insert into sys_config(cfg_key,cfg_value) values (?,'old')",marker);
                yield new Operation(()->put("/api/config/{key}",marker).param("value","new"),null,
                        ()->List.of(row("sys_config","cfg_key",marker),row("sys_config_history","cfg_key",marker)));
            }
            case "grant" -> {
                long id=role();jdbc.update("insert into sys_role_menu(role_id,menu_id) values (?,?)",id,menu(0));
                yield new Operation(()->put("/api/system/roles/{id}/menus",id),Map.of("menuIds",List.of(menu(1))),()->row("sys_role_menu","role_id",id));
            }
            case "user-create" -> {
                String username="create_"+marker;
                yield new Operation(()->post("/api/system/users"),Map.of("username",username,"realName","新用户","password","Master2026!","roleCodes",List.of("HANDLER")),
                        ()->row("sys_user","username",username));
            }
            case "user-update", "user-enabled", "user-unlock" -> {
                Actor target=actor("HANDLER");
                if(name.equals("user-unlock"))jdbc.update("update sys_user set failed_attempts=5,locked_until=now()+interval '1 hour' where id=?",target.id());
                Supplier<MockHttpServletRequestBuilder> request=name.equals("user-update")?()->put("/api/system/users/{id}",target.id()):
                        name.equals("user-enabled")?()->put("/api/system/users/{id}/enabled",target.id()).param("enabled","false"):
                                ()->put("/api/system/users/{id}/unlock",target.id());
                yield new Operation(request,name.equals("user-update")?userBody(target):null,
                        ()->List.of(row("sys_user","id",target.id()),row("sys_user_role","user_id",target.id())));
            }
            default -> throw new IllegalArgumentException(name);
        };
    }
    void awaitBlocked(int blocker) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline) {
            if(jdbc.queryForObject("select count(*) from pg_locks where not granted and ?=any(pg_blocking_pids(pid))",Integer.class,blocker)>0)return;
            Thread.sleep(25);
        }
        fail("Authenticated request never waited for the held database lock");
    }
    int whileActorChanges(Actor actor,Operation operation,Runnable change) throws Exception {
        var pool=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<Integer>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status->{
                jdbc.queryForList("select id from sys_user where id=? for update",actor.id());
                int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.set(pool.submit(()->code(actor,operation)));
                try {awaitBlocked(blocker);}catch(Exception failure){throw new RuntimeException(failure);}
                change.run();
            });
            return pending.get().get(20,TimeUnit.SECONDS);
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
    void rejection(String name,String change) throws Exception {
        Actor admin=actor("ADMIN");Operation operation=operation(name);Object before=operation.snapshot().get();
        assertEquals(403,whileActorChanges(admin,operation,()->{
            if(change.equals("role")) {
                jdbc.update("delete from sys_user_role where user_id=?",admin.id());
                jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code='HANDLER'",admin.id());
            }else if(change.equals("disabled"))jdbc.update("update sys_user set enabled=false where id=?",admin.id());
            else jdbc.update("update sys_user set token_version=token_version+1 where id=?",admin.id());
        }));
        assertEquals(before,operation.snapshot().get(),"Revoked administrator changed "+name);
    }
    @ParameterizedTest @ValueSource(strings={"dept-create","dept-update","dept-enabled","config","grant","user-create","user-update","user-enabled","user-unlock"})
    void administratorRoleRevokedWhileWaitingCannotWrite(String name) throws Exception {rejection(name,"role");}
    @ParameterizedTest @ValueSource(strings={"dept-create","dept-update","dept-enabled","config","grant","user-create","user-update","user-enabled","user-unlock"})
    void administratorDisabledWhileWaitingCannotWrite(String name) throws Exception {rejection(name,"disabled");}
    @ParameterizedTest @ValueSource(strings={"dept-create","dept-update","dept-enabled","config","grant","user-create","user-update","user-enabled","user-unlock"})
    void oldTokenWhileWaitingCannotWrite(String name) throws Exception {rejection(name,"token");}
    @ParameterizedTest @ValueSource(strings={"dept-create","dept-update","dept-enabled","config","grant","user-create","user-update","user-enabled","user-unlock"})
    void unchangedAdministratorStillPerformsExistingWrite(String name) throws Exception {
        var admin=actor("ADMIN");var operation=operation(name);Object before=operation.snapshot().get();
        assertEquals(0,code(admin,operation));assertNotEquals(before,operation.snapshot().get());
    }

    @Test void invalidDepartmentFieldsAndParentsNeverPartiallyUpdate() throws Exception {
        var admin=actor("ADMIN");String marker=unique();long id=dept(marker);long child=dept(unique());
        jdbc.update("update sys_dept set parent_id=? where id=?",id,child);
        var before=row("sys_dept","id",id);
        for(var entry:List.of(Map.entry("name"," "),Map.entry("name","😀".repeat(65)),Map.entry("code","a\0b"),
                Map.entry("code","x".repeat(33)),Map.entry("type","BOGUS"),Map.entry("parentId",(Object)id),
                Map.entry("parentId",(Object)child),Map.entry("parentId",(Object)Long.MAX_VALUE))) {
            var body=deptBody(marker);body.put("name","不得部分写入");body.put(entry.getKey(),entry.getValue());
            assertEquals(1202,call(admin,put("/api/system/depts/{id}",id),body).path("code").asInt());
            assertEquals(before,row("sys_dept","id",id));
        }
        var body=deptBody(marker);body.put("name",null);
        assertEquals(1202,call(admin,post("/api/system/depts"),body).path("code").asInt());
        assertEquals(before,row("sys_dept","id",id));
    }
    @Test void duplicateDepartmentCodeAndUnicodeLimitsPreserveExistingRows() throws Exception {
        var admin=actor("ADMIN");String code=unique();long id=dept(code);var before=row("sys_dept","id",id);
        assertEquals(1202,call(admin,post("/api/system/depts"),deptBody(code)).path("code").asInt());
        assertEquals(before,row("sys_dept","id",id));
        var body=deptBody("😀".repeat(24)+unique().substring(0,8));body.put("name","😀".repeat(64));
        assertEquals(0,call(admin,post("/api/system/depts"),body).path("code").asInt());
        assertEquals(body.get("name"),jdbc.queryForObject("select name from sys_dept where code=?",String.class,body.get("code")));
    }
    @Test void missingOrUnknownRoleMenusCannotSilentlyClearExistingGrant() throws Exception {
        var admin=actor("ADMIN");long id=role();jdbc.update("insert into sys_role_menu(role_id,menu_id) values (?,?)",id,menu(0));
        var before=row("sys_role_menu","role_id",id);
        var nullIds=new HashMap<String,Object>();nullIds.put("menuIds",null);
        var withNull=new ArrayList<Long>();withNull.add(menu(1));withNull.add(null);
        for(Object body:List.of(Map.of(),nullIds,Map.of("menuIds",List.of(menu(1),Long.MAX_VALUE)),
                Map.of("menuIds",List.of(-1)),Map.of("menuIds",withNull))) {
            assertEquals(1303,call(admin,put("/api/system/roles/{id}/menus",id),body).path("code").asInt());
            assertEquals(before,row("sys_role_menu","role_id",id));
        }
        assertEquals(0,call(admin,put("/api/system/roles/{id}/menus",id),Map.of("menuIds",List.of(menu(1),menu(1)))).path("code").asInt());
        assertEquals(List.of(menu(1)),jdbc.queryForList("select menu_id from sys_role_menu where role_id=?",Long.class,id));
        assertEquals(0,call(admin,put("/api/system/roles/{id}/menus",id),Map.of("menuIds",List.of())).path("code").asInt());
        assertTrue(row("sys_role_menu","role_id",id).isEmpty());
    }
    @Test void protectedAdministratorRoleCannotBeChanged() throws Exception {
        var admin=actor("ADMIN");long id=roles.findByCode("ADMIN").orElseThrow().getId();var before=row("sys_role_menu","role_id",id);
        assertEquals(1302,call(admin,put("/api/system/roles/{id}/menus",id),Map.of("menuIds",List.of())).path("code").asInt());
        assertEquals(before,row("sys_role_menu","role_id",id));
    }
    @Test void invalidConfigValueDoesNotChangeValueOrHistoryAndNoopIsStillNoop() throws Exception {
        var admin=actor("ADMIN");String key=unique();jdbc.update("insert into sys_config(cfg_key,cfg_value) values (?,'old')",key);
        var before=row("sys_config","cfg_key",key);
        for(String value:List.of("x".repeat(256),"a\0b","😀".repeat(256))) {
            assertEquals(1402,call(admin,put("/api/config/{key}",key).param("value",value),null).path("code").asInt());
            assertEquals(before,row("sys_config","cfg_key",key));assertTrue(row("sys_config_history","cfg_key",key).isEmpty());
        }
        assertEquals(0,call(admin,put("/api/config/{key}",key).param("value","old"),null).path("code").asInt());
        assertEquals(before,row("sys_config","cfg_key",key));assertTrue(row("sys_config_history","cfg_key",key).isEmpty());
        assertEquals(0,call(admin,put("/api/config/{key}",key).param("value","😀".repeat(255)),null).path("code").asInt());
        var history=jdbc.queryForMap("select old_value,new_value,changed_by from sys_config_history where cfg_key=?",key);
        assertEquals("old",history.get("old_value"));assertEquals("😀".repeat(255),history.get("new_value"));assertEquals(admin.name(),history.get("changed_by"));
    }

    @Test void departmentEditWaitingForDisablePreservesNewDisabledState() throws Exception {
        var admin=actor("ADMIN");String code=unique();long id=dept(code);var body=deptBody(code);body.put("name","编辑后科室");
        var pool=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<JsonNode>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status->{
                jdbc.queryForList("select id from sys_dept where id=? for update",id);int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.set(pool.submit(()->call(admin,put("/api/system/depts/{id}",id),body)));
                try{awaitBlocked(blocker);}catch(Exception failure){throw new RuntimeException(failure);}
                jdbc.update("update sys_dept set enabled=false where id=?",id);
            });
            assertEquals(0,pending.get().get(20,TimeUnit.SECONDS).path("code").asInt());
            var row=jdbc.queryForMap("select name,enabled from sys_dept where id=?",id);
            assertEquals("编辑后科室",row.get("name"));assertEquals(false,row.get("enabled"));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }

    @Test void concurrentRoleReplacementIsExactlyOneCompleteGrant() throws Exception {
        var a=actor("ADMIN");var b=actor("ADMIN");long id=role();
        var first=List.of(menu(0),menu(1));var second=List.of(menu(2),menu(3));
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            Future<Integer> one=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return call(a,put("/api/system/roles/{id}/menus",id),Map.of("menuIds",first)).path("code").asInt();});
            Future<Integer> two=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return call(b,put("/api/system/roles/{id}/menus",id),Map.of("menuIds",second)).path("code").asInt();});
            start.countDown();assertEquals(0,one.get(20,TimeUnit.SECONDS));assertEquals(0,two.get(20,TimeUnit.SECONDS));
            var actual=jdbc.queryForList("select menu_id from sys_role_menu where role_id=? order by menu_id",Long.class,id);
            assertTrue(actual.equals(first)||actual.equals(second),"Concurrent grants merged or lost only part of their selection: "+actual);
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }

    @Test void roleGrantWaitingForActorDoesNotBlockUserRoleForeignKey() throws Exception {
        var admin=actor("ADMIN");long id=role();var pool=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<JsonNode>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status->{
                jdbc.execute("set local lock_timeout='3s'");
                jdbc.queryForList("select id from sys_user where id=? for update",admin.id());
                int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.set(pool.submit(()->call(admin,put("/api/system/roles/{id}/menus",id),Map.of("menuIds",List.of(menu(0))))));
                try{awaitBlocked(blocker);}catch(Exception failure){throw new RuntimeException(failure);}
                // An account editor owns its user row before its role FK insert. The menu writer
                // must not hold an incompatible lock on that role while waiting for the account.
                jdbc.update("insert into sys_user_role(user_id,role_id) values (?,?)",admin.id(),id);
            });
            assertEquals(0,pending.get().get(20,TimeUnit.SECONDS).path("code").asInt());
            assertEquals(1,jdbc.queryForObject("select count(*) from sys_user_role where user_id=? and role_id=?",Integer.class,admin.id(),id));
            assertEquals(List.of(menu(0)),jdbc.queryForList("select menu_id from sys_role_menu where role_id=?",Long.class,id));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }

    @Test void concurrentDepartmentParentsCannotCreateTwoNodeCycle() throws Exception {
        var admin=actor("ADMIN");String aCode=unique(),bCode=unique();long a=dept(aCode),b=dept(bCode);
        var aBody=deptBody(aCode);aBody.put("parentId",b);var bBody=deptBody(bCode);bBody.put("parentId",a);
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return call(admin,put("/api/system/depts/{id}",a),aBody).path("code").asInt();});
            var two=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return call(admin,put("/api/system/depts/{id}",b),bBody).path("code").asInt();});
            start.countDown();var codes=new ArrayList<>(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS)));
            Collections.sort(codes);assertEquals(List.of(0,1202),codes);
            var parents=jdbc.queryForList("select parent_id from sys_dept where id in (?,?) order by id",a,b);
            assertEquals(1,parents.stream().filter(row->row.get("parent_id")!=null).count());
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }

    void context(Actor actor) {
        var principal=new AuthUser(actor.name(),"unused",true,actor.version(),actor.id(),false,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,"unused",principal.getAuthorities()));
    }
    @Test void oppositeAdministratorUpdatesUseOneJointUserLockOrder() throws Exception {
        var a=actor("ADMIN");var b=actor("ADMIN");var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->{context(a);try{assertTrue(start.await(10,TimeUnit.SECONDS));return userController.update(b.id(),new SysUserController.SaveUserRequest(b.name(),null,"B已更新",null,null,null,List.of("ADMIN"))).getCode();}finally{SecurityContextHolder.clearContext();}});
            var two=pool.submit(()->{context(b);try{assertTrue(start.await(10,TimeUnit.SECONDS));return userController.update(a.id(),new SysUserController.SaveUserRequest(a.name(),null,"A已更新",null,null,null,List.of("ADMIN"))).getCode();}finally{SecurityContextHolder.clearContext();}});
            start.countDown();assertEquals(0,one.get(20,TimeUnit.SECONDS));assertEquals(0,two.get(20,TimeUnit.SECONDS));
            assertEquals("A已更新",jdbc.queryForObject("select real_name from sys_user where id=?",String.class,a.id()));
            assertEquals("B已更新",jdbc.queryForObject("select real_name from sys_user where id=?",String.class,b.id()));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }

    @Test void unlockWaitingForDisablePreservesDisabledTokenAndIdentity() throws Exception {
        var admin=actor("ADMIN");var target=actor("HANDLER");
        jdbc.update("update sys_user set failed_attempts=5,locked_until=now()+interval '1 hour' where id=?",target.id());
        var pool=Executors.newSingleThreadExecutor();var pending=new AtomicReference<Future<JsonNode>>();
        try {
            new TransactionTemplate(tm).executeWithoutResult(status->{
                jdbc.queryForList("select id from sys_user where id=? for update",target.id());int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.set(pool.submit(()->call(admin,put("/api/system/users/{id}/unlock",target.id()),null)));
                try{awaitBlocked(blocker);}catch(Exception failure){throw new RuntimeException(failure);}
                jdbc.update("update sys_user set enabled=false,token_version=token_version+1,real_name='停用时的新姓名',row_version=row_version+1 where id=?",target.id());
            });
            assertEquals(0,pending.get().get(20,TimeUnit.SECONDS).path("code").asInt());
            var after=jdbc.queryForMap("select enabled,token_version,real_name,failed_attempts,locked_until from sys_user where id=?",target.id());
            assertEquals(false,after.get("enabled"));assertEquals(target.version()+1,((Number)after.get("token_version")).intValue());
            assertEquals("停用时的新姓名",after.get("real_name"));assertEquals(0,after.get("failed_attempts"));assertNull(after.get("locked_until"));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }

    @Test void concurrentAdministratorsCreatingSameUsernamePreserveOnlyTheWinner() throws Exception {
        var a=actor("ADMIN");var b=actor("ADMIN");String username="create_"+unique();
        var first=Map.of("username",username,"realName","第一个候选","password","Master2026!","roleCodes",List.of("HANDLER"));
        var second=Map.of("username",username,"realName","第二个候选","password","Master2026!","roleCodes",List.of("LEGAL"));
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var one=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return call(a,post("/api/system/users"),first).path("code").asInt();});
            var two=pool.submit(()->{assertTrue(start.await(10,TimeUnit.SECONDS));return call(b,post("/api/system/users"),second).path("code").asInt();});
            start.countDown();int oneCode=one.get(20,TimeUnit.SECONDS),twoCode=two.get(20,TimeUnit.SECONDS);
            var codes=new ArrayList<>(List.of(oneCode,twoCode));Collections.sort(codes);assertEquals(List.of(0,1101),codes);
            var rows=jdbc.queryForList("select id,real_name from sys_user where username=?",username);assertEquals(1,rows.size());
            var winner=oneCode==0?first:second;assertEquals(winner.get("realName"),rows.get(0).get("real_name"));
            assertEquals(winner.get("roleCodes"),jdbc.queryForList("select r.code from sys_role r join sys_user_role ur on ur.role_id=r.id where ur.user_id=? order by r.code",String.class,rows.get(0).get("id")));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
    }
}
