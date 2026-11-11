package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.*;
import cn.ybcase.core.common.ConfigChangedEvent;
import org.junit.jupiter.api.*;
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
class ExecutionIntegrityIntegrationTest {
    @Autowired CaseService cases;
    @Autowired ExecutionService execution;
    @Autowired CaseCauseRepository causes;
    @Autowired JdbcTemplate jdbc;
    @Autowired BureauConfig config;
    final Map<String,String> originals=new HashMap<>();
    void cfg(String k,String v){ originals.computeIfAbsent(k,x->jdbc.queryForObject("select cfg_value from sys_config where cfg_key=?",String.class,k)); jdbc.update("update sys_config set cfg_value=? where cfg_key=?",v,k);config.onConfigChanged(new ConfigChangedEvent(k)); }
    @BeforeEach void setup(){cfg("late_fee_base","FULL");cfg("payment_days","15");}
    @AfterEach void restore(){originals.forEach((k,v)->{jdbc.update("update sys_config set cfg_value=? where cfg_key=?",v,k);config.onConfigChanged(new ConfigChangedEvent(k));});}
    CaseFile decided(){
        long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();
        var c=cases.create(new CaseService.CaseCreateReq(null,cause,"SUMMARY","EXEC-INTEGRITY-"+System.nanoTime(),"PROVIDER",null,null,null,null,"执行真实性",BigDecimal.TEN,List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),"banban");
        cases.decide(c.getId(),new CaseService.DecisionReq("PUNISH",new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,null,"罚款100元",null,"一般情形"));return c;
    }
    CaseFile overdue(){var c=decided();jdbc.update("update case_file set decided_at=current_date-50 where id=?",c.getId());jdbc.update("update case_decision set decided_at=current_date-50 where case_id=?",c.getId());cases.deliver(c.getId(),new CaseService.DeliveryReq("DIRECT",LocalDate.now().minusDays(30),"法人",null,"TEST-RECEIPT",null));return c;}
    void pay(long id,String kind,String amount,LocalDate date){execution.addExecution(id,new ExecutionService.ExecutionReq(kind,new BigDecimal(amount),date,"BANK",null,null));}
    void reject(int code,Runnable action){assertEquals(code,assertThrows(BizException.class,action::run).code);}
    @Test void futureAndPreDecisionDatesCannotCreateExecutionOrEnableClosure(){var c=decided();reject(2042,()->pay(c.getId(),"FINE","100",LocalDate.now().plusDays(1)));reject(2042,()->pay(c.getId(),"FINE","100",LocalDate.now().minusDays(1)));assertEquals(0,execution.sum(c.getId(),"FINE").signum());reject(2011,()->cases.close(c.getId(),"尚未送达执行","banban"));assertEquals("DECIDED",cases.get(c.getId()).getStatus());}
    @Test void omittedDateDefaultsToTodayAndKnownMethodsRemainUsable(){var c=decided();for(String method:List.of("BANK","ONSITE","INSTALLMENT"))execution.addExecution(c.getId(),new ExecutionService.ExecutionReq("FINE",BigDecimal.TEN,null,method,null,"RECEIPT-"+System.nanoTime()));assertEquals(3,jdbc.queryForObject("select count(*) from case_execution where case_id=? and paid_at=current_date",Integer.class,c.getId()));}
    @Test void arbitraryOrAbsentMethodIsRejectedWithoutRows(){var c=decided();for(String method:Arrays.asList("FUTURE_PROMISE",null))reject(2042,()->execution.addExecution(c.getId(),new ExecutionService.ExecutionReq("FINE",BigDecimal.TEN,null,method,null,null)));assertEquals(0,execution.sum(c.getId(),"FINE").signum());}
    @Test void lateFeeNeedsEffectiveDeliveryAndExpiredPaymentDeadline(){var c=decided();reject(2042,()->pay(c.getId(),"LATE_FEE","1",null));reject(2012,()->pay(c.getId(),"LATE_FEE","101",null));cases.deliver(c.getId(),new CaseService.DeliveryReq("DIRECT",LocalDate.now(),"法人",null,"TEST-RECEIPT",null));reject(2042,()->pay(c.getId(),"LATE_FEE","1",null));var future=decided();cases.deliver(future.getId(),new CaseService.DeliveryReq("ANNOUNCE",LocalDate.now(),"公告受送达人",null,"TEST-RECEIPT",null));reject(2042,()->pay(future.getId(),"LATE_FEE","1",null));}
    @Test void accruedCapSubtractsExistingLateFeesAndAllowsExactRemainder(){var c=overdue();assertEquals(0,new BigDecimal("45").compareTo((BigDecimal)execution.lateFeeQuote(c.getId()).get("capped")));pay(c.getId(),"LATE_FEE","30",null);reject(2012,()->pay(c.getId(),"LATE_FEE","15.01",null));pay(c.getId(),"LATE_FEE","15",null);assertEquals(0,new BigDecimal("45").compareTo(execution.sum(c.getId(),"LATE_FEE")));}
    @Test void unpaidHistoricalQuoteDoesNotSubtractLaterPrincipalPayment(){cfg("late_fee_base","UNPAID");var c=overdue();pay(c.getId(),"FINE","40",LocalDate.now());pay(c.getId(),"LATE_FEE","25",LocalDate.now().minusDays(5));assertEquals(0,new BigDecimal("25").compareTo(execution.sum(c.getId(),"LATE_FEE")));assertEquals(0,new BigDecimal("60").compareTo((BigDecimal)execution.lateFeeQuote(c.getId()).get("base")));}
    @Test void approvedButUnspecifiedDeferCannotCollectLateFees(){var c=overdue();execution.approveDefer(c.getId());reject(2042,()->pay(c.getId(),"LATE_FEE","1",null));assertEquals(0,execution.sum(c.getId(),"LATE_FEE").signum());}
    @Test void concurrentLateFeesCannotExceedAccruedAmount() throws Exception {var c=overdue();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);try{List<Future<Integer>> tasks=new ArrayList<>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{gate.await();try{pay(c.getId(),"LATE_FEE","30",null);return 0;}catch(BizException e){return e.code;}}));gate.countDown();List<Integer> codes=new ArrayList<>();for(var f:tasks)codes.add(f.get(15,TimeUnit.SECONDS));Collections.sort(codes);assertEquals(List.of(0,2012),codes);assertEquals(0,new BigDecimal("30").compareTo(execution.sum(c.getId(),"LATE_FEE")));}finally{pool.shutdownNow();}}
    @Test void lateFeeStartsStrictlyAfterDeadlineAndApprovedFutureDeferStillBlocks(){var c=overdue();reject(2042,()->pay(c.getId(),"LATE_FEE","1",LocalDate.now().minusDays(15)));pay(c.getId(),"LATE_FEE","3",LocalDate.now().minusDays(14));var deferred=overdue();execution.approveDefer(deferred.getId());jdbc.update("update case_file set defer_until=current_date+5 where id=?",deferred.getId());reject(2042,()->pay(deferred.getId(),"LATE_FEE","1",null));assertEquals(LocalDate.now().plusDays(5),execution.lateFeeQuote(deferred.getId()).get("payDeadline"));}}
