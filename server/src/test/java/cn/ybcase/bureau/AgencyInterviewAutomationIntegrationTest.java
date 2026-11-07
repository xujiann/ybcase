package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AgencyInterviewAutomationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    private final java.util.List<Long> fixtureCases = new java.util.ArrayList<>();
    private final java.util.List<String> fixtureUsers = new java.util.ArrayList<>();
    private final java.util.List<Long> fixtureDepartments = new java.util.ArrayList<>();

    @AfterEach void cleanFixtures() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
            for (long id : fixtureCases) {
                jdbc.update("delete from department_interview where source_agreement_id in (select id from agreement_action where case_id=?)", id);
                jdbc.update("delete from agency_agreement_event where agreement_id in (select id from agreement_action where case_id=?)", id);
                jdbc.update("delete from agreement_action where case_id=?", id);
                jdbc.update("delete from case_decision where case_id=?", id);
                jdbc.update("delete from case_file where id=?", id);
            }
            for (String username : fixtureUsers) {
                jdbc.update("delete from sys_message where username=?", username);
                jdbc.update("delete from sys_user_role where user_id in (select id from sys_user where username=?)", username);
                jdbc.update("delete from sys_user where username=?", username);
            }
            for (long id : fixtureDepartments) jdbc.update("delete from sys_dept where id=?", id);
        });
    }

    private record Actor(String name, String token, long dept) {}
    private record Source(long caseId, long agreementId, Actor sender, Actor receiver) {}

    @Test void actualInterviewCommitsWithReplyAndRetryCreatesOneLedgerRow() throws Exception {
        var source = source();
        var details = details();
        assertEquals(0, call(source.receiver(), "/api/agency/agreement-actions/" + source.agreementId() + "/reply",
                Map.of("cycle", 1, "result", "已完成实际约谈", "resultType", "COMPLETED", "interview", details)).path("code").asInt());
        var registered = call(source.receiver(), actualPath(source), withCycle(details));
        assertEquals(0, registered.path("code").asInt());
        assertEquals(1, jdbc.queryForObject("select count(*) from department_interview where source_agreement_id=?",
                Integer.class, source.agreementId()));
        assertEquals("COMPLETED", jdbc.queryForObject("select agency_status from agreement_action where id=?",
                String.class, source.agreementId()));
        var row = jdbc.queryForMap("select * from department_interview where source_agreement_id=?", source.agreementId());
        assertEquals(source.receiver().dept(), ((Number) row.get("dept_id")).longValue());
        assertEquals("实际约谈合成机构", row.get("institution_name"));
        assertEquals("机构登记编码", row.get("institution_code"));
        var changed = new java.util.HashMap<>(withCycle(details));
        changed.put("participants", "另一组人员");
        assertEquals(2192, call(source.receiver(), actualPath(source), changed).path("code").asInt());
        var reader = actor("BUREAU", "LEADER");
        byte[] xlsx = mvc.perform(get("/api/bureau/stats/interviews/download")
                .param("year", String.valueOf(LocalDate.now().getYear()))
                .header("Authorization", "Bearer " + reader.token())).andReturn().getResponse().getContentAsByteArray();
        assertTrue(new String(unzipWorksheets(xlsx), java.nio.charset.StandardCharsets.UTF_8).contains("实际约谈合成机构"));
    }

    @Test void invalidOrForeignInterviewCannotPartiallyCompleteReplyAndLegacyCanBeConfirmed() throws Exception {
        var source = source();
        var invalid = new java.util.HashMap<>(details());
        invalid.put("interviewDate", LocalDate.now().minusDays(20).toString());
        assertEquals(2192, call(source.receiver(), "/api/agency/agreement-actions/" + source.agreementId() + "/reply",
                Map.of("cycle", 1, "result", "错误资料", "resultType", "COMPLETED", "interview", invalid)).path("code").asInt());
        assertEquals("ACCEPTED", jdbc.queryForObject("select agency_status from agreement_action where id=?",
                String.class, source.agreementId()));
        assertEquals(0, jdbc.queryForObject("select count(*) from department_interview where source_agreement_id=?",
                Integer.class, source.agreementId()));
        jdbc.update("update agreement_action set agency_status='COMPLETED', replied_at=current_date,result='历史回复' where id=?",
                source.agreementId());
        var other = actor("AGENCY", "AGENCY");
        assertEquals(2192, call(other, actualPath(source), withCycle(details())).path("code").asInt());
        var stale = new java.util.HashMap<>(withCycle(details()));
        stale.put("cycle", 2);
        assertEquals(2192, call(source.receiver(), actualPath(source), stale).path("code").asInt());
        jdbc.update("""
                insert into sys_user_role(user_id,role_id)
                select u.id,r.id from sys_user u cross join sys_role r where u.username=? and r.code='HANDLER'
                """, source.receiver().name());
        assertEquals(403, call(source.receiver(), actualPath(source), withCycle(details())).path("code").asInt());
        jdbc.update("""
                delete from sys_user_role ur using sys_user u,sys_role r
                where ur.user_id=u.id and ur.role_id=r.id and u.username=? and r.code='HANDLER'
                """, source.receiver().name());
        assertEquals(0, call(source.receiver(), actualPath(source), withCycle(details())).path("code").asInt());
    }

    @Test void scheduledDeadlineMarkerIsDailyAndKeepsManualReminderSeparate() throws Exception {
        var source = source();
        jdbc.update("update agreement_action set agency_due_at=current_date-1,agency_reminded_at=current_date where id=?",
                source.agreementId());
        var admin = actor("BUREAU", "ADMIN");
        assertEquals(0, call(admin, "/api/bureau/messages/generate-reminders", Map.of()).path("code").asInt());
        assertEquals(0, call(admin, "/api/bureau/messages/generate-reminders", Map.of()).path("code").asInt());
        assertEquals(LocalDate.now(), jdbc.queryForObject(
                "select agency_auto_reminded_at from agreement_action where id=?", LocalDate.class, source.agreementId()));
        assertEquals(LocalDate.now(), jdbc.queryForObject(
                "select agency_reminded_at from agreement_action where id=?", LocalDate.class, source.agreementId()));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from agency_agreement_event where agreement_id=? and event_type='AUTO_REMINDED'",
                Integer.class, source.agreementId()));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from sys_message where username=? and dedup_key like ?", Integer.class,
                source.sender().name(), "AGENCY-DUE-" + source.agreementId() + "-1-%"));
        jdbc.update("update agreement_action set agency_status='RETURNED',agency_auto_reminded_at=null where id=?",
                source.agreementId());
        call(admin, "/api/bureau/messages/generate-reminders", Map.of());
        assertNull(jdbc.queryForObject("select agency_auto_reminded_at from agreement_action where id=?",
                LocalDate.class, source.agreementId()));
    }

    private Source source() {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class));
        var sender = actor("BUREAU", "HANDLER");
        var receiver = actor("AGENCY", "AGENCY");
        long id = jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,status,
                    owner_dept_id,owner_user,institution_code)
                values(?,'约谈联动演练',(select min(id) from case_cause),'实际约谈合成机构','PROVIDER',
                    current_date-10,current_date+20,'DECIDED',?,?,'机构登记编码') returning id
                """, Long.class, "AGENCY-AUTO-" + System.nanoTime(), sender.dept(), sender.name());
        fixtureCases.add(id);
        jdbc.update("insert into case_decision(case_id,decision_type,content,decided_at) values(?,'PUNISH','合成决定',current_date-3)", id);
        long agreement = jdbc.queryForObject("""
                insert into agreement_action(case_id,action,org,sent_at,agency_dept_id,agency_status,agency_cycle,agency_due_at)
                values(?,'INTERVIEW','合成经办部门',current_date-2,?,'ACCEPTED',1,current_date+2) returning id
                """, Long.class, id, receiver.dept());
        return new Source(id, agreement, sender, receiver);
    }

    private Actor actor(String category, String role) {
        String name = "agency_auto_" + System.nanoTime();
        long dept = jdbc.queryForObject("insert into sys_dept(name,code,type,org_kind) values(?,?,'ADMIN',?) returning id",
                Long.class, "合成机构", name, category);
        fixtureDepartments.add(dept);
        var user = new SysUser();
        user.setUsername(name);
        user.setRealName("自动化演练员");
        user.setPassword("unused");
        user.setDeptId(dept);
        user.setAccountCategory(category);
        user.getRoles().add(roles.findByCode(role).orElseThrow());
        user = users.saveAndFlush(user);
        fixtureUsers.add(name);
        return new Actor(name, jwt.issue(name, user.getTokenVersion()), dept);
    }

    private static Map<String, Object> details() {
        return Map.of("interviewType", "工作约谈", "reason", "实际事由", "content", "实际约谈记录",
                "participants", "甲、乙", "interviewDate", LocalDate.now().toString());
    }

    private static Map<String, Object> withCycle(Map<String, Object> details) {
        var value = new java.util.HashMap<>(details);
        value.put("cycle", 1);
        return value;
    }

    private static String actualPath(Source source) {
        return "/api/agency/agreement-actions/" + source.agreementId() + "/interview";
    }

    private JsonNode call(Actor actor, String path, Object body) throws Exception {
        return mapper.readTree(mvc.perform(post(path).header("Authorization", "Bearer " + actor.token())
                .contentType("application/json").content(mapper.writeValueAsBytes(body)))
                .andReturn().getResponse().getContentAsByteArray());
    }

    private static byte[] unzipWorksheets(byte[] data) throws Exception {
        var xml = new java.io.ByteArrayOutputStream();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
                if (entry.getName().startsWith("xl/worksheets/")) zip.transferTo(xml);
        }
        return xml.toByteArray();
    }
}
