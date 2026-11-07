package cn.ybcase.core.web;

import cn.ybcase.core.common.R;
import cn.ybcase.core.security.AdminReadAccess;
import cn.ybcase.core.security.AdminWriteAccess;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/api/system/depts")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class DepartmentClassificationController {
    private final JdbcTemplate jdbc;
    private final AdminReadAccess reads;
    private final AdminWriteAccess writes;
    private final ObjectMapper mapper;
    private static final Set<String> KINDS=Set.of("BUREAU","DISTRICT","AGENCY");

    public record Confirm(String orgKind,String expectedCode,String fingerprint,String reason,String sourceReference) {}

    @GetMapping("/{id}/classification-preview")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public R<Map<String,Object>> preview(@PathVariable Long id,@RequestParam String orgKind,
                                         jakarta.servlet.http.HttpServletResponse response) {
        reads.requireCurrentAdmin();
        response.setHeader("Cache-Control","no-store");
        if (!KINDS.contains(orgKind)) return R.fail(1202,"请选择处室、分局或医保中心");
        var department=department(id);
        if(department==null) return R.fail(1201,"机构不存在");
        return R.ok(snapshot(department,accounts(id),orgKind));
    }

    @PostMapping("/{id}/classification")
    @Transactional
    public R<Map<String,Object>> confirm(@PathVariable Long id,@RequestBody Confirm request) {
        jdbc.queryForList("select pg_advisory_xact_lock(20261028,1201)");
        if(jdbc.queryForList("select id from sys_dept where id=? for no key update",id).isEmpty())
            return R.fail(1201,"机构不存在");
        var ids=jdbc.queryForList("select id from sys_user where dept_id=? order by id",Long.class,id);
        String actor=writes.requireCurrentAdmin(ids.toArray(Long[]::new));
        if(actor==null) throw new org.springframework.security.access.AccessDeniedException("需要管理员登录");
        if(request==null || request.orgKind()==null || !KINDS.contains(request.orgKind())
                || !text(request.reason(),1000) || !text(request.sourceReference(),255))
            return R.fail(1202,"请选择机构类别，填写归类理由及局方核对依据");
        var department=department(id);
        if(department.get("org_kind")!=null) return R.fail(1202,"该机构已归类，不能再次变更类别");
        var accounts=accounts(id);
        var snapshot=snapshot(department,accounts,request.orgKind());
        if(!Objects.equals(department.get("code"),request.expectedCode())
                || !Objects.equals(snapshot.get("fingerprint"),request.fingerprint()))
            return R.fail(1202,"机构或所属账号已有变化，请重新预览核对");
        if(!((List<?>)snapshot.get("conflicts")).isEmpty())
            return R.fail(1202,"所属账号已有不同类别，请先逐项核对账号归属");
        // The directory gate also covers account creation, edits and roster application.
        // All checks precede the only business mutation; existing source IDs remain untouched.
        jdbc.update("update sys_dept set org_kind=? where id=? and org_kind is null",request.orgKind(),id);
        jdbc.update("""
                insert into dept_classification_history(dept_id,org_kind,actor,reason,source_reference,preview_fingerprint,account_snapshot)
                values (?,?,?,?,?,?,?::jsonb)""",id,request.orgKind(),actor,request.reason(),request.sourceReference(),
                request.fingerprint(),json(accounts));
        return R.ok(snapshot(department(id),accounts,request.orgKind()));
    }

    private Map<String,Object> department(Long id) {
        var rows=jdbc.queryForList("select id,name,code,type,parent_id,sort_no,enabled,org_kind,responsibility from sys_dept where id=?",id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    private List<Map<String,Object>> accounts(Long id) {
        return jdbc.queryForList("select id,username,real_name,enabled,account_category from sys_user where dept_id=? order by id",id);
    }
    private Map<String,Object> snapshot(Map<String,Object> department,List<Map<String,Object>> accounts,String kind) {
        var result=new LinkedHashMap<String,Object>();
        result.put("department",department);result.put("accounts",accounts);
        result.put("unclassifiedAccounts",accounts.stream().filter(a->a.get("account_category")==null).count());
        result.put("conflicts",accounts.stream().filter(a->a.get("account_category")!=null && !kind.equals(a.get("account_category"))).toList());
        result.put("orgKind",kind);result.put("fingerprint",hash(json(List.of(department,accounts,kind))));
        result.put("history",jdbc.queryForList("select org_kind,actor,reason,source_reference,created_at from dept_classification_history where dept_id=? order by id",department.get("id")));
        return result;
    }
    private static boolean text(String value,int max) {return value!=null && !value.isBlank() && value.indexOf(0)<0 && value.codePointCount(0,value.length())<=max;}
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}}
    private static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
