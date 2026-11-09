package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.*;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class InterviewAndBatchLifecycleIntegrationTest {
 @Autowired JdbcTemplate jdbc;
 @Autowired InterviewChangeService changes;
 @Autowired ReportingVersionService versions;
 @Autowired DepartmentInterviewService interviews;
 @Autowired MockMvc mvc;
 @Autowired JwtService jwt;
 @Autowired ObjectMapper mapper;
 record Actor(long id,String name,long department){}
 @BeforeEach void isolated(){assertTrue(jdbc.queryForObject("select current_database()",String.class).matches("ybcase_authowner_test_[0-9]{8}_[0-9a-f]{8}"));}

 @Test void originalInterviewAndFrozenBytesSurviveApprovedCorrectionAndReissue() {
  var owner=actor("DISTRICT","HANDLER");var generator=actor("BUREAU","LEADER");var reviewer=actor("BUREAU","LEGAL");
  long id=interview(owner);String original=original(id);long batch=batch(generator,null);byte[] bytes=versions.download(batch,generator.name()).bytes();
  versions.confirm(batch,"按来源核对",reviewer.name());
  var input=request("CORRECT",value("NEW"));long change=changes.request(id,input,owner.name());
  assertEquals(change,changes.request(id,input,owner.name()));assertEquals("OLD",code(id));
  assertEquals(403,assertThrows(BizException.class,()->changes.decide(change,true,"自审",owner.name())).code);
  changes.decide(change,true,"材料核对通过",reviewer.name());changes.decide(change,true,"材料核对通过",reviewer.name());
  assertEquals(original,original(id));assertEquals("NEW",code(id));assertArrayEquals(bytes,versions.download(batch,generator.name()).bytes());
  long next=batch(generator,batch);var diff=versions.changes(next,generator.name(),1,100);
  assertTrue(((List<?>)diff.get("rows")).stream().anyMatch(o->((Map<?,?>)o).get("row_key").equals("INTERVIEW:"+id)));
  assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("delete from interview_change_request where id=?",change));
 }
 @Test void approvedVoidExcludesNewReportsButPreservesOriginalAndOldFrozenFile() {
  var owner=actor("DISTRICT","HANDLER");var bureau=actor("BUREAU","LEGAL");var generator=actor("BUREAU","LEADER");
  long id=interview(owner);String source=original(id);long batch=batch(generator,null);byte[] bytes=versions.download(batch,generator.name()).bytes();
  long change=changes.request(id,request("VOID",null),owner.name());changes.decide(change,true,"确认误建",bureau.name());
  assertEquals(0,jdbc.queryForObject("select count(*) from effective_department_interviews() where id=?",Integer.class,id));
  assertEquals(source,original(id));assertArrayEquals(bytes,versions.download(batch,generator.name()).bytes());
  assertEquals(2196,assertThrows(BizException.class,()->changes.request(id,request("CORRECT",value("AFTERVOID")),owner.name())).code);
 }
 @Test void requesterAndOriginalSnapshotChangesCannotBeApprovedButCanBeRejected() {
  var owner=actor("DISTRICT","HANDLER");var reviewer=actor("BUREAU","LEGAL");long id=interview(owner);
  long change=changes.request(id,request("CORRECT",value("NEW")),owner.name());jdbc.update("update department_interview set participants='原卷新增人员' where id=?",id);
  assertEquals(2196,assertThrows(BizException.class,()->changes.decide(change,true,"不应批准",reviewer.name())).code);
  changes.decide(change,false,"来源已变，请重新核对",reviewer.name());assertEquals("OLD",code(id));
  long another=changes.request(id,request("CORRECT",value("NEW")),owner.name());jdbc.update("update sys_user set enabled=false where id=?",owner.id());
  assertEquals(403,assertThrows(BizException.class,()->changes.decide(another,true,"停用不能批准",reviewer.name())).code);
  changes.decide(another,false,"申请人已停用",reviewer.name());
 }
 @Test void sourceLinkedAgencyInterviewUsesSameReviewedHistoryAndScope() {
  var agency=actor("AGENCY","AGENCY");var other=actor("AGENCY","AGENCY");var reviewer=actor("BUREAU","LEGAL");
  long id=interview(agency);long caseId=jdbc.queryForObject("""
   insert into case_file(case_no,name,cause_id,party_name,party_type,status,filed_at,deadline_at,owner_dept_id)
   values(?,'合成中心约谈',(select min(id) from case_cause),'合成机构','PROVIDER','CLOSED','2010-01-01','2010-01-01',?) returning id
   """,Long.class,"CHANGE-"+UUID.randomUUID(),reviewer.department());
  long agreement=jdbc.queryForObject("insert into agreement_action(case_id,action,org,sent_at,agency_dept_id,agency_status,agency_cycle,agency_result_type,replied_at) values(?,'INTERVIEW','合成中心','2010-01-01',?,'COMPLETED',1,'COMPLETED','2010-06-15') returning id",Long.class,caseId,agency.department());
  jdbc.update("update department_interview set source_agreement_id=?,source_agency_cycle=1 where id=?",agreement,id);
  assertEquals(403,assertThrows(BizException.class,()->changes.request(id,request("CORRECT",value("CROSS")),other.name())).code);
  long change=changes.request(id,request("CORRECT",value("CENTER-NEW")),agency.name());changes.decide(change,true,"核对中心原始约谈",reviewer.name());
  assertEquals("CENTER-NEW",code(id));assertEquals(agreement,jdbc.queryForObject("select source_agreement_id from department_interview where id=?",Long.class,id));
 }
 @Test void cancelAndRejectDoNotChangeSourceAndAnotherRequestCanFollow() {
  var owner=actor("DISTRICT","HANDLER");var reviewer=actor("BUREAU","LEGAL");long id=interview(owner);String source=original(id);
  long first=changes.request(id,request("CORRECT",value("ONE")),owner.name());changes.cancel(first,"申请人撤回核查",owner.name());changes.cancel(first,"申请人撤回核查",owner.name());
  long second=changes.request(id,request("CORRECT",value("TWO")),owner.name());changes.decide(second,false,"材料不足",reviewer.name());assertEquals(source,original(id));assertEquals("OLD",code(id));
 }
 @Test void oldDirectEditCannotBypassReviewAndInvalidChangesFailAtomically() {
  var owner=actor("DISTRICT","HANDLER");long id=interview(owner);String source=original(id);
  assertEquals(2196,assertThrows(BizException.class,()->interviews.edit(id,value("ILLEGAL"),owner.name())).code);
  assertThrows(BizException.class,()->changes.request(id,new InterviewChangeService.Request(UUID.randomUUID(),"CORRECT",value("NEW"),"","来源"),owner.name()));
  assertEquals(source,original(id));assertEquals(0,jdbc.queryForObject("select count(*) from interview_change_request where interview_id=?",Integer.class,id));
  var admin=actor("BUREAU","ADMIN");
  assertTrue(((List<?>)changes.records(admin.name(),1,100).get("rows")).stream().anyMatch(o->((Number)((Map<?,?>)o).get("id")).longValue()==id));
  long change=changes.request(id,request("CORRECT",value("REVIEW")),owner.name());
  assertEquals(403,assertThrows(BizException.class,()->changes.decide(change,true,"纯管理员不能替代复核",admin.name())).code);
  changes.cancel(change,"收回待补材料",owner.name());
 }
 @Test void frozenBatchWithdrawReturnAndVoidAreTerminalAuditedAndPreserveFiles() {
  var generator=actor("BUREAU","LEADER");var reviewer=actor("BUREAU","LEGAL");
  for(String action:List.of("WITHDRAW","RETURN","VOID")) {
   long id=batch(generator,null);byte[] bytes=versions.download(id,generator.name()).bytes();var by="WITHDRAW".equals(action)?generator:reviewer;
   var end=versions.transition(id,action,"核对后处理",by.name());assertNotEquals("GENERATED",end.get("status"));
   versions.transition(id,action,"核对后处理",by.name());assertArrayEquals(bytes,versions.download(id,generator.name()).bytes());
   assertEquals(2195,assertThrows(BizException.class,()->versions.confirm(id,"不能再确认",reviewer.name())).code);
   assertEquals(1,jdbc.queryForObject("select count(*) from report_batch_event where batch_id=?",Integer.class,id));
   assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("delete from report_batch_event where batch_id=?",id));
  }
 }
 @Test void voidConfirmedBatchRetainsConfirmationAndReissueCannotUseVoidedParent() {
  var generator=actor("BUREAU","LEADER");var reviewer=actor("BUREAU","LEGAL");long id=batch(generator,null);versions.confirm(id,"原确认",reviewer.name());
  byte[] file=versions.download(id,generator.name()).bytes();versions.transition(id,"VOID","确认后发现口径不符",reviewer.name());
  assertEquals("原确认",versions.detail(id,generator.name()).get("confirmation_note"));assertArrayEquals(file,versions.download(id,generator.name()).bytes());
  assertEquals(2195,assertThrows(BizException.class,()->batch(generator,id)).code);
 }
 @Test void batchLifecycleRejectsSelfReviewCrossCategoryAndUnclassifiedReviewer() {
  var generator=actor("BUREAU","LEADER");var peer=actor("BUREAU","LEGAL");var district=actor("DISTRICT","LEADER");var admin=actor("BUREAU","ADMIN");long id=batch(generator,null);
  assertEquals(403,assertThrows(BizException.class,()->versions.transition(id,"RETURN","自己退回",generator.name())).code);
  assertEquals(403,assertThrows(BizException.class,()->versions.transition(id,"WITHDRAW","不是生成者",peer.name())).code);
  assertEquals(403,assertThrows(BizException.class,()->versions.transition(id,"VOID","纯管理员",admin.name())).code);
  assertEquals(403,assertThrows(BizException.class,()->versions.transition(id,"VOID","跨类别",district.name())).code);
  jdbc.update("update sys_dept set org_kind=null where id=?",peer.department());
  assertEquals(403,assertThrows(BizException.class,()->versions.transition(id,"RETURN","未归类",peer.name())).code);
 }
 @Test void concurrentBatchConfirmationAndReturnCannotBothWin() throws Exception {
  var generator=actor("BUREAU","LEADER");var reviewer=actor("BUREAU","LEGAL");long id=batch(generator,null);byte[] bytes=versions.download(id,generator.name()).bytes();
  try(var pool=Executors.newFixedThreadPool(2)) {
   var gate=new CountDownLatch(1);
   var a=pool.submit(()->{gate.await();try{versions.confirm(id,"竞争确认",reviewer.name());return true;}catch(BizException e){return false;}});
   var b=pool.submit(()->{gate.await();try{versions.transition(id,"RETURN","竞争退回",reviewer.name());return true;}catch(BizException e){return false;}});
   gate.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
  }
  assertArrayEquals(bytes,versions.download(id,generator.name()).bytes());
 }
 @Test void agencyHttpRouteRequiresAgencyIdentityAndBusinessReviewerUsesBureauRoute() throws Exception {
  var agency=actor("AGENCY","AGENCY");long id=interview(agency);String token=jwt.issue(agency.name(),0);
  var response=mvc.perform(get("/api/agency/interview-changes/records").header("Authorization","Bearer "+token)).andReturn().getResponse();assertEquals(0,mapper.readTree(response.getContentAsString()).path("code").asInt());
  assertEquals(403,mvc.perform(get("/api/bureau/interview-changes/records").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus());
 }
 private Actor actor(String category,String role){String key="IC-"+UUID.randomUUID().toString().substring(0,16);long d=jdbc.queryForObject("insert into sys_dept(name,code,type,org_kind) values(?,?,'ADMIN',?) returning id",Long.class,key,key,category);FixtureUsers.ensure(jdbc,key,role);jdbc.update("update sys_user set account_category=?,dept_id=? where username=?",category,d,key);return new Actor(jdbc.queryForObject("select id from sys_user where username=?",Long.class,key),key,d);}
 private long interview(Actor a){return jdbc.queryForObject("insert into department_interview(dept_id,department_name,institution_code,institution_name,interview_type,reason,content,participants,interview_date,created_by) values(?,'合成部门','OLD','合成机构','个别约谈','原事由','原内容','甲乙','2010-06-15',?) returning id",Long.class,a.department(),a.id());}
 private DepartmentInterviewService.InterviewInput value(String code){return new DepartmentInterviewService.InterviewInput(code,"合成机构","个别约谈","原事由","更正后内容","甲乙",LocalDate.of(2010,6,15));}
 private InterviewChangeService.Request request(String kind,DepartmentInterviewService.InterviewInput v){return new InterviewChangeService.Request(UUID.randomUUID(),kind,v,"按原始材料核实","材料第3页");}
 private String original(long id){return jdbc.queryForObject("select to_jsonb(i)::text from department_interview i where id=?",String.class,id);}
 private String code(long id){return jdbc.queryForObject("select institution_code from effective_department_interviews() where id=?",String.class,id);}
 private long batch(Actor a,Long parent){return ((Number)versions.generate(new ReportingVersionService.GenerateRequest(ReportExport.Kind.INTERVIEWS,2010,LocalDate.of(2010,1,1),LocalDate.of(2010,12,31),LocalDate.of(2010,12,31),parent,UUID.randomUUID()),a.name()).get("id")).longValue();}
}
