package cn.ybcase.bureau.service;
import cn.ybcase.bureau.common.BizException;
import cn.ybcase.core.security.AdminReadAccess;
import cn.ybcase.core.security.AdminWriteAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;
import static cn.ybcase.bureau.service.IntegrationSupport.*;
/** Explicit authorization grants preserve the historic officer row instead of backfilling sealed material. */
@Service @RequiredArgsConstructor
public class ParticipantAuthorizationService {
 public static final String SCOPE_SQL=" " + """
 and (case_file.owner_user=? or exists(select 1 from case_officer o join sys_user su on su.id=o.user_id
      where o.case_id=case_file.id and not o.avoided and su.username=?)
   or exists(select 1 from case_participant_access g join sys_user gu on gu.id=g.user_id
      join case_officer go on go.id=g.officer_id and go.case_id=g.case_id and not go.avoided
      join enforcer ge on ge.cert_no=g.cert_no and ge.user_id=gu.id and ge.enabled and ge.cert_expire_at>=current_date
      join sys_dept gd on gd.id=gu.dept_id and gd.enabled and gd.org_kind=gu.account_category
      where g.case_id=case_file.id and gu.username=? and gu.enabled and gu.dept_id=g.dept_id
        and not exists(select 1 from case_participant_access_revocation gr where gr.grant_id=g.id)
        and case_file.owner_dept_id=g.dept_id and go.user_id is null and go.cert_no=g.cert_no))
 """;
 private final JdbcTemplate jdbc;
 private final AdminReadAccess reader;
 private final AdminWriteAccess writer;
 private final WorkflowUserAccess users;
 private final EnforcerRegistryAccess registry;
 private final ObjectMapper mapper;
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Map<String,Object> preview(long caseId,long officerId,String target) {
  reader.requireCurrentAdmin();return previewValue(caseId,officerId,text(target,64,"目标账号"));
 }
 @Transactional
 public long approve(long caseId,long officerId,Map<String,Object>b,String username) {
  String target=text(SupplementBundleService.string(b,"username"),64,"目标账号");
  CaseGuards.lock(jdbc,caseId);registry.lockRegistry();
  var departments=jdbc.queryForList("select distinct dept_id from sys_user where username in (?,?) and dept_id is not null order by dept_id",username,target);
  for(var d:departments)jdbc.queryForList("select id from sys_dept where id=? for share",d.get("dept_id"));
  users.lockUsers(username,target);writer.requireCurrentAdmin();
  if(username.equals(target))throw new BizException(403,"不能为本人批准历史授权");
  var preview=previewValue(caseId,officerId,target);
  if(!Boolean.TRUE.equals(preview.get("eligible")))throw invalid((String)preview.get("reason"));
  if(!Objects.equals(preview.get("fingerprint"),b.get("fingerprint")))throw invalid("授权来源已变化，请重新预览");
  var source=(Map<?,?>)preview.get("source");String reason=SupplementBundleService.humanText(SupplementBundleService.string(b,"reason"),1000,"核定理由"),ref=SupplementBundleService.humanText(SupplementBundleService.string(b,"sourceReference"),1000,"原卷核对依据");
  var old=jdbc.queryForList("select id,reason,source_reference,fingerprint from case_participant_access g where case_id=? and officer_id=? and user_id=? and not exists(select 1 from case_participant_access_revocation r where r.grant_id=g.id)",caseId,officerId,source.get("target_id"));
  if(!old.isEmpty()){var row=old.getFirst();if(!Objects.equals(row.get("reason"),reason)||!Objects.equals(row.get("source_reference"),ref)||!Objects.equals(row.get("fingerprint"),b.get("fingerprint")))throw invalid("该历史授权已批准，请核对记录");return ((Number)row.get("id")).longValue();}
  return jdbc.queryForObject("insert into case_participant_access(case_id,officer_id,user_id,dept_id,cert_no,source_snapshot,fingerprint,reason,source_reference,approved_by) values(?,?,?,?,?,?::jsonb,?,?,?,?) returning id",Long.class,caseId,officerId,source.get("target_id"),source.get("dept_id"),source.get("cert_no"),json(source),preview.get("fingerprint"),reason,ref,username);
 }
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public List<Map<String,Object>> history(long caseId) {
  reader.requireCurrentAdmin();return jdbc.queryForList("select g.id,g.case_id,g.officer_id,g.cert_no,u.username,g.reason,g.source_reference,g.approved_by,g.approved_at,r.revoked_by,r.revoked_at,r.reason revoke_reason from case_participant_access g join sys_user u on u.id=g.user_id left join case_participant_access_revocation r on r.grant_id=g.id where g.case_id=? order by g.id desc",caseId);
 }
 @Transactional
 public void revoke(long grantId,Map<String,Object>b,String username) {
  var grants=jdbc.queryForList("select case_id from case_participant_access where id=?",grantId);if(grants.isEmpty())throw invalid("历史授权不存在");
  CaseGuards.lock(jdbc,((Number)grants.getFirst().get("case_id")).longValue());writer.requireCurrentAdmin();
  String reason=SupplementBundleService.humanText(SupplementBundleService.string(b,"reason"),1000,"收回理由"),ref=SupplementBundleService.humanText(SupplementBundleService.string(b,"sourceReference"),1000,"核对依据");
  var old=jdbc.queryForList("select reason,source_reference from case_participant_access_revocation where grant_id=?",grantId);
  if(!old.isEmpty()){if(!Objects.equals(reason,old.getFirst().get("reason"))||!Objects.equals(ref,old.getFirst().get("source_reference")))throw invalid("授权已收回，请核对原记录");return;}
  jdbc.update("insert into case_participant_access_revocation(grant_id,reason,source_reference,revoked_by) values(?,?,?,?)",grantId,reason,ref,username);
 }
 private Map<String,Object> previewValue(long caseId,long officerId,String target) {
  var rows=jdbc.queryForList("""
   select c.id case_id,c.case_no,c.owner_dept_id,o.id officer_id,o.cert_no,o.user_id original_user_id,o.avoided,
     u.id target_id,u.username,u.enabled,u.dept_id,u.account_category,u.token_version,
     e.id certificate_id,e.user_id certificate_user_id,e.enabled certificate_enabled,e.cert_expire_at,
     d.enabled department_enabled,d.org_kind,
     exists(select 1 from sys_user_role ur join sys_role r on r.id=ur.role_id where ur.user_id=u.id and r.code in ('HANDLER','LEADER','ADMIN')) writer_role
   from case_file c join case_officer o on o.case_id=c.id and o.id=?
   join sys_user u on u.username=? left join enforcer e on e.cert_no=o.cert_no left join sys_dept d on d.id=u.dept_id
   where c.id=?
   """,officerId,target,caseId);
  if(rows.isEmpty())throw invalid("案件、参办记录或目标账号不存在");var row=rows.getFirst();String problem=null;
  if(row.get("original_user_id")!=null)problem="原参办记录已有账号映射，不能覆盖";
  else if(Boolean.TRUE.equals(row.get("avoided")))problem="已回避人员不能恢复授权";
  else if(!Boolean.TRUE.equals(row.get("enabled"))||!Boolean.TRUE.equals(row.get("writer_role")))problem="目标账号未启用或不具备办案岗位";
  else if(!Boolean.TRUE.equals(row.get("department_enabled"))||row.get("dept_id")==null||!Objects.equals(row.get("dept_id"),row.get("owner_dept_id"))||!Objects.equals(row.get("org_kind"),row.get("account_category"))||!Set.of("BUREAU","DISTRICT").contains(Objects.toString(row.get("account_category"),"")))problem="目标账号机构类别或案件所属机构不一致";
  else if(!Objects.equals(row.get("target_id"),row.get("certificate_user_id"))||!Boolean.TRUE.equals(row.get("certificate_enabled"))||!(row.get("cert_expire_at") instanceof java.sql.Date date)||date.toLocalDate().isBefore(java.time.LocalDate.now()))problem="须按原证号核实当前有效账号映射";
  return Map.of("eligible",problem==null,"reason",problem==null?"可按原卷核对后批准":problem,"source",row,"fingerprint",hash(json(row)));
 }
 private String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw invalid("授权来源格式无效");}}
}
