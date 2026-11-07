package cn.ybcase.bureau.service;

import cn.ybcase.bureau.spi.IntegrationGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Timeout evidence never declares a document served and never changes the case or its archive. */
@Service @RequiredArgsConstructor
public class ElectronicDeliveryTimeoutService {
    private final JdbcTemplate jdbc;
    private final IntegrationGateway gateway;

    @Scheduled(fixedDelayString="${ybcase.integration.delivery.timeout-scan-ms:60000}")
    @Transactional
    public void scheduledScan() {
        if(!gateway.timeoutScanEnabled()) return;
        Boolean locked=jdbc.queryForObject("select pg_try_advisory_xact_lock(2026114901)",Boolean.class);
        if(Boolean.TRUE.equals(locked)) expire(null,"SYSTEM");
    }
    /** Callers already hold source/actor locks for a manual case-specific scan. */
    public int expire(Long caseId,String actor) {
        List<Map<String,Object>> rows=caseId==null
                ?jdbc.queryForList("select id,attempts,deadline_at from electronic_delivery where status='SENT' and deadline_at<current_timestamp order by id limit 100 for update skip locked")
                :jdbc.queryForList("select id,attempts,deadline_at from electronic_delivery where status='SENT' and deadline_at<current_timestamp and case_id=? order by id for update",caseId);
        for(var row:rows) {
            long id=((Number)row.get("id")).longValue();
            String event="system-timeout-"+id+"-"+row.get("attempts");
            jdbc.update("update electronic_delivery set status='TIMED_OUT',error_code='RECEIPT_TIMEOUT' where id=?",id);
            jdbc.update("insert into electronic_delivery_event(envelope_id,event_key,payload_hash,status,occurred_at,outcome,reason) values(?,?,?,'TIMED_OUT',current_timestamp,'APPLIED','超过回执等待时间，未认定送达') on conflict(event_key) do nothing",id,event,IntegrationSupport.hash(event));
            jdbc.update("insert into integration_operation(target,operation,actor,source_id,status,error_code) values('delivery','TIMEOUT',?,?,'TIMED_OUT','RECEIPT_TIMEOUT')",actor,id);
        }
        return rows.size();
    }
}
