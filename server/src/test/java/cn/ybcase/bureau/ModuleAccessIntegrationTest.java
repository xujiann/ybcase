package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.*;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.*;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class ModuleAccessIntegrationTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper mapper; @Autowired JdbcTemplate jdbc;
    @Autowired CaseService cases; @Autowired ClueService clues; @Autowired ProcedureService procedure;
    @Autowired OversightService oversight; @Autowired CaseCauseRepository causes; @Autowired BureauConfig config;
    @Autowired SysUserRepository users; @Autowired SysRoleRepository roles; @Autowired JwtService jwt;
    String scope;
    record Actor(String name,String token){}
    Actor actor(String role){var u=new SysUser();u.setUsername("module_"+System.nanoTime());u.setRealName("矩阵"+role);u.setPassword("unused");u.getRoles().add(roles.findByCode(role).orElseThrow());u=users.save(u);return new Actor(u.getUsername(),jwt.issue(u.getUsername(),u.getTokenVersion()));}
    @BeforeEach void self(){scope=jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'",String.class);jdbc.update("update sys_config set cfg_value='SELF' where cfg_key='case_view_scope'");config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));}
    @AfterEach void restore(){jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'",scope);config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));}
    long fresh(String owner){long cause=causes.findAll().stream().filter(x->x.getItemNo()==13).findFirst().orElseThrow().getId();return cases.create(new CaseService.CaseCreateReq(null,cause,"SUMMARY","MODULE-"+System.nanoTime(),"PROVIDER",null,null,null,null,"权限边界",BigDecimal.TEN,List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),owner).getId();}
    long clue(String owner){return clues.create(new ClueService.ClueCreateReq("COMPLAINT","模块范围","医院","PROVIDER",LocalDate.now(),owner),owner).getId();}
    JsonNode call(Actor a,String method,String path,Object body,int code) throws Exception {var req=("GET".equals(method)?get(path):post(path)).header("Authorization","Bearer "+a.token());if(body!=null)req.contentType("application/json").content(mapper.writeValueAsBytes(body));var r=mvc.perform(req).andReturn().getResponse();var json=mapper.readTree(r.getContentAsString());assertEquals(code,json.path("code").asInt(),path+" "+r.getContentAsString());if(code==403)assertTrue(r.getStatus()==200 || r.getStatus()==403, "Role denial must use a supported HTTP envelope");return json.path("data");}
    long inspection(Actor owner){return ((Number)oversight.createInspection(new OversightService.InspectionReq(null,"INSPECT-"+System.nanoTime(),"PROVIDER","甲、乙",LocalDate.now()),owner.name()).get("id")).longValue();}
    long transfer(long clue){procedure.addTransfer(new ProcedureService.TransferReq(clue,null,"IN","移送机关","ADMIN","范围测试",null,null));return jdbc.queryForObject("select max(id) from case_transfer where clue_id=?",Long.class,clue);}
    @Test void legalCannotPerformOrdinaryWritesButRetainsModuleReadAccess() throws Exception {
        var legal=actor("LEGAL");long cid=fresh("banban");String p="/api/bureau/cases/"+cid;
        var probes=new LinkedHashMap<String,Object>();
        probes.put(p+"/hearings",Map.of("scheduledAt",LocalDate.now().plusDays(7).toString(),"host","主持人"));
        probes.put(p+"/hearings/999999/hold",Map.of("record","笔录"));probes.put(p+"/hearings/999999/opinion",Map.of("opinion","意见"));probes.put(p+"/hearings/999999/cancel",Map.of("reason","改期"));
        probes.put(p+"/summary-record",Map.of());probes.put(p+"/e-delivery-consent",Map.of("receiver","法人","channel","a@b.cn"));probes.put(p+"/assists",Map.of("direction","OUT","org","协查机关","content","事项"));probes.put(p+"/assists/999999/reply",Map.of("result","回复"));
        probes.put("/api/bureau/transfers",Map.of("caseId",cid,"direction","IN","kind","ADMIN","targetOrg","机关","reason","来源"));probes.put("/api/bureau/transfers/999999/confirm",Map.of());
        probes.put("/api/bureau/inspections",Map.of("objectName","医院","objectType","PROVIDER","officers","甲、乙"));probes.put("/api/bureau/inspections/999999/complete",Map.of("result","结果"));probes.put("/api/bureau/inspections/999999/to-clue",Map.of());
        probes.put("/api/bureau/rewards",Map.of("clueId",clue("banban"),"reporterName","举报人"));probes.put("/api/bureau/rewards/999999/approve",Map.of("amount",10));probes.put("/api/bureau/rewards/999999/pay",Map.of());
        probes.put(p+"/installments",Map.of("seq",1,"amount",10,"dueAt",LocalDate.now().toString()));probes.put("/api/bureau/installments/999999/pay",Map.of());probes.put(p+"/expert-reviews",Map.of("experts","专家"));probes.put(p+"/expert-reviews/999999/end",Map.of("opinion","意见"));
        var before=jdbc.queryForMap("select * from case_file where id=?",cid);for(var probe:probes.entrySet())call(legal,"POST",probe.getKey(),probe.getValue(),403);
        assertEquals(before,jdbc.queryForMap("select * from case_file where id=?",cid));for(String path:List.of("/inspections","/transfers","/rewards","/enforcers","/decisions/publish-export"))call(legal,"GET","/api/bureau"+path,null,0);
    }
    @Test void selfInspectionListAndCompletionEnforceSourceOwnerAndLifecycle() throws Exception {var owner=actor("HANDLER");var other=actor("HANDLER");long id=inspection(owner);assertFalse(call(other,"GET","/api/bureau/inspections",null,0).toString().contains("\"id\":"+id+","));call(other,"POST","/api/bureau/inspections/"+id+"/complete",Map.of("result","越权完成"),2080);assertNull(jdbc.queryForObject("select done_at from inspection where id=?",java.sql.Date.class,id));call(owner,"POST","/api/bureau/inspections/"+id+"/complete",Map.of("result","发现违法","violationFound",true),0);call(owner,"POST","/api/bureau/inspections/"+id+"/to-clue",Map.of(),0);call(owner,"POST","/api/bureau/inspections/"+id+"/complete",Map.of("result","改写已转换来源"),2146);}
    @Test void clueTransfersCannotBeListedCreatedOrConfirmedByOtherHandler() throws Exception {var owner=actor("HANDLER");var other=actor("HANDLER");long clue=clue(owner.name()),id=transfer(clue);var list=call(other,"GET","/api/bureau/transfers",null,0);for(var row:list)assertNotEquals(id,row.path("id").asLong());call(other,"POST","/api/bureau/transfers/"+id+"/confirm",Map.of(),2080);call(other,"POST","/api/bureau/transfers",Map.of("clueId",clue,"direction","OUT","targetOrg","机关","kind","ADMIN","reason","越权移送"),2080);assertEquals("PENDING",clues.get(clue).getStatus());call(owner,"POST","/api/bureau/transfers/"+id+"/confirm",Map.of(),0);}
    @Test void rewardCreationAndListFollowFiledClueCaseScope() throws Exception {var owner=actor("HANDLER");var other=actor("HANDLER");long clue=clue(owner.name()),cid=fresh(owner.name());jdbc.update("update case_file set clue_id=? where id=?",clue,cid);jdbc.update("update case_clue set status='FILED' where id=?",clue);call(other,"POST","/api/bureau/rewards",Map.of("clueId",clue,"reporterName","保密举报人"),2080);call(owner,"POST","/api/bureau/rewards",Map.of("clueId",clue,"reporterName","保密举报人","reporterContact","secret-contact"),0);assertFalse(call(other,"GET","/api/bureau/rewards",null,0).toString().contains("secret-contact"));for(var row:call(other,"GET","/api/bureau/rewards",null,0))assertNotEquals(clue,row.path("clue_id").asLong());assertTrue(call(owner,"GET","/api/bureau/rewards",null,0).toString().contains("保**"));call(owner,"POST","/api/bureau/rewards/999999/approve",Map.of("amount",10),403);}
    @Test void nestedHearingAssistAndExpertIdsCannotMutateAnotherCase() {long a=fresh("banban"),b=fresh("banban");long hearing=jdbc.queryForObject("insert into case_hearing(case_id,notice_sent_at,scheduled_at,host) values(?,current_date-7,current_date,'主持人') returning id",Long.class,b);var h=jdbc.queryForMap("select * from case_hearing where id=?",hearing);assertEquals(2057,assertThrows(BizException.class,()->procedure.holdHearing(a,hearing,"串案笔录")).code);assertEquals(2057,assertThrows(BizException.class,()->procedure.cancelHearing(a,hearing,"串案撤销")).code);assertEquals(2057,assertThrows(BizException.class,()->procedure.hearingOpinion(a,hearing,"串案意见")).code);assertEquals(h,jdbc.queryForMap("select * from case_hearing where id=?",hearing));procedure.addAssist(b,new ProcedureService.AssistReq("OUT","机关","事项",null));long assist=jdbc.queryForObject("select max(id) from case_assist where case_id=?",Long.class,b);assertEquals(2060,assertThrows(BizException.class,()->procedure.replyAssist(a,assist,"串案回复",false,null)).code);oversight.startExpertReview(b,"专家",null);long expert=jdbc.queryForObject("select max(id) from expert_review where case_id=?",Long.class,b);assertEquals(2066,assertThrows(BizException.class,()->oversight.endExpertReview(a,expert,"串案意见",null,null)).code);}
    @Test void clueTransferCannotBypassArchivedLinkedCaseAndSourcesCannotBeMismatched(){var owner=actor("HANDLER");long clue=clue(owner.name()),a=fresh(owner.name()),b=fresh(owner.name());jdbc.update("update case_file set clue_id=? where id=?",clue,a);assertEquals(2059,assertThrows(BizException.class,()->procedure.addTransfer(new ProcedureService.TransferReq(clue,b,"IN","机关","ADMIN","串源",null,null),owner.name())).code);long id=transfer(clue);jdbc.update("update case_file set status='CLOSED',archive_no='MODULE-SEALED' where id=?",a);assertEquals(2031,assertThrows(BizException.class,()->procedure.confirmTransfer(id,owner.name())).code);assertNull(jdbc.queryForObject("select confirmed_at from case_transfer where id=?",java.sql.Date.class,id));}
    @Test void summaryRecordSerializesAndOnlyUpdatesItsOwnMetadata() throws Exception {long id=fresh("banban");cases.decide(id,new CaseService.DecisionReq("PUNISH",BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO,null,"简易处罚",null,"一般"));jdbc.update("update case_file set status='CLOSED',archive_no='SUMMARY-SEALED' where id=?",id);var before=jdbc.queryForMap("select * from case_file where id=?",id);var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{List<Future<Integer>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(pool.submit(()->{gate.await();try{procedure.summaryRecord(id);return 0;}catch(BizException e){return e.code;}}));gate.countDown();List<Integer> codes=new ArrayList<>();for(var r:results)codes.add(r.get(15,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(0,2058),codes);}finally{pool.shutdownNow();}var after=jdbc.queryForMap("select * from case_file where id=?",id);assertNotNull(after.remove("summary_record_at"));before.remove("summary_record_at");assertEquals(before,after);}
    @Test void historicalTransferWithoutAnySourceIsNeitherVisibleNorActionable() throws Exception {var handler=actor("HANDLER");long id=jdbc.queryForObject("insert into case_transfer(direction,target_org,kind,reason,sent_at) values('IN','历史机关','ADMIN','历史缺来源',current_date) returning id",Long.class);for(var row:call(handler,"GET","/api/bureau/transfers",null,0))assertNotEquals(id,row.path("id").asLong());call(handler,"POST","/api/bureau/transfers/"+id+"/confirm",Map.of(),2059);assertNull(jdbc.queryForObject("select confirmed_at from case_transfer where id=?",java.sql.Date.class,id));}
}
