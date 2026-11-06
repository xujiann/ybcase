package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.web.FormalizationController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Invalid receipt text must leave the existing one-shot completion available for a valid reply. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgreementReplyValidationIntegrationTest {
    @Autowired CaseService cases;
    @Autowired CaseCauseRepository causes;
    @Autowired FormalizationController formalization;
    @Autowired JdbcTemplate jdbc;
    Authentication admin;

    @BeforeEach void authenticate() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class));
        admin = new UsernamePasswordAuthenticationToken("admin", "unused",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(admin);
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    long action() {
        long cause = causes.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow().getId();
        long caseId = cases.create(new CaseService.CaseCreateReq(null, cause, "NORMAL",
                "AGREEMENT-REPLY-" + System.nanoTime(), "PROVIDER", null, null, null, null,
                "合成协议回执校验", BigDecimal.TEN,
                List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
                        new CaseService.OfficerReq("张协办", "YB002", "MEMBER")),
                null, null, null, null), "admin").getId();
        formalization.addAgreementAction(caseId, Map.of("action", "INTERVIEW", "org", "合成经办机构"));
        return jdbc.queryForObject("select id from agreement_action where case_id=?", Long.class, caseId);
    }

    static Stream<Map<String, String>> emptyResults() {
        Map<String, String> nullResult = new HashMap<>();
        nullResult.put("result", null);
        return Stream.of(Map.of(), nullResult, Map.of("result", ""),
                Map.of("result", " \t\r\n"), Map.of("result", "\u3000"));
    }

    @ParameterizedTest
    @MethodSource("emptyResults")
    void invalidResultLeavesWholeRowUnchangedAndAllowsLaterValidReply(Map<String, String> body) {
        long id = action();
        var before = jdbc.queryForMap("select * from agreement_action where id=?", id);
        assertEquals(2073, assertThrows(BizException.class,
                () -> formalization.replyAgreementAction(id, body, admin)).code);
        assertEquals(before, jdbc.queryForMap("select * from agreement_action where id=?", id));

        assertEquals(0, formalization.replyAgreementAction(id, Map.of("result", "经办已完成约谈并回函"), admin).getCode());
        assertEquals("经办已完成约谈并回函", jdbc.queryForObject(
                "select result from agreement_action where id=?", String.class, id));
        assertNotNull(jdbc.queryForObject("select replied_at from agreement_action where id=?", LocalDate.class, id));
    }

    @Test void validReplyPreservesOriginalTextAndCannotBeOverwritten() {
        long id = action();
        String result = "  经办已完成追回。\n保留原始回函格式  ";
        assertEquals(0, formalization.replyAgreementAction(id, Map.of("result", result), admin).getCode());
        assertEquals(result, jdbc.queryForObject("select result from agreement_action where id=?", String.class, id));
        var completed = jdbc.queryForMap("select * from agreement_action where id=?", id);
        assertNotNull(completed.get("replied_at"));
        assertEquals(2073, assertThrows(BizException.class,
                () -> formalization.replyAgreementAction(id, Map.of("result", "不得覆盖的后来结果"), admin)).code);
        assertEquals(completed, jdbc.queryForMap("select * from agreement_action where id=?", id));
    }
}
