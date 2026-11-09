package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.*;

/** Reviewed changes form an overlay; original actual-interview facts and source links never change. */
@Service
@RequiredArgsConstructor
public class InterviewChangeService {
    private final JdbcTemplate jdbc;
    private final MaterialAccess access;
    private final WorkflowUserAccess users;
    private final ObjectMapper mapper;
    public record Request(UUID requestKey,String kind,DepartmentInterviewService.InterviewInput values,
                          String reason,String sourceReference) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> records(String username,int page,int size) {
        var actor=access.readActor(username); requireReader(actor);bounds(page,size);
        long total=jdbc.queryForObject("select count(*) from department_interview where (? or dept_id=?)",Long.class,supervisor(actor),actor.deptId());
        var rows=jdbc.queryForList("""
          select i.id,i.department_name,coalesce(e.institution_name,i.institution_name) institution_name,
            e.institution_code,e.interview_type,e.reason,e.content,e.participants,e.interview_date,
            e.id is null as voided,i.source_agreement_id,
            i.dept_id=? and (i.created_by=? or ?) and ? as can_request
          from department_interview i left join effective_department_interviews() e on e.id=i.id
          where (? or i.dept_id=?) order by i.id desc limit ? offset ?
          """,actor.deptId(),actor.id(),actor.privileged()||actor.roles().contains("AGENCY"),actor.roles().stream().anyMatch(Set.of("AGENCY","HANDLER","LEADER","ADMIN")::contains),supervisor(actor),actor.deptId(),size,(page-1L)*size);
        return Map.of("rows",rows,"total",total,"page",page,"size",size);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> history(long id,String username) {
        var actor=access.readActor(username); requireReader(actor); requireVisible(source(id),actor);
        return historyRows(jdbc.queryForList("select * from interview_change_request where interview_id=? order by id desc",id),actor);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> pending(String username,int page,int size) {
        var actor=access.readActor(username); if(!reviewer(actor)) throw denied();
        bounds(page,size);
        long total=jdbc.queryForObject("select count(*) from interview_change_request where status='PENDING'",Long.class);
        return Map.of("rows",historyRows(jdbc.queryForList("select * from interview_change_request where status='PENDING' order by id limit ? offset ?",size,(page-1L)*size),actor),"total",total,"page",page,"size",size);
    }

    @Transactional
    public long request(long id,Request input,String username) {
        lockSource(id);var actor=access.lockActor(username,false);requireWriter(source(id),actor);
        if(input==null||input.requestKey()==null||!Set.of("CORRECT","VOID").contains(Objects.toString(input.kind(),""))) throw invalid("请选择更正或作废并提供请求号");
        String reason=text(input.reason(),2000),reference=text(input.sourceReference(),1000);
        String values="VOID".equals(input.kind())?"{}":json(DepartmentInterviewService.validate(input.values()));
        var old=jdbc.queryForList("select * from interview_change_request where requested_by=? and request_key=?",actor.id(),input.requestKey());
        if(!old.isEmpty()) {
            var row=old.getFirst();
            if(((Number)row.get("interview_id")).longValue()!=id||!input.kind().equals(row.get("request_kind"))
                ||!reason.equals(row.get("reason"))||!reference.equals(row.get("source_reference"))||!same(values,row.get("proposed_values"))) throw invalid("同一请求号不能变更申请内容");
            return ((Number)row.get("id")).longValue();
        }
        var effective=jdbc.queryForList("select * from effective_department_interviews() where id=?",id);
        if(effective.isEmpty()) throw invalid("该约谈已作废，不可再次申请");
        if("CORRECT".equals(input.kind())&&same(values,jsonValue(effective.getFirst()))) throw invalid("没有需要更正的约谈字段");
        if(Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from interview_change_request where interview_id=? and status='PENDING')",Boolean.class,id))) throw invalid("该约谈已有待复核申请");
        return jdbc.queryForObject("""
          insert into interview_change_request(interview_id,request_key,request_kind,proposed_values,source_snapshot,
            reason,source_reference,requested_by,requested_username,requester_dept_id,requester_category)
          values(?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?) returning id
          """,Long.class,id,input.requestKey(),input.kind(),values,snapshot(id),reason,reference,actor.id(),actor.username(),actor.deptId(),actor.accountCategory());
    }

    @Transactional
    public void decide(long requestId,boolean approve,String opinion,String username) {
        var observed=requestRow(requestId,false);long id=((Number)observed.get("interview_id")).longValue();
        lockSource(id);var request=requestRow(requestId,true);
        var dept=jdbc.queryForList("select dept_id from sys_user where username=?",Long.class,username);
        if(!dept.isEmpty()&&dept.getFirst()!=null) jdbc.queryForList("select id from sys_dept where id=? for share",dept.getFirst());
        var locked=users.lockUsers(username,(String)request.get("requested_username"));
        var actor=access.lockActor(username,false);if(!Objects.equals(dept.isEmpty()?null:dept.getFirst(),actor.deptId())||!reviewer(actor)) throw denied();
        String note=text(opinion,2000);String outcome=approve?"APPROVED":"REJECTED";
        if(!"PENDING".equals(request.get("status"))) {
            if(outcome.equals(request.get("status"))&&actor.id().equals(request.get("reviewed_by"))&&note.equals(request.get("opinion"))) return;
            throw invalid("申请已处理");
        }
        if(actor.id().equals(request.get("requested_by"))) throw new BizException(403,"申请人不能复核本人约谈申请");
        if(approve) {
            var applicant=locked.get(request.get("requested_username"));
            requireWriter(source(id),applicant);
            if(!Objects.equals(applicant.deptId(),request.get("requester_dept_id"))||!Objects.equals(applicant.accountCategory(),request.get("requester_category"))
               ||!same(snapshot(id),request.get("source_snapshot"))) throw invalid("来源或申请人归属已变化，请驳回并重新申请");
        }
        finish(requestId,outcome,actor,note);
    }

    @Transactional
    public void cancel(long requestId,String opinion,String username) {
        var r=requestRow(requestId,false);long id=((Number)r.get("interview_id")).longValue();lockSource(id);r=requestRow(requestId,true);
        var actor=access.lockActor(username,false);requireReader(actor);requireVisible(source(id),actor);
        if(!actor.id().equals(r.get("requested_by"))) throw denied();
        String note=text(opinion,2000);
        if("CANCELLED".equals(r.get("status"))&&note.equals(r.get("opinion"))) return;
        if(!"PENDING".equals(r.get("status"))) throw invalid("申请已处理");
        finish(requestId,"CANCELLED",actor,note);
    }

    private void lockSource(long id) {
        var row=source(id);
        if(row.get("source_agreement_id")!=null) jdbc.queryForList("select id from agreement_action where id=? for update",row.get("source_agreement_id"));
        jdbc.queryForList("select id from department_interview where id=? for update",id);
    }
    private Map<String,Object> source(long id) {
        var rows=jdbc.queryForList("select * from department_interview where id=?",id);if(rows.isEmpty()) throw denied();return rows.getFirst();
    }
    private String snapshot(long id) {
        return jdbc.queryForObject("""
           select jsonb_build_object('original',to_jsonb(i),'effective',to_jsonb(e),
             'agreement',case when a.id is null then null else jsonb_build_object('cycle',a.agency_cycle,
               'status',a.agency_status,'department',a.agency_dept_id,'result',a.agency_result_type) end)::text
           from department_interview i left join effective_department_interviews() e on e.id=i.id
             left join agreement_action a on a.id=i.source_agreement_id where i.id=?
           """,String.class,id);
    }
    private Map<String,Object> requestRow(long id,boolean lock) {
        var rows=jdbc.queryForList("select * from interview_change_request where id=?"+(lock?" for update":""),id);
        if(rows.isEmpty()) throw denied();return rows.getFirst();
    }
    private void finish(long id,String status,WorkflowUserAccess.User actor,String opinion) {
        jdbc.update("update interview_change_request set status=?,reviewed_by=?,reviewed_username=?,reviewed_at=clock_timestamp(),opinion=? where id=?",status,actor.id(),actor.username(),opinion,id);
    }
    private boolean supervisor(WorkflowUserAccess.User actor) {
        return actor!=null&&actor.enabled()&&"BUREAU".equals(actor.accountCategory())&&actor.roles().stream().anyMatch(Set.of("LEGAL","LEADER","ADMIN")::contains);
    }
    private boolean reviewer(WorkflowUserAccess.User actor) {
        return actor!=null&&actor.enabled()&&"BUREAU".equals(actor.accountCategory())
            &&actor.roles().stream().anyMatch(Set.of("LEGAL","LEADER")::contains)&&actor.deptId()!=null
            &&Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sys_dept where id=? and enabled and org_kind='BUREAU')",Boolean.class,actor.deptId()));
    }
    private void requireReader(WorkflowUserAccess.User actor) {
        if(actor==null||!actor.enabled()||actor.deptId()==null||actor.roles().stream().noneMatch(Set.of("AGENCY","HANDLER","LEGAL","LEADER","ADMIN")::contains)
            ||!Set.of("AGENCY","DISTRICT","BUREAU").contains(Objects.toString(actor.accountCategory(),""))) throw denied();
    }
    private void requireVisible(Map<String,Object> row,WorkflowUserAccess.User actor) {
        if(!supervisor(actor)&&!Objects.equals(actor.deptId(),row.get("dept_id"))) throw denied();
    }
    private void requireWriter(Map<String,Object> row,WorkflowUserAccess.User actor) {
        requireReader(actor);if(!Objects.equals(actor.deptId(),row.get("dept_id"))) throw denied();
        if(actor.roles().stream().noneMatch(Set.of("AGENCY","HANDLER","LEADER","ADMIN")::contains)) throw denied();
        if(!actor.privileged()&&!actor.roles().contains("AGENCY")&&!actor.id().equals(row.get("created_by"))) throw denied();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from sys_dept where id=? and enabled and org_kind=?)",Boolean.class,actor.deptId(),actor.accountCategory()))) throw denied();
    }
    private List<Map<String,Object>> historyRows(List<Map<String,Object>> rows,WorkflowUserAccess.User actor) {
        return rows.stream().map(row->{var copy=new LinkedHashMap<>(row);copy.put("proposed_values",parse(row.get("proposed_values")));copy.put("source_snapshot",parse(row.get("source_snapshot")));
            copy.put("canReview","PENDING".equals(row.get("status"))&&reviewer(actor)&&!actor.id().equals(row.get("requested_by")));
            copy.put("canCancel","PENDING".equals(row.get("status"))&&actor.id().equals(row.get("requested_by")));return (Map<String,Object>)copy;}).toList();
    }
    private String jsonValue(Map<String,Object> row) {
        return json(new DepartmentInterviewService.InterviewInput((String)row.get("institution_code"),(String)row.get("institution_name"),(String)row.get("interview_type"),(String)row.get("reason"),(String)row.get("content"),(String)row.get("participants"),((java.sql.Date)row.get("interview_date")).toLocalDate()));
    }
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw invalid("约谈值无效");}}
    private Object parse(Object value) {try{return mapper.readTree(value.toString());}catch(Exception e){throw invalid("约谈历史不可读");}}
    private boolean same(String value,Object other) {return parse(value).equals(parse(other));}
    private static String text(String s,int max) {if(s==null||s.isBlank()||s.length()>max||s.indexOf(0)>=0)throw invalid("须填写有效理由、出处和复核意见");return s.strip();}
    private static void bounds(int page,int size){if(page<1||size<1||size>100)throw invalid("页码及每页条数无效");}
    private static BizException denied(){return new BizException(403,"当前账号不具备该约谈申请或复核权限，请核对机构类别和岗位");}
    private static BizException invalid(String s){return new BizException(2196,s);}
}
