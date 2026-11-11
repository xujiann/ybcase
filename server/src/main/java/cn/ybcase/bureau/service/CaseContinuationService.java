package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.common.Money;
import cn.ybcase.bureau.repository.CaseFileRepository;
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
    private final CaseFactService factHistory;
    private final SupplementBundleService materialBundles;
    private final CaseFileRepository caseRepository;
    private final ExecutionDeadlines executionDeadlines;
    private static final Set<String> TYPES = Set.of("EXECUTION", "REVERSAL", "REFUND", "COURT_APPLY", "COURT_RECEIPT",
            "REMEDY_RESULT", "REMEDY_VOID", "FACT_CORRECTION", "COURT_RECEIPT_CORRECTION");
    private static final Set<String> FACT_FIELDS=Set.of("institutionCode","institutionOwnership","institutionGrade","institutionKind",
            "confirmedViolationStart","confirmedViolationEnd","confirmedViolationFacts","confirmedViolationAmount");
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
        result.put("canRegisterRemedy", eligible(source) && writer(actor));
        result.put("canReview", reviewer(actor));
        result.put("cutoff", cutoff);
        result.put("originalCaseStatus", source.get("status"));
        result.put("originalArchiveNo", source.get("archive_no"));
        var originals=jdbc.queryForList("select * from case_decision where case_id=?", caseId);
        result.put("originalDecision", originals.isEmpty()?Map.of():originals.getFirst());
        result.put("effectiveDecision", effectiveDecision(caseId, cutoff));
        result.put("effectiveFacts",jdbc.queryForMap("select * from case_effective_reporting_facts(?) where case_id=?",cutoff,caseId));
        result.put("executions", jdbc.queryForList("select * from case_effective_executions(?) where case_id=? order by paid_at,source,id", cutoff, caseId));
        result.put("balances", balances(caseId, cutoff));
        result.put("courtRounds", courtRounds(caseId));
        result.put("remedies", jdbc.queryForList("select * from case_remedy_status where case_id=? order by id desc",caseId));
        var current = executionDeadlines.summary(CaseGuards.get(caseRepository,caseId),today());
        result.put("executionDeadline",current);
        result.put("canRegisterExecutionBasis",reviewer(actor) && !pending && current.get("decisionVersionId") != null
                && "PUNISH".equals(current.get("decisionType")) && current.get("deliveryId") != null
                && (current.get("courtStatus") == null || "RETURNED".equals(current.get("courtStatus"))));
        result.put("canRegisterVersionDefer",actor.roles().contains("LEADER"));
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
        return jdbc.queryForObject("""
                insert into case_remedy(case_id,request_key,remedy_type,accepted_at,reference,reason,source_reference,
                   registered_by,registered_username) values(?,?,?,?,?,?,?,?,?) returning id
                """,Long.class,caseId,key,type,accepted,reference,reason,evidence,actor.id(),username);
    }

    @Transactional
    public long registerExecutionBasis(long caseId,Map<String,Object> body,String username) {
        allowedKeys(body,Set.of("decisionVersionId","deliveryId","reliefDeadline","reliefPending","basis"),Set.of("deferUntil"));
        long versionId = positiveId(body.get("decisionVersionId"));
        long deliveryId = positiveId(body.get("deliveryId"));
        LocalDate relief = deadlineDate(body.get("reliefDeadline"),"救济期限届满日");
        LocalDate defer = body.get("deferUntil") == null ? null : deadlineDate(body.get("deferUntil"),"暂缓或分期届满日");
        if (!(body.get("reliefPending") instanceof Boolean pending)) throw invalid("请选择救济是否尚未办结");
        String basis = text(body.get("basis"),2000,"期限及批准材料依据");
        CaseGuards.lock(jdbc,caseId);
        var actor = access.lockActor(username,false);
        requireReviewer(actor);
        access.assertCaseScope(caseId,actor);
        if (defer != null && !actor.roles().contains("LEADER")) throw forbidden("暂缓或分期期限须由负责人核定");
        var c = CaseGuards.get(caseRepository,caseId);
        LocalDate today = today();
        var decision = executionDeadlines.effectiveDecision(caseId,today);
        if (!"PUNISH".equals(decision.get("decision_type")) || !Objects.equals(versionId,ExecutionDeadlines.version(decision)))
            throw invalid("请选择当前有效的处罚决定版本");
        var delivery = executionDeadlines.effectiveDelivery(c,decision,today);
        if (delivery == null || !Objects.equals(deliveryId,delivery.id()))
            throw invalid("当前决定版本的有效送达记录已变化，请重新核实");
        var rows = jdbc.queryForList("""
                select id,delivery_id,relief_deadline,relief_pending,basis,defer_until,recorded_by
                from case_version_execution_basis where decision_version_id=? order by id desc limit 1
                """,versionId);
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            if (defer == null) defer = date(row.get("defer_until"));
            if (Objects.equals(deliveryId,number(row.get("delivery_id"))) && relief.equals(date(row.get("relief_deadline")))
                    && pending.equals(row.get("relief_pending")) && basis.equals(row.get("basis"))
                    && Objects.equals(defer,date(row.get("defer_until"))) && username.equals(row.get("recorded_by")))
                return number(row.get("id"));
        }
        if (relief.isBefore(delivery.date()) || defer != null && defer.isBefore(delivery.date()))
            throw invalid("期限届满日不得早于当前决定送达日");
        noPending(caseId);
        var round = executionDeadlines.currentCourtRound(c,decision,today);
        if (round != null && !"RETURNED".equals(round.status()))
            throw invalid("当前决定已登记法院申请，须有退回补正回执方可重新核定期限");
        return jdbc.queryForObject("""
                insert into case_version_execution_basis(case_id,decision_version_id,delivery_id,relief_deadline,
                    relief_pending,basis,defer_until,recorded_by) values(?,?,?,?,?,?,?,?) returning id
                """,Long.class,caseId,versionId,deliveryId,relief,pending,basis,defer,username);
    }

    @Transactional
    public long request(long caseId, Map<String,Object> body, String username) {
        allowedKeys(body,Set.of("type","requestKey","reason","sourceReference","payload"),Set.of("materialBundleId"));
        String type=choice(body.get("type"),TYPES,"续办类型");
        String key=requestKey(body.get("requestKey"));
        String reason=text(body.get("reason"),1000,"申请理由");
        String evidence=text(body.get("sourceReference"),1000,"材料出处");
        Map<String,Object> payload=object(body.get("payload"));
        Long bundleId=body.get("materialBundleId")==null?null:positiveId(body.get("materialBundleId"));
        payload.replaceAll((field,value)->value instanceof String s?s.strip():value);
        CaseGuards.lock(jdbc,caseId);
        var actor=access.requireCaseWriter(caseId,username);
        requireWriter(actor);
        var old=jdbc.queryForList("select * from case_continuation_request where case_id=? and request_key=?",caseId,key);
        if (!old.isEmpty()) {
            var row=old.getFirst();
            if (type.equals(row.get("request_type")) && same(payload,row.get("payload")) && reason.equals(row.get("reason"))
                    && evidence.equals(row.get("source_reference")) && actor.id().equals(number(row.get("requested_by")))) {
                long existingId=number(row.get("id"));
                materialBundles.attachToRequest(caseId,existingId,bundleId,username);
                return existingId;
            }
            throw invalid("请求号已用于其他申请内容");
        }
        if (!eligible(source(caseId))) throw invalid("仅已有处理决定的案件可以办理后续事项");
        noPending(caseId);
        validate(caseId,type,payload);
        long requestId=jdbc.queryForObject("""
                insert into case_continuation_request(case_id,request_key,request_type,payload,source_snapshot,reason,
                   source_reference,requested_by,requested_username,requester_dept_id,requester_category)
                values(?,?,?,?::jsonb,?::jsonb,?,?,?,?,?,?) returning id
                """,Long.class,caseId,key,type,json(payload),snapshot(caseId,type,payload),reason,evidence,actor.id(),username,actor.deptId(),actor.accountCategory());
        materialBundles.attachToRequest(caseId,requestId,bundleId,username);
        return requestId;
    }

    @Transactional
    public void review(long caseId,long id,Map<String,Object> body,String username) {
        keys(body,Set.of("approve","opinion"));
        if (!(body.get("approve") instanceof Boolean approve)) throw invalid("请选择批准或驳回");
        String opinion=text(body.get("opinion"),2000,"复核意见");
        CaseGuards.lock(jdbc,caseId);
        var request=lockedRequest(caseId,id);
        boolean factReview=needsFactReviewer(request);
        Long observedDepartment=null;
        if(factReview) {
            var department=jdbc.queryForList("select dept_id from sys_user where username=?",Long.class,username);
            observedDepartment=department.isEmpty()?null:department.getFirst();
            if(observedDepartment!=null) jdbc.queryForList("select id from sys_dept where id=? for share",observedDepartment);
        }
        // Ordered account locking matches account administration and concurrent actor reassignments.
        var actors=users.lockUsers(username,(String)request.get("requested_username"));
        var actor=access.lockActor(username,false);
        requireReviewer(actor);
        access.assertCaseScope(caseId,actor);
        if(factReview && (!Objects.equals(observedDepartment,actor.deptId()) || !factReviewer(actor)))
            throw forbidden("本项更正须由另一名所属机构已启用并归类的局机关法制或负责人复核；请核对当前机构归属");
        if (actor.id().equals(number(request.get("requested_by")))) throw forbidden("申请人不能复核本人申请");
        pending(request);
        if (approve) {
            var applicant=users.requireFiling(actors.get(request.get("requested_username")));
            requireWriter(applicant);
            access.assertCaseScope(caseId,applicant);
            var payload=object(request.get("payload"));
            String type=(String)request.get("request_type");
            if (!Objects.equals(applicant.deptId(),number(request.get("requester_dept_id")))
                    || !Objects.equals(applicant.accountCategory(),request.get("requester_category"))
                    || !same(object(snapshot(caseId,type,payload)),request.get("source_snapshot")))
                throw invalid("来源资料或申请人归属已变化，请驳回后重新申请");
            validate(caseId,type,payload);
            materialBundles.assertRequestMaterials(caseId,id);
            finish(id,"APPROVED",actor,opinion);
            apply(caseId,id,type,payload,(String)request.get("reason"),(String)request.get("source_reference"),username);
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
                BigDecimal cap=cap(decision,kind);
                if (cap.signum()<=0 || exceedsExecutionCap(caseId,kind,paid,amount))
                    throw invalid("续记后累计金额超出对应日期有效决定额度；请先核实到账、退付或决定结果");
                if ("LATE_FEE".equals(kind)) {
                    BigDecimal assessed=money(p.get("assessedLateFeeAmount"),true);
                    text(p.get("assessmentReference"),2000,"人工核定文号及按日核算依据");
                    var delivered = executionDeadlines.effectiveDelivery(CaseGuards.get(caseRepository,caseId),decision,paid);
                    if (delivered == null || !paid.isAfter(delivered.date()))
                        throw invalid("加处罚款续记须有到账当日有效决定的送达事实并核实实际缴款日期");
                    if (assessed.compareTo(cap)>0 || total(caseId,kind).add(amount).compareTo(assessed)>0)
                        throw invalid("加处罚款累计净额不得超过人工核定总额及到账日有效罚款本金");
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
                LocalDate applied=day(p.get("appliedAt"),"实际申请日期");
                text(p.get("applicationReference"),128,"申请文号");
                text(p.get("eligibilityBasis"),2000,"本轮申请材料依据");
                LocalDate today = today();
                var c = CaseGuards.get(caseRepository,caseId);
                var decision = executionDeadlines.effectiveDecision(caseId,today);
                var round = executionDeadlines.currentCourtRound(c,decision,today);
                if (round == null) {
                    if (ExecutionDeadlines.version(decision) == null
                            && !exists("select exists(select 1 from case_execution_continuation where case_id=?)",caseId))
                        throw invalid("首次法院申请请使用原执行流程；有独立后续义务时可在此登记");
                } else {
                    if (!"RETURNED".equals(round.status())) throw invalid("仅法院退回补正后可以登记当前决定的新轮次");
                    if (round.receivedAt() == null || applied.isBefore(round.receivedAt()))
                        throw invalid("再次申请日期不得早于退回回执");
                }
                executionDeadlines.requireCourtApplication(c,decision,applied,today,true);
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
            case "COURT_RECEIPT_CORRECTION" -> validateCourtReceiptCorrection(caseId,p);
            case "REMEDY_RESULT" -> validateRemedy(caseId,p);
            case "REMEDY_VOID" -> {
                keys(p,Set.of("remedyId"));
                openRemedyRecord(caseId,positiveId(p.get("remedyId")));
            }
            case "FACT_CORRECTION" -> {
                allowedKeys(p,Set.of("effectiveAt"),FACT_FIELDS);
                LocalDate effective=day(p.get("effectiveAt"),"事实更正生效日期");
                if(effective.isBefore(date(source(caseId).get("decided_at")))) throw invalid("决定后的事实更正不得早于原决定日期");
                if(normalizedFacts(caseId,p).isEmpty()) throw invalid("须填写要更正的完整机构资料或完整认定事实");
            }
            default -> throw invalid("不支持的续办类型");
        }
    }

    private Long correctionRound(Map<String,Object> payload) {
        return "LEGACY".equals(payload.get("round")) ? null : positiveId(payload.get("round"));
    }

    private void validateCourtReceiptCorrection(long caseId, Map<String,Object> payload) {
        keys(payload,Set.of("round","status","receivedAt","receiptNo"));
        Long round = correctionRound(payload);
        String status = choice(payload.get("status"),Set.of("ACCEPTED","RETURNED","REJECTED"),"法院回执结果");
        LocalDate received = day(payload.get("receivedAt"),"回执日期");
        String receiptNo = text(payload.get("receiptNo"),128,"法院回执文号");
        var rounds = jdbc.queryForList("""
                select * from case_effective_court_receipt(?)
                where case_id=? and id is not distinct from ?::bigint and status in ('ACCEPTED','RETURNED','REJECTED')
                """,today(),caseId,round);
        if (rounds.isEmpty()) throw invalid("仅可更正本案已登记的法院回执");
        var current = rounds.getFirst();
        LocalDate applied = date(current.get("applied_at"));
        if (applied == null) applied = date(source(caseId).get("decided_at"));
        if (received.isBefore(applied)) throw invalid("回执日期不得早于本轮申请日期");
        if (status.equals(current.get("status")) && received.equals(date(current.get("received_at")))
                && receiptNo.equals(current.get("receipt_no"))) throw invalid("请填写有变化的法院回执信息");
        var next = jdbc.queryForList("""
                select applied_at from case_court_round
                where case_id=? and round_no>? and decision_version_id is not distinct from ?::bigint
                order by round_no limit 1
                """,caseId,current.get("round_no"),current.get("decision_version_id"));
        if (!next.isEmpty() && (!"RETURNED".equals(status) || received.isAfter(date(next.getFirst().get("applied_at")))))
            throw invalid("本决定已有下一轮申请，更正仍须支持其退回补正依据和日期顺序");
    }

    private void validateRemedy(long caseId,Map<String,Object> p) {
        String outcome=choice(p.get("outcome"),Set.of("MAINTAIN","CORRECT","CHANGE","REVOKE"),"结果结论");
        Set<String> fields=new HashSet<>(Set.of("remedyId","outcome","effectiveAt","resultReference","resultContent"));
        Set<String> optionalFields=new HashSet<>();
        if (!"MAINTAIN".equals(outcome)) {
            fields.addAll(Set.of("decisionType","decisionNo","fineAmount","recoupAmount","confiscateAmount","penaltyBasis","content"));
            optionalFields.add("otherMeasures");optionalFields.addAll(FACT_FIELDS);
        }
        allowedKeys(p,fields,optionalFields);
        long remedy=positiveId(p.get("remedyId"));
        var record = openRemedyRecord(caseId,remedy);
        LocalDate effective=day(p.get("effectiveAt"),"结果生效日期");
        if (effective.isBefore(date(record.get("accepted_at")))) throw invalid("结果生效日期不得早于受理日期");
        text(p.get("resultReference"),128,"结果文号");
        text(p.get("resultContent"),12000,"结果材料及人工确认结论");
        if ("MAINTAIN".equals(outcome)) return;
        var facts=normalizedFacts(caseId,p);
        if("CORRECT".equals(outcome) && !facts.isEmpty()) throw invalid("文字更正不能改变结构化认定事实；请按材料选择变更结果或独立事实更正");
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

    private Map<String,Object> openRemedyRecord(long caseId,long remedyId) {
        var rows = jdbc.queryForList("select * from case_remedy_status where id=? and case_id=? and status='OPEN'",remedyId,caseId);
        if (rows.isEmpty()) throw invalid("受理记录不存在、已作废或已有批准结果");
        return rows.getFirst();
    }

    private void apply(long caseId,long requestId,String type,Map<String,Object> p,String reason,String reference,String username) {
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
                insert into case_court_round(case_id,request_id,round_no,applied_at,application_reference,eligibility_basis,decision_version_id)
                values(?,?,coalesce((select max(round_no)+1 from case_court_round where case_id=?),
                  case when (select court_enforce_applied from case_file where id=?) then 2 else 1 end),?,?,?,?)
                """,caseId,requestId,caseId,caseId,day(p.get("appliedAt"),"实际申请日期"),p.get("applicationReference"),
                    p.get("eligibilityBasis"),ExecutionDeadlines.version(effectiveDecision(caseId,today())));
            case "COURT_RECEIPT" -> jdbc.update("""
                insert into case_court_receipt(case_id,request_id,round_id,status,received_at,receipt_no) values(?,?,?,?,?,?)
                """,caseId,requestId,positiveId(p.get("roundId")),p.get("status"),day(p.get("receivedAt"),"回执日期"),p.get("receiptNo"));
            case "COURT_RECEIPT_CORRECTION" -> jdbc.update("""
                insert into case_court_receipt_correction(case_id,request_id,round_id,status,received_at,receipt_no)
                values(?,?,?,?,?,?)
                """,caseId,requestId,correctionRound(p),p.get("status"),day(p.get("receivedAt"),"回执日期"),
                text(p.get("receiptNo"),128,"法院回执文号"));
            case "REMEDY_RESULT" -> {
                if (!"MAINTAIN".equals(p.get("outcome"))) jdbc.update("""
                    insert into case_decision_version(case_id,request_id,remedy_id,outcome,effective_at,decision_type,decision_no,
                      fine_amount,recoup_amount,confiscate_amount,penalty_basis,content,other_measures)
                    values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,caseId,requestId,positiveId(p.get("remedyId")),p.get("outcome"),day(p.get("effectiveAt"),"生效日期"),p.get("decisionType"),p.get("decisionNo"),
                        money(p.get("fineAmount"),false),money(p.get("recoupAmount"),false),money(p.get("confiscateAmount"),false),p.get("penaltyBasis"),p.get("content"),p.get("otherMeasures"));
                var facts=normalizedFacts(caseId,p);
                if(!facts.isEmpty()) recordApprovedFacts(caseId,requestId,"APPROVED_RESULT",p,facts,reason,reference,username);
            }
            case "REMEDY_VOID" -> { /* The approved request is the immutable void event. */ }
            case "FACT_CORRECTION" -> recordApprovedFacts(caseId,requestId,"APPROVED_FACT_CORRECTION",p,normalizedFacts(caseId,p),reason,reference,username);
            default -> throw invalid("不支持的续办类型");
        }
    }

    private void recordApprovedFacts(long caseId,long requestId,String type,Map<String,Object> p,Map<String,Object> facts,
                                     String reason,String reference,String username) {
        Map<String,Object> previous=jdbc.queryForMap("select * from case_effective_reporting_facts() where case_id=?",caseId);
        factHistory.record(caseId,type,requestId,null,day(p.get("effectiveAt"),"生效日期"),facts,previous,reason,reference,username);
    }

    private Map<String,Object> normalizedFacts(long caseId,Map<String,Object> p) {
        var result=new LinkedHashMap<String,Object>();
        boolean institution=List.of("institutionCode","institutionOwnership","institutionGrade","institutionKind")
                .stream().anyMatch(key->p.get(key)!=null && !"".equals(p.get(key)));
        if(institution) {
            String party=jdbc.queryForObject("select party_type from case_file where id=?",String.class,caseId);
            var value=CaseService.normalizeInstitution(party,new CaseService.InstitutionReq(
                    text(p.get("institutionCode"),64,"机构编码"),choice(p.get("institutionOwnership"),Set.of("PUBLIC","SOCIAL"),"机构性质"),
                    choice(p.get("institutionGrade"),Set.of("TERTIARY","SECONDARY","PRIMARY","UNGRADED"),"机构等级"),
                    p.get("institutionKind")==null?null:choice(p.get("institutionKind"),Set.of("MEDICAL","SINGLE_PHARMACY","CHAIN_PHARMACY"),"机构类型")));
            result.put("institutionCode",value.code());result.put("institutionOwnership",value.ownership());result.put("institutionGrade",value.grade());
            if(value.kind()!=null) result.put("institutionKind",value.kind());
        }
        boolean finding=List.of("confirmedViolationStart","confirmedViolationEnd","confirmedViolationFacts","confirmedViolationAmount")
                .stream().anyMatch(key->p.get(key)!=null && !"".equals(p.get(key)));
        if(finding) {
            LocalDate start=day(p.get("confirmedViolationStart"),"违法开始日期"),end=day(p.get("confirmedViolationEnd"),"违法结束日期");
            if(start.isAfter(end) || end.isAfter(day(p.get("effectiveAt"),"生效日期"))) throw invalid("违法期间须有效且不晚于结果生效日期");
            result.put("confirmedViolationStart",start.toString());result.put("confirmedViolationEnd",end.toString());
            result.put("confirmedViolationFacts",text(p.get("confirmedViolationFacts"),12000,"认定违法事实"));
            result.put("confirmedViolationAmount",money(p.get("confirmedViolationAmount"),false));
        }
        return result;
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
        return executionDeadlines.effectiveDecision(caseId,cutoff);
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
        return (BigDecimal)d.get(obligationColumn(kind));
    }
    private static String obligationColumn(String kind) {
        return switch(kind) {case "FINE","LATE_FEE"->"fine_amount";case "RECOUP"->"recoup_amount";default->"confiscate_amount";};
    }
    private boolean exceedsExecutionCap(long caseId,String kind,LocalDate paid,BigDecimal amount) {
        // Check every affected balance. Later refunds cannot erase an earlier excess,
        // while a later reduction never prevents recording a payment lawful when made.
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                with ledger as (
                    select paid_at,sum(amount) amount from case_effective_executions(?,clock_timestamp())
                    where case_id=? and kind=? group by paid_at
                ), days as (
                    select cast(? as date) as balance_day union select cast(? as date)
                    union select paid_at from ledger where paid_at>=?
                    union select v.effective_at from case_decision_version v
                    join case_continuation_request r on r.id=v.request_id and r.status='APPROVED'
                    where v.case_id=? and v.effective_at between ? and ? and r.reviewed_at<=clock_timestamp()
                ), balances as (
                    select days.balance_day,
                        coalesce((select sum(amount) from ledger where paid_at<?),0)
                            + sum(coalesce(ledger.amount,0)) over(order by days.balance_day) as paid_total,
                        case when d.decision_type='PUNISH' then d.%s else 0 end as obligation
                    from days left join ledger on ledger.paid_at=days.balance_day
                    left join lateral case_effective_decisions(days.balance_day,clock_timestamp()) d on d.case_id=?
                ), limits as (
                    select paid_total,max(obligation) over(order by balance_day) as obligation from balances
                )
                select exists(select 1 from limits where paid_total+?>obligation)
                """.formatted(obligationColumn(kind)),Boolean.class,today(),caseId,kind,paid,today(),paid,
                caseId,paid,today(),paid,caseId,amount));
    }
    private BigDecimal total(long id,String kind) { return jdbc.queryForObject("select coalesce(sum(amount),0) from case_effective_executions(?,clock_timestamp()) where case_id=? and kind=?",BigDecimal.class,today(),id,kind); }
    private List<Map<String,Object>> courtRounds(long id) {
        return jdbc.queryForList("select * from case_effective_court_receipt(?) where case_id=? order by round_no desc",today(),id);
    }
    private String snapshot(long caseId,String type,Map<String,Object> payload) {
        String original = snapshot(caseId);
        if (!"COURT_APPLY".equals(type)) return original;
        var source = object(original);
        var c = CaseGuards.get(caseRepository,caseId);
        LocalDate today = today();
        var decision = effectiveDecision(caseId,today);
        var window = executionDeadlines.window(c,decision,today);
        var proof = new LinkedHashMap<String,Object>();
        var delivery = window.delivery();
        proof.put("deliveryId",delivery == null ? null : delivery.id());
        proof.put("deliveredAt",delivery == null ? null : delivery.date());
        proof.put("basisId",window.basisId());
        proof.put("start",window.start());
        proof.put("end",window.end());
        proof.put("urgedAt",delivery == null ? null : executionDeadlines.urgedAt(caseId,ExecutionDeadlines.version(decision),
                delivery.date(),day(payload.get("appliedAt"),"实际申请日期")));
        source.put("courtEligibility",proof);
        return json(source);
    }
    private String snapshot(long id) {
        return jdbc.queryForObject("""
                select (jsonb_build_object('case',to_jsonb(c),'decision',to_jsonb(d),
                  'executions',(select jsonb_agg(to_jsonb(e) order by id) from case_execution e where e.case_id=c.id),
                  'versions',(select jsonb_agg(to_jsonb(v) order by id) from case_decision_version v where v.case_id=c.id),
                  'factVersions',(select jsonb_agg(to_jsonb(v) order by id) from case_fact_revision v where v.case_id=c.id),
                  'continuations',(select jsonb_agg(to_jsonb(e) order by id) from case_execution_continuation e where e.case_id=c.id),
                  'rounds',(select jsonb_agg(to_jsonb(r) order by id) from case_court_round r where r.case_id=c.id),
                  'receipts',(select jsonb_agg(to_jsonb(r) order by id) from case_court_receipt r where r.case_id=c.id),
                  'remedies',(select jsonb_agg(to_jsonb(m) order by id) from case_remedy m where m.case_id=c.id))
                  || coalesce((select jsonb_build_object('receiptCorrections',jsonb_agg(to_jsonb(v) order by id))
                    from case_court_receipt_correction v where v.case_id=c.id having count(*)>0),'{}'::jsonb))::text
                from case_file c join case_decision d on d.case_id=c.id where c.id=?
                """,String.class,id);
    }
    private Map<String,Object> history(Map<String,Object> row,WorkflowUserAccess.User actor) {
        var result=new LinkedHashMap<String,Object>();
        result.put("id",row.get("id")); result.put("type",row.get("request_type"));result.put("payload",object(row.get("payload")));
        for (String name:List.of("status","reason","opinion")) result.put(name,row.get(name));
        result.put("sourceReference",row.get("source_reference"));result.put("requestedBy",row.get("requested_username"));result.put("requestedAt",row.get("requested_at"));
        result.put("reviewedBy",row.get("reviewed_username"));result.put("reviewedAt",row.get("reviewed_at"));
        result.put("canReview","PENDING".equals(row.get("status")) && reviewer(actor) && (!needsFactReviewer(row) || factReviewer(actor)) && !actor.id().equals(number(row.get("requested_by"))));
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
    private boolean factReviewer(WorkflowUserAccess.User actor) {
        return reviewer(actor) && "BUREAU".equals(actor.accountCategory()) && actor.deptId()!=null
                && exists("select exists(select 1 from sys_dept where id=? and enabled and org_kind='BUREAU')",actor.deptId());
    }
    private boolean needsFactReviewer(Map<String,Object> request) {
        if(Set.of("FACT_CORRECTION","COURT_RECEIPT_CORRECTION").contains(request.get("request_type")))return true;
        if(!"REMEDY_RESULT".equals(request.get("request_type")))return false;
        var payload=object(request.get("payload"));
        return FACT_FIELDS.stream().anyMatch(key->payload.get(key)!=null && !"".equals(payload.get(key)));
    }
    private void requireReader(WorkflowUserAccess.User actor) {
        if (!Set.of("DISTRICT","BUREAU").contains(actor.accountCategory()==null?"":actor.accountCategory()) || actor.deptId()==null
                || actor.roles().contains("AGENCY") || actor.roles().stream().noneMatch(Set.of("HANDLER","LEGAL","LEADER","ADMIN")::contains)) throw forbidden("须使用已归类的行政办案账号");
    }
    private void requireWriter(WorkflowUserAccess.User actor) { requireReader(actor);if (!writer(actor)) throw forbidden("当前岗位无权提交续办事项"); }
    private void requireReviewer(WorkflowUserAccess.User actor) { requireReader(actor);if (!reviewer(actor)) throw forbidden("须由另一名法制或负责人复核；管理员须兼任相应岗位"); }
    private static void keys(Map<String,Object> body,Set<String> allowed) { if (body==null || !body.keySet().equals(allowed)) throw invalid("请求字段缺失或包含未支持的字段"); }
    private static void allowedKeys(Map<String,Object> body,Set<String> required,Set<String> optional) {
        var allowed=new HashSet<>(required);allowed.addAll(optional);
        if(body==null || !body.keySet().containsAll(required) || !allowed.containsAll(body.keySet()))
            throw invalid("请求字段缺失或包含未支持的字段");
    }
    private static String text(Object value,int max,String label) { if (!(value instanceof String s) || s.isBlank() || s.codePointCount(0,s.length())>max || s.indexOf(0)>=0) throw invalid(label+"须为不超过"+max+"字的非空文本");return s.strip(); }
    private static String optional(Object value,int max,String label) { return value==null || value instanceof String s && s.isBlank()?null:text(value,max,label); }
    private static String choice(Object value,Set<String> choices,String label) { if (!(value instanceof String s) || !choices.contains(s)) throw invalid(label+"无效");return s; }
    private static String requestKey(Object value) { String s=text(value,64,"请求号");if (!s.matches("[A-Za-z0-9_-]{8,64}")) throw invalid("请求号格式无效");return s; }
    private static Long number(Object value) { return value instanceof Number n?n.longValue():null; }
    private static long positiveId(Object value) { try { if (!(value instanceof Number n)) throw new IllegalArgumentException();return new BigDecimal(n.toString()).toBigIntegerExact().longValueExact()>0?new BigDecimal(n.toString()).longValueExact():failId(); } catch (ArithmeticException|IllegalArgumentException e) { throw invalid("编号须为正整数"); } }
    private static long failId() { throw invalid("编号须为正整数"); }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private static LocalDate deadlineDate(Object value,String label) {
        try {
            String input = text(value,10,label);
            if (input.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                LocalDate parsed = LocalDate.parse(input);
                if (parsed.getYear() > 0) return parsed;
            }
        } catch (java.time.DateTimeException ignored) { }
        throw invalid(label+"须为有效日期");
    }
    private static LocalDate day(Object value,String label) { try { String s=text(value,10,label);if (!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();LocalDate d=LocalDate.parse(s);if (d.getYear()<1 || d.isAfter(today())) throw new IllegalArgumentException();return d; } catch (java.time.DateTimeException|IllegalArgumentException e) { throw invalid(label+"须为不晚于今天的有效日期"); } }
    private static LocalDate date(Object value) { return value instanceof Date d?d.toLocalDate():value instanceof LocalDate d?d:null; }
    private static BigDecimal money(Object value,boolean positive) { if (!(value instanceof Number n)) throw invalid("金额须为精确数字");BigDecimal amount;try {amount=new BigDecimal(n.toString());}catch(NumberFormatException e){throw invalid("金额无效");}return positive?Money.positive(amount,"续办金额"):Money.nonNegative(amount,"决定金额"); }
    private String json(Object value) { try{return mapper.writeValueAsString(value);}catch(Exception e){throw invalid("请求格式无效");} }
    private Map<String,Object> object(Object value) { try { if (value instanceof Map<?,?> map) {var result=new LinkedHashMap<String,Object>();for(var entry:map.entrySet()){if(!(entry.getKey() instanceof String key))throw invalid("字段无效");result.put(key,entry.getValue());}return result;}return mapper.readValue(value.toString(),new TypeReference<Map<String,Object>>(){}); }catch(Exception e){throw invalid("请求内容须为对象");} }
    private boolean same(Map<String,Object> a,Object b) { try {return mapper.readTree(json(a)).equals(mapper.readTree(b.toString()));}catch(Exception e){throw invalid("快照格式无效");} }
    private static BizException invalid(String message) { return new BizException(2193,message); }
    private static BizException forbidden(String message) { return new BizException(403,message); }
}
