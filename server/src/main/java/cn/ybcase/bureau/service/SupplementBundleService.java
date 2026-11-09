package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.spi.SignatureProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.*;
import static cn.ybcase.bureau.service.IntegrationSupport.*;

/** Independent post-decision evidence: never writes case_file, case_document or original attachments. */
@Service @RequiredArgsConstructor
public class SupplementBundleService {
 private final JdbcTemplate jdbc;
 private final MaterialAccess access;
 private final WorkflowUserAccess users;
 private final ObjectMapper mapper;
 private final SignatureProvider signatures;
 private final RuntimePolicy runtime;
 private final BureauConfig config;
 public record FileData(String filename,String contentType,byte[] bytes,String sha256) {}
 private static final String FILES="select id,filename,content_type,size_bytes,sha256,uploaded_by,uploaded_at from case_supplement_file where bundle_id=? order by id";
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Map<String,Object> list(long caseId,String username) {
  bureau(access.requireCaseReader(caseId,username));
  var bundles=jdbc.queryForList("select id,title,source_reference,status,created_by,created_at,manifest_sha256,reviewed_by,reviewed_at,opinion from case_supplement_bundle where case_id=? order by id desc",caseId);
  for(var b:bundles)b.put("files",jdbc.queryForList(FILES,b.get("id")));
  var docs=jdbc.queryForList("select d.id,d.bundle_id,d.decision_version_id,d.doc_type,d.title,d.content,d.content_sha256,d.made_at,d.created_by from case_supplement_document d where case_id=? order by id",caseId);
  for(var d:docs) {
   d.put("signatures",jdbc.queryForList("select id,signer,provider,content_hash,signed_at from case_supplement_signature where document_id=? order by id",d.get("id")));
   d.put("deliveries",jdbc.queryForList("select * from case_supplement_delivery where document_id=? order by id",d.get("id")));
   d.put("consents",jdbc.queryForList("select * from case_supplement_consent where document_id=? order by id desc",d.get("id")));
  }
  return Map.of("bundles",bundles,"documents",docs,"timeline",timeline(caseId),"decisionVersions",jdbc.queryForList("select id,decision_no,outcome,effective_at,content,penalty_basis from case_decision_version where case_id=? order by id",caseId));
 }
 private List<Map<String,Object>> timeline(long caseId) {
  return jdbc.queryForList("""
   select * from (
     select '材料包创建' event_type,b.created_at event_time,b.title reference,b.source_reference detail,b.created_by actor from case_supplement_bundle b where b.case_id=?
     union all select '材料提交复核',b.submitted_at,b.title,'材料清单已封存',b.created_by from case_supplement_bundle b where b.case_id=? and b.submitted_at is not null
     union all select case b.status when 'APPROVED' then '材料复核通过' when 'WITHDRAWN' then '材料撤回' else '材料复核退回' end,b.reviewed_at,b.title,b.opinion,b.reviewed_by from case_supplement_bundle b where b.case_id=? and b.reviewed_at is not null
     union all select '后续文书制作',d.created_at,d.title,'补充卷文书，原卷保持封存',d.created_by from case_supplement_document d where d.case_id=?
     union all select '后续文书签章',s.signed_at,d.title,'签章提供者：'||s.provider,s.signer from case_supplement_signature s join case_supplement_document d on d.id=s.document_id where d.case_id=?
     union all select '后续文书送达',s.recorded_at,d.title,s.receipt_no||'；送达生效日 '||s.delivered_at,s.recorded_by from case_supplement_delivery s join case_supplement_document d on d.id=s.document_id where d.case_id=?
     union all select '续办申请',r.requested_at,'续办编号 '||r.id,r.reason,r.requested_username from case_continuation_request r where r.case_id=?
     union all select case r.status when 'APPROVED' then '续办批准' when 'CANCELLED' then '续办撤回' else '续办退回' end,r.reviewed_at,'续办编号 '||r.id,r.opinion,r.reviewed_username from case_continuation_request r where r.case_id=? and r.reviewed_at is not null
   ) events order by event_time,reference
   """,caseId,caseId,caseId,caseId,caseId,caseId,caseId,caseId);
 }
 @Transactional
 public long create(long caseId,Map<String,Object> body,String username) {
  CaseGuards.lock(jdbc,caseId);var a=access.requireCaseWriter(caseId,username);bureau(a);eligible(caseId);
  String key=text(string(body,"requestKey"),80,"请求号"),title=text(string(body,"title"),200,"材料包名称"),ref=humanText(string(body,"sourceReference"),1000,"材料出处");
  var old=jdbc.queryForList("select id,title,source_reference from case_supplement_bundle where created_by=? and request_key=?",username,key);
  if(!old.isEmpty()) {if(!title.equals(old.getFirst().get("title"))||!ref.equals(old.getFirst().get("source_reference"))||!Objects.equals(caseId,jdbc.queryForObject("select case_id from case_supplement_bundle where id=?",Long.class,old.getFirst().get("id"))))throw invalid("材料请求号已用于其他内容");return number(old.getFirst().get("id"));}
  return jdbc.queryForObject("insert into case_supplement_bundle(case_id,request_key,title,source_reference,created_by,applicant_id,applicant_dept_id,applicant_category,source_snapshot) values(?,?,?,?,?,?,?,?,?::jsonb) returning id",Long.class,caseId,key,title,ref,username,a.id(),a.deptId(),a.accountCategory(),source(caseId));
 }
 @Transactional(rollbackFor=Exception.class)
 public long upload(long caseId,long bundleId,MultipartFile file,String username)throws IOException {
  CaseGuards.lock(jdbc,caseId);var b=locked(caseId,bundleId);var a=access.requireCaseWriter(caseId,username);bureau(a);owner(b,a);
  if(!"DRAFT".equals(b.get("status")))throw invalid("仅草稿材料包可以上传");
  if(file==null||file.isEmpty()||file.getSize()>10L*1024*1024)throw invalid("材料文件须为1字节至10MB");
  String filename=text(file.getOriginalFilename(),200,"文件名");
  if(filename.contains("/")||filename.contains("\\"))throw invalid("材料文件名不能包含路径");
  String type=text(file.getContentType()==null?"application/octet-stream":file.getContentType(),100,"文件类型");
  byte[] bytes=file.getBytes();if(bytes.length!=file.getSize())throw invalid("材料文件读取不完整");String sha=bytesHash(bytes);
  var old=jdbc.queryForList("select id from case_supplement_file where bundle_id=? and filename=? and sha256=?",bundleId,filename,sha);
  if(!old.isEmpty())return number(old.getFirst().get("id"));
  var size=jdbc.queryForMap("select count(*) n,coalesce(sum(size_bytes),0) bytes from case_supplement_file where bundle_id=?",bundleId);
  if(number(size.get("n"))>=20||number(size.get("bytes"))+bytes.length>32L*1024*1024)throw invalid("每包最多20份文件、合计32MB");
  return jdbc.queryForObject("insert into case_supplement_file(bundle_id,filename,content_type,sha256,size_bytes,data,uploaded_by) values(?,?,?,?,?,?,?) returning id",Long.class,bundleId,filename,type,sha,bytes.length,bytes,username);
 }
 @Transactional
 public void submit(long caseId,long bundleId,String username) {
  CaseGuards.lock(jdbc,caseId);var b=locked(caseId,bundleId);var a=access.requireCaseWriter(caseId,username);bureau(a);owner(b,a);
  if("PENDING".equals(b.get("status")))return;
  if(!"DRAFT".equals(b.get("status")))throw invalid("材料包不是草稿");
  var files=jdbc.queryForList(FILES,bundleId);if(files.isEmpty())throw invalid("须先上传实际材料");
  String manifest=canonical(json(Map.of("bundleId",bundleId,"caseId",caseId,"title",b.get("title"),"sourceReference",b.get("source_reference"),"files",files)));
  jdbc.update("update case_supplement_bundle set status='PENDING',manifest=?::jsonb,manifest_sha256=?,submitted_at=clock_timestamp() where id=?",manifest,hash(manifest),bundleId);
 }
 @Transactional
 public void review(long caseId,long bundleId,boolean approve,String opinion,String username) {
  opinion=humanText(opinion,2000,"复核意见");CaseGuards.lock(jdbc,caseId);var b=locked(caseId,bundleId);
  var observed=jdbc.queryForMap("select u.dept_id reviewer_dept,(select dept_id from sys_user where username=?) applicant_dept from sys_user u where u.username=?",b.get("created_by"),username);
  var deptIds=new TreeSet<Long>();for(Object value:observed.values())if(value instanceof Number n)deptIds.add(n.longValue());
  for(Long dept:deptIds)jdbc.queryForList("select id from sys_dept where id=? for share",dept);
  var actors=users.lockUsers(username,(String)b.get("created_by"));var a=access.lockActor(username,false);reviewer(a);access.assertCaseScope(caseId,a);
  if(!Objects.equals(a.deptId(),observed.get("reviewer_dept")))throw invalid("复核机构已变化，请重新核对");
  if(a.id().equals(number(b.get("applicant_id"))))throw new BizException(403,"材料申请人不能复核本人材料");
  if(!"PENDING".equals(b.get("status")))throw invalid("材料包已处理");
  if(approve) {
   var applicant=users.requireFiling(actors.get(b.get("created_by")));bureau(applicant);access.assertCaseScope(caseId,applicant);
   if(!Objects.equals(applicant.deptId(),b.get("applicant_dept_id"))||!Objects.equals(applicant.accountCategory(),b.get("applicant_category"))||!sameJson(source(caseId),b.get("source_snapshot")))throw invalid("原案或申请人归属已变化，请退回重新申请");
   assertManifest(b);
  }
  jdbc.update("update case_supplement_bundle set status=?,reviewed_by=?,reviewed_at=clock_timestamp(),opinion=? where id=?",approve?"APPROVED":"REJECTED",username,opinion,bundleId);
 }
 @Transactional
 public void withdraw(long caseId,long bundleId,String reason,String username) {
  CaseGuards.lock(jdbc,caseId);var b=locked(caseId,bundleId);var a=access.requireCaseWriter(caseId,username);owner(b,a);
  if(!Set.of("DRAFT","PENDING").contains(b.get("status")))throw invalid("只能撤回草稿或待复核材料");
  jdbc.update("update case_supplement_bundle set status='WITHDRAWN',reviewed_by=?,reviewed_at=clock_timestamp(),opinion=? where id=?",username,humanText(reason,2000,"撤回理由"),bundleId);
 }
 /** Called after the continuation source and actor are locked. Approved bytes cannot later change. */
 @Transactional
 public void attachToRequest(long caseId,long requestId,Long bundleId,String username) {
  var requests=jdbc.queryForList("select status,requested_username from case_continuation_request where id=? and case_id=? for update",requestId,caseId);
  if(requests.isEmpty()||!Objects.equals(username,requests.getFirst().get("requested_username")))throw invalid("材料绑定申请不匹配");
  var old=jdbc.queryForList("select bundle_id from case_supplement_request_material where request_id=?",requestId);
  if(!old.isEmpty()) {if(bundleId==null||bundleId.longValue()!=number(old.getFirst().get("bundle_id")))throw invalid("续办材料绑定不能更换或撤掉");assertRequestMaterials(caseId,requestId);return;}
  if(bundleId==null)return;
  if(!"PENDING".equals(requests.getFirst().get("status")))throw invalid("已处理的续办申请不能事后补绑材料");
  var bundles=jdbc.queryForList("select * from case_supplement_bundle where id=? and case_id=?",bundleId,caseId);if(bundles.isEmpty())throw invalid("材料包不属于本案");var b=bundles.getFirst();if(!"APPROVED".equals(b.get("status")))throw invalid("请关联已复核通过的同案材料包");assertManifest(b);
  if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from case_continuation_request where id=? and case_id=? and requested_username=?)",Boolean.class,requestId,caseId,username)))throw invalid("材料绑定申请不匹配");
  jdbc.update("insert into case_supplement_request_material(request_id,bundle_id,manifest_sha256,bound_by) values(?,?,?,?)",requestId,bundleId,b.get("manifest_sha256"),username);
 }
 @Transactional(readOnly=true)
 public void assertRequestMaterials(long caseId,long requestId) {
  var rows=jdbc.queryForList("select b.*,m.manifest_sha256 bound_sha from case_supplement_request_material m join case_supplement_bundle b on b.id=m.bundle_id where m.request_id=? and b.case_id=?",requestId,caseId);
  for(var b:rows){if(!"APPROVED".equals(b.get("status"))||!Objects.equals(b.get("manifest_sha256"),b.get("bound_sha")))throw invalid("复核材料包与申请快照不一致");assertManifest(b);}
 }
 @Transactional
 public long document(long caseId,long bundleId,Map<String,Object> body,String username) {
  CaseGuards.lock(jdbc,caseId);var b=locked(caseId,bundleId);bureau(access.requireCaseWriter(caseId,username));if(!"APPROVED".equals(b.get("status")))throw invalid("文书须关联已复核材料包");
  String key=text(string(body,"requestKey"),80,"请求号"),type=text(string(body,"docType"),24,"文书类型"),title=text(string(body,"title"),200,"文书标题"),content=documentText(string(body,"content"));
  if(!Set.of("DECISION","NOTICE","URGE_LETTER","COURT_RECEIPT","OTHER").contains(type))throw invalid("补充文书类型无效");
  Long version=positiveOptional(body.get("decisionVersionId"));
  if("DECISION".equals(type)) {
   if(version==null)throw invalid("后续决定文书须选择具体结果版本");
   var rows=jdbc.queryForList("select content from case_decision_version where id=? and case_id=?",version,caseId);
   if(rows.isEmpty()||!content.equals(rows.getFirst().get("content")))throw invalid("后续决定正文须与已批准结果版本一致");
  } else if(version!=null&&!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from case_decision_version where id=? and case_id=?)",Boolean.class,version,caseId)))throw invalid("决定版本不属于本案");
  String sha=hash(content);var old=jdbc.queryForList("select * from case_supplement_document where bundle_id=? and request_key=?",bundleId,key);
  if(!old.isEmpty()){var d=old.getFirst();if(!Objects.equals(type,d.get("doc_type"))||!Objects.equals(title,d.get("title"))||!Objects.equals(sha,d.get("content_sha256"))||!Objects.equals(version,d.get("decision_version_id")))throw invalid("文书请求号已用于其他内容");return number(d.get("id"));}
  return jdbc.queryForObject("insert into case_supplement_document(case_id,bundle_id,decision_version_id,request_key,doc_type,title,content,content_sha256,made_at,created_by) values(?,?,?,?,?,?,?,?,current_date,?) returning id",Long.class,caseId,bundleId,version,key,type,title,content,sha,username);
 }
 @Transactional
 public Map<String,Object> sign(long caseId,long documentId,String username) {
  CaseGuards.lock(jdbc,caseId);var a=access.requireCaseWriter(caseId,username);bureau(a);var d=deliveryDocument(caseId,documentId);
  runtime.requireProvider(signatures);
  String digest=hash((String)d.get("content"));var old=jdbc.queryForList("select * from case_supplement_signature where document_id=? and signer=? and content_hash=?",documentId,username,digest);
  if(!old.isEmpty()&&(!runtime.isFormal()||!"MOCK".equals(old.getFirst().get("provider"))))return Map.of("provider",old.getFirst().get("provider"),"contentHash",digest);
  var signed=signatures.sign((String)d.get("content"),username);
  jdbc.update("insert into case_supplement_signature(document_id,signer,provider,content_hash,signature) values(?,?,?,?,?)",documentId,username,signed.provider(),signed.contentHash(),signed.signature());
  return Map.of("provider",signed.provider(),"contentHash",signed.contentHash(),"formal",runtime.isFormal());
 }
 @Transactional
 public long recordDelivery(long caseId,long documentId,Map<String,Object> body,String username) {
  CaseGuards.lock(jdbc,caseId);bureau(access.requireCaseWriter(caseId,username));var document=deliveryDocument(caseId,documentId);
  String method=text(string(body,"method"),24,"送达方式");if(!Set.of("DIRECT","POST","LEAVE","ANNOUNCE","OTHER").contains(method))throw invalid("电子回执须通过专用送达链登记");
  boolean announcement="ANNOUNCE".equals(method);
  String dateLabel=announcement?"公告发布日期":"实际送达日期";
  LocalDate day;try{day=LocalDate.parse(string(body,announcement?"announcedAt":"deliveredAt"));}catch(Exception ex){throw invalid("须填写"+dateLabel);}
  if(day.isAfter(ExecutionDeadlines.today())||day.getYear()<1)throw invalid(dateLabel+"无效");
  if(document.get("decision_version_id") instanceof Number version) {
   java.sql.Date effective=jdbc.queryForObject("select effective_at from case_decision_version where id=? and case_id=?",
           java.sql.Date.class,version.longValue(),caseId);
   if(day.isBefore(effective.toLocalDate()))throw invalid(dateLabel+"不得早于对应决定版本生效日");
  }
  LocalDate delivered=announcement?day.plusDays(config.intVal("announce_deliver_days",60)):day;
  return insertDelivery(documentId,method,delivered,text(string(body,"receiver"),64,"受送达人"),
          text(string(body,"receiptNo"),64,"回证号"),humanText(string(body,"sourceReference"),1000,"回证出处"),username,
          announcement?day:null);
 }
 long insertDelivery(long doc,String method,LocalDate day,String receiver,String receipt,String ref,String username) {
  return insertDelivery(doc,method,day,receiver,receipt,ref,username,null);
 }
 private long insertDelivery(long doc,String method,LocalDate day,String receiver,String receipt,String ref,String username,
                             LocalDate announcedAt) {
  var old=jdbc.queryForList("select * from case_supplement_delivery where document_id=? and receipt_no=?",doc,receipt);
  if(!old.isEmpty()){
   var row=old.getFirst();
   boolean sameDate=announcedAt==null
           ? row.get("announced_at")==null && java.sql.Date.valueOf(day).equals(row.get("delivered_at"))
           : java.sql.Date.valueOf(announcedAt).equals(row.get("announced_at"));
   if(!sameDate||!Objects.equals(row.get("method"),method)||!Objects.equals(row.get("receiver"),receiver)
           ||!Objects.equals(row.get("source_reference"),ref))throw invalid("回证号已用于其他送达内容");
   return number(row.get("id"));
  }
  return jdbc.queryForObject("""
          insert into case_supplement_delivery(document_id,method,delivered_at,receiver,receipt_no,source_reference,recorded_by,announced_at)
          values(?,?,?,?,?,?,?,?) returning id
          """,Long.class,doc,method,day,receiver,receipt,ref,username,announcedAt);
 }
 @Transactional
 public void consent(long caseId,long documentId,Map<String,Object> body,String username) {
  CaseGuards.lock(jdbc,caseId);bureau(access.requireCaseWriter(caseId,username));deliveryDocument(caseId,documentId);
  String receiver=text(string(body,"receiver"),64,"同意受送达人"),channel=text(string(body,"channel"),500,"同意地址"),ref=humanText(string(body,"sourceReference"),1000,"确认书材料出处");
  var old=jdbc.queryForList("select receiver,channel,source_reference from case_supplement_consent where document_id=? order by id desc limit 1",documentId);
  if(!old.isEmpty()&&Objects.equals(receiver,old.getFirst().get("receiver"))&&Objects.equals(channel,old.getFirst().get("channel"))&&Objects.equals(ref,old.getFirst().get("source_reference")))return;
  jdbc.update("insert into case_supplement_consent(document_id,receiver,channel,source_reference,recorded_by) values(?,?,?,?,?)",documentId,receiver,channel,ref,username);
 }
 Map<String,Object> deliveryDocument(long caseId,long id) {
  var rows=jdbc.queryForList("select d.*,b.status bundle_status from case_supplement_document d join case_supplement_bundle b on b.id=d.bundle_id where d.id=? and d.case_id=?",id,caseId);
  if(rows.isEmpty()||!"APPROVED".equals(rows.getFirst().get("bundle_status")))throw invalid("补充文书不存在或材料未批准");return rows.getFirst();
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public FileData file(long caseId,long fileId,String username) {
  bureau(access.requireCaseReader(caseId,username));var rows=jdbc.queryForList("select f.* from case_supplement_file f join case_supplement_bundle b on b.id=f.bundle_id where f.id=? and b.case_id=?",fileId,caseId);
  if(rows.isEmpty())throw invalid("后续材料不存在");var f=rows.getFirst();byte[] bytes=(byte[])f.get("data");if(!bytesHash(bytes).equals(f.get("sha256")))throw invalid("材料摘要不匹配");return new FileData((String)f.get("filename"),(String)f.get("content_type"),bytes,(String)f.get("sha256"));
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public FileData export(long caseId,long bundleId,String username)throws IOException {
  bureau(access.requireCaseReader(caseId,username));var b=jdbc.queryForMap("select * from case_supplement_bundle where id=? and case_id=?",bundleId,caseId);if(!"APPROVED".equals(b.get("status")))throw invalid("仅已批准材料可以导出补充卷");assertManifest(b);
  var out=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(out,StandardCharsets.UTF_8)) {
   var metadata=new LinkedHashMap<>(b);metadata.put("source_snapshot",mapper.readTree(b.get("source_snapshot").toString()));metadata.put("manifest",mapper.readTree(b.get("manifest").toString()));
   var docs=jdbc.queryForList("select * from case_supplement_document where bundle_id=? order by id",bundleId);
   for(var d:docs){d.put("signatures",jdbc.queryForList("select * from case_supplement_signature where document_id=? order by id",d.get("id")));d.put("deliveries",jdbc.queryForList("select * from case_supplement_delivery where document_id=? order by id",d.get("id")));d.put("consents",jdbc.queryForList("select * from case_supplement_consent where document_id=? order by id",d.get("id")));}
   addZip(zip,"manifest.json",canonical(json(Map.of("bundle",metadata,"originalCase",mapper.readTree(source(caseId)),"documents",docs))).getBytes(StandardCharsets.UTF_8));
   addZip(zip,"materials-manifest.json",canonical(b.get("manifest").toString()).getBytes(StandardCharsets.UTF_8));
   for(var f:jdbc.queryForList("select * from case_supplement_file where bundle_id=? order by id",bundleId))addZip(zip,"materials/"+f.get("id")+"-"+f.get("filename"),(byte[])f.get("data"));
   for(var d:jdbc.queryForList("select * from case_supplement_document where bundle_id=? order by id",bundleId))addZip(zip,"documents/"+d.get("id")+".txt",((String)d.get("content")).getBytes(StandardCharsets.UTF_8));
   var receipts=jdbc.queryForList("select ev.id,ev.event_key,ev.outcome,p.sha256,p.content_type,p.data from electronic_delivery e join case_supplement_document d on d.id=e.supplement_document_id join electronic_delivery_event ev on ev.envelope_id=e.id join electronic_delivery_receipt_evidence p on p.event_id=ev.id where d.bundle_id=? order by ev.id",bundleId);
   for(var r:receipts){byte[] proof=(byte[])r.remove("data");if(!bytesHash(proof).equals(r.get("sha256")))throw invalid("回证证据摘要不符");addZip(zip,"receipts/"+r.get("id")+".bin",proof);}
   addZip(zip,"receipts/index.json",json(receipts).getBytes(StandardCharsets.UTF_8));
  }byte[] bytes=out.toByteArray();return new FileData("supplement-"+caseId+"-"+bundleId+".zip","application/zip",bytes,bytesHash(bytes));
 }
 private void addZip(ZipOutputStream zip,String name,byte[] bytes)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
 private String source(long id){return jdbc.queryForObject("select jsonb_build_object('caseId',c.id,'archiveNo',c.archive_no,'ownerDeptId',c.owner_dept_id,'decision',(select to_jsonb(d) from case_decision d where d.case_id=c.id))::text from case_file c where c.id=?",String.class,id);}
 private void eligible(long id){if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from case_decision where case_id=?)",Boolean.class,id)))throw invalid("后续材料仅用于已作出决定的案件");}
 private Map<String,Object> locked(long caseId,long id){var rows=jdbc.queryForList("select * from case_supplement_bundle where id=? and case_id=? for update",id,caseId);if(rows.isEmpty())throw invalid("后续材料包不存在");return rows.getFirst();}
 private void owner(Map<String,Object>b,WorkflowUserAccess.User a){if(!a.id().equals(number(b.get("applicant_id"))))throw new BizException(403,"仅材料申请人可操作本人草稿");}
 private void reviewer(WorkflowUserAccess.User a){if(!"BUREAU".equals(a.accountCategory())||a.roles().stream().noneMatch(Set.of("LEGAL","LEADER")::contains)||a.deptId()==null||!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sys_dept where id=? and org_kind='BUREAU' and enabled)",Boolean.class,a.deptId())))throw new BizException(403,"材料复核须由已归类局机关法制或负责人办理");}
 private void assertManifest(Map<String,Object>b){String stored=Objects.toString(b.get("manifest_sha256"),"");String expected=canonical(json(Map.of("bundleId",b.get("id"),"caseId",b.get("case_id"),"title",b.get("title"),"sourceReference",b.get("source_reference"),"files",jdbc.queryForList(FILES,b.get("id")))));if(b.get("manifest")==null||!sameJson(expected,b.get("manifest")))throw invalid("材料清单与已封存快照不一致");if(!hash(expected).equals(stored))throw invalid("材料清单摘要不匹配");for(var f:jdbc.queryForList("select data,sha256 from case_supplement_file where bundle_id=?",b.get("id")))if(!bytesHash((byte[])f.get("data")).equals(f.get("sha256")))throw invalid("材料字节摘要不匹配");}
 private String canonical(String value){try{return mapper.writeValueAsString(sorted(mapper.readTree(value)));}catch(Exception e){throw invalid("材料清单格式无效");}}
 private com.fasterxml.jackson.databind.JsonNode sorted(com.fasterxml.jackson.databind.JsonNode n){if(n.isObject()){var result=mapper.createObjectNode();var keys=new TreeSet<String>();n.fieldNames().forEachRemaining(keys::add);for(String key:keys)result.set(key,sorted(n.get(key)));return result;}if(n.isArray()){var result=mapper.createArrayNode();n.forEach(v->result.add(sorted(v)));return result;}return n;}
 private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception ex){throw invalid("材料内容格式无效");}}
 private boolean sameJson(String value,Object other){try{return mapper.readTree(value).equals(mapper.readTree(other.toString()));}catch(Exception ex){return false;}}
 static String bytesHash(byte[] bytes){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception ex){throw new IllegalStateException(ex);}}
 static String string(Map<String,Object>b,String key){Object value=b==null?null:b.get(key);if(value!=null&&!(value instanceof String))throw invalid("字段须为文本："+key);return (String)value;}
 public static String humanText(String value,int limit,String label){if(value==null||value.isBlank()||value.codePointCount(0,value.length())>limit||value.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw invalid(label+"须为有效文本，最多"+limit+"字");return value.strip();}
 private String documentText(String value){if(value==null||value.isBlank()||value.length()>16000||value.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\r'&&c!='\t'))throw invalid("文书正文须为有效文本，最多16000字");return value;}
 static Long positiveOptional(Object value){if(value==null)return null;try{long n;if(value instanceof Integer||value instanceof Long)n=((Number)value).longValue();else if(value instanceof java.math.BigInteger big)n=big.longValueExact();else throw new IllegalArgumentException();if(n<1)throw new IllegalArgumentException();return n;}catch(Exception ex){throw invalid("编号须为精确正整数");}}
 private static long number(Object value){return ((Number)value).longValue();}
}
