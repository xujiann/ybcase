package cn.ybcase.bureau.service;
import cn.ybcase.bureau.common.BizException;
import cn.ybcase.core.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDate;
import java.util.*;
@Service @RequiredArgsConstructor
public class AccountReadinessService {
 private final JdbcTemplate jdbc;
 @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
 public Map<String,Object> readiness(String username) {
  var rows=jdbc.queryForList("select u.id,u.enabled,u.token_version,u.account_category,u.dept_id,d.name department_name,d.enabled department_enabled,d.org_kind from sys_user u left join sys_dept d on d.id=u.dept_id where u.username=?",username);
  if(rows.isEmpty()||!Boolean.TRUE.equals(rows.getFirst().get("enabled")))throw new BizException(403,"账号已停用或不存在");var row=rows.getFirst();
  var auth=SecurityContextHolder.getContext().getAuthentication();if(auth!=null&&auth.getPrincipal() instanceof AuthUser principal&&((Number)row.get("token_version")).intValue()!=principal.getTokenVersion())throw new BizException(403,"登录状态已变化");
  var roles=Set.copyOf(jdbc.queryForList("select r.code from sys_role r join sys_user_role ur on ur.role_id=r.id where ur.user_id=?",String.class,row.get("id")));
  if(roles.contains("INTEGRATION"))throw new BizException(403,"服务账号不使用业务就绪入口");
  var issues=new ArrayList<Map<String,String>>();String category=(String)row.get("account_category");boolean departmentReady=true;
  if(category==null){issue(issues,"ACCOUNT_CATEGORY","账号类别尚未核定，请管理员按正式名册归类");departmentReady=false;}
  if(row.get("dept_id")==null){issue(issues,"DEPARTMENT_MISSING","尚未关联所属机构");departmentReady=false;}
  else if(!Boolean.TRUE.equals(row.get("department_enabled"))){issue(issues,"DEPARTMENT_DISABLED","所属机构已停用，请管理员核实");departmentReady=false;}
  else if(row.get("org_kind")==null){issue(issues,"DEPARTMENT_UNCLASSIFIED","所属机构待核对归类，反复刷新不能完成复核配置");departmentReady=false;}
  else if(!Objects.equals(category,row.get("org_kind"))){issue(issues,"CATEGORY_CONFLICT","账号类别与所属机构类别不一致");departmentReady=false;}
  boolean certificate=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from enforcer where user_id=? and enabled and cert_expire_at>=?)",Boolean.class,row.get("id"),LocalDate.now()));
  boolean qualified=Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from enforcer where user_id=? and enabled and cert_expire_at>=? and legal_qualified)",Boolean.class,row.get("id"),LocalDate.now()));
  if(roles.contains("HANDLER")&&!certificate)issue(issues,"CERTIFICATE","执法证账号映射或有效期尚未核实，办案前请核对");
  if(roles.contains("LEGAL")&&!qualified)issue(issues,"LEGAL_QUALIFICATION","法审资格映射或有效期尚未核实，不能用岗位代替资格");
  boolean bureau="BUREAU".equals(category)&&departmentReady,reviewRole=roles.contains("LEGAL")||roles.contains("LEADER");
  boolean reportExport="BUREAU".equals(category)&&(reviewRole||roles.contains("ADMIN"))&&(row.get("dept_id")==null||Boolean.TRUE.equals(row.get("department_enabled")));
  return Map.of("issues",issues,"department",Objects.toString(row.get("department_name"),"未关联"),"accountCategory",Objects.toString(category,"未归类"),"administrator",roles.contains("ADMIN"),"capabilities",Map.of("departmentReady",departmentReady,"reportExport",reportExport,"reportReview",bureau&&reviewRole,"certificateMapped",certificate,"legalQualified",qualified,"agencyWork",departmentReady&&"AGENCY".equals(category)&&roles.contains("AGENCY")));
 }
 private void issue(List<Map<String,String>> issues,String code,String message){issues.add(Map.of("code",code,"message",message,"adminPath","/system/users"));}
}
