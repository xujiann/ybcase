package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.ExecutionService;
import cn.ybcase.bureau.web.FormalizationController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @ActiveProfiles("test") @Transactional
class NullEnumValidationIntegrationTest {
    @Autowired CaseService cases;
    @Autowired ExecutionService execution;
    @Autowired FormalizationController formalization;
    @Autowired CaseCauseRepository causes;
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin","unused",List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    CaseFile fixture() {
        long cause=causes.findAll().stream().filter(c->c.getItemNo()==13).findFirst().orElseThrow().getId();
        return cases.create(new CaseService.CaseCreateReq(null,cause,"SUMMARY","NULL-ENUM-"+System.nanoTime(),"PROVIDER",null,null,null,null,"无效输入回归",BigDecimal.TEN,List.of(new CaseService.OfficerReq("王办案","YB001","LEAD"),new CaseService.OfficerReq("张协办","YB002","MEMBER")),null,null,null,null),"banban");
    }
    void rejects(int code,Runnable action) { assertEquals(code,assertThrows(BizException.class,action::run).code); }
    @Test void absentOrNullDocumentMethodIsValidationFailureAndDoesNotCreateDelivery() {
        var c=fixture();
        for (String method:Arrays.asList(null,"","INVALID")) {
            Map<String,Object> body=new HashMap<>();body.put("method",method);
            rejects(2041,()->formalization.deliverDocument(c.getId(),body));
        }
        rejects(2041,()->formalization.deliverDocument(c.getId(),Map.of()));
        assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,c.getId()));
    }
    @Test void absentOrNullAgreementActionIsValidationFailureAndDoesNotCreateAction() {
        var c=fixture();
        for (String action:Arrays.asList(null,"","INVALID")) {
            Map<String,String> body=new HashMap<>();body.put("action",action);
            rejects(2073,()->formalization.addAgreementAction(c.getId(),body));
        }
        rejects(2073,()->formalization.addAgreementAction(c.getId(),Map.of()));
        assertEquals(0,jdbc.queryForObject("select count(*) from agreement_action where case_id=?",Integer.class,c.getId()));
    }
    @Test void wrongJsonDocumentMethodTypesAreValidationFailuresWithoutRows() {
        var c=fixture();
        for(Object method:List.of(1,true,List.of("DIRECT"),Map.of("value","DIRECT")))
            rejects(2041,()->formalization.deliverDocument(c.getId(),Map.of("method",method)));
        assertEquals(0,jdbc.queryForObject("select count(*) from case_delivery where case_id=?",Integer.class,c.getId()));
    }
    @Test void nullExecutionKindIsValidationFailureAndDoesNotCreatePayment() {
        var c=fixture();
        cases.decide(c.getId(),new CaseService.DecisionReq("PUNISH",new BigDecimal("100"),BigDecimal.ZERO,BigDecimal.ZERO,null,"罚款100元",null,"一般情形"));
        for(String kind:Arrays.asList(null,"","INVALID"))
            rejects(2042,()->execution.addExecution(c.getId(),new ExecutionService.ExecutionReq(kind,BigDecimal.ONE,null,"BANK",null,null)));
        assertEquals(0,jdbc.queryForObject("select count(*) from case_execution where case_id=?",Integer.class,c.getId()));
    }
}
