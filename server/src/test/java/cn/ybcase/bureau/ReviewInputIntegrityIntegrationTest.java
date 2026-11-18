package cn.ybcase.bureau;

import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Real HTTP requests commit independently; rejected legal opinions must not complete a pending review. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReviewInputIntegrityIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;

    record Actor(long id, String username, String token) {}
    record Source(long caseId, Actor owner, Actor legal) {}

    @BeforeEach void guardedDatabaseOnly() {
        assertTrue(jdbc.queryForObject("select current_database()", String.class)
                .matches("ybcase_authowner_test_20261028_[0-9a-f]{8}"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003", "\u00a0", "\u202f", "\ufeff", "原因\u0000非法"})
    void missingBlankOrNulReasonCannotCreateAReview(String reason) throws Exception {
        Source source = source();
        String before = original(source.caseId());
        var body = new LinkedHashMap<String, Object>();
        if (reason != null) body.put("requiredReason", reason);
        JsonNode response = call(source.owner(), "/" + source.caseId() + "/reviews", body);
        int records = count(source.caseId());
        System.out.printf("REVIEW_REASON_BOUNDARY code=%d records=%d%n", response.path("code").asInt(-1), records);
        assertEquals(2037, response.path("code").asInt(-1), response.toString());
        assertEquals(0, records);
        assertEquals(before, original(source.caseId()));
    }

    static Stream<String> oversizedReasons() {
        return Stream.of("原".repeat(256), "🩺".repeat(256));
    }

    @ParameterizedTest
    @MethodSource("oversizedReasons")
    void reasonExceedingTheReal255CodePointColumnIsRejectedBeforePersistence(String reason) throws Exception {
        Source source = source();
        JsonNode response = call(source.owner(), "/" + source.caseId() + "/reviews", Map.of("requiredReason", reason));
        System.out.printf("REVIEW_REASON_LENGTH code=%d records=%d codePoints=%d%n",
                response.path("code").asInt(-1), count(source.caseId()), reason.codePointCount(0, reason.length()));
        assertEquals(2037, response.path("code").asInt(-1), response.toString());
        assertEquals(0, count(source.caseId()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "\u2003", "\u00a0", "\u202f", "\ufeff", "意见\u0000非法"})
    void missingBlankOrNulOpinionCannotPassOrConsumeThePendingReview(String opinion) throws Exception {
        Source source = source();
        long reviewId = submit(source, "依法提交法制审核");
        String before = review(reviewId);
        var body = new LinkedHashMap<String, Object>();
        body.put("opinionType", "AGREE");
        if (opinion != null) body.put("opinion", opinion);
        JsonNode response = call(source.legal(), "/reviews/" + reviewId, body);
        var state = jdbc.queryForMap("select passed,reviewed_at from case_review where id=?", reviewId);
        System.out.printf("REVIEW_OPINION_BOUNDARY code=%d passed=%s reviewed=%s%n",
                response.path("code").asInt(-1), state.get("passed"), state.get("reviewed_at"));
        assertEquals(2037, response.path("code").asInt(-1), response.toString());
        assertEquals(before, review(reviewId));
        assertEquals(0, call(source.legal(), "/reviews/" + reviewId,
                Map.of("opinionType", "AGREE", "opinion", "补齐意见后依法审核通过")).path("code").asInt(-1));
        assertTrue(jdbc.queryForObject("select passed from case_review where id=?", Boolean.class, reviewId));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"UNKNOWN"})
    void missingOrUnknownTypeIsABusinessRejectionAndDoesNotCompleteReview(String type) throws Exception {
        Source source = source();
        long reviewId = submit(source, "依法提交法制审核");
        String before = review(reviewId);
        var body = new LinkedHashMap<String, Object>();
        if (type != null) body.put("opinionType", type);
        body.put("opinion", "意见类型须明确");
        JsonNode response = call(source.legal(), "/reviews/" + reviewId, body);
        System.out.printf("REVIEW_TYPE_BOUNDARY code=%d%n", response.path("code").asInt(-1));
        assertEquals(2037, response.path("code").asInt(-1), response.toString());
        assertEquals(before, review(reviewId));
    }

    @Test void fullUnicodeColumnBoundaryAndLegalMultilineOpinionPreserveTheirOriginalText() throws Exception {
        Source source = source();
        String reason = " \t" + "原".repeat(250) + "🩺" + " \n";
        assertEquals(255, reason.codePointCount(0, reason.length()));
        long reviewId = submit(source, reason);
        assertEquals(reason, jdbc.queryForObject("select required_reason from case_review where id=?", String.class, reviewId));
        assertEquals(reviewId, submit(source, reason), "An identical pending submission retains its original identity");
        String opinion = " \t依法核对🩺\n保留合法多行审核原文。\r\n ";
        assertEquals(0, call(source.legal(), "/reviews/" + reviewId,
                Map.of("opinionType", "AGREE", "opinion", opinion)).path("code").asInt(-1));
        assertEquals(opinion, jdbc.queryForObject("select opinion from case_review where id=?", String.class, reviewId));
        assertTrue(jdbc.queryForObject("select passed from case_review where id=?", Boolean.class, reviewId));
        assertEquals("REPORTED", jdbc.queryForObject("select status from case_file where id=?", String.class, source.caseId()));
    }

    @Test void existingTextOpinionColumnKeepsItsLongMultilineContent() throws Exception {
        Source source = source();
        long reviewId = submit(source, "合法长篇审核意见");
        String opinion = "审核内容".repeat(2000) + "\n依法说明后续处理意见";
        assertEquals(0, call(source.legal(), "/reviews/" + reviewId,
                Map.of("opinionType", "CONTINUE", "opinion", opinion)).path("code").asInt(-1));
        assertEquals(opinion, jdbc.queryForObject("select opinion from case_review where id=?", String.class, reviewId));
        assertFalse(jdbc.queryForObject("select passed from case_review where id=?", Boolean.class, reviewId));
        assertNotNull(jdbc.queryForObject("select reviewed_at from case_review where id=?", java.sql.Date.class, reviewId));
    }

    private Source source() {
        String nonce = UUID.randomUUID().toString().replace("-", "");
        long dept = jdbc.queryForObject("insert into sys_dept(name,code,type,org_kind) values(?,?,'ADMIN','BUREAU') returning id",
                Long.class, "法审输入合成机构", "RINPUT-" + nonce.substring(0, 12));
        Actor owner = actor("HANDLER", dept, nonce + "a"), legal = actor("LEGAL", dept, nonce + "b");
        jdbc.update("insert into enforcer(name,cert_no,dept,cert_expire_at,legal_qualified,enabled,user_id) values(?,?,?,current_date+365,true,true,?)",
                "独立法审合成人员", "RINPUT-" + nonce, "法审输入合成机构", legal.id());
        long caseId = jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,status,filed_at,deadline_at,reported_at,
                    owner_user,owner_dept_id,created_by)
                values(?, '法审输入合成案件',(select min(id) from case_cause where subject_type='PROVIDER'),
                    '法审输入合成机构','PROVIDER','REPORTED',current_date,current_date+90,current_date,?,?,?) returning id
                """, Long.class, "RINPUT-" + nonce, owner.username(), dept, owner.username());
        jdbc.update("insert into case_officer(case_id,name,cert_no,duty,user_id) values(?,? ,?,'LEAD',?),(?, ?,?,'MEMBER',null)",
                caseId, "原办案甲", "RINPUT-LEAD-" + nonce, owner.id(), caseId, "原办案乙", "RINPUT-MEMBER-" + nonce);
        jdbc.update("insert into case_document(case_id,doc_type,title,content,made_at,maker) values(?,'INVESTIGATION_REPORT','调查终结报告','合成调查终结事实',current_date,?)",
                caseId, owner.username());
        return new Source(caseId, owner, legal);
    }

    private Actor actor(String role, long dept, String suffix) {
        String username = "rinput_" + suffix;
        long id = jdbc.queryForObject("""
                insert into sys_user(username,password,real_name,dept_id,account_category,must_change_password)
                values(?,'unused-jwt-fixture',?,?, 'BUREAU',false) returning id
                """, Long.class, username, "法审输入" + role, dept);
        jdbc.update("insert into sys_user_role(user_id,role_id) select ?,id from sys_role where code=?", id, role);
        return new Actor(id, username, jwt.issue(username, 0));
    }

    private long submit(Source source, String reason) throws Exception {
        JsonNode response = call(source.owner(), "/" + source.caseId() + "/reviews", Map.of("requiredReason", reason));
        assertEquals(0, response.path("code").asInt(-1), response.toString());
        return response.path("data").path("id").asLong();
    }

    private JsonNode call(Actor actor, String suffix, Map<String, Object> body) throws Exception {
        var response = mvc.perform(post("/api/bureau/cases" + suffix).header("Authorization", "Bearer " + actor.token())
                .contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        return mapper.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private int count(long caseId) {
        return jdbc.queryForObject("select count(*) from case_review where case_id=?", Integer.class, caseId);
    }
    private String original(long caseId) {
        return jdbc.queryForObject("select row_to_json(c)::text from case_file c where id=?", String.class, caseId);
    }
    private String review(long id) {
        return jdbc.queryForObject("select row_to_json(r)::text from case_review r where id=?", String.class, id);
    }
}
