package cn.ybcase.bureau;

import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class AgencyAgreementIntegrationTest {
    private static final String INBOX = "/api/agency/agreement-actions";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users;
    @Autowired SysRoleRepository roles;
    @Autowired JwtService jwt;

    private record Actor(long id, String token) {}

    private Actor actor(String role, Long department) {
        var user = new SysUser();
        user.setUsername("agency_test_" + System.nanoTime());
        user.setRealName("经办测试");
        user.setPassword("unused");
        user.setDeptId(department);
        if ("AGENCY".equals(role)) user.setAccountCategory("AGENCY");
        user.getRoles().add(roles.findByCode(role).orElseThrow());
        var saved = users.saveAndFlush(user);
        return new Actor(saved.getId(), jwt.issue(saved.getUsername(), saved.getTokenVersion()));
    }

    private long department() {
        return jdbc.queryForObject("""
                insert into sys_dept(name,code,type,org_kind)
                values('测试经办部门',?,'ADMIN','AGENCY') returning id
                """, Long.class, "AGENCY_" + System.nanoTime());
    }

    private JsonNode read(String url, Actor actor, int code) throws Exception {
        var response = mvc.perform(get(url).header("Authorization", "Bearer " + actor.token())).andReturn().getResponse();
        if (code == 403 && response.getStatus() == 403) return mapper.nullNode();
        var result = mapper.readTree(response.getContentAsString());
        assertEquals(code, result.path("code").asInt(-1), result.toString());
        return result.path("data");
    }

    private void submit(String url, Actor actor, Map<String, ?> body, int code) throws Exception {
        var response = mvc.perform(post(url).header("Authorization", "Bearer " + actor.token())
                .contentType("application/json").content(mapper.writeValueAsString(body))).andReturn().getResponse();
        if (code == 403 && response.getStatus() == 403) return;
        var result = mapper.readTree(response.getContentAsString());
        assertEquals(code, result.path("code").asInt(-1), result.toString());
    }

    @Test void onlineHandoffIsDepartmentScopedAndTheReplyReturnsToTheAdministrativeLedger() throws Exception {
        assertNotEquals("ybcase", jdbc.queryForObject("select current_database()", String.class));
        long firstDept = department(), otherDept = department();
        Actor writer = actor("LEADER", null);
        Actor agency = actor("AGENCY", firstDept), outsider = actor("AGENCY", otherDept);
        long caseId = jdbc.queryForObject("""
                insert into case_file (case_no,name,cause_id,party_name,party_type,filed_at,deadline_at,status,summary)
                values (?, '案件标题', (select min(id) from case_cause), '测试医院', 'PROVIDER',
                        current_date, current_date, 'DECIDED', '仅执法端可见')
                returning id
                """, Long.class, "AGREEMENT-" + System.nanoTime());
        jdbc.update("""
                insert into case_decision (case_id,decision_type,content,decided_at)
                values (?,'PUNISH','内部决定内容',current_date)
                """, caseId);
        assertTrue(read("/api/bureau/agency-departments", writer, 0).size() >= 2);
        read(INBOX, writer, 403);
        read("/api/bureau/cases/" + caseId, agency, 403);

        String create = "/api/bureau/cases/" + caseId + "/agreement-actions";
        submit(create, writer, Map.of("action", "RECOVER", "agencyDeptId", "-1"), 2073);
        submit(create, writer, Map.of("action", "RECOVER", "agencyDeptId", Long.toString(firstDept)), 2073);
        String due = LocalDate.now().plusDays(3).toString();
        submit(create, writer, Map.of("action", "RECOVER", "agencyDeptId", Long.toString(firstDept),
                "org", "不可覆盖目录名称", "note", "协同办理", "dueAt", due), 0);
        submit(create, writer, Map.of("action", "INTERVIEW", "org", "线下经办机构"), 0);
        var inbox = read(INBOX, agency, 0);
        assertEquals(1, inbox.size());
        var handoff = inbox.get(0);
        long id = handoff.path("id").asLong();
        assertEquals("测试医院", handoff.path("institutionName").asText());
        assertEquals("协同办理", handoff.path("note").asText());
        assertFalse(handoff.has("caseId"));
        assertFalse(handoff.toString().contains("仅执法端可见"));
        assertFalse(handoff.toString().contains("内部决定内容"));
        assertEquals("测试经办部门", jdbc.queryForObject("select org from agreement_action where id=?", String.class, id));
        assertTrue(read(INBOX, outsider, 0).isEmpty());

        assertEquals(due, handoff.path("dueAt").asText());
        assertEquals("PENDING", handoff.path("status").asText());
        String agencyItem = INBOX + "/" + id;
        String bureauItem = "/api/bureau/agreement-actions/" + id;
        submit(agencyItem + "/receive", outsider, Map.of("cycle", 1), 2073);
        submit(agencyItem + "/reply", agency, Map.of("cycle", 1, "result", "尚未接收"), 2073);
        submit(bureauItem + "/reply", writer, Map.of("result", "不可代办"), 2073);
        submit(bureauItem + "/remind", writer, Map.of("cycle", 1), 0);
        submit(bureauItem + "/remind", writer, Map.of("cycle", 1), 2073);
        assertEquals(LocalDate.now().toString(), read(INBOX, agency, 0).get(0).path("remindedAt").asText());
        submit(agencyItem + "/receive", agency, Map.of("cycle", 1), 0);
        submit(bureauItem + "/reassign", writer, Map.of("cycle", 1, "agencyDeptId", otherDept,
                "dueAt", due, "reason", "待退回"), 2073);
        submit(agencyItem + "/return", agency, Map.of("cycle", 1, "reason", " "), 2073);
        submit(agencyItem + "/return", agency, Map.of("cycle", 1, "reason", "材料应由另一核查部门处理"), 0);
        assertNull(jdbc.queryForObject("select replied_at from agreement_action where id=?", LocalDate.class, id));
        submit(bureauItem + "/reassign", writer, Map.of("cycle", 1, "agencyDeptId", otherDept,
                "dueAt", due, "reason", "调整核查部门"), 0);
        assertTrue(read(INBOX, agency, 0).isEmpty());
        submit(agencyItem + "/receive", outsider, Map.of("cycle", 1), 2073);
        submit(agencyItem + "/receive", outsider, Map.of("cycle", 2), 0);
        jdbc.update("update case_file set status='CLOSED',closed_at=current_date,archive_no=? where id=?",
                "AGENCY-" + caseId, caseId);
        submit(agencyItem + "/reply", outsider, Map.of("cycle", 2, "resultType", "COMPLETED", "result", "缺少金额"), 2140);
        submit(agencyItem + "/reply", outsider, Map.of("cycle", 2, "resultType", "COMPLETED",
                "result", "已完成追回", "recoveredAmount", new BigDecimal("33.40"), "reference", "R-001"), 0);
        submit(agencyItem + "/reply", outsider, Map.of("cycle", 2, "result", "重复回执"), 2073);
        assertEquals("已完成追回", read(INBOX, outsider, 0).get(0).path("result").asText());
        assertEquals(outsider.id(), jdbc.queryForObject("select agency_replied_by from agreement_action where id=?", Long.class, id));
        assertEquals(new BigDecimal("33.40"), jdbc.queryForObject(
                "select agency_recovered_amount from agreement_action where id=?", BigDecimal.class, id));
        var history = read(bureauItem + "/history", writer, 0);
        assertEquals(7, history.size());
        assertEquals("材料应由另一核查部门处理", history.get(3).path("detail").asText());
        assertEquals("调整核查部门", history.get(4).path("detail").asText());
    }
}
