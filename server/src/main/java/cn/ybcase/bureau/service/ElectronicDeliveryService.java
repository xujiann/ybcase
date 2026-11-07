package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.spi.IntegrationGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static cn.ybcase.bureau.service.IntegrationSupport.*;

@Service
@RequiredArgsConstructor
public class ElectronicDeliveryService {
    private final JdbcTemplate jdbc;
    private final MaterialAccess access;
    private final IntegrationGateway gateway;
    private final ElectronicDeliveryTimeoutService timeouts;
    private static final Set<String> DELIVERABLE=Set.of("NOTICE","HEARING_NOTICE","DECISION","ORDER_CORRECT","URGE_LETTER","SUSPEND_DECISION","RESUME_NOTICE","TERMINATE_DECISION");
    public record Receipt(long deliveryId,String providerId,String eventId,String status,String occurredAt,String receiptNo) {}

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> list(String username,long caseId) {
        var actor=access.requireCaseReader(caseId,username);bureau(actor);
        var c=jdbc.queryForMap("select e_delivery_consent,e_delivery_receiver,e_delivery_channel,status,archive_no from case_file where id=?",caseId);
        // Show the recorded recipient to case readers only; operational diagnostics never contain it.
        c.put("gatewayConfigured",gateway.configured("delivery"));
        c.put("documents",jdbc.queryForList("select id,doc_type,title from case_document where case_id=? and doc_type in ('NOTICE','HEARING_NOTICE','DECISION','ORDER_CORRECT','URGE_LETTER','SUSPEND_DECISION','RESUME_NOTICE','TERMINATE_DECISION') order by id desc",caseId));
        c.put("deliveries",jdbc.queryForList("select e.*,d.title from electronic_delivery e join case_document d on d.id=e.document_id where e.case_id=? order by e.id desc",caseId));
        c.put("events",jdbc.queryForList("select ev.* from electronic_delivery_event ev join electronic_delivery e on e.id=ev.envelope_id where e.case_id=? order by ev.id desc",caseId));
        return c;
    }
    private Map<String,Object> document(long caseId,long documentId) {
        var rows=jdbc.queryForList("select id,doc_type,title,content from case_document where id=? and case_id=?",documentId,caseId);
        if(rows.isEmpty()) throw invalid("所选文书不属于本案");
        var doc=rows.getFirst();
        if(!DELIVERABLE.contains(doc.get("doc_type"))) throw invalid("该文书类型不支持电子送达");
        return doc;
    }
    private String fingerprint(Map<String,Object> doc) {
        var text=new StringBuilder();
        for(String field:List.of("doc_type","title","content")) {
            String value=Objects.toString(doc.get(field),"");
            text.append(value.length()).append(':').append(value);
        }
        return hash(text.toString());
    }
    private Map<String,Object> allowed(long caseId,Map<String,Object> doc) {
        if(!gateway.configured("delivery")) throw new BizException(2160,"电子送达网关未启用");
        var c=jdbc.queryForMap("select status,archive_no,e_delivery_consent,e_delivery_receiver,e_delivery_channel from case_file where id=?",caseId);
        if("CLOSED".equals(c.get("status")) || c.get("archive_no")!=null) throw new BizException(2031,"案件已封卷，不可追加电子送达材料");
        if(!Boolean.TRUE.equals(c.get("e_delivery_consent"))) throw new BizException(2054,"须先登记当事人电子送达确认书");
        if(!(c.get("e_delivery_receiver") instanceof String receiver) || receiver.isBlank()
                || !(c.get("e_delivery_channel") instanceof String channel) || channel.isBlank())
            throw new BizException(2054,"历史确认书未保存结构化地址，请核实后重新登记电子送达确认书");
        if("DECISION".equals(doc.get("doc_type"))) {
            // A cached earlier rule must never authorize a new send or receipt after it has been disabled.
            var rules=jdbc.queryForList("select cfg_value from sys_config where cfg_key='delivery_electronic_decision_allowed'",String.class);
            if(rules.isEmpty() || !Boolean.parseBoolean(rules.getFirst())) throw new BizException(2049,"当前规则未明确允许决定书电子送达");
            if(!"DECIDED".equals(c.get("status"))) throw new BizException(2041,"仅已决定且未送达的案件可电子送达决定书");
        }
        return c;
    }
    @Transactional
    public long create(String username,long caseId,long documentId,String requestKey) {
        String key=text(requestKey,80,"请求号");
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))",Object.class,"electronic-delivery:"+key);
        CaseGuards.lockWritable(jdbc,caseId);
        var actor=access.requireCaseWriter(caseId,username);bureau(actor);
        var doc=document(caseId,documentId);var c=allowed(caseId,doc);
        var previous=jdbc.queryForList("select id,request_key from electronic_delivery where document_id=?",documentId);
        if(!previous.isEmpty()) return ((Number)previous.getFirst().get("id")).longValue();
        if(!jdbc.queryForList("select id from electronic_delivery where request_key=?",key).isEmpty()) throw invalid("请求号已用于另一文书");
        return jdbc.queryForObject("insert into electronic_delivery(case_id,document_id,request_key,document_hash,receiver,channel,created_by) values(?,?,?,?,?,?,?) returning id",Long.class,
                caseId,documentId,key,fingerprint(doc),c.get("e_delivery_receiver"),c.get("e_delivery_channel"),username);
    }
    @Transactional
    public Map<String,Object> send(String username,long caseId,long id) {
        CaseGuards.lockWritable(jdbc,caseId);
        var rows=jdbc.queryForList("select * from electronic_delivery where id=? and case_id=? for update",id,caseId);
        if(rows.isEmpty()) throw invalid("电子送达记录不存在");var e=rows.getFirst();
        var actor=access.requireCaseWriter(caseId,username);bureau(actor);
        if(Set.of("SENT","DELIVERED","REJECTED").contains(e.get("status"))) return summary(e);
        var doc=document(caseId,((Number)e.get("document_id")).longValue());var c=allowed(caseId,doc);
        if(!fingerprint(doc).equals(e.get("document_hash")) || !Objects.equals(c.get("e_delivery_receiver"),e.get("receiver")) || !Objects.equals(c.get("e_delivery_channel"),e.get("channel")))
            throw invalid("文书或同意地址已经变化，原电子送达草稿不能发送");
        int timeoutHours=gateway.receiptTimeoutHours();
        String state="FAILED",error=null,provider=null;
        try {
            var response=gateway.post("delivery","deliveries/send",Map.of("deliveryId",id,"documentId",e.get("document_id"),"contentHash",e.get("document_hash"),"title",doc.get("title"),"content",doc.get("content"),"receiver",e.get("receiver"),"channel",e.get("channel")),(String)e.get("request_key"));
            provider=IntegrationGateway.text(response,"providerId",128);
            if(!"ACCEPTED".equals(IntegrationGateway.text(response,"status",24))) throw IntegrationGateway.invalidResponse();
            if(e.get("provider_id")!=null && !provider.equals(e.get("provider_id"))) throw IntegrationGateway.invalidResponse();
            if(!jdbc.queryForList("select id from electronic_delivery where provider_id=? and id<>?",provider,id).isEmpty()) throw IntegrationGateway.invalidResponse();
            state="SENT";
        } catch(BizException ex) {error=Integer.toString(ex.code);provider=(String)e.get("provider_id");}
        jdbc.update("update electronic_delivery set status=?,provider_id=?,attempts=attempts+1,error_code=?,sent_at=coalesce(sent_at,current_timestamp),deadline_at=current_timestamp+(? * interval '1 hour') where id=?",state,provider,error,timeoutHours,id);
        operation(username,id,state,error);
        return Map.of("id",id,"status",state);
    }
    private Map<String,Object> summary(Map<String,Object> e) {return Map.of("id",e.get("id"),"status",e.get("status"));}
    /** A callback is authenticated by JWT plus a dedicated live account. No anonymous or business-user callback access. */
    @Transactional
    public Map<String,Object> receipt(String username,Receipt receipt) {
        if(receipt==null || receipt.deliveryId()<1) throw invalid("电子送达编号无效");
        String provider=text(receipt.providerId(),128,"供应商编号"),event=text(receipt.eventId(),128,"事件编号");
        if(receipt.status()==null || !Set.of("DELIVERED","REJECTED","FAILED","TIMED_OUT").contains(receipt.status())) throw invalid("回执状态无效");
        OffsetDateTime occurred;
        try {occurred=OffsetDateTime.parse(receipt.occurredAt());} catch(Exception ex) {throw invalid("回执时间须包含时区");}
        if(occurred.toInstant().isAfter(Instant.now().plusSeconds(300))) throw invalid("回执时间不能晚于当前时间");
        String receiptNo="DELIVERED".equals(receipt.status())?text(receipt.receiptNo(),64,"回证编号"):null;
        var found=jdbc.queryForList("select case_id from electronic_delivery where id=?",receipt.deliveryId());
        if(found.isEmpty()) throw invalid("电子送达记录不存在");
        long caseId=((Number)found.getFirst().get("case_id")).longValue();CaseGuards.lock(jdbc,caseId);
        var envelope=jdbc.queryForMap("select * from electronic_delivery where id=? for update",receipt.deliveryId());
        var actor=access.lockActor(username,false);
        if(!gateway.callbackAccount(username) || !actor.roles().equals(Set.of("INTEGRATION"))) throw new BizException(403,"当前账号不是指定电子回执服务账号");
        if(!provider.equals(envelope.get("provider_id"))) throw invalid("供应商编号与发送记录不符");
        String fingerprint=hash(receipt.deliveryId()+"\n"+provider+"\n"+receipt.status()+"\n"+occurred.toInstant()+"\n"+receiptNo);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtext(?))",Object.class,"delivery-event:"+event);
        var existing=jdbc.queryForList("select payload_hash,outcome,reason from electronic_delivery_event where event_key=?",event);
        if(!existing.isEmpty()) {
            if(!fingerprint.equals(existing.getFirst().get("payload_hash"))) throw invalid("事件编号已对应其他回执内容");
            return Map.of("accepted","APPLIED".equals(existing.getFirst().get("outcome")),"outcome",existing.getFirst().get("outcome"));
        }
        String outcome="APPLIED",reason=null;
        if(envelope.get("sent_at")==null || occurred.toInstant().isBefore(((java.sql.Timestamp)envelope.get("sent_at")).toInstant().minusSeconds(300))) {outcome="REJECTED";reason="回执早于发送记录";}
        if("DELIVERED".equals(envelope.get("status")) || "REJECTED".equals(envelope.get("status"))) {outcome="REJECTED";reason="送达已是终态，不得覆盖";}
        if("APPLIED".equals(outcome) && "DELIVERED".equals(receipt.status())) {
            try {
                var doc=document(caseId,((Number)envelope.get("document_id")).longValue());allowed(caseId,doc);
                if(!fingerprint(doc).equals(envelope.get("document_hash"))) throw invalid("文书版本已变化");
                var current=jdbc.queryForMap("select e_delivery_receiver,e_delivery_channel from case_file where id=?",caseId);
                if(!Objects.equals(current.get("e_delivery_receiver"),envelope.get("receiver")) || !Objects.equals(current.get("e_delivery_channel"),envelope.get("channel"))) throw invalid("同意地址已变化");
                LocalDate date=businessDate(occurred.toInstant(),Instant.now());
                long delivery=jdbc.queryForObject("insert into case_delivery(case_id,method,delivered_at,receiver,note,receipt_no,receipt_signed_at,doc_kind,document_id) values(?,'ELECTRONIC',?,?,?, ?,?,?,?) returning id",Long.class,
                        caseId,date,envelope.get("receiver"),"供应商回执；电子送达编号 "+receipt.deliveryId(),receiptNo,date,doc.get("doc_type"),envelope.get("document_id"));
                jdbc.update("insert into case_document(case_id,doc_type,title,content,made_at,maker,signed) values(?,'DELIVERY_RECEIPT','电子送达回证',?,?,?,true)",caseId,
                        "原文书："+doc.get("title")+"；送达编号："+receipt.deliveryId()+"；供应商编号："+provider+"；事件："+event+"；回证："+receiptNo+"；签收时间："+occurred,date,username);
                if("DECISION".equals(doc.get("doc_type"))) jdbc.update("update case_file set status='DELIVERED',delivered_at=? where id=?",date,caseId);
                jdbc.update("update electronic_delivery set delivery_id=? where id=?",delivery,receipt.deliveryId());
            } catch(BizException ex) {outcome="REJECTED";reason=ex.getMessage();}
        }
        if("APPLIED".equals(outcome)) jdbc.update("update electronic_delivery set status=?,error_code=null where id=?",receipt.status(),receipt.deliveryId());
        jdbc.update("insert into electronic_delivery_event(envelope_id,event_key,payload_hash,status,occurred_at,receipt_no,outcome,reason) values(?,?,?,?,?,?,?,?)",receipt.deliveryId(),event,fingerprint,receipt.status(),occurred,receiptNo,outcome,reason);
        operation(username,receipt.deliveryId(),outcome,null);
        return Map.of("accepted","APPLIED".equals(outcome),"outcome",outcome);
    }
    @Transactional
    public int expire(String username,long caseId) {
        CaseGuards.lock(jdbc,caseId);var actor=access.requireCaseWriter(caseId,username);bureau(actor);
        return timeouts.expire(caseId,username);
    }
    private void operation(String username,long id,String status,String code) {
        jdbc.update("insert into integration_operation(target,operation,actor,source_id,status,error_code) values('delivery','DELIVERY',?,?,?,?)",username,id,status,code);
    }
    public static LocalDate businessDate(Instant occurred,Instant now) {
        ZoneId zone=ZoneId.of("Asia/Shanghai");
        LocalDate date=occurred.atZone(zone).toLocalDate();
        if(date.isAfter(now.atZone(zone).toLocalDate())) throw invalid("回执签收业务日期不能晚于今天");
        return date;
    }
}
