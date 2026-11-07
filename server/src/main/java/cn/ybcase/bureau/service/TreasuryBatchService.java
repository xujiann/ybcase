package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static cn.ybcase.bureau.service.IntegrationSupport.*;

@Service
@RequiredArgsConstructor
public class TreasuryBatchService {
    private final JdbcTemplate jdbc;
    private final MaterialAccess access;
    private final WorkflowUserAccess workflowUsers;
    private final TreasuryReconciliationService treasury;
    private final PlatformTransactionManager transactionManager;
    public record Entry(String source, long id) {}

    private WorkflowUserAccess.User reader(String username) {
        var actor = access.readActor(username); bureau(actor); return actor;
    }
    private WorkflowUserAccess.User writer(String username) {
        var actor = access.lockActor(username, true); bureau(actor); return actor;
    }
    private MaterialAccess.CaseScope scope(WorkflowUserAccess.User actor) {
        return access.caseScope(actor.username(), actor.roles().stream().anyMatch(Set.of("LEGAL","LEADER","ADMIN")::contains));
    }
    private List<Map<String,Object>> source(Entry entry) {
        return jdbc.queryForList("select * from case_effective_executions((current_timestamp at time zone 'Asia/Shanghai')::date) where source=? and id=? and amount>0",entry.source(),entry.id());
    }
    private void validEntry(Entry entry) {
        if (entry == null || entry.source()==null || !Set.of("ORIGINAL","CONTINUATION").contains(entry.source()) || entry.id() < 1)
            throw invalid("执行记录来源或编号无效");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> sources(String username, int page, int size, String query) {
        if(page<1 || size<1 || size>100) throw invalid("页码或每页数量无效");
        var actor=reader(username); var scope=scope(actor);
        String q=query==null?"":query.strip(); if(q.length()>100) throw invalid("搜索文字过长");
        String from=" from case_effective_executions((current_timestamp at time zone 'Asia/Shanghai')::date) e join case_file on case_file.id=e.case_id where e.amount>0"+scope.sql()+" and (?='' or position(lower(?) in lower(case_file.case_no))>0)";
        var args=new ArrayList<Object>(scope.args()); args.add(q);args.add(q);
        Long total=jdbc.queryForObject("select count(*)"+from,Long.class,args.toArray());
        args.add(size);args.add((page-1L)*size);
        return Map.of("rows",jdbc.queryForList("select e.source,e.id,e.case_id,case_file.case_no,e.kind,e.amount,e.paid_at,e.receipt_no"+from+" order by e.paid_at desc,e.source,e.id desc limit ? offset ?",args.toArray()),"total",total,"page",page,"size",size);
    }
    @Transactional
    public long create(String username, String requestKey, List<Entry> requested) {
        String key=text(requestKey,80,"请求号");
        if(requested==null || requested.isEmpty() || requested.size()>100) throw invalid("每批须选择1至100条执行记录");
        requested.forEach(this::validEntry);
        var entries=requested.stream().sorted(Comparator.comparing(Entry::source).thenComparingLong(Entry::id)).toList();
        if(new HashSet<>(entries).size()!=entries.size()) throw invalid("同一批次不能重复选择执行记录");
        String fingerprint=hash(entries.toString());
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))",Object.class,"treasury:"+username+":"+key);
        var existing=jdbc.queryForList("select id,request_hash from treasury_batch where created_by=? and request_key=?",username,key);
        if(!existing.isEmpty()) {
            if(!fingerprint.equals(existing.getFirst().get("request_hash"))) throw invalid("请求号已用于另一组记录");
            long id=((Number)existing.getFirst().get("id")).longValue(); requireBatch(id,reader(username));return id;
        }
        var snapshots=new ArrayList<Map<String,Object>>();
        for(var entry:entries) {var rows=source(entry); if(rows.isEmpty()) throw invalid("所选记录不存在或不是可核验的正数缴款");snapshots.add(rows.getFirst());}
        snapshots.stream().map(row->((Number)row.get("case_id")).longValue()).distinct().sorted().forEach(id->CaseGuards.lock(jdbc,id));
        var actor=writer(username);
        for(var row:snapshots) access.assertCaseScope(((Number)row.get("case_id")).longValue(),actor);
        Long batch=jdbc.queryForObject("insert into treasury_batch(request_key,request_hash,created_by) values(?,?,?) returning id",Long.class,key,fingerprint,username);
        for(int i=0;i<entries.size();i++) {
            var entry=entries.get(i);var row=source(entry).getFirst();
            jdbc.update("insert into treasury_batch_item(batch_id,source,source_id,case_id,kind,receipt_no,amount,paid_at) values(?,?,?,?,?,?,?,?)",batch,entry.source(),entry.id(),row.get("case_id"),row.get("kind"),row.get("receipt_no"),row.get("amount"),row.get("paid_at"));
        }
        return batch;
    }
    private void requireBatch(long id, WorkflowUserAccess.User actor) {
        var caseScope=scope(actor); var args=new ArrayList<Object>();args.add(id);args.addAll(caseScope.args());
        Long total=jdbc.queryForObject("select count(*) from treasury_batch_item where batch_id=?",Long.class,id);
        Long allowed=jdbc.queryForObject("select count(*) from treasury_batch_item i join case_file on case_file.id=i.case_id where i.batch_id=?"+caseScope.sql(),Long.class,args.toArray());
        if(total==null || total==0 || !total.equals(allowed)) throw new BizException(403,"批次不存在或包含当前不可查看的案件");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> batches(String username,int page,int size) {
        if(page<1 || size<1 || size>100) throw invalid("页码或每页数量无效");
        var actor=reader(username); var scope=scope(actor);
        String allowed="""
            from treasury_batch b where exists(select 1 from treasury_batch_item z where z.batch_id=b.id)
            and not exists(select 1 from treasury_batch_item x where x.batch_id=b.id and x.case_id not in
              (select case_file.id from case_file where true
            """+scope.sql()+"))";
        Long total=jdbc.queryForObject("select count(*) "+allowed,Long.class,scope.args().toArray());
        var args=new ArrayList<Object>(scope.args());args.add(size);args.add((page-1L)*size);
        var rows=jdbc.queryForList("""
            select b.id,b.created_by,b.created_at,count(i.id) as total,
                   count(*) filter(where i.result='PENDING') as pending,
                   count(*) filter(where i.result='MATCH') as matched,
                   count(*) filter(where i.result not in ('PENDING','MATCH')) as differences
            from (select b.*
            """+allowed+" order by b.id desc limit ? offset ?) b join treasury_batch_item i on i.batch_id=b.id group by b.id,b.created_by,b.created_at order by b.id desc",args.toArray());
        return Map.of("rows",rows,"total",total,"page",page,"size",size);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> detail(String username,long id) {
        requireBatch(id,reader(username));
        var batch=jdbc.queryForMap("select id,created_by,created_at from treasury_batch where id=?",id);
        batch.put("items",jdbc.queryForList("select i.*,c.case_no from treasury_batch_item i join case_file c on c.id=i.case_id where i.batch_id=? order by i.id",id));
        batch.put("history",jdbc.queryForList("select h.id,h.item_id,h.action,h.actor,h.note,h.source_reference,h.created_at,h.result_snapshot->>'external_amount' as external_amount,h.result_snapshot->>'external_paid_at' as external_paid_at,h.result_snapshot->>'result' as result from treasury_item_history h join treasury_batch_item i on i.id=h.item_id where i.batch_id=? order by h.id",id));
        return batch;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> itemDetail(String username,long id) {
        var actor=reader(username);var scope=scope(actor);
        var args=new ArrayList<Object>(scope.args());args.add(id);
        var rows=jdbc.queryForList("select i.*,case_file.case_no from treasury_batch_item i join case_file on case_file.id=i.case_id where true"+scope.sql()+" and i.id=?",args.toArray());
        if(rows.isEmpty()) throw new BizException(403,"核对条目不存在或超出当前账号范围");
        var history=jdbc.queryForList("select id,item_id,action,actor,note,source_reference,created_at,result_snapshot->>'external_amount' as external_amount,result_snapshot->>'external_paid_at' as external_paid_at,result_snapshot->>'result' as result from treasury_item_history where item_id=? order by id",id);
        return Map.of("id",rows.getFirst().get("batch_id"),"focusedItem",true,"items",rows,"history",history);
    }
    /** Each ticket commits independently. A crash rolls back only its in-flight read, leaving other results recoverable. */
    public Map<String,Object> run(String username,long batch) {
        var tx=new TransactionTemplate(transactionManager);
        List<Long> ids=tx.execute(status->{requireBatch(batch,reader(username));return jdbc.queryForList("select id from treasury_batch_item where batch_id=? and result in ('PENDING','FAILED','UNCONFIGURED') and review_status not in ('SUBMITTED','RESOLVED') order by case when result='PENDING' then 0 else 1 end,id limit 10",Long.class,batch);});
        for(long id:Objects.requireNonNull(ids)) tx.executeWithoutResult(status->check(username,batch,id));
        return tx.execute(status->{requireBatch(batch,reader(username));return Map.of("processed",ids.size(),"remaining",jdbc.queryForObject("select count(*) from treasury_batch_item where batch_id=? and result='PENDING'",Long.class,batch));});
    }
    private void check(String username,long batch,long id) {
        var raw=jdbc.queryForList("select case_id from treasury_batch_item where id=? and batch_id=?",id,batch);
        if(raw.isEmpty()) throw invalid("核对记录不存在");
        long caseId=((Number)raw.getFirst().get("case_id")).longValue();CaseGuards.lock(jdbc,caseId);
        var item=jdbc.queryForMap("select * from treasury_batch_item where id=? for update",id);
        var actor=writer(username);access.assertCaseScope(caseId,actor);
        if(!Set.of("PENDING","FAILED","UNCONFIGURED").contains(item.get("result")) || Set.of("SUBMITTED","RESOLVED").contains(item.get("review_status"))) return;
        var current=source(new Entry((String)item.get("source"),((Number)item.get("source_id")).longValue()));
        TreasuryReconciliationService.Result result=null;String code=null;
        String state="SOURCE_CHANGED";
        if(!current.isEmpty() && same(item,current.getFirst())) {
            try {result=treasury.lookup((String)item.get("receipt_no"),(BigDecimal)item.get("amount"),date(item.get("paid_at")));state=result.status();}
            catch(BizException ex) {state="FAILED";code=Integer.toString(ex.code);}
        }
        jdbc.update("update treasury_batch_item set result=?,amount_matches=?,date_matches=?,paid=?,external_amount=?,external_paid_at=?,attempts=attempts+1,checked_at=current_timestamp,error_code=?,review_status=? where id=?",state,result==null?null:result.amountMatches(),result==null?null:result.dateMatches(),result==null?null:result.paid(),result==null?null:result.externalAmount(),result==null?null:result.externalPaidAt(),code,"MATCH".equals(state)?"NOT_REQUIRED":"OPEN",id);
        history(id,"CHECK",username,state,null);
        jdbc.update("insert into integration_operation(target,operation,actor,source_id,status,error_code) values('treasury','LOOKUP',?,?,?,?)",username,id,state,code);
    }
    private static LocalDate date(Object value) {return value instanceof java.sql.Date d?d.toLocalDate():(LocalDate)value;}
    private static boolean same(Map<String,Object> left,Map<String,Object> right) {
        return Objects.equals(left.get("kind"),right.get("kind")) && Objects.equals(left.get("receipt_no"),right.get("receipt_no")) && ((BigDecimal)left.get("amount")).compareTo((BigDecimal)right.get("amount"))==0 && date(left.get("paid_at")).equals(date(right.get("paid_at")));
    }
    private Map<String,Object> lockItem(long id) {
        var rows=jdbc.queryForList("select case_id from treasury_batch_item where id=?",id);
        if(rows.isEmpty()) throw invalid("核对记录不存在");
        CaseGuards.lock(jdbc,((Number)rows.getFirst().get("case_id")).longValue());
        return jdbc.queryForMap("select * from treasury_batch_item where id=? for update",id);
    }
    private void difference(Map<String,Object> item) {if(Set.of("PENDING","MATCH").contains(item.get("result"))) throw invalid("该记录无需差异复核");}
    @Transactional
    public void assign(String username,long id,String assignee) {
        String target=text(assignee,64,"承办账号");var item=lockItem(id);
        var locked=workflowUsers.lockUsers(username,target);
        var actor=access.lockActor(username,false);bureau(actor);access.assertCaseScope(((Number)item.get("case_id")).longValue(),actor);
        if(actor.roles().stream().noneMatch(Set.of("LEADER","LEGAL","ADMIN")::contains)) throw new BizException(403,"须由监督岗位分派差异");
        var receiver=locked.get(target);bureau(receiver);
        if(!receiver.enabled() || receiver.roles().stream().noneMatch(Set.of("HANDLER","LEADER")::contains)) throw invalid("承办账号须启用且具备办理岗位");
        workflowUsers.assertDepartmentEnabled(receiver);access.assertCaseScope(((Number)item.get("case_id")).longValue(),receiver);difference(item);
        if("RESOLVED".equals(item.get("review_status"))) throw invalid("已复核差异不能重新分派");
        jdbc.update("update treasury_batch_item set assignee=?,review_status='ASSIGNED',investigated_by=null,investigation_note=null,source_reference=null where id=?",target,id);
        history(id,"ASSIGN",username,target,null);
    }
    @Transactional
    public void investigate(String username,long id,String note,String reference) {
        String explanation=text(note,1000,"核查说明"),ref=text(reference,255,"核查依据");
        var item=lockItem(id);var actor=writer(username);access.assertCaseScope(((Number)item.get("case_id")).longValue(),actor);difference(item);
        if(!username.equals(item.get("assignee")) || !"ASSIGNED".equals(item.get("review_status"))) throw invalid("仅当前差异承办人可提交核查结果");
        jdbc.update("update treasury_batch_item set review_status='SUBMITTED',investigated_by=?,investigation_note=?,source_reference=? where id=?",username,explanation,ref,id);
        history(id,"SUBMIT",username,explanation,ref);
    }
    @Transactional
    public void retry(String username,long id) {
        var item=lockItem(id);var actor=writer(username);access.assertCaseScope(((Number)item.get("case_id")).longValue(),actor);difference(item);
        if(Set.of("SUBMITTED","RESOLVED").contains(item.get("review_status"))) throw invalid("待复核或已复核的结果不能重新核对");
        jdbc.update("update treasury_batch_item set result='PENDING',review_status='OPEN',assignee=null,investigated_by=null,investigation_note=null,source_reference=null where id=?",id);
        history(id,"RETRY",username,"申请重新核对原快照",null);
    }
    @Transactional
    public void review(String username,long id,Boolean approve,String reason) {
        if(approve==null) throw invalid("须选择复核结论");String note=text(reason,1000,"复核说明");
        var item=lockItem(id);var actor=access.lockActor(username,false);bureau(actor);access.assertCaseScope(((Number)item.get("case_id")).longValue(),actor);
        if(actor.roles().stream().noneMatch(Set.of("LEGAL","LEADER")::contains)) throw new BizException(403,"须由法制或负责人复核");
        if(!"SUBMITTED".equals(item.get("review_status")) || username.equals(item.get("investigated_by"))) throw invalid("须由另一名复核人处理已提交核查结果");
        jdbc.update("update treasury_batch_item set review_status=? where id=?",approve?"RESOLVED":"ASSIGNED",id);
        history(id,approve?"APPROVE":"RETURN",username,note,null);
    }
    private void history(long id,String action,String actor,String note,String ref) {
        jdbc.update("""
            insert into treasury_item_history(item_id,action,actor,note,source_reference,result_snapshot)
            select ?,?,?,?,?,jsonb_build_object('result',result,'kind',kind,'receipt_no',receipt_no,'amount',amount,'paid_at',paid_at,
                'external_amount',external_amount,'external_paid_at',external_paid_at,'paid',paid,
                'amount_matches',amount_matches,'date_matches',date_matches,'error_code',error_code,'checked_at',checked_at)
            from treasury_batch_item where id=?
            """,id,action,actor,note,ref,id);
    }
}
