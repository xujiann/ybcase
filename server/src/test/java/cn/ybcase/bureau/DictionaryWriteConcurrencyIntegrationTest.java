package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class DictionaryWriteConcurrencyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired PlatformTransactionManager tm;
    record Actor(long id, String name, String token) {}
    record Mutation(String path, String method, long gate, String revoke) {}
    @BeforeEach void isolate() {
        assertTrue(jdbc.queryForObject("select current_database()",String.class)
                .matches("ybcase_(?:master|authowner)_(?:test|final)_20261028_[a-f0-9]{8}"),
                "Only an explicitly named disposable test database is permitted");
        SecurityContextHolder.clearContext();
    }
    Actor admin() {
        var user=new SysUser();user.setUsername("dict_"+UUID.randomUUID().toString().replace("-",""));
        user.setRealName("字典回归");user.setPassword("unused-jwt-fixture");user.setEnabled(true);
        user.getRoles().add(roles.findByCode("ADMIN").orElseThrow());user=users.saveAndFlush(user);
        return new Actor(user.getId(),user.getUsername(),jwt.issue(user.getUsername(),user.getTokenVersion()));
    }
    int call(Actor actor,String method,String path,Object body) throws Exception {
        var req=request(org.springframework.http.HttpMethod.valueOf(method),path).header("Authorization","Bearer "+actor.token());
        if(body!=null)req.contentType("application/json").content(mapper.writeValueAsBytes(body));
        var response=mvc.perform(req).andReturn().getResponse();
        assertNotEquals(500,response.getStatus(),response.getContentAsString());
        return response.getContentAsByteArray().length==0?response.getStatus():mapper.readTree(response.getContentAsString()).path("code").asInt();
    }
    Map<String,Object> holiday(LocalDate day) {return new LinkedHashMap<>(Map.of("day",day.toString(),"kind","HOLIDAY","name","原始名称"));}
    Map<String,Object> cause(int no) {return new LinkedHashMap<>(Map.of("subjectType","PROVIDER","category","补充案由","description","测试情形","itemNo",no));}
    int nextNo(){return jdbc.queryForObject("select coalesce(max(item_no),0)+1 from case_cause",Integer.class);}
    List<Map<String,Object>> holidays(){return jdbc.queryForList("select * from sys_holiday order by day");}
    void awaitBlocked(int blocker,int expected) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until){
            int n=jdbc.queryForObject("select count(*) from pg_stat_activity where ?=any(pg_blocking_pids(pid))",Integer.class,blocker);
            if(n>=expected)return;Thread.sleep(25);
        }
        fail("Dictionary request did not reach maintenance gate");
    }
    static Stream<Mutation> changes(){
        return Stream.of("ROLE","DISABLED","TOKEN").flatMap(revoke->Stream.of(
                new Mutation("/api/bureau/holidays","POST",2026102801L,revoke),
                new Mutation("/api/bureau/holidays/batch","POST",2026102801L,revoke),
                new Mutation("/api/bureau/holidays/2091-01-02","DELETE",2026102801L,revoke),
                new Mutation("/api/bureau/causes","POST",2026102802L,revoke)));
    }
    @ParameterizedTest @MethodSource("changes")
    void queuedAdministratorMustRetainLiveAuthority(Mutation mutation) throws Exception {
        var actor=admin();LocalDate day=LocalDate.of(2091,1,2);
        jdbc.update("insert into sys_holiday(day,kind,name)values(?,'HOLIDAY','保留') on conflict(day)do nothing",day);
        var before=holidays();int causeCount=jdbc.queryForObject("select count(*) from case_cause",Integer.class);
        Object body=mutation.path().contains("causes")?cause(nextNo()):mutation.path().endsWith("batch")?List.of(holiday(day)):holiday(day);
        var pool=Executors.newSingleThreadExecutor();var future=new AtomicReference<Future<Integer>>();
        try{
            new TransactionTemplate(tm).executeWithoutResult(tx->{
                jdbc.queryForList("select pg_advisory_xact_lock(?)",mutation.gate());
                int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                future.set(pool.submit(()->call(actor,mutation.method(),mutation.path(),body)));
                try{awaitBlocked(blocker,1);}catch(Exception e){throw new RuntimeException(e);}
                if(mutation.revoke().equals("ROLE"))jdbc.update("delete from sys_user_role where user_id=?",actor.id());
                else if(mutation.revoke().equals("DISABLED"))jdbc.update("update sys_user set enabled=false where id=?",actor.id());
                else jdbc.update("update sys_user set token_version=token_version+1 where id=?",actor.id());
            });
            assertEquals(403,future.get().get(20,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
        assertEquals(before,holidays());assertEquals(causeCount,jdbc.queryForObject("select count(*) from case_cause",Integer.class));
    }
    static Stream<Map<String,Object>> invalidHolidays(){
        var base=new LinkedHashMap<>(Map.<String,Object>of("day","2092-01-02","kind","HOLIDAY","name","正常"));
        List<Map<String,Object>> bad=new ArrayList<>();
        for(var change:List.of(Map.entry("day",(Object)17),Map.entry("day",(Object)"2092-02-31"),
                Map.entry("kind",(Object)"TYPO"),Map.entry("kind",(Object)5),Map.entry("name",(Object)false),
                Map.entry("name",(Object)"bad\0text"),Map.entry("name",(Object)"字".repeat(65)))){
            var row=new LinkedHashMap<>(base);row.put(change.getKey(),change.getValue());bad.add(row);
        }
        var row=new LinkedHashMap<>(base);row.put("kind",null);bad.add(row);return bad.stream();
    }
    @ParameterizedTest @MethodSource("invalidHolidays")
    void invalidSingleAndBatchRequestsDoNotWriteAnyHoliday(Map<String,Object> invalid) throws Exception {
        var actor=admin();var before=holidays();
        assertEquals(2100,call(actor,"POST","/api/bureau/holidays",invalid));
        assertEquals(2100,call(actor,"POST","/api/bureau/holidays/batch",List.of(holiday(LocalDate.of(2092,1,1)),invalid)));
        assertEquals(before,holidays());
    }
    @Test void nullBatchRowDoesNotPartiallyImport() throws Exception {
        var before=holidays();var rows=new ArrayList<Object>();rows.add(holiday(LocalDate.of(2092,1,1)));rows.add(null);
        assertEquals(2100,call(admin(),"POST","/api/bureau/holidays/batch",rows));assertEquals(before,holidays());
    }
    @Test void unicodeLimitDefaultKindAndOptionalNameRemainUsable() throws Exception {
        var actor=admin();String name="𠮷".repeat(64);var row=holiday(LocalDate.of(2093,1,2));row.put("name",name);row.remove("kind");
        assertEquals(0,call(actor,"POST","/api/bureau/holidays",row));
        assertEquals(name,jdbc.queryForObject("select name from sys_holiday where day='2093-01-02'",String.class));
        row.put("name",null);assertEquals(0,call(actor,"POST","/api/bureau/holidays/batch",List.of(row)));
        assertEquals(0,call(actor,"DELETE","/api/bureau/holidays/2093-01-02",null));
    }
    @Test void concurrentReversedBatchesKeepOneCompleteBatchWithoutDeadlock() throws Exception {
        var actor=admin();LocalDate first=LocalDate.of(2094,1,2),second=first.plusDays(1);
        var a=holiday(first);var b=holiday(second);var c=holiday(second);var d=holiday(first);
        c.put("kind","SHIFT_WORK");d.put("kind","SHIFT_WORK");
        var pool=Executors.newFixedThreadPool(2);var pending=new ArrayList<Future<Integer>>();
        try{
            new TransactionTemplate(tm).executeWithoutResult(tx->{
                jdbc.queryForList("select pg_advisory_xact_lock(2026102801)");int blocker=jdbc.queryForObject("select pg_backend_pid()",Integer.class);
                pending.add(pool.submit(()->call(actor,"POST","/api/bureau/holidays/batch",List.of(a,b))));
                pending.add(pool.submit(()->call(actor,"POST","/api/bureau/holidays/batch",List.of(c,d))));
                try{awaitBlocked(blocker,2);}catch(Exception e){throw new RuntimeException(e);}
            });
            for(var future:pending)assertEquals(0,future.get(20,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(25,TimeUnit.SECONDS));}
        assertEquals(1,jdbc.queryForObject("select count(distinct kind) from sys_holiday where day in (?,?)",Integer.class,first,second));
    }
    @Test void duplicateCauseSerializesAndPreservesFirstRecord() throws Exception {
        var actor=admin();int number=nextNo();var body=cause(number);
        assertEquals(0,call(actor,"POST","/api/bureau/causes",body));
        body.put("description","不应覆盖");assertEquals(2100,call(actor,"POST","/api/bureau/causes",body));
        assertEquals("测试情形",jdbc.queryForObject("select description from case_cause where item_no=?",String.class,number));
    }
    static Stream<String> invalidCauseFields(){return Stream.of("category","description","subjectType","itemNo","enabled");}
    @ParameterizedTest @MethodSource("invalidCauseFields")
    void invalidCauseInputsLeaveDictionaryUnchanged(String field) throws Exception {
        var actor=admin();var before=jdbc.queryForList("select * from case_cause order by id");var body=cause(nextNo());
        body.put(field,switch(field){case "itemNo"->1.5;case "enabled"->"true";case "subjectType"->"UNKNOWN";default->"bad\0text";});
        assertEquals(2100,call(actor,"POST","/api/bureau/causes",body));assertEquals(before,jdbc.queryForList("select * from case_cause order by id"));
    }
    @Test void fractionalExclusionOrNonTextReasonCannotChangeClueDeadline() throws Exception {
        var actor=admin();String marker="字典期限-"+UUID.randomUUID();
        assertEquals(0,call(actor,"POST","/api/bureau/clues",Map.of("source","OTHER","content",marker,"suspectName","测试机构","suspectType","PROVIDER")));
        long id=jdbc.queryForObject("select id from case_clue where content=?",Long.class,marker);
        var before=jdbc.queryForMap("select * from case_clue where id=?",id);
        for(var body:List.of(Map.of("days",1.5,"reason","鉴定"),Map.of("days",1,"reason",7),Map.of("days",1,"reason","bad\0text")))
            assertEquals(2026,call(actor,"POST","/api/bureau/clues/"+id+"/exclusions",body));
        assertEquals(before,jdbc.queryForMap("select * from case_clue where id=?",id));
        jdbc.update("update case_clue set excluded_days=2147483647 where id=?",id);
        assertEquals(2026,call(actor,"POST","/api/bureau/clues/"+id+"/exclusions",Map.of("days",1,"reason","鉴定")));
        assertEquals(2147483647,jdbc.queryForObject("select excluded_days from case_clue where id=?",Integer.class,id));
        assertEquals(before.get("deadline_at"),jdbc.queryForMap("select deadline_at from case_clue where id=?",id).get("deadline_at"));
    }
}
