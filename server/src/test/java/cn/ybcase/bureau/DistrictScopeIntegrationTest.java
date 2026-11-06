package cn.ybcase.bureau;

import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DistrictScopeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    private String originalScope;

    private record Actor(String name, String token, Long department) {}

    private Actor actor(String role, String category, Long department) {
        String username = "district_scope_" + System.nanoTime();
        FixtureUsers.ensure(jdbc, username, role);
        jdbc.update("update sys_user set dept_id=?, account_category=? where username=?", department, category, username);
        return new Actor(username, jwt.issue(username, 0), department);
    }

    private long department() {
        String code = "TEST-" + System.nanoTime();
        return jdbc.queryForObject("""
                insert into sys_dept(name,code,type,org_kind) values (?,?,'ADMIN','DISTRICT')
                returning id""", Long.class, code, code);
    }

    private long clue(Actor owner, String title) {
        return jdbc.queryForObject("""
                insert into case_clue(clue_no,source,content,suspect_name,suspect_type,received_at,deadline_at,created_by,owner_dept_id)
                values (?,'COMPLAINT',?,?,'PROVIDER',current_date,current_date,?,?) returning id
                """, Long.class, "DS-" + System.nanoTime(), title, title, owner.name(), owner.department());
    }

    private long caseFile(Actor owner, String title, Long clue) {
        return jdbc.queryForObject("""
                insert into case_file(case_no,name,clue_id,cause_id,party_name,party_type,filed_at,deadline_at,owner_user,owner_dept_id)
                values (?,?,?,(select min(id) from case_cause),?,'PROVIDER',current_date,current_date,?,?) returning id
                """, Long.class, "DS-" + System.nanoTime(), title, clue, title, owner.name(), owner.department());
    }

    private void scope(String value) {
        if (originalScope == null)
            originalScope = jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'", String.class);
        jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'", value);
    }

    @AfterEach void restore() {
        if (originalScope != null)
            jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'", originalScope);
    }

    private JsonNode read(Actor who, String path, int code) throws Exception {
        var response = mvc.perform(get("/api/bureau" + path).header("Authorization", "Bearer " + who.token()))
                .andReturn().getResponse();
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertEquals(code, body.path("code").asInt(-1), path + " " + body);
        return body.path("data");
    }

    private JsonNode write(Actor who, String path, Map<String, Object> value, int code) throws Exception {
        var response = mvc.perform(post("/api/bureau" + path).header("Authorization", "Bearer " + who.token())
                        .contentType("application/json").content(mapper.writeValueAsBytes(value)))
                .andReturn().getResponse();
        JsonNode body = mapper.readTree(response.getContentAsString());
        assertEquals(code, body.path("code").asInt(-1), path + " " + body);
        return body.path("data");
    }

    private Set<Long> ids(JsonNode array) {
        Set<Long> values = new HashSet<>();
        array.forEach(row -> values.add(row.path("id").asLong()));
        return values;
    }

    @Test void districtCaseScopeIncludesOwnDepartmentAndStillHonorsHandlerSelf() throws Exception {
        long first = department(), second = department();
        var leader = actor("LEADER", "DISTRICT", first);
        var handler = actor("HANDLER", "DISTRICT", first);
        var elsewhere = actor("HANDLER", "DISTRICT", second);
        var city = actor("LEADER", "BUREAU", null);
        String marker = "district-scope-" + System.nanoTime();
        long local = caseFile(leader, marker, null);
        long own = caseFile(handler, marker, null);
        long foreign = caseFile(elsewhere, marker, null);
        jdbc.update("insert into case_officer(case_id,name,cert_no,duty,user_id) values(?,?,'DISTRICT-TEST','MEMBER',(select id from sys_user where username=?))",
                foreign, "跨区参办", handler.name());
        scope("ALL");
        assertEquals(Set.of(local, own), ids(read(leader, "/cases?page=1&q=" + marker, 0).path("rows")));
        assertEquals(Set.of(local, own), ids(read(handler, "/cases?page=1&q=" + marker, 0).path("rows")));
        assertTrue(ids(read(leader, "/cases", 0).path("rows")).contains(local));
        assertFalse(ids(read(leader, "/cases", 0).path("rows")).contains(foreign));
        assertEquals(Set.of(local, own, foreign), ids(read(city, "/cases?page=1&q=" + marker, 0).path("rows")));
        assertEquals(Set.of(local, own), ids(read(leader, "/search?q=" + marker, 0).path("cases")));
        read(leader, "/cases/" + foreign, 2080);
        read(leader, "/cases/" + foreign + "/approvals", 2080);
        write(leader, "/cases/" + local + "/transfer-owner", Map.of("newOwner", elsewhere.name()), 2080);
        assertEquals(first, jdbc.queryForObject("select owner_dept_id from case_file where id=?", Long.class, local));
        scope("SELF");
        assertEquals(Set.of(own), ids(read(handler, "/cases?page=1&q=" + marker, 0).path("rows")));
        read(handler, "/cases/" + foreign, 2080);
    }

    @Test void alternateNumericCasePathsCannotExposeForeignCorrectionHistory() throws Exception {
        var local = actor("LEADER", "DISTRICT", department());
        var foreign = actor("LEADER", "DISTRICT", department());
        long foreignCase = caseFile(foreign, "其他单位案件", null);
        jdbc.update("""
                insert into case_correction(case_id,batch_no,field,old_value,new_value,reason,operator)
                values (?,'test','partyContact','previous','updated','更正联系信息',?)
                """, foreignCase, foreign.name());

        for (String id : List.of(Long.toString(foreignCase), "+" + foreignCase, "0x" + Long.toHexString(foreignCase))) {
            URI path = URI.create("/api/bureau/cases/" + id + "/corrections");
            var allowed = mvc.perform(get(path).header("Authorization", "Bearer " + foreign.token()))
                    .andReturn().getResponse();
            var own = mapper.readTree(allowed.getContentAsString());
            assertEquals(0, own.path("code").asInt(-1), id);
            assertEquals("previous", own.path("data").get(0).path("old_value").asText());

            var denied = mvc.perform(get(path).header("Authorization", "Bearer " + local.token()))
                    .andReturn().getResponse();
            var other = mapper.readTree(denied.getContentAsString());
            assertEquals(2080, other.path("code").asInt(-1), id);
            assertTrue(other.path("data").isNull(), id);
        }
    }

    @Test void filingApprovalsAndCluesStayInsideTheApplicantDepartment() throws Exception {
        long first = department(), second = department();
        var firstLeader = actor("LEADER", "DISTRICT", first);
        var secondLeader = actor("LEADER", "DISTRICT", second);
        var applicant = actor("HANDLER", "DISTRICT", first);
        String marker = "district-clue-" + System.nanoTime();
        long localClue = clue(applicant, marker), foreignClue = clue(secondLeader, marker);
        assertEquals(Set.of(localClue), ids(read(firstLeader, "/clues?page=1&q=" + marker, 0).path("rows")));
        assertEquals(Set.of(localClue), ids(read(firstLeader, "/search?q=" + marker, 0).path("clues")));
        assertFalse(ids(read(firstLeader, "/clues", 0)).contains(foreignClue));
        write(firstLeader, "/clues/" + foreignClue + "/extend", Map.of("reason", "越权延期"), 2080);
        long approval = write(applicant, "/approvals",
                Map.of("kind", "FILE_CASE", "reason", marker, "payload", Map.of("partyName", "拟立案机构")), 0)
                .path("id").asLong();
        assertTrue(ids(read(firstLeader, "/approvals/pending", 0)).contains(approval));
        assertFalse(ids(read(secondLeader, "/approvals/pending", 0)).contains(approval));
        assertTrue(read(firstLeader, "/messages", 0).toString().contains(marker));
        assertFalse(read(secondLeader, "/messages", 0).toString().contains(marker));
        assertEquals(0, read(secondLeader, "/my/workbench", 0).path("pendingForMe").asInt(-1));
        write(secondLeader, "/approvals/" + approval + "/decide", Map.of("approve", false, "opinion", "越权"), 2080);
        assertEquals("PENDING", jdbc.queryForObject("select status from biz_approval where id=?", String.class, approval));
        write(firstLeader, "/approvals/" + approval + "/decide", Map.of("approve", false, "opinion", "同部门驳回"), 0);
        assertEquals("REJECTED", jdbc.queryForObject("select status from biz_approval where id=?", String.class, approval));
    }

    @Test void districtRewardApprovalAndPaymentFollowTheLinkedCase() throws Exception {
        var local = actor("LEADER", "DISTRICT", department());
        var foreign = actor("LEADER", "DISTRICT", department());
        long clueId = clue(local, "奖励来源");
        caseFile(local, "奖励关联案件", clueId);
        long reward = jdbc.queryForObject("""
                insert into reward(clue_id,reporter_name,verified) values (?,'举报人',true) returning id
                """, Long.class, clueId);
        String path = "/rewards/" + reward;
        write(foreign, path + "/approve", Map.of("amount", 10), 2080);
        assertNull(jdbc.queryForObject("select approved_at from reward where id=?", java.sql.Date.class, reward));
        write(local, path + "/approve", Map.of("amount", 10), 0);
        write(foreign, path + "/pay", Map.of(), 2080);
        assertNull(jdbc.queryForObject("select paid_at from reward where id=?", java.sql.Date.class, reward));
        write(local, path + "/pay", Map.of(), 0);
        assertNotNull(jdbc.queryForObject("select paid_at from reward where id=?", java.sql.Date.class, reward));
    }

    @Test void employeeReassignmentDoesNotMovePreCaseMaterialsOrApplications() throws Exception {
        long first = department(), second = department();
        var applicant = actor("HANDLER", "DISTRICT", first);
        var originalLeader = actor("LEADER", "DISTRICT", first);
        var newLeader = actor("LEADER", "DISTRICT", second);
        String marker = "source-snapshot-" + System.nanoTime();
        long clueId = write(applicant, "/clues", Map.of("source", "COMPLAINT", "content", marker,
                "suspectName", marker, "suspectType", "PROVIDER"), 0).path("id").asLong();
        long inspectionId = write(applicant, "/inspections", Map.of("objectName", marker,
                "objectType", "PROVIDER", "officers", "检查员甲、检查员乙"), 0).path("id").asLong();
        long approvalId = write(applicant, "/approvals", Map.of("kind", "FILE_CASE", "reason", marker,
                "payload", Map.of("partyName", marker)), 0).path("id").asLong();
        assertEquals(first, jdbc.queryForObject("select owner_dept_id from case_clue where id=?", Long.class, clueId));
        assertEquals(first, jdbc.queryForObject("select owner_dept_id from inspection where id=?", Long.class, inspectionId));
        assertEquals(first, jdbc.queryForObject("select applicant_dept_id from biz_approval where id=?", Long.class, approvalId));

        jdbc.update("update sys_user set dept_id=? where username=?", second, applicant.name());
        for (String path : List.of("/clues", "/inspections")) {
            long expected = path.equals("/clues") ? clueId : inspectionId;
            String query = path + "?page=1&q=" + marker;
            assertEquals(Set.of(expected), ids(read(originalLeader, query, 0).path("rows")));
            assertTrue(ids(read(newLeader, query, 0).path("rows")).isEmpty());
            assertTrue(ids(read(applicant, query, 0).path("rows")).isEmpty());
        }
        assertTrue(ids(read(originalLeader, "/approvals/pending", 0)).contains(approvalId));
        assertFalse(ids(read(newLeader, "/approvals/pending", 0)).contains(approvalId));
        assertFalse(ids(read(applicant, "/approvals/pending", 0)).contains(approvalId));
        write(newLeader, "/approvals/" + approvalId + "/decide", Map.of("approve", false, "opinion", "无权驳回"), 2080);
        write(originalLeader, "/approvals/" + approvalId + "/decide", Map.of("approve", false, "opinion", "原机构驳回"), 0);
        write(originalLeader, "/inspections/" + inspectionId + "/complete",
                Map.of("result", "原机构完成检查", "violationFound", true), 0);
        var converted = write(originalLeader, "/inspections/" + inspectionId + "/to-clue", Map.of(), 0);
        assertEquals(first, converted.path("ownerDeptId").asLong());
    }

    @Test void departmentHandoffOnlyChangesOwnerAfterOneAuthorizedAcceptance() throws Exception {
        long first = department(), second = department();
        var sender = actor("LEADER", "DISTRICT", first);
        var receiver = actor("LEADER", "DISTRICT", second);
        var alternateReceiver = actor("LEADER", "DISTRICT", second);
        var outsider = actor("LEADER", "DISTRICT", department());
        String marker = "handoff-scope-" + System.nanoTime();
        long caseId = caseFile(sender, marker, null);
        String path = "/department-handoffs";
        var body = Map.<String, Object>of("sourceType", "CASE", "sourceId", caseId,
                "targetDeptId", second, "reason", "跨区交接");
        write(outsider, path, body, 2080);
        long handoffId = write(sender, path, body, 0).path("id").asLong();
        write(sender, path, body, 2190);
        assertEquals(first, jdbc.queryForObject("select owner_dept_id from case_file where id=?", Long.class, caseId));
        read(receiver, "/cases/" + caseId, 2080);
        var visible = read(receiver, path + "?status=PENDING", 0).path("rows");
        assertEquals(Set.of(handoffId), ids(visible));
        assertTrue(visible.get(0).path("can_decide").asBoolean());
        assertFalse(visible.get(0).path("can_cancel").asBoolean());
        assertFalse(ids(read(outsider, path, 0).path("rows")).contains(handoffId));
        var decision = Map.<String, Object>of("accept", true, "opinion", "确认接收");
        String decide = path + "/" + handoffId + "/decision";
        write(sender, decide, decision, 2080);

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var results = List.of(receiver, alternateReceiver).stream().map(who -> workers.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("交接测试未能同时启动");
                var response = mvc.perform(post("/api/bureau" + decide).header("Authorization", "Bearer " + who.token())
                                .contentType("application/json").content(mapper.writeValueAsBytes(decision)))
                        .andReturn().getResponse();
                return mapper.readTree(response.getContentAsString()).path("code").asInt(-1);
            })).toList();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertEquals(Set.of(0, 2190), Set.of(results.getFirst().get(10, TimeUnit.SECONDS), results.getLast().get(10, TimeUnit.SECONDS)));
        }
        assertEquals(second, jdbc.queryForObject("select owner_dept_id from case_file where id=?", Long.class, caseId));
        assertTrue(Set.of(receiver.name(), alternateReceiver.name()).contains(
                jdbc.queryForObject("select owner_user from case_file where id=?", String.class, caseId)));
        read(sender, "/cases/" + caseId, 2080);
        read(receiver, "/cases/" + caseId, 0);
    }

    @Test void legacySourceNeedsExplicitReconciliationAndDisabledTargetCannotReceive() throws Exception {
        long target = department();
        var manager = actor("ADMIN", "BUREAU", null);
        var receiver = actor("LEADER", "DISTRICT", target);
        var other = actor("LEADER", "DISTRICT", department());
        String marker = "legacy-unassigned-" + System.nanoTime();
        long clueId = clue(manager, marker);
        jdbc.update("update case_clue set created_by=? where id=?", other.name(), clueId);
        assertTrue(ids(read(other, "/clues?page=1&q=" + marker, 0).path("rows")).isEmpty());
        String path = "/department-handoffs";
        var body = Map.<String, Object>of("sourceType", "CLUE", "sourceId", clueId,
                "targetDeptId", target, "reason", "核实历史来源归属");
        write(other, path, body, 2080);
        long handoffId = write(manager, path, body, 0).path("id").asLong();
        jdbc.update("update sys_dept set enabled=false where id=?", target);
        var disabled = mvc.perform(get("/api/bureau" + path).header("Authorization", "Bearer " + receiver.token()))
                .andReturn().getResponse();
        assertEquals(401, disabled.getStatus());
        write(manager, path + "/" + handoffId + "/cancel", Map.of("opinion", "机构已停用"), 0);
        write(manager, path, body, 2190);
        assertNull(jdbc.queryForObject("select owner_dept_id from case_clue where id=?", Long.class, clueId));

        jdbc.update("update sys_dept set enabled=true where id=?", target);
        long replacement = write(manager, path, body, 0).path("id").asLong();
        write(receiver, path + "/" + replacement + "/decision", Map.of("accept", true, "opinion", "确认历史归属"), 0);
        assertEquals(Set.of(clueId), ids(read(receiver, "/clues?page=1&q=" + marker, 0).path("rows")));
        assertEquals(target, jdbc.queryForObject("select owner_dept_id from case_clue where id=?", Long.class, clueId));
    }

    @Test void departmentDisableCannotBypassBuiltInAdministratorProtection() throws Exception {
        long department = department();
        var manager = actor("ADMIN", null, null);
        Long previous = jdbc.queryForObject("select dept_id from sys_user where username='admin'", Long.class);
        try {
            jdbc.update("update sys_user set dept_id=? where username='admin'", department);
            var response = mvc.perform(put("/api/system/depts/" + department + "/enabled").param("enabled", "false")
                    .header("Authorization", "Bearer " + manager.token())).andReturn().getResponse();
            assertEquals(1202, mapper.readTree(response.getContentAsString()).path("code").asInt(-1));
            assertTrue(jdbc.queryForObject("select enabled from sys_dept where id=?", Boolean.class, department));
        } finally {
            jdbc.update("update sys_user set dept_id=? where username='admin'", previous);
        }
    }
}
