package cn.ybcase.bureau;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.net.InetSocketAddress;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class SupplementAndDeliveryRecoveryIntegrationTest {
 @Autowired MockMvc mvc;@Autowired JdbcTemplate jdbc;@Autowired ObjectMapper mapper;@Autowired JwtService jwt;
 @Autowired cn.ybcase.bureau.service.AccountReadinessService readiness;
 static final AtomicReference<String> CALLBACK=new AtomicReference<>("unused"),MODE=new AtomicReference<>("SIMULATION");
 static final HttpServer GATEWAY=gateway();
 @DynamicPropertySource static void props(DynamicPropertyRegistry r){r.add("ybcase.integration.delivery.enabled",()->"true");r.add("ybcase.integration.delivery.url",()->"http://127.0.0.1:"+GATEWAY.getAddress().getPort());r.add("ybcase.integration.delivery.token",()->"synthetic-token");r.add("ybcase.integration.delivery.callback-username",CALLBACK::get);r.add("ybcase.runtime.mode",MODE::get);}
 static HttpServer gateway(){try{var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);s.createContext("/deliveries/send",e->{var body=new ObjectMapper().readTree(e.getRequestBody());byte[] bytes=("{\"status\":\"ACCEPTED\",\"providerId\":\"fixture-"+body.path("deliveryId").asLong()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});s.start();return s;}catch(Exception e){throw new RuntimeException(e);}}
 @AfterAll static void stop(){GATEWAY.stop(0);}
 @BeforeEach void guard(){MODE.set("SIMULATION");assertTrue(jdbc.queryForObject("select current_database()",String.class).matches("ybcase_authowner_test_20261028_[a-f0-9]{8}"));}
 record Actor(String name,String token,long id,long dept){}
 Actor actor(String category,String role){String name="rec_"+UUID.randomUUID().toString().replace("-","");long dept=jdbc.queryForObject("insert into sys_dept(code,name,org_kind,type) values(?,?,?,'ADMIN') returning id",Long.class,name.substring(0,25),"合成恢复机构",category);FixtureUsers.ensure(jdbc,name,role);jdbc.update("update sys_user set account_category=?,dept_id=? where username=?",category,dept,name);return new Actor(name,jwt.issue(name,0),jdbc.queryForObject("select id from sys_user where username=?",Long.class,name),dept);}
 long caseFile(Actor owner,boolean closed){long c=jdbc.queryForObject("insert into case_file(case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,status,owner_user,owner_dept_id,archive_no,e_delivery_consent,e_delivery_receiver,e_delivery_channel) values(?,'合成后续案件',(select min(id) from case_cause),'合成机构','PROVIDER',current_date-30,current_date+60,?,?,?, ?,true,'合成收件人','old@example.invalid') returning id",Long.class,"REC-"+UUID.randomUUID(),closed?"CLOSED":"DECIDED",owner.name(),owner.dept(),closed?"原封存卷":"");jdbc.update("update case_file set archive_no=null where id=? and archive_no=''",c);jdbc.update("insert into case_decision(case_id,decision_type,decision_no,content,decided_at,fine_amount,recoup_amount,confiscate_amount) values(?,'PUNISH',?,'原决定正文',current_date-20,100,0,0)",c,"REC-D-"+c);return c;}
 String base(long c){return "/bureau/cases/"+c+"/supplement-bundles";}
 String original(long c){return jdbc.queryForObject("select jsonb_build_object('case',to_jsonb(c),'decision',(select to_jsonb(d) from case_decision d where d.case_id=c.id),'documents',(select jsonb_agg(to_jsonb(d) order by id) from case_document d where d.case_id=c.id),'attachments',(select jsonb_agg(to_jsonb(a) order by id) from case_attachment a where a.case_id=c.id))::text from case_file c where id=?",String.class,c);}
 JsonNode post(Actor a,String path,Object body,int code)throws Exception{var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api"+path).header("Authorization","Bearer "+a.token()).contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();var n=mapper.readTree(response.getContentAsString());assertEquals(code,n.path("code").asInt(-1),n.toString());return n.path("data");}
 JsonNode get(Actor a,String path)throws Exception{var n=mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api"+path).header("Authorization","Bearer "+a.token())).andReturn().getResponse().getContentAsString());assertEquals(0,n.path("code").asInt(-1),n.toString());return n.path("data");}
 long bundle(Actor owner,long c)throws Exception{return post(owner,base(c),Map.of("requestKey",UUID.randomUUID().toString(),"title","合成后续材料","sourceReference","原件第1页\n核对第2项"),0).path("id").asLong();}
 long upload(Actor owner,long c,long b)throws Exception{var file=new MockMultipartFile("file","receipt.txt","text/plain","合成原始凭证".getBytes(java.nio.charset.StandardCharsets.UTF_8));var n=mapper.readTree(mvc.perform(multipart("/api"+base(c)+"/"+b+"/files").file(file).header("Authorization","Bearer "+owner.token())).andReturn().getResponse().getContentAsString());assertEquals(0,n.path("code").asInt(-1),n.toString());return n.path("data").path("id").asLong();}
 long approved(Actor owner,Actor legal,long c)throws Exception{long b=bundle(owner,c);upload(owner,c,b);post(owner,base(c)+"/"+b+"/submit",Map.of(),0);post(legal,base(c)+"/"+b+"/review",Map.of("approve",true,"opinion","核实原始材料"),0);return b;}
 long doc(Actor owner,long c,String type){return jdbc.queryForObject("insert into case_document(case_id,doc_type,title,content,made_at,maker) values(?,?,'合成送达文书','正文',current_date,?) returning id",Long.class,c,type,owner.name());}
 String delivery(long c){return "/bureau/cases/"+c+"/electronic-deliveries";}
 Map<String,Object> receipt(long id,String status,String event){return new LinkedHashMap<>(Map.of("deliveryId",id,"providerId","fixture-"+id,"eventId",event,"status",status,"occurredAt",OffsetDateTime.now().minusSeconds(1).toString(),"receiptNo","REC-"+event));}
 @Test void electronicNoticeReceiptsRespectVersionPaperAndReplay()throws Exception{
  var owner=actor("DISTRICT","HANDLER");var callback=actor(null,"INTEGRATION");CALLBACK.set(callback.name());
  long c=caseFile(owner,false);jdbc.update("update case_file set status='REPORTED' where id=?",c);
  String root="/bureau/cases/"+c,p=delivery(c),callbackPath="/integrations/delivery/receipts";
  var body=new LinkedHashMap<String,Object>(Map.of("content","版本告知正文","proposedFine",100,"requireDelivery",true));
  long first=post(owner,root+"/notice",body,0).path("id").asLong();
  post(owner,p,Map.of("documentId",doc(owner,c,"NOTICE"),"requestKey",UUID.randomUUID().toString()),2193);
  long firstEnvelope=sendNotice(owner,c,first);
  body.put("previousNoticeId",first);
  long second=post(owner,root+"/notice",body,0).path("id").asLong();
  assertFalse(post(callback,callbackPath,receipt(firstEnvelope,"DELIVERED",UUID.randomUUID().toString()),0).path("accepted").asBoolean());
  long secondEnvelope=sendNotice(owner,c,second);
  var paper=Map.of("method","DIRECT","noticeId",second,"docKind","NOTICE","receiptNo","PAPER","receiver","实际受送达人",
          "deliveredAt",LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
  post(owner,root+"/doc-deliveries",paper,0);
  assertFalse(post(callback,callbackPath,receipt(secondEnvelope,"DELIVERED",UUID.randomUUID().toString()),0).path("accepted").asBoolean());
  body.put("previousNoticeId",second);
  long third=post(owner,root+"/notice",body,0).path("id").asLong(),envelope=sendNotice(owner,c,third);
  var event=receipt(envelope,"DELIVERED",UUID.randomUUID().toString());
  assertTrue(post(callback,callbackPath,event,0).path("accepted").asBoolean());
  assertTrue(post(callback,callbackPath,event,0).path("accepted").asBoolean());
  assertFalse(post(callback,callbackPath,receipt(envelope,"DELIVERED",UUID.randomUUID().toString()),0).path("accepted").asBoolean());
  var latePaper=new LinkedHashMap<>(paper);latePaper.put("noticeId",third);
  post(owner,root+"/doc-deliveries",latePaper,2041);
  assertEquals(List.of("DIRECT","ELECTRONIC"),jdbc.queryForList("select method from case_delivery where case_id=? order by id",String.class,c));
  assertTrue(jdbc.queryForObject("""
      select n.statement_deadline=d.delivered_at+n.statement_period_days
          and n.hearing_request_deadline=d.delivered_at+n.hearing_period_days
      from case_notice n join case_delivery d on d.notice_id=n.id where n.id=?
      """,Boolean.class,third));
 }
 long sendNotice(Actor owner,long caseId,long notice)throws Exception{
  String p=delivery(caseId),prepare=p+"/notices/"+notice+"/document";
  long document=post(owner,prepare,Map.of(),0).path("id").asLong();
  assertEquals(document,post(owner,prepare,Map.of(),0).path("id").asLong());
  long id=post(owner,p,Map.of("documentId",document,"requestKey",UUID.randomUUID().toString()),0).path("id").asLong();
  assertEquals("SENT",post(owner,p+"/"+id+"/send",Map.of(),0).path("status").asText());
  return id;
 }
 @Test void approvedMaterialsRemainIndependentImmutableAndExportable()throws Exception{
  var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long c=caseFile(owner,true);String before=original(c);
  long b=bundle(owner,c);long f=upload(owner,c,b);assertEquals(f,upload(owner,c,b));post(owner,base(c)+"/"+b+"/submit",Map.of(),0);post(legal,base(c)+"/"+b+"/review",Map.of("approve",true,"opinion","材料核实\n另行复核原件"),0);
  assertThrows(Exception.class,()->jdbc.update("update case_supplement_file set data='x'::bytea where id=?",f));
  var zip=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api"+base(c)+"/"+b+"/export").header("Authorization","Bearer "+owner.token())).andReturn().getResponse();assertEquals(200,zip.getStatus());assertTrue(zip.getContentAsByteArray().length>100);assertTrue(zip.getHeader("Content-Disposition").contains("attachment"));assertEquals(before,original(c));
 }
 @Test void materialApprovalChecksSelfAndChangedApplicantAndDoesNotPermitPostSubmitUpload()throws Exception{
  var applicant=actor("BUREAU","LEADER");var legal=actor("BUREAU","LEGAL");long c=caseFile(applicant,true),b=bundle(applicant,c);upload(applicant,c,b);post(applicant,base(c)+"/"+b+"/submit",Map.of(),0);post(applicant,base(c)+"/"+b+"/review",Map.of("approve",true,"opinion","本人核对"),403);
  var file=new MockMultipartFile("file","late.txt","text/plain",new byte[]{1});var response=mvc.perform(multipart("/api"+base(c)+"/"+b+"/files").file(file).header("Authorization","Bearer "+applicant.token())).andReturn().getResponse();assertEquals(2193,mapper.readTree(response.getContentAsString()).path("code").asInt());
  jdbc.update("update sys_user set enabled=false where id=?",applicant.id());post(legal,base(c)+"/"+b+"/review",Map.of("approve",true,"opinion","来源已停用"),403);assertEquals("PENDING",jdbc.queryForObject("select status from case_supplement_bundle where id=?",String.class,b));
 }
 @Test void continuationBindsApprovedMaterialAndReplayCannotSwitchOrRemoveIt()throws Exception{
  var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long c=caseFile(owner,true),b=approved(owner,legal,c);var body=new LinkedHashMap<String,Object>(Map.of("type","EXECUTION","requestKey",UUID.randomUUID().toString(),"reason","真实到账续记","sourceReference","合成凭证","materialBundleId",b,"payload",Map.of("kind","FINE","amount",1,"paidAt",LocalDate.now().toString(),"receiptNo","PAY-"+UUID.randomUUID(),"method","BANK")));
  String p="/bureau/cases/"+c+"/continuations/requests";long id=post(owner,p,body,0).path("id").asLong();assertEquals(id,post(owner,p,body,0).path("id").asLong());body.remove("materialBundleId");post(owner,p,body,2193);post(legal,p+"/"+id+"/review",Map.of("approve",true,"opinion","核实绑定凭证"),0);assertEquals(1,jdbc.queryForObject("select count(*) from case_supplement_request_material where request_id=?",Integer.class,id));
 }
 @Test void oldEnvelopeCanBeReplacedAfterAddressChangeAndOldReceiptsCannotApply()throws Exception{
  var owner=actor("DISTRICT","HANDLER");var callback=actor(null,"INTEGRATION");CALLBACK.set(callback.name());long c=caseFile(owner,false),doc=doc(owner,c,"NOTICE");String p=delivery(c);long old=post(owner,p,Map.of("documentId",doc,"requestKey",UUID.randomUUID().toString()),0).path("id").asLong();post(owner,p+"/"+old+"/send",Map.of(),0);
  jdbc.update("update electronic_delivery set status='TIMED_OUT' where id=?",old);jdbc.update("update case_file set e_delivery_channel='new@example.invalid' where id=?",c);
  var replacement=Map.of("requestKey",UUID.randomUUID().toString(),"reason","核实供应商未完成，已确认新地址","providerChecked",true);long next=post(owner,p+"/"+old+"/replace",replacement,0).path("id").asLong();assertEquals(next,post(owner,p+"/"+old+"/replace",replacement,0).path("id").asLong());assertEquals("SUPERSEDED",jdbc.queryForObject("select status from electronic_delivery where id=?",String.class,old));
  assertFalse(post(callback,"/integrations/delivery/receipts",receipt(old,"DELIVERED",UUID.randomUUID().toString()),0).path("accepted").asBoolean());assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,c));assertEquals("DRAFT",jdbc.queryForObject("select status from electronic_delivery where id=?",String.class,next));
 }
 @Test void inFlightEnvelopeCannotBeReplacedAndRepeatedKeysCannotMoveToAnotherDocument()throws Exception{
  var owner=actor("DISTRICT","HANDLER");long c=caseFile(owner,false),d=doc(owner,c,"NOTICE");String key=UUID.randomUUID().toString(),p=delivery(c);long id=post(owner,p,Map.of("documentId",d,"requestKey",key),0).path("id").asLong();post(owner,p+"/"+id+"/send",Map.of(),0);post(owner,p+"/"+id+"/replace",Map.of("requestKey",UUID.randomUUID().toString(),"reason","不应覆盖在途","providerChecked",true),2193);post(owner,p,Map.of("documentId",doc(owner,c,"NOTICE"),"requestKey",key),2193);
 }
 @Test void approvedRequestWithoutMaterialsCannotAcquireAFirstBindingOnReplay()throws Exception{
  var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");long c=caseFile(owner,true),b=approved(owner,legal,c);var body=new LinkedHashMap<String,Object>(Map.of("type","EXECUTION","requestKey",UUID.randomUUID().toString(),"reason","核实续记","sourceReference","实际凭证","payload",Map.of("kind","FINE","amount",1,"paidAt",LocalDate.now().toString(),"receiptNo","REPLAY-"+UUID.randomUUID(),"method","BANK")));
  String p="/bureau/cases/"+c+"/continuations/requests";long id=post(owner,p,body,0).path("id").asLong();post(legal,p+"/"+id+"/review",Map.of("approve",true,"opinion","批准原资料"),0);body.put("materialBundleId",b);post(owner,p,body,2193);assertEquals(0,jdbc.queryForObject("select count(*) from case_supplement_request_material where request_id=?",Integer.class,id));
 }
 @Test void effectiveReplacementBlocksOriginalElectronicSendAndReceipt()throws Exception{
  var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");var callback=actor(null,"INTEGRATION");CALLBACK.set(callback.name());long c=caseFile(owner,false),d=doc(owner,c,"DECISION");
  String previous=jdbc.queryForObject("select cfg_value from sys_config where cfg_key='delivery_electronic_decision_allowed'",String.class);jdbc.update("update sys_config set cfg_value='true' where cfg_key='delivery_electronic_decision_allowed'");
  try{long envelope=post(owner,delivery(c),Map.of("documentId",d,"requestKey",UUID.randomUUID().toString()),0).path("id").asLong();post(owner,delivery(c)+"/"+envelope+"/send",Map.of(),0);
   String p="/bureau/cases/"+c+"/continuations";long remedy=post(owner,p+"/remedies",Map.of("requestKey",UUID.randomUUID().toString(),"remedyType","RECONSIDERATION","acceptedAt",LocalDate.now().minusDays(5).toString(),"reference","结果受理","reason","实际受理","sourceReference","受理原件"),0).path("id").asLong();
   var payload=Map.ofEntries(Map.entry("remedyId",remedy),Map.entry("outcome","CHANGE"),Map.entry("effectiveAt",LocalDate.now().toString()),Map.entry("resultReference","批准结果"),Map.entry("resultContent","依法人工核对"),Map.entry("decisionType","PUNISH"),Map.entry("decisionNo","新决定"),Map.entry("fineAmount",50),Map.entry("recoupAmount",0),Map.entry("confiscateAmount",0),Map.entry("penaltyBasis","新依据"),Map.entry("content","新正文\n第二行"));
   long request=post(owner,p+"/requests",Map.of("type","REMEDY_RESULT","requestKey",UUID.randomUUID().toString(),"reason","核实结果材料","sourceReference","结果原件","payload",payload),0).path("id").asLong();post(legal,p+"/requests/"+request+"/review",Map.of("approve",true,"opinion","结果核实"),0);
   assertFalse(post(callback,"/integrations/delivery/receipts",receipt(envelope,"DELIVERED",UUID.randomUUID().toString()),0).path("accepted").asBoolean());assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,c));post(owner,delivery(c),Map.of("documentId",d,"requestKey",UUID.randomUUID().toString()),2193);
  }finally{jdbc.update("update sys_config set cfg_value=? where cfg_key='delivery_electronic_decision_allowed'",previous);}
 }
 @Test void rawReceiptEvidenceHasImmutableBytesAndPayloadBoundReplay()throws Exception{
  var owner=actor("DISTRICT","HANDLER");var callback=actor(null,"INTEGRATION");CALLBACK.set(callback.name());long c=caseFile(owner,false),d=doc(owner,c,"NOTICE"),id=post(owner,delivery(c),Map.of("documentId",d,"requestKey",UUID.randomUUID().toString()),0).path("id").asLong();post(owner,delivery(c)+"/"+id+"/send",Map.of(),0);
  byte[] bytes="合成原始回证".getBytes(java.nio.charset.StandardCharsets.UTF_8);var r=receipt(id,"DELIVERED",UUID.randomUUID().toString());r.put("evidenceBase64",Base64.getEncoder().encodeToString(bytes));r.put("evidenceSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));r.put("evidenceContentType","text/plain");assertTrue(post(callback,"/integrations/delivery/receipts",r,0).path("accepted").asBoolean());assertTrue(post(callback,"/integrations/delivery/receipts",r,0).path("accepted").asBoolean());
  long event=jdbc.queryForObject("select id from electronic_delivery_event where event_key=?",Long.class,r.get("eventId"));var file=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api"+delivery(c)+"/events/"+event+"/evidence").header("Authorization","Bearer "+owner.token())).andReturn().getResponse();assertArrayEquals(bytes,file.getContentAsByteArray());r.put("evidenceBase64",Base64.getEncoder().encodeToString(new byte[]{9}));post(callback,"/integrations/delivery/receipts",r,2193);assertEquals(1,jdbc.queryForObject("select count(*) from electronic_delivery_receipt_evidence where event_id=?",Integer.class,event));
 }
 @Test void postArchiveSupplementDocumentDeliveryNeverWritesOriginalVolume()throws Exception{
  var owner=actor("DISTRICT","HANDLER");var legal=actor("BUREAU","LEGAL");var callback=actor(null,"INTEGRATION");CALLBACK.set(callback.name());long c=caseFile(owner,true),b=approved(owner,legal,c);String before=original(c);
  long d=post(owner,base(c)+"/"+b+"/documents",Map.of("requestKey",UUID.randomUUID().toString(),"docType","NOTICE","title","独立后续告知","content","第一行\n第二行\t第三栏"),0).path("id").asLong();post(owner,base(c)+"/documents/"+d+"/consent",Map.of("receiver","后续收件人","channel","supplement@example.invalid","sourceReference","已核实的新确认书"),0);long envelope=post(owner,delivery(c)+"/supplements",Map.of("documentId",d,"requestKey",UUID.randomUUID().toString()),0).path("id").asLong();post(owner,delivery(c)+"/"+envelope+"/send",Map.of(),0);var receipt=receipt(envelope,"DELIVERED",UUID.randomUUID().toString());assertTrue(post(callback,"/integrations/delivery/receipts",receipt,0).path("accepted").asBoolean());receipt.put("evidenceSha256","0".repeat(64));post(callback,"/integrations/delivery/receipts",receipt,2193);assertEquals(before,original(c));assertEquals(1,jdbc.queryForObject("select count(*) from case_supplement_delivery where document_id=?",Integer.class,d));
 }
 @Test void readinessExplainsUnclassifiedDepartmentWithoutDisablingExistingReportExport()throws Exception{
  var legal=actor("BUREAU","LEGAL");jdbc.update("update sys_dept set org_kind=null where id=?",legal.dept());var r=get(legal,"/bureau/account-readiness");assertTrue(r.path("issues").toString().contains("DEPARTMENT_UNCLASSIFIED"));assertFalse(r.path("capabilities").path("reportReview").asBoolean());assertTrue(r.path("capabilities").path("reportExport").asBoolean());
  jdbc.update("update sys_dept set enabled=false where id=?",legal.dept());assertEquals(401,mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/bureau/account-readiness").header("Authorization","Bearer "+legal.token())).andReturn().getResponse().getStatus(),"Disabled departments remain blocked at authentication; diagnostics must not weaken that boundary");var disabled=readiness.readiness(legal.name());assertEquals(false,((Map<?,?>)disabled.get("capabilities")).get("reportExport"));assertTrue(disabled.get("issues").toString().contains("DEPARTMENT_DISABLED"));
 }
 @Test void historicalAccessIsReviewedSeparateFromOriginalOfficerAndTracksCurrentCertificate()throws Exception{
  var owner=actor("DISTRICT","HANDLER");var target=actor("DISTRICT","HANDLER");var admin=actor("BUREAU","ADMIN");jdbc.update("update sys_user set dept_id=? where id=?",owner.dept(),target.id());long c=caseFile(owner,true);String cert="CERT-"+UUID.randomUUID();long officer=jdbc.queryForObject("insert into case_officer(case_id,name,cert_no,duty) values(?,'历史参办',?,'ASSIST') returning id",Long.class,c,cert);jdbc.update("insert into enforcer(name,cert_no,cert_expire_at,enabled,user_id) values('历史参办',?,current_date+10,true,?)",cert,target.id());String original=jdbc.queryForObject("select row_to_json(o)::text from case_officer o where id=?",String.class,officer);
  String priorScope=jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'",String.class);jdbc.update("update sys_config set cfg_value='SELF' where cfg_key='case_view_scope'");
  try{
  assertEquals(2080,readCode(target,"/bureau/cases/"+c));
  var p=get(admin,"/system/participant-access/preview?caseId="+c+"&officerId="+officer+"&username="+target.name());assertTrue(p.path("eligible").asBoolean());post(admin,"/system/participant-access/"+c+"/"+officer,Map.of("username",target.name(),"fingerprint",p.path("fingerprint").asText(),"reason","核实原卷参办人员","sourceReference","原卷证号记录"),0);assertEquals(original,jdbc.queryForObject("select row_to_json(o)::text from case_officer o where id=?",String.class,officer));
  assertEquals(0,readCode(target,"/bureau/cases/"+c));
  assertEquals(1,jdbc.queryForObject("select count(*) from case_participant_access where officer_id=?",Integer.class,officer));
  long grant=jdbc.queryForObject("select id from case_participant_access where officer_id=?",Long.class,officer);post(admin,"/system/participant-access/"+grant+"/revoke",Map.of("reason","再次核对仅收回本案","sourceReference","原卷复核意见"),0);assertEquals(1,jdbc.queryForObject("select count(*) from case_participant_access_revocation where grant_id=?",Integer.class,grant));assertEquals(original,jdbc.queryForObject("select row_to_json(o)::text from case_officer o where id=?",String.class,officer));
  assertEquals(2080,readCode(target,"/bureau/cases/"+c));
  }finally{jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'",priorScope);}
 }
 int readCode(Actor a,String path)throws Exception{return mapper.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api"+path).header("Authorization","Bearer "+a.token())).andReturn().getResponse().getContentAsString()).path("code").asInt(-1);}
}
