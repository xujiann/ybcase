package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Invalid explicit values must not consume a one-shot reply or bypass hearing-host separation. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ProcedureInputIntegrityIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired cn.ybcase.bureau.service.BureauConfig config;

    record Actor(String username, String token) {}
    record Assist(Actor actor, long caseId, long id) {}
    record InvalidText(String field, Object value) {}
    record HostNames(String officer, String host) {}

    @BeforeEach void isolate() {
        assertTrue(jdbc.queryForObject("select current_database()", String.class)
                .matches("ybcase_authowner_(?:test|final)_20261028_[a-f0-9]{8}"),
                "Only the current disposable test database is permitted");
        SecurityContextHolder.clearContext();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    Actor actor() {
        var user = new SysUser();
        user.setUsername("procedure_input_" + UUID.randomUUID().toString().replace("-", ""));
        user.setRealName("程序输入回归");
        user.setPassword("unused-jwt-fixture");
        user.setEnabled(true);
        user.getRoles().add(roles.findByCode("ADMIN").orElseThrow());
        user = users.saveAndFlush(user);
        return new Actor(user.getUsername(), jwt.issue(user.getUsername(), user.getTokenVersion()));
    }

    long caseFile(Actor actor) {
        return jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,owner_user)
                values(?, '程序输入回归', (select min(id) from case_cause), '合成机构', 'PROVIDER',
                       current_date,current_date+90,?) returning id""", Long.class,
                "PROCEDURE-INPUT-" + UUID.randomUUID(), actor.username());
    }

    Assist assist() {
        var actor = actor();
        long caseId = caseFile(actor);
        long id = jdbc.queryForObject("""
                insert into case_assist(case_id,direction,org,content,sent_at,due_at)
                values(?,'OUT','合成机关','原始协查事项',current_date,current_date+15) returning id""", Long.class, caseId);
        return new Assist(actor, caseId, id);
    }

    JsonNode call(Actor actor, String path, Object body) throws Exception {
        var response = mvc.perform(post(path).header("Authorization", "Bearer " + actor.token())
                .contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return mapper.readTree(response.getContentAsString());
    }

    JsonNode reply(Assist assist, Object body) throws Exception {
        return call(assist.actor(), "/api/bureau/cases/" + assist.caseId() + "/assists/" + assist.id() + "/reply", body);
    }

    Map<String, Object> assistRow(Assist assist) {
        return jdbc.queryForMap("select * from case_assist where id=?", assist.id());
    }

    void rejectsWithoutConsumingThenAllowsValidReply(Assist assist, Object body) throws Exception {
        var before = assistRow(assist);
        var response = reply(assist, body);
        assertEquals(2060, response.path("code").asInt(), response.toString());
        assertEquals(before, assistRow(assist));
        String originalText = "  合成机关有效复函。\n保留原始格式  ";
        assertEquals(0, reply(assist, Map.of("result", originalText, "refused", false)).path("code").asInt());
        var completed = assistRow(assist);
        assertEquals(originalText, completed.get("result"));
        assertEquals(false, completed.get("refused"));
        assertNotNull(completed.get("replied_at"));
        assertEquals(2060, reply(assist, Map.of("result", "不得覆盖", "refused", false)).path("code").asInt());
        assertEquals(completed, assistRow(assist));
    }

    static Stream<Object> invalidFlags() {
        return Stream.of("true", "false", "", 1, 0, List.of(true), Map.of("value", true));
    }

    @ParameterizedTest
    @MethodSource("invalidFlags")
    void explicitNonBooleanRefusalCannotSilentlyCompleteAsNonRefusal(Object flag) throws Exception {
        rejectsWithoutConsumingThenAllowsValidReply(assist(),
                Map.of("result", "有实质复函内容", "refused", flag, "refuseReason", "有实质拒绝理由"));
    }

    static Stream<InvalidText> invalidTexts() {
        return Stream.of("result", "refuseReason").flatMap(field ->
                Stream.of(12, true, List.of("文本"), Map.of("text", "文本")).map(value -> new InvalidText(field, value)));
    }

    @ParameterizedTest
    @MethodSource("invalidTexts")
    void explicitNonStringReplyFieldsAreControlledErrorsAndKeepTheReplyAvailable(InvalidText invalid) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("result", "有实质复函内容");
        body.put("refused", "refuseReason".equals(invalid.field()));
        body.put("refuseReason", "有实质拒绝理由");
        body.put(invalid.field(), invalid.value());
        rejectsWithoutConsumingThenAllowsValidReply(assist(), body);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingOrNullRefusalRetainsTheExistingNonRefusalDefault(boolean explicitNull) throws Exception {
        var assist = assist();
        var body = new LinkedHashMap<String, Object>();
        body.put("result", "兼容旧调用的实质复函");
        if (explicitNull) body.put("refused", null);
        assertEquals(0, reply(assist, body).path("code").asInt());
        var row = assistRow(assist);
        assertEquals(false, row.get("refused"));
        assertEquals("兼容旧调用的实质复函", row.get("result"));
        assertNull(row.get("refuse_reason"));
        assertNotNull(row.get("replied_at"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realBooleanValuesRetainTheirExistingReplyAndReasonRequirements(boolean refused) throws Exception {
        var assist = assist();
        var before = assistRow(assist);
        assertEquals(2060, reply(assist, Map.of("refused", refused)).path("code").asInt());
        assertEquals(before, assistRow(assist));
        var body = new LinkedHashMap<String, Object>();
        body.put("refused", refused);
        String text = "  保留正常复函或拒绝理由原文。\n ";
        body.put(refused ? "refuseReason" : "result", text);
        assertEquals(0, reply(assist, body).path("code").asInt());
        var row = assistRow(assist);
        assertEquals(refused, row.get("refused"));
        assertEquals(text, row.get(refused ? "refuse_reason" : "result"));
        assertNull(row.get(refused ? "result" : "refuse_reason"));
        assertNotNull(row.get("replied_at"));
    }

    static Stream<HostNames> sameOfficerNames() {
        return Stream.of(" 王办案", "王办案 ", "\t王办案\n", "\u3000王办案\u3000")
                .flatMap(padded -> Stream.of(new HostNames("王办案", padded), new HostNames(padded, "王办案")));
    }

    @ParameterizedTest
    @MethodSource("sameOfficerNames")
    void edgeWhitespaceOnEitherNameCannotMakeAnOfficerTheirOwnHearingHost(HostNames names) throws Exception {
        hearingHostMustBeIndependent(names);
    }

    @Test void exactOfficerNameRemainsRejectedAndIndependentHostTextIsPreserved() throws Exception {
        hearingHostMustBeIndependent(new HostNames("王办案", "王办案"));
    }

    void hearingHostMustBeIndependent(HostNames names) throws Exception {
        var actor = actor();
        long caseId = caseFile(actor);
        jdbc.update("insert into case_officer(case_id,name,cert_no,duty) values(?,?,?,'LEAD')", caseId, names.officer(), "SYNTHETIC-CERT");
        jdbc.update("""
                insert into case_notice(case_id,notified_at,content,hearing_requested)
                values(?,current_date,'原始处罚告知',true)""", caseId);
        var notice = jdbc.queryForMap("select * from case_notice where case_id=?", caseId);
        var originalCase = jdbc.queryForMap("select * from case_file where id=?", caseId);
        var originalOfficer = jdbc.queryForMap("select * from case_officer where case_id=?", caseId);
        var body = new LinkedHashMap<String, Object>();
        body.put("noticeSentAt", LocalDate.now().toString());
        body.put("scheduledAt", LocalDate.now().plusDays(Math.max(0, config.intVal("hearing_notice_ahead_days", 7)) + 1L).toString());
        body.put("host", names.host());
        String path = "/api/bureau/cases/" + caseId + "/hearings";
        JsonNode rejected = call(actor, path, body);
        assertEquals(2056, rejected.path("code").asInt(), rejected.toString());
        assertEquals(0, jdbc.queryForObject("select count(*) from case_hearing where case_id=?", Integer.class, caseId));
        assertEquals(notice, jdbc.queryForMap("select * from case_notice where case_id=?", caseId));
        assertEquals(originalCase, jdbc.queryForMap("select * from case_file where id=?", caseId));
        assertEquals(originalOfficer, jdbc.queryForMap("select * from case_officer where case_id=?", caseId));
        String independent = "\u3000独立主持人 ";
        body.put("host", independent);
        assertEquals(0, call(actor, path, body).path("code").asInt());
        assertEquals(independent, jdbc.queryForObject("select host from case_hearing where case_id=?", String.class, caseId));
        assertEquals("SCHEDULED", jdbc.queryForObject("select status from case_hearing where case_id=?", String.class, caseId));
        assertEquals(2057, call(actor, path, body).path("code").asInt());
        assertEquals(1, jdbc.queryForObject("select count(*) from case_hearing where case_id=?", Integer.class, caseId));
    }
}
