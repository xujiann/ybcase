package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.common.Money;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Independent, reviewed post-decision facts. No mutation of the original legal case or volume. */
@Service
@RequiredArgsConstructor
public class CaseContinuationService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MaterialAccess access;
    private final WorkflowUserAccess users;
    private static final Set<String> TYPES = Set.of("EXECUTION", "REVERSAL", "REFUND", "COURT_APPLY", "COURT_RECEIPT", "REMEDY_RESULT");
    private static final Set<String> KINDS = Set.of("FINE", "RECOUP", "CONFISCATE", "LATE_FEE");

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> state(long caseId, LocalDate cutoff, String username) {
        var actor = access.requireCaseReader(caseId, username);
        requireReader(actor);
        cutoff = cutoff == null ? today() : cutoff;
        if (cutoff.isAfter(today()) || cutoff.getYear()<1) throw invalid("截至日不得晚于今天");
        var source = source(caseId);
        var requests = jdbc.queryForList("select * from case_continuation_request where case_id=? order by id desc", caseId)
                .stream().map(row -> history(row, actor)).toList();
        boolean pending = requests.stream().anyMatch(row -> "PENDING".equals(row.get("status")));
        var result = new LinkedHashMap<String, Object>();
        result.put("eligible", eligible(source));
        result.put("canRequest", eligible(source) && writer(actor) && !pending);
        result.put("canRegisterRemedy", eligible(source) && writer(actor) && !pending);
        result.put("canReview", reviewer(actor));
        result.put("cutoff", cutoff);
        result.put("originalCaseStatus", source.get("status"));
        result.put("originalArchiveNo", source.get("archive_no"));
        var originals=jdbc.queryForList("select * from case_decision where case_id=?", caseId);
        result.put("originalDecision", originals.isEmpty()?Map.of():originals.getFirst());
        result.put("effectiveDecision", effectiveDecision(caseId, cutoff));
        result.put("executions", jdbc.queryForList("select * from case_effective_executions(?) where case_id=? order by paid_at,source,id", cutoff, caseId));
        result.put("balances", balances(caseId, cutoff));
        result.put("courtRounds", courtRounds(caseId));
        result.put("remedies", jdbc.queryForList("""
                select m.*,case when exists(select 1 from case_continuation_request r where r.case_id=m.case_id
                   and r.request_type='REMEDY_RESULT' and (r.payload->>'remedyId')::bigint=m.id and r.status='APPROVED')
                   then 'COMPLETED' else 'OPEN' end as status from case_remedy m where m.case_id=? order by m.id desc
                """, caseId));
        result.put("requests", requests);
        return result;
    }

    @Transactional
    public long registerRemedy(long caseId, Map<String,Object> body, String username) {
        keys(body, Set.of("requestKey","remedyType","acceptedAt","reference","reason","sourceReference"));
        String key = requestKey(body.get("requestKey"));
        String type = choice(body.get("remedyType"), Set.of("RECONSIDERATION","LITIGATION","CORRECTION"), "救济/更正类型");
        LocalDate accepted = day(body.get("acceptedAt"), "受理日期");
        String reference = text(body.get("reference"),128,"受理文号");
        String reason = text(body.get("reason"),1000,"登记理由");
        String evidence = text(body.get("sourceReference"),1000,"材料出处");
        CaseGuards.lock(jdbc, caseId);
        var actor = access.requireCaseWriter(caseId, username);
        requireWriter(actor);
        var source = source(caseId);
        if (!eligible(source) || accepted.isBefore(date(source.get("decided_at")))) throw invalid("须为处理决定作出后的受理记录");
        var old = jdbc.queryForList("select * from case_remedy where case_id=? and request_key=?",caseId,key);
        if (!old.isEmpty()) {
            var row=old.getFirst();
            if (type.equals(row.get("remedy_type")) && accepted.equals(date(row.get("accepted_at")))
                    && reference.equals(row.get("reference")) && reason.equals(row.get("reason"))
                    && evidence.equals(row.get("source_reference")) && actor.id().equals(number(row.get("registered_by"))))
                return number(row.get("id"));
            throw invalid("请求号已用于其他登记内容");
        }
        noPending(caseId);
        return jdbc.queryForObject("""
                insert into case_remedy(case_id,request_key,remedy_type,accepted_at,reference,reason,source_reference,
                   registered_by,registered_username) values(?,?,?,?,?,?,?,?,?) returning id
                """,Long.class,caseId,key,type,accepted,reference,reason,evidence,actor.id(),username);
    }

    @Transactional
    public long request(long caseId, Map<String,Object> body, String username) {
        keys(body,Set.of("type","requestKey","reason","sourceReference","payload"));
        String type=choice(body.get("type"),TYPES,"续办类型");
        String key=requestKey(body.get("requestKey"));
        String reason=text(body.get("reason"),1000,"申请理由");
        String evidence=text(body.get("sourceReference"),1000,"材料出处");
        Map<String,Object> payload=object(body.get("payload"));
        payload.replaceAll((field,value)->value instanceof String s?s.strip():value);
        CaseGuards.lock(jdbc,caseId);
        var actor=access.requireCaseWriter(caseId,username);
        requireWriter(actor);
        var old=jdbc.queryForList("select * from case_continuation_request where case_id=? and request_key=?",caseId,key);
        if (!old.isEmpty()) {
            var row=old.getFirst();
            if (type.equals(row.get("request_type")) && same(payload,row.get("payload")) && reason.equals(row.get("reason"))
                    && evidence.equals(row.get("source_reference")) && actor.id().equals(number(row.get("requested_by")))) return number(row.get("id"));
            throw invalid("请求号已用于其他申请内容");
        }
        if (!eligible(source(caseId))) throw invalid("仅已有处理决定的案件可以办理后续事项");
        noPending(caseId);
        validate(caseId,type,payload);
        return jdbc.queryForObject("""
                insert into case_continuation_request(case_id,request_key,request_type,payload,source_snapshot,reason,
                   source_reference,requested_by,requested_username,requester_dept_id,requester_category)
                values(?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?) returning id
                """,Long.class,caseId,key,type,json(payload),snapshot(caseId),reason,evidence,actor.id(),username,actor.deptId(),actor.accountCategory());
    }

    @Transactional
    public void review(long caseId,long id,Map<String,Object> body,String username) {
        keys(body,Set.of("approve","opinion"));
        if (!(body.get("approve") instanceof Boolean approve)) throw invalid("请选择批准或驳回");
        String opinion=text(body.get("opinion"),2000,"复核意见");
        CaseGuards.lock(jdbc,caseId);
        var request=lockedRequest(caseId,id);
        // Ordered account locking matches account administration and concurrent actor reassignments.
        var actors=users.lockUsers(username,(String)request.get("requested_username"));
        var actor=access.lockActor(username,false);
        requireReviewer(actor);
        access.assertCaseScope(caseId,actor);
        if (actor.id().equals(number(request.get("requested_by")))) throw forbidden("申请人不能复核本人申请");
        pending(request);
        if (approve) {
            var applicant=users.requireFiling(actors.get(request.get("requested_username")));
            requireWriter(applicant);
            access.assertCaseScope(caseId,applicant);
            if (!Objects.equals(applicant.deptId(),number(request.get("requester_dept_id")))
                    || !Objects.equals(applicant.accountCategory(),request.get("requester_category"))
                    || !same(object(snapshot(caseId)),request.get("source_snapshot")))
                throw invalid("来源资料或申请人归属已变化，请驳回后重新申请");
            var payload=object(request.get("payload"));
            String type=(String)request.get("request_type");
            validate(caseId,type,payload);
            finish(id,"APPROVED",actor,opinion);
            apply(caseId,id,type,payload,(String)request.get("reason"));
        } else finish(id,"REJECTED",actor,opinion);
    }

    @Transactional
    public void cancel(long caseId,long id,Map<String,Object> body,String username) {
        keys(body,Set.of("opinion"));
        String opinion=text(body.get("opinion"),2000,"撤回原因");
        CaseGuards.lock(jdbc,caseId);
        var row=lockedRequest(caseId,id);
        var actor=access.lockActor(username,false);
        requireReader(actor);
        access.assertCaseScope(caseId,actor);
        if (!actor.id().equals(number(row.get("requested_by"))) && !actor.roles().contains("LEADER")) throw forbidden("仅申请人或本案负责人可以撤回");
        pending(row);
        finish(id,"CANCELLED",actor,opinion);
    }

    private void validate(long caseId,String type,Map<String,Object> p) {
        switch(type) {
            case "EXECUTION" -> {
                String kind=choice(p.get("kind"),KINDS,"款项类型");
                var fields=new HashSet<>(Set.of("kind","amount","paidAt","receiptNo","method"));
                if ("LATE_FEE".equals(kind)) fields.addAll(Set.of("assessedLateFeeAmount","assessmentReference"));
                keys(p,fields);
                BigDecimal amount=money(p.get("amount"),true);
                LocalDate paid=day(p.get("paidAt"),"实际到账日期");
                String method=choice(p.get("method"),Set.of("BANK","ONSITE","INSTALLMENT"),"缴款方式");
                receipt(p.get("receiptNo"));
                var decision=effectiveDecision(caseId,paid);
                if (decision.isEmpty() || !"PUNISH".equals(decision.get("decision_type"))) throw invalid("到账日期须存在有效处罚决定");
                if (paid.isBefore(date(source(caseId).get("decided_at")))) throw invalid("到账日期不得早于原处理决定");
                if ("ONSITE".equals(method)) {
                    BigDecimal limit=jdbc.queryForObject("select cfg_value::numeric from sys_config where cfg_key='onsite_collect_limit'",BigDecimal.class);
                    if (limit==null || amount.compareTo(limit)>0) throw invalid("当场收缴超出已配置限额");
                }
                // The current effective obligation also caps a historical correction. Existing excess is visible, never hidden.
                BigDecimal cap=cap(effectiveDecision(caseId,today()),kind);
                if (cap.signum()<=0 || total(caseId,kind).add(amount).compareTo(cap)>0) throw invalid("续记后累计金额超出当前有效决定；请先核实冲正或决定结果");
                if ("LATE_FEE".equals(kind)) {
                    BigDecimal assessed=money(p.get("assessedLateFeeAmount"),true);
                    text(p.get("assessmentReference"),2000,"人工核定文号及按日核算依据");
                    Date delivered=jdbc.queryForObject("select delivered_at from case_file where id=?",Date.class,caseId);
                    if (delivered==null || !paid.isAfter(delivered.toLocalDate())) throw invalid("加处罚款续记须已有送达事实并核实实际缴款日期");
                    if (assessed.compareTo(cap)>0 || total(caseId,kind).add(amount).compareTo(assessed)>0)
                        throw invalid("加处罚款累计净额不得超过人工核定总额及当前有效罚款本金");
                }
            }
            case "REVERSAL" -> {
                keys(p,Set.of("source","sourceId","paidAt","receiptNo"));
                var original=reversalSource(caseId,p);
                LocalDate paid=day(p.get("paidAt"),"冲正生效日期");
                if (paid.isBefore(date(original.get("paid_at")))) throw invalid("冲正日期不得早于原到账日期");
                receipt(p.get("receiptNo"));
                String column="ORIGINAL".equals(p.get("source"))?"reverses_execution_id":"reverses_entry_id";
                if (usedSource(p).signum()>0) throw invalid("该笔执行已有冲正或退款，不能再整笔冲正");
            }
            case "REFUND" -> {
                keys(p,Set.of("source","sourceId","paidAt","receiptNo","amount"));
                var original=reversalSource(caseId,p);
                if (day(p.get("paidAt"),"实际退付日期").isBefore(date(original.get("paid_at")))) throw invalid("退付日期不得早于原到账日期");
                receipt(p.get("receiptNo"));
                if (money(p.get("amount"),true).compareTo(((BigDecimal)original.get("amount")).subtract(usedSource(p)))>0)
                    throw invalid("本次退款超过该来源剩余已缴金额，或该笔款项已冲正");
            }
            case "COURT_APPLY" -> {
                keys(p,Set.of("appliedAt","applicationReference","eligibilityBasis"));
                LocalDate applied=day(p.get("appliedAt"),"重新申请日期");
                text(p.get("applicationReference"),128,"申请文号");
                text(p.get("eligibilityBasis"),2000,"补正及再次申请依据");
                var rounds=courtRounds(caseId);
                if (rounds.isEmpty()) {
                    if (!exists("select exists(select 1 from case_decision_version where case_id=? union all select 1 from case_execution_continuation where case_id=?)",caseId,caseId))
                        throw invalid("首次法院申请请使用原执行流程；有独立后续义务时可在此登记");
                    var delivered=jdbc.queryForObject("select delivered_at from case_file where id=?",Date.class,caseId);
                    if (delivered==null || applied.isBefore(delivered.toLocalDate())) throw invalid("申请日期不得早于原决定送达日期");
                } else {
                    if (!"RETURNED".equals(rounds.getFirst().get("status"))) throw invalid("仅法院退回补正后可以登记新申请轮次");
                    LocalDate received=date(rounds.getFirst().get("received_at"));
                    if (received==null || applied.isBefore(received)) throw invalid("再次申请日期不得早于退回回执");
                }
                if (openRemedy(caseId)) throw invalid("尚有未完成复核的救济事项，请先核实办理结果");
                var d=effectiveDecision(caseId,applied);
                if (!"PUNISH".equals(d.get("decision_type")) || KINDS.stream().filter(k->!"LATE_FEE".equals(k))
                        .noneMatch(k->cap(d,k).compareTo(total(caseId,k))>0)) throw invalid("没有尚待执行的有效处罚款项");
            }
            case "COURT_RECEIPT" -> {
                keys(p,Set.of("roundId","status","receivedAt","receiptNo"));
                long round=positiveId(p.get("roundId"));
                var rounds=jdbc.queryForList("select * from case_court_round where id=? and case_id=?",round,caseId);
                if (rounds.isEmpty() || exists("select exists(select 1 from case_court_receipt where round_id=?)",round)) throw invalid("申请轮次不存在或已有回执");
                choice(p.get("status"),Set.of("ACCEPTED","RETURNED","REJECTED"),"法院回执结果");
                if (day(p.get("receivedAt"),"回执日期").isBefore(date(rounds.getFirst().get("applied_at")))) throw invalid("回执日期不得早于本轮申请日期");
                text(p.get("receiptNo"),128,"法院回执文号");
            }
            case "REMEDY_RESULT" -> validateRemedy(caseId,p);
            default -> throw invalid("不支持的续办类型");
        }
    }

    private void validateRemedy(long caseId,Map<String,Object> p) {
        String outcome=choice(p.get("outcome"),Set.of("MAINTAIN","CORRECT","CHANGE","REVOKE"),"结果结论");
        Set<String> fields=new HashSet<>(Set.of("remedyId","outcome","effectiveAt","resultReference","resultContent"));
        if (!"MAINTAIN".equals(outcome)) fields.addAll(Set.of("decisionType","decisionNo","fineAmount","recoupAmount","confiscateAmount","penaltyBasis","content","otherMeasures"));
        keys(p,fields);
        long remedy=positiveId(p.get("remedyId"));
        var rows=jdbc.queryForList("select * from case_remedy where id=? and case_id=?",remedy,caseId);
        if (rows.isEmpty() || exists("select exists(select 1 from case_continuation_request where case_id="+caseId
                +" and request_type='REMEDY_RESULT' and status='APPROVED' and (payload->>'remedyId')::bigint=?)",remedy)) throw invalid("受理记录不存在或已有批准结果");
        LocalDate effective=day(p.get("effectiveAt"),"结果生效日期");
        if (effective.isBefore(date(rows.getFirst().get("accepted_at")))) throw invalid("结果生效日期不得早于受理日期");
        text(p.get("resultReference"),128,"结果文号");
        text(p.get("resultContent"),12000,"结果材料及人工确认结论");
        if ("MAINTAIN".equals(outcome)) return;
        var previous=effectiveDecision(caseId,today());
        if (effective.isBefore(date(previous.get("effective_at")))) throw invalid("不得插入早于当前有效决定版本的结果，请核实历史版本顺序");
        String decision=choice(p.get("decisionType"),Set.of("PUNISH","NO_PUNISH","NOT_ESTABLISHED","TRANSFER_ADMIN","TRANSFER_JUDICIAL","REVOKED"),"有效决定类型");
        BigDecimal fine=money(p.get("fineAmount"),false),recoup=money(p.get("recoupAmount"),false),conf=money(p.get("confiscateAmount"),false);
        if (!"PUNISH".equals(decision) && (fine.signum()!=0 || conf.signum()!=0)) throw invalid("非处罚结果不得确定罚没金额");
        if ("REVOKE".equals(outcome)!= "REVOKED".equals(decision) || "REVOKED".equals(decision) && recoup.signum()!=0) throw invalid("撤销结果须明确选择撤销类型并将款项义务填为零");
        if ("CORRECT".equals(outcome) && (!decision.equals(previous.get("decision_type"))
                || fine.compareTo((BigDecimal)previous.get("fine_amount"))!=0 || recoup.compareTo((BigDecimal)previous.get("recoup_amount"))!=0
                || conf.compareTo((BigDecimal)previous.get("confiscate_amount"))!=0)) throw invalid("文字更正不能改变决定类型或金额；请按实际材料选择变更结果");
        text(p.get("decisionNo"),128,"结果决定文号");
        text(p.get("penaltyBasis"),4000,"决定依据");
        text(p.get("content"),16000,"有效决定正文");
        optional(p.get("otherMeasures"),512,"其他措施");
    }

    private void apply(long caseId,long requestId,String type,Map<String,Object> p,String reason) {
        switch(type) {
            case "EXECUTION" -> jdbc.update("""
                insert into case_execution_continuation(case_id,request_id,kind,amount,paid_at,receipt_no,method,note)
                values(?,?,?,?,?,?,?,?)
                """,caseId,requestId,p.get("kind"),money(p.get("amount"),true),day(p.get("paidAt"),"到账日期"),p.get("receiptNo"),p.get("method"),reason);
            case "REVERSAL" -> {
                var original=reversalSource(caseId,p);
                jdbc.update("""
                    insert into case_execution_continuation(case_id,request_id,kind,amount,paid_at,receipt_no,method,note,reverses_execution_id,reverses_entry_id)
                    values(?,?,?,?,?,?,'REVERSAL',?,?,?)
                    """,caseId,requestId,original.get("kind"),((BigDecimal)original.get("amount")).negate(),day(p.get("paidAt"),"冲正日期"),p.get("receiptNo"),reason,
                        "ORIGINAL".equals(p.get("source"))?positiveId(p.get("sourceId")):null,"CONTINUATION".equals(p.get("source"))?positiveId(p.get("sourceId")):null);
            }
            case "REFUND" -> {
                var original=reversalSource(caseId,p);
                jdbc.update("""
                    insert into case_execution_continuation(case_id,request_id,kind,amount,paid_at,receipt_no,method,note,refunds_execution_id,refunds_entry_id)
                    values(?,?,?,?,?,?,'REFUND',?,?,?)
                    """,caseId,requestId,original.get("kind"),money(p.get("amount"),true).negate(),day(p.get("paidAt"),"退付日期"),p.get("receiptNo"),reason,
                        "ORIGINAL".equals(p.get("source"))?positiveId(p.get("sourceId")):null,"CONTINUATION".equals(p.get("source"))?positiveId(p.get("sourceId")):null);
            }
            case "COURT_APPLY" -> jdbc.update("""
                insert into case_court_round(case_id,request_id,round_no,applied_at,application_reference,eligibility_basis)
                values(?,?,coalesce((select max(round_no)+1 from case_court_round where case_id=?),
                  case when (select court_enforce_applied from case_file where id=?) then 2 else 1 end),?,?,?)
                """,caseId,requestId,caseId,caseId,day(p.get("appliedAt"),"重新申请日期"),p.get("applicationReference"),p.get("eligibilityBasis"));
            case "COURT_RECEIPT" -> jdbc.update("""
                insert into case_court_receipt(case_id,request_id,round_id,status,received_at,receipt_no) values(?,?,?,?,?,?)
                """,caseId,requestId,positiveId(p.get("roundId")),p.get("status"),day(p.get("receivedAt"),"回执日期"),p.get("receiptNo"));
            case "REMEDY_RESULT" -> {
                if (!"MAINTAIN".equals(p.get("outcome"))) jdbc.update("""
                    insert into case_decision_version(case_id,request_id,remedy_id,outcome,effective_at,decision_type,decision_no,
                      fine_amount,recoup_amount,confiscate_amount,penalty_basis,content,other_measures)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,caseId,requestId,positiveId(p.get("remedyId")),p.get("outcome"),day(p.get("effectiveAt"),"生效日期"),p.get("decisionType"),p.get("decisionNo"),
                        money(p.get("fineAmount"),false),money(p.get("recoupAmount"),false),money(p.get("confiscateAmount"),false),p.get("penaltyBasis"),p.get("content"),p.get("otherMeasures"));
            }
            default -> throw invalid("不支持的续办类型");
        }
    }

    private Map<String,Object> reversalSource(long caseId,Map<String,Object> p) {
        String source=choice(p.get("source"),Set.of("ORIGINAL","CONTINUATION"),"原执行来源");
        long id=positiveId(p.get("sourceId"));
        String table="ORIGINAL".equals(source)?"case_execution":"case_execution_continuation";
        var rows=jdbc.queryForList("select * from "+table+" where id=? and case_id=?",id,caseId);
        if (rows.isEmpty() || ((BigDecimal)rows.getFirst().get("amount")).signum()<=0) throw invalid("只能冲正本案尚未冲正的正数执行记录");
        return rows.getFirst();
    }
    private BigDecimal usedSource(Map<String,Object> p) {
        boolean original="ORIGINAL".equals(p.get("source"));
        String reversal=original?"reverses_execution_id":"reverses_entry_id";
        String refund=original?"refunds_execution_id":"refunds_entry_id";
        return jdbc.queryForObject("select coalesce(sum(-amount),0) from case_execution_continuation where "+reversal+"=? or "+refund+"=?",
                BigDecimal.class,positiveId(p.get("sourceId")),positiveId(p.get("sourceId")));
    }
    private void receipt(Object value) {
        String receipt=text(value,128,"凭证号");
        jdbc.queryForList("select pg_advisory_xact_lock(20261107,hashtext(?))",receipt);
        if (exists("select exists(select 1 from case_execution where receipt_no=? union all select 1 from case_execution_continuation where receipt_no=?)",receipt,receipt)) throw invalid("凭证号已登记，不可重复入账");
    }
    private Map<String,Object> source(long id) {
        var rows=jdbc.queryForList("select c.status,c.archive_no,d.decided_at from case_file c left join case_decision d on d.case_id=c.id where c.id=?",id);
        if (rows.isEmpty()) throw new BizException(2043,"案件不存在");
        return rows.getFirst();
    }
    private boolean eligible(Map<String,Object> source) { return source.get("decided_at")!=null && Set.of("DECIDED","DELIVERED","CLOSED","TERMINATED").contains(source.get("status")); }
    private Map<String,Object> effectiveDecision(long caseId,LocalDate cutoff) {
        var rows=jdbc.queryForList("select * from case_effective_decisions(?,clock_timestamp()) where case_id=?",cutoff,caseId);
        return rows.isEmpty()?Map.of():rows.getFirst();
    }
    private Map<String,Object> balances(long id,LocalDate cutoff) {
        var d=effectiveDecision(id,cutoff);
        var result=new LinkedHashMap<String,Object>();
        for (String kind:List.of("FINE","RECOUP","CONFISCATE","LATE_FEE")) {
            BigDecimal paid=jdbc.queryForObject("select coalesce(sum(amount),0) from case_effective_executions(?) where case_id=? and kind=?",BigDecimal.class,cutoff,id,kind);
            BigDecimal obligation=cap(d,kind);
            if ("LATE_FEE".equals(kind)) {
                var assessed=jdbc.queryForList("""
                        select (payload->>'assessedLateFeeAmount')::numeric as amount from case_continuation_request
                        where case_id=? and status='APPROVED' and request_type='EXECUTION' and payload->>'kind'='LATE_FEE'
                          and (payload->>'paidAt')::date<=? and reviewed_at<=clock_timestamp()
                        order by reviewed_at desc,id desc limit 1
                        """,id,cutoff);
                obligation=assessed.isEmpty()?null:(BigDecimal)assessed.getFirst().get("amount");
            }
            var balance=new LinkedHashMap<String,Object>();
            balance.put("obligation",obligation);balance.put("paid",paid);
            balance.put("remaining",obligation==null?null:obligation.subtract(paid));
            balance.put("principalCap","LATE_FEE".equals(kind)?cap(d,kind):null);
            result.put(kind,balance);
        }
        return result;
    }
    private BigDecimal cap(Map<String,Object> d,String kind) {
        if (d.isEmpty() || !"PUNISH".equals(d.get("decision_type"))) return BigDecimal.ZERO;
        return (BigDecimal)d.get(switch(kind) {case "FINE","LATE_FEE"->"fine_amount";case "RECOUP"->"recoup_amount";default->"confiscate_amount";});
    }
    private BigDecimal total(long id,String kind) { return jdbc.queryForObject("select coalesce(sum(amount),0) from case_effective_executions(?,clock_timestamp()) where case_id=? and kind=?",BigDecimal.class,today(),id,kind); }
    private List<Map<String,Object>> courtRounds(long id) {
        var rows=new ArrayList<>(jdbc.queryForList("""
                select r.id,r.round_no,r.applied_at,r.application_reference,coalesce(p.status,'APPLIED') as status,p.received_at,p.receipt_no
                from case_court_round r left join case_court_receipt p on p.round_id=r.id where r.case_id=? order by r.round_no desc
                """,id));
        rows.addAll(jdbc.queryForList("""
                select null::bigint as id,1 as round_no,court_applied_at as applied_at,null::text as application_reference,
                 court_enforce_status as status,court_received_at as received_at,court_receipt_no as receipt_no
                from case_file where id=? and court_enforce_applied
                """,id));
        return rows;
    }
    private boolean openRemedy(long id) {
        return exists("""
                select exists(select 1 from case_remedy m where m.case_id=? and not exists(select 1 from case_continuation_request r
                 where r.case_id=m.case_id and r.request_type='REMEDY_RESULT' and r.status='APPROVED' and (r.payload->>'remedyId')::bigint=m.id))
                """,id);
    }
    private String snapshot(long id) {
        return jdbc.queryForObject("""
                select jsonb_build_object('case',to_jsonb(c),'decision',to_jsonb(d),
                  'executions',(select jsonb_agg(to_jsonb(e) order by id) from case_execution e where e.case_id=c.id),
                  'versions',(select jsonb_agg(to_jsonb(v) order by id) from case_decision_version v where v.case_id=c.id),
                  'continuations',(select jsonb_agg(to_jsonb(e) order by id) from case_execution_continuation e where e.case_id=c.id),
                  'rounds',(select jsonb_agg(to_jsonb(r) order by id) from case_court_round r where r.case_id=c.id),
                  'receipts',(select jsonb_agg(to_jsonb(r) order by id) from case_court_receipt r where r.case_id=c.id),
                  'remedies',(select jsonb_agg(to_jsonb(m) order by id) from case_remedy m where m.case_id=c.id))::text
                from case_file c join case_decision d on d.case_id=c.id where c.id=?
                """,String.class,id);
    }
    private Map<String,Object> history(Map<String,Object> row,WorkflowUserAccess.User actor) {
        var result=new LinkedHashMap<String,Object>();
        result.put("id",row.get("id")); result.put("type",row.get("request_type"));result.put("payload",object(row.get("payload")));
        for (String name:List.of("status","reason","opinion")) result.put(name,row.get(name));
        result.put("sourceReference",row.get("source_reference"));result.put("requestedBy",row.get("requested_username"));result.put("requestedAt",row.get("requested_at"));
        result.put("reviewedBy",row.get("reviewed_username"));result.put("reviewedAt",row.get("reviewed_at"));
        result.put("canReview","PENDING".equals(row.get("status")) && reviewer(actor) && !actor.id().equals(number(row.get("requested_by"))));
        result.put("canCancel","PENDING".equals(row.get("status")) && (actor.id().equals(number(row.get("requested_by"))) || actor.roles().contains("LEADER")));
        return result;
    }
    private Map<String,Object> lockedRequest(long caseId,long id) {
        var rows=jdbc.queryForList("select * from case_continuation_request where case_id=? and id=? for update",caseId,id);
        if (rows.isEmpty()) throw forbidden("续办申请不存在或不属于本案");return rows.getFirst();
    }
    private void finish(long id,String status,WorkflowUserAccess.User actor,String opinion) { jdbc.update("update case_continuation_request set status=?,reviewed_by=?,reviewed_username=?,reviewed_at=clock_timestamp(),opinion=? where id=?",status,actor.id(),actor.username(),opinion,id); }
    private void pending(Map<String,Object> row) { if (!"PENDING".equals(row.get("status"))) throw invalid("申请已办结，不可覆盖历史"); }
    private void noPending(long id) { if (exists("select exists(select 1 from case_continuation_request where case_id=? and status='PENDING')",id)) throw invalid("本案已有待复核续办申请"); }
    private boolean exists(String sql,Object...args) { return Boolean.TRUE.equals(jdbc.queryForObject(sql,Boolean.class,args)); }
    private boolean writer(WorkflowUserAccess.User actor) { return actor.roles().stream().anyMatch(Set.of("HANDLER","LEADER","ADMIN")::contains); }
    private boolean reviewer(WorkflowUserAccess.User actor) { return actor.roles().contains("LEGAL") || actor.roles().contains("LEADER"); }
    private void requireReader(WorkflowUserAccess.User actor) {
        if (!Set.of("DISTRICT","BUREAU").contains(actor.accountCategory()==null?"":actor.accountCategory()) || actor.deptId()==null
                || actor.roles().contains("AGENCY") || actor.roles().stream().noneMatch(Set.of("HANDLER","LEGAL","LEADER","ADMIN")::contains)) throw forbidden("须使用已归类的行政办案账号");
    }
    private void requireWriter(WorkflowUserAccess.User actor) { requireReader(actor);if (!writer(actor)) throw forbidden("当前岗位无权提交续办事项"); }
    private void requireReviewer(WorkflowUserAccess.User actor) { requireReader(actor);if (!reviewer(actor)) throw forbidden("须由另一名法制或负责人复核；管理员须兼任相应岗位"); }
    private static void keys(Map<String,Object> body,Set<String> allowed) { if (body==null || !body.keySet().equals(allowed)) throw invalid("请求字段缺失或包含未支持的字段"); }
    private static String text(Object value,int max,String label) { if (!(value instanceof String s) || s.isBlank() || s.codePointCount(0,s.length())>max || s.indexOf(0)>=0) throw invalid(label+"须为不超过"+max+"字的非空文本");return s.strip(); }
    private static String optional(Object value,int max,String label) { return value==null || value instanceof String s && s.isBlank()?null:text(value,max,label); }
    private static String choice(Object value,Set<String> choices,String label) { if (!(value instanceof String s) || !choices.contains(s)) throw invalid(label+"无效");return s; }
    private static String requestKey(Object value) { String s=text(value,64,"请求号");if (!s.matches("[A-Za-z0-9_-]{8,64}")) throw invalid("请求号格式无效");return s; }
    private static Long number(Object value) { return value instanceof Number n?n.longValue():null; }
    private static long positiveId(Object value) { try { if (!(value instanceof Number n)) throw new IllegalArgumentException();return new BigDecimal(n.toString()).toBigIntegerExact().longValueExact()>0?new BigDecimal(n.toString()).longValueExact():failId(); } catch (ArithmeticException|IllegalArgumentException e) { throw invalid("编号须为正整数"); } }
    private static long failId() { throw invalid("编号须为正整数"); }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private static LocalDate day(Object value,String label) { try { String s=text(value,10,label);if (!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();LocalDate d=LocalDate.parse(s);if (d.getYear()<1 || d.isAfter(today())) throw new IllegalArgumentException();return d; } catch (java.time.DateTimeException|IllegalArgumentException e) { throw invalid(label+"须为不晚于今天的有效日期"); } }
    private static LocalDate date(Object value) { return value instanceof Date d?d.toLocalDate():value instanceof LocalDate d?d:null; }
    private static BigDecimal money(Object value,boolean positive) { if (!(value instanceof Number n)) throw invalid("金额须为精确数字");BigDecimal amount;try {amount=new BigDecimal(n.toString());}catch(NumberFormatException e){throw invalid("金额无效");}return positive?Money.positive(amount,"续办金额"):Money.nonNegative(amount,"决定金额"); }
    private String json(Object value) { try{return mapper.writeValueAsString(value);}catch(Exception e){throw invalid("请求格式无效");} }
    private Map<String,Object> object(Object value) { try { if (value instanceof Map<?,?> map) {var result=new LinkedHashMap<String,Object>();for(var entry:map.entrySet()){if(!(entry.getKey() instanceof String key))throw invalid("字段无效");result.put(key,entry.getValue());}return result;}return mapper.readValue(value.toString(),new TypeReference<Map<String,Object>>(){}); }catch(Exception e){throw invalid("请求内容须为对象");} }
    private boolean same(Map<String,Object> a,Object b) { try {return mapper.readTree(json(a)).equals(mapper.readTree(b.toString()));}catch(Exception e){throw invalid("快照格式无效");} }
    private static BizException invalid(String message) { return new BizException(2193,message); }
    private static BizException forbidden(String message) { return new BizException(403,message); }
}
