package cn.ybcase.bureau;

import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Version fixtures include future-dated approvals that cannot be submitted through normal forms. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PublicationVersionIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;

    record Actor(long id, String name, long department, String token) {}

    @BeforeEach void isolated() {
        assertTrue(jdbc.queryForObject("select current_database()", String.class)
                .matches("ybcase_authowner_test_[0-9]{8}_[0-9a-f]{8}"));
    }

    @ParameterizedTest
    @CsvSource({"CHANGE,PUNISH", "CORRECT,PUNISH", "REVOKE,REVOKED"})
    void originalPublicationSurvivesFutureAndPendingVersionsButStopsWhenApprovedVersionIsEffective(
            String outcome, String decisionType) throws Exception {
        var owner = actor("DISTRICT", "HANDLER");
        var reviewer = actor("BUREAU", "LEGAL");
        LocalDate today = jdbc.queryForObject("select (current_timestamp at time zone 'Asia/Shanghai')::date",
                java.sql.Date.class).toLocalDate();
        String number = "PUB-" + UUID.randomUUID();
        long caseId = jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,status,filed_at,deadline_at,
                    owner_user,owner_dept_id,decided_at,delivered_at)
                values(?,'公示版本回归',(select min(id) from case_cause),'公示回归机构','PROVIDER','DELIVERED',
                    ?,?,?,?, ?,?) returning id
                """, Long.class, number, today.minusDays(20), today.plusDays(20), owner.name(), owner.department(),
                today.minusDays(10), today.minusDays(9));
        jdbc.update("""
                insert into case_decision(case_id,decision_type,decision_no,content,decided_at,fine_amount,
                    published,published_at,publish_withdrawn)
                values(?,'PUNISH',?,'原决定正文',?,100,true,?,false)
                """, caseId, number, today.minusDays(10), today);
        String original = original(caseId);
        assertTrue(contains(export(reviewer), number));

        // Neither an unapproved historical version nor a future approved version replaces current publication.
        version(caseId, owner, reviewer, outcome, decisionType, today.minusDays(1), "PENDING", false);
        assertTrue(contains(export(reviewer), number));
        version(caseId, owner, reviewer, outcome, decisionType, today.plusDays(1), "APPROVED", false);
        assertTrue(contains(export(reviewer), number));
        version(caseId, owner, reviewer, outcome, decisionType, today.minusDays(1), "APPROVED", true);
        assertTrue(contains(export(reviewer), number), "Future knowledge is not currently approved information");

        String effectiveNumber = version(caseId, owner, reviewer, outcome, decisionType, today, "APPROVED", false);
        JsonNode published = export(reviewer);
        assertFalse(contains(published, number), "An effective replacement/revocation removes the old public export entry");
        assertFalse(contains(published, effectiveNumber), "A new effective version is not automatically published");
        assertEquals(original, original(caseId), "Original case, decision and publication flags remain intact");
        var flags = jdbc.queryForMap("select published,publish_withdrawn from case_decision where case_id=?", caseId);
        assertEquals(Boolean.TRUE, flags.get("published"));
        assertEquals(Boolean.FALSE, flags.get("publish_withdrawn"));
    }

    private String version(long caseId, Actor owner, Actor reviewer, String outcome, String decisionType,
                           LocalDate effectiveAt, String status, boolean futureApproval) {
        String key = UUID.randomUUID().toString();
        long remedy = jdbc.queryForObject("""
                insert into case_remedy(case_id,request_key,remedy_type,accepted_at,reference,reason,source_reference,
                    registered_by,registered_username)
                values(?,?,'CORRECTION',?,'受理文号','人工核对','来源原件',?,?) returning id
                """, Long.class, caseId, key, effectiveAt.minusDays(1), owner.id(), owner.name());
        long request = jdbc.queryForObject("""
                insert into case_continuation_request(case_id,request_key,request_type,payload,source_snapshot,
                    reason,source_reference,requested_by,requested_username,requester_dept_id,requester_category,
                    status,reviewed_by,reviewed_username,reviewed_at,opinion)
                values(?,?,'REMEDY_RESULT','{}'::jsonb,'{}'::jsonb,'结果材料','结果文书',?,?,?,'DISTRICT',?,
                    case when ?='APPROVED' then ?::bigint end,case when ?='APPROVED' then ? end,
                    case when ?='APPROVED' then current_timestamp+(? * interval '1 day') end,
                    case when ?='APPROVED' then '复核通过' end) returning id
                """, Long.class, caseId, key, owner.id(), owner.name(), owner.department(), status, status, reviewer.id(),
                status, reviewer.name(), status, futureApproval ? 1 : 0, status);
        String decisionNo = "PUB-V-" + key;
        jdbc.update("""
                insert into case_decision_version(case_id,request_id,remedy_id,outcome,effective_at,decision_type,
                    decision_no,fine_amount,recoup_amount,confiscate_amount,penalty_basis,content)
                values(?,?,?,?,?,?,?, ?,0,0,'结果依据','结果决定正文')
                """, caseId, request, remedy, outcome, effectiveAt, decisionType, decisionNo,
                "REVOKED".equals(decisionType) ? 0 : "CORRECT".equals(outcome) ? 100 : 25);
        return decisionNo;
    }

    private JsonNode export(Actor actor) throws Exception {
        var response = mvc.perform(get("/api/bureau/decisions/publish-export")
                .header("Authorization", "Bearer " + actor.token())).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        JsonNode result = mapper.readTree(response.getContentAsString());
        assertEquals(0, result.path("code").asInt(-1), result.toString());
        return result.path("data");
    }

    private static boolean contains(JsonNode rows, String decisionNo) {
        for (JsonNode row : rows) if (decisionNo.equals(row.path("decision_no").asText())) return true;
        return false;
    }

    private Actor actor(String category, String role) {
        String name = "PUB-" + UUID.randomUUID().toString().substring(0, 16);
        long department = jdbc.queryForObject("insert into sys_dept(name,code,type,org_kind) values(?,?,'ADMIN',?) returning id",
                Long.class, name, name, category);
        FixtureUsers.ensure(jdbc, name, role);
        jdbc.update("update sys_user set account_category=?,dept_id=? where username=?", category, department, name);
        long id = jdbc.queryForObject("select id from sys_user where username=?", Long.class, name);
        return new Actor(id, name, department, jwt.issue(name, 0));
    }

    private String original(long caseId) {
        return jdbc.queryForObject("""
                select md5(jsonb_build_object('c',to_jsonb(c),'d',to_jsonb(d))::text)
                from case_file c join case_decision d on d.case_id=c.id where c.id=?
                """, String.class, caseId);
    }
}
