package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test")
class CasePublicationIntegrationTest {
    @Autowired CaseService cases;
    @Autowired OversightService oversight;
    @Autowired CaseCauseRepository causes;
    @Autowired JdbcTemplate jdbc;
    long delivered(){long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();var c=cases.create(new CaseService.CaseCreateReq(null,cause,"SUMMARY","PUBLICATION-"+System.nanoTime(),"PROVIDER",null,null,null,null,"公开备案并发",BigDecimal.TEN,List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),"banban");cases.decide(c.getId(),new CaseService.DecisionReq("PUNISH",new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,null,"处罚决定",null,"一般情形"));cases.deliver(c.getId(),new CaseService.DeliveryReq("DIRECT",LocalDate.now(),"法人",null,"TEST-RECEIPT",null));return c.getId();}
    Map<String,Object> row(long id){return jdbc.queryForMap("select * from case_decision where case_id=?",id);}
    List<Integer> race(Runnable a,Runnable b) throws Exception {var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{var results=new ArrayList<Future<Integer>>();for(Runnable task:List.of(a,b))results.add(pool.submit(()->{gate.await();try{task.run();return 0;}catch(BizException e){return e.code;}}));gate.countDown();List<Integer> codes=new ArrayList<>();for(var f:results)codes.add(f.get(15,TimeUnit.SECONDS));Collections.sort(codes);return codes;}finally{pool.shutdownNow();}}
    @Test void firstGovernmentRecordCannotBeOverwritten(){long id=delivered();cases.govRecord(id,"原备案文号");var original=row(id);assertEquals(2042,assertThrows(BizException.class,()->cases.govRecord(id,"另一文号")).code);assertEquals(original,row(id));}
    @Test void publishAndGovernmentRecordPreserveBothIndependentFields() throws Exception {for(int i=0;i<4;i++){long id=delivered();assertEquals(List.of(0,0),race(()->cases.publish(id),()->cases.govRecord(id,"并发备案")));var r=row(id);assertEquals(true,r.get("published"));assertNotNull(r.get("published_at"));assertEquals("并发备案",r.get("gov_record_no"));assertNotNull(r.get("gov_record_at"));}}
    @Test void governmentRecordCannotRestoreWithdrawnPublication() throws Exception {for(int i=0;i<4;i++){long id=delivered();cases.publish(id);var date=row(id).get("published_at");assertEquals(List.of(0,0),race(()->cases.govRecord(id,"撤回同时备案"),()->oversight.withdrawPublish(id,"原决定依法撤销","juzhang")));var r=row(id);assertEquals(true,r.get("publish_withdrawn"));assertEquals("原决定依法撤销",r.get("withdraw_reason"));assertEquals(date,r.get("published_at"));assertEquals("撤回同时备案",r.get("gov_record_no"));}}
    @Test void concurrentRepeatedPublicationHasOnlyOneWinner() throws Exception {long id=delivered();assertEquals(List.of(0,2042),race(()->cases.publish(id),()->cases.publish(id)));assertNotNull(row(id).get("published_at"));}
    @Test void concurrentRepeatedGovernmentRecordKeepsWinningOriginal() throws Exception {long id=delivered();assertEquals(List.of(0,2042),race(()->cases.govRecord(id,"备案甲"),()->cases.govRecord(id,"备案乙")));assertTrue(List.of("备案甲","备案乙").contains(row(id).get("gov_record_no")));}
    @Test void externalPublicationObligationsRemainPossibleAfterArchivalWithoutRewritingCase() {long id=delivered();jdbc.update("update case_file set status='CLOSED',archive_no='PUBLICATION-SEALED',closed_at=current_date where id=?",id);var before=jdbc.queryForMap("select * from case_file where id=?",id);int docs=jdbc.queryForObject("select count(*) from case_document where case_id=?",Integer.class,id);cases.publish(id);cases.govRecord(id,"归档后备案");oversight.withdrawPublish(id,"决定被撤销","juzhang");assertEquals(before,jdbc.queryForMap("select * from case_file where id=?",id));assertEquals(docs,jdbc.queryForObject("select count(*) from case_document where case_id=?",Integer.class,id));assertEquals(true,row(id).get("publish_withdrawn"));}
}
