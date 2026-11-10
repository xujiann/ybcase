package cn.ybcase.bureau;

import cn.ybcase.core.security.JwtService;
import cn.ybcase.bureau.service.MessageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
    @Autowired MessageService messages;
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

    @Test void receivingLeaderCanAssignTransferredInspectionWithoutReplacingCreatorOrLineage() throws Exception {
        long first = department(), second = department();
        var original = actor("HANDLER", "DISTRICT", first);
        var sender = actor("LEADER", "DISTRICT", first);
        var receiver = actor("LEADER", "DISTRICT", second);
        var handler = actor("HANDLER", "DISTRICT", second);
        var replacement = actor("HANDLER", "DISTRICT", second);
        String marker = "inspection-handoff-" + System.nanoTime();
        long inspection = write(original, "/inspections", Map.of("objectName", marker,
                "objectType", "PROVIDER", "officers", "检查员甲、检查员乙"), 0).path("id").asLong();
        String inspectionPath = "/inspections/" + inspection;
        String listings = "/inspections?page=1&q=" + marker;
        long handoff = write(sender, "/department-handoffs", Map.of("sourceType", "INSPECTION", "sourceId", inspection,
                "targetDeptId", second, "reason", "跨区交接检查"), 0).path("id").asLong();
        String handoffPath = "/department-handoffs/" + handoff;
        write(receiver, handoffPath + "/decision", Map.of("accept", true, "opinion", "接收"), 0);
        assertTrue(read(receiver, "/department-handoffs?focusId=" + handoff, 0).path("rows")
                .get(0).path("can_assign_inspection").asBoolean());
        assertTrue(ids(read(handler, listings, 0).path("rows")).isEmpty());
        read(sender, handoffPath + "/inspection-assignees", 2080);
        var options = read(receiver, handoffPath + "/inspection-assignees", 0);
        assertTrue(options.path("assignedUserId").isNull());
        assertEquals(2, options.path("candidates").size());
        String assignment = handoffPath + "/inspection-assignment";
        write(receiver, assignment, Map.of("username", original.name(), "reason", "不能指派原机构"), 2190);
        var firstAssignment = Map.<String, Object>of("username", handler.name(), "reason", "分派接续");
        write(receiver, assignment, firstAssignment, 0);
        write(receiver, assignment, firstAssignment, 0); // same request must not leave a duplicate event
        assertEquals(Set.of(inspection), ids(read(handler, listings, 0).path("rows")));
        String focus = "/inspections?focusId=" + inspection + "&q=ignored&page=19";
        assertEquals(Set.of(inspection), ids(read(handler, focus, 0).path("rows")));
        assertTrue(read(original, focus, 0).path("rows").isEmpty());
        assertTrue(ids(read(replacement, listings, 0).path("rows")).isEmpty());
        assertTrue(ids(read(original, listings, 0).path("rows")).isEmpty());
        read(replacement, inspectionPath + "/attachments", 2080);
        read(original, inspectionPath + "/attachments", 2080);

        var uploaded = mvc.perform(multipart("/api/bureau" + inspectionPath + "/attachments")
                        .file(new MockMultipartFile("file", "receipt.pdf", "application/pdf",
                                "%PDF-1.4\nfixture".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + handler.token()))
                .andReturn().getResponse();
        var attachment = mapper.readTree(uploaded.getContentAsString());
        assertEquals(0, attachment.path("code").asInt(-1), attachment.toString());
        long file = attachment.path("data").path("id").asLong();
        assertEquals(Set.of(file), ids(read(handler, inspectionPath + "/attachments", 0)));
        Long current = jdbc.queryForObject("select assigned_user_id from inspection where id=?", Long.class, inspection);
        write(receiver, assignment, Map.of("username", replacement.name(), "reason", "确认改派"), 2190);
        write(receiver, assignment, Map.of("username", replacement.name(), "expectedAssignedUserId", current,
                "reason", "确认改派"), 0);
        read(handler, inspectionPath + "/attachments", 2080);
        write(handler, inspectionPath + "/complete", Map.of("result", "失权尝试", "violationFound", true), 2080);
        assertEquals(Set.of(file), ids(read(replacement, inspectionPath + "/attachments", 0)));
        write(replacement, inspectionPath + "/complete", Map.of("result", "发现违法", "violationFound", true), 0);
        long clueId = write(replacement, inspectionPath + "/to-clue", Map.of(), 0).path("id").asLong();
        assertEquals(second, jdbc.queryForObject("select owner_dept_id from case_clue where id=?", Long.class, clueId));
        assertEquals(Set.of(file), ids(read(replacement, "/clues/" + clueId + "/attachments", 0)));
        assertEquals(original.name(), jdbc.queryForObject("select created_by from inspection where id=?", String.class, inspection));
        assertEquals(2, jdbc.queryForObject("select count(*) from inspection_assignment where inspection_id=?",
                Integer.class, inspection));
        write(receiver, assignment, firstAssignment, 2190); // converted inspections follow the new clue's scope
    }

    @Test void transferredClueRemindersFollowCurrentHandlerAndScopedFallback() throws Exception {
        long first = department(), second = department();
        var original = actor("HANDLER", "DISTRICT", first);
        var sender = actor("LEADER", "DISTRICT", first);
        var receiver = actor("LEADER", "DISTRICT", second);
        var fallback = actor("LEADER", "DISTRICT", second);
        var city = actor("LEADER", "BUREAU", null);
        long clueId = clue(original, "交接提醒");
        long handoff = write(sender, "/department-handoffs", Map.of("sourceType", "CLUE", "sourceId", clueId,
                "targetDeptId", second, "reason", "跨区交接线索"), 0).path("id").asLong();
        write(receiver, "/department-handoffs/" + handoff + "/decision", Map.of("accept", true, "opinion", "接收"), 0);
        String key = "CLUE-" + clueId + "-" + LocalDate.now();
        messages.generateReminders();
        messages.generateReminders();
        assertEquals(List.of(receiver.name()), jdbc.queryForList("select username from sys_message where dedup_key=?", String.class, key));
        String link = "/case/clues?focus=" + clueId;
        assertTrue(read(receiver, "/messages", 0).toString().contains(link));
        assertEquals(Set.of(clueId), ids(read(receiver, "/clues?focusId=" + clueId + "&page=1", 0).path("rows")));
        jdbc.update("update sys_user set enabled=false where username=?", receiver.name());
        messages.generateReminders();
        messages.generateReminders();
        var targets = jdbc.queryForList("select username from sys_message where dedup_key=?", String.class, key);
        assertEquals(2, targets.size());
        assertEquals(Set.of(receiver.name(), fallback.name()), new HashSet<>(targets));
        assertFalse(targets.contains(original.name()));
        assertFalse(targets.contains(sender.name()));
        assertFalse(targets.contains(city.name()));
    }

    @Test void currentReceiverCanAssignTransferredClueAndFileWithItsOriginalEvidence() throws Exception {
        long source = department(), target = department();
        var creator = actor("HANDLER", "DISTRICT", source);
        var sender = actor("LEADER", "DISTRICT", source);
        var receiver = actor("LEADER", "DISTRICT", target);
        var handler = actor("HANDLER", "DISTRICT", target);
        var replacement = actor("HANDLER", "DISTRICT", target);
        long clueId = clue(creator, "跨机构指派与立案");
        String cluePath = "/clues/" + clueId;
        var uploaded = mvc.perform(multipart("/api/bureau" + cluePath + "/attachments")
                        .file(new MockMultipartFile("file", "clue.pdf", "application/pdf",
                                "%PDF-1.4\nclue fixture".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + creator.token()))
                .andReturn().getResponse();
        JsonNode attachment = mapper.readTree(uploaded.getContentAsString());
        assertEquals(0, attachment.path("code").asInt(-1), attachment.toString());
        long file = attachment.path("data").path("id").asLong();
        long handoff = write(sender, "/department-handoffs", Map.of("sourceType", "CLUE", "sourceId", clueId,
                "targetDeptId", target, "reason", "跨区接续"), 0).path("id").asLong();
        String handoffPath = "/department-handoffs/" + handoff;
        write(receiver, handoffPath + "/decision", Map.of("accept", true, "opinion", "接收"), 0);
        assertTrue(read(receiver, "/department-handoffs?focusId=" + handoff, 0).path("rows")
                .get(0).path("can_assign_clue").asBoolean());
        String options = handoffPath + "/clue-assignees", assignment = handoffPath + "/clue-assignment";
        read(sender, options, 2080);
        assertEquals(2, read(receiver, options, 0).path("candidates").size());
        read(handler, cluePath + "/attachments", 2080);
        write(receiver, assignment, Map.of("username", creator.name(), "reason", "不同机构"), 2190);
        var firstAssignment = Map.<String, Object>of("username", handler.name(), "reason", "继续核查");
        write(receiver, assignment, firstAssignment, 0);
        write(receiver, assignment, firstAssignment, 0);
        assertEquals(Set.of(file), ids(read(handler, cluePath + "/attachments", 0)));
        String focus = "/clues?focusId=" + clueId + "&page=1";
        assertEquals(Set.of(clueId), ids(read(handler, focus, 0).path("rows")));
        jdbc.update("update sys_user set dept_id=? where username=?", target, creator.name());
        assertTrue(read(creator, focus, 0).path("rows").isEmpty());
        read(creator, cluePath + "/attachments", 2080);
        write(receiver, assignment, Map.of("username", replacement.name(), "reason", "过期页面"), 2190);
        long current = read(receiver, options, 0).path("assignmentId").asLong();
        write(receiver, assignment, Map.of("username", replacement.name(), "expectedAssignmentId", current,
                "reason", "交接改派"), 0);
        read(handler, cluePath + "/attachments", 2080);
        assertEquals(Set.of(file), ids(read(replacement, cluePath + "/attachments", 0)));
        assertEquals(2, jdbc.queryForObject("select count(*) from clue_assignment where clue_id=?", Integer.class, clueId));
        assertEquals(creator.name(), jdbc.queryForObject("select created_by from case_clue where id=?", String.class, clueId));

        long cause = jdbc.queryForObject("select min(id) from case_cause where subject_type='PROVIDER'", Long.class);
        var payload = Map.of("clueId", clueId, "causeId", cause, "procedureType", "NORMAL", "partyType", "PROVIDER",
                "partyName", "交接立案测试机构", "amountInvolved", 100, "institution",
                Map.of("code", "V64-TEST", "ownership", "PUBLIC", "grade", "SECONDARY"), "officers", List.of(
                        Map.of("name", "王办案", "certNo", "YB001", "duty", "LEAD"),
                        Map.of("name", "张协办", "certNo", "YB002", "duty", "MEMBER")));
        var filing = Map.<String, Object>of("kind", "FILE_CASE", "clueId", clueId, "payload", payload,
                "reason", "已完成核查", "requestKey", "clue-filing-" + System.nanoTime());
        write(handler, "/approvals", filing, 2080);
        long approval = write(replacement, "/approvals", filing, 0).path("id").asLong();
        write(receiver, "/approvals/" + approval + "/decide", Map.of("approve", true, "opinion", "同意立案"), 0);
        long caseId = jdbc.queryForObject("select id from case_file where clue_id=?", Long.class, clueId);
        assertEquals(target, jdbc.queryForObject("select owner_dept_id from case_file where id=?", Long.class, caseId));
        assertEquals(caseId, jdbc.queryForObject("select case_id from case_attachment where id=?", Long.class, file));
        write(receiver, assignment, firstAssignment, 2190);
    }

    @Test void summaryConversionKeepsCaseIdentityAttachmentsAndAnAuditedReason() throws Exception {
        long department = department();
        var handler = actor("HANDLER", "DISTRICT", department);
        var leader = actor("LEADER", "DISTRICT", department);
        var elsewhere = actor("LEADER", "DISTRICT", department());
        long clueId = clue(handler, "简易程序保留材料");
        long caseId = caseFile(handler, "简易程序转普通", clueId);
        jdbc.update("update case_file set procedure_type='SUMMARY' where id=?", caseId);
        String number = jdbc.queryForObject("select case_no from case_file where id=?", String.class, caseId);
        String path = "/cases/" + caseId;
        var upload = mvc.perform(multipart("/api/bureau" + path + "/attachments")
                        .file(new MockMultipartFile("file", "proof.pdf", "application/pdf",
                                "%PDF-1.4\ncase fixture".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + handler.token()))
                .andReturn().getResponse();
        JsonNode uploaded = mapper.readTree(upload.getContentAsString());
        assertEquals(0, uploaded.path("code").asInt(-1), uploaded.toString());
        Set<Long> files = ids(read(handler, path + "/attachments", 0));
        assertEquals(1, files.size());
        String convert = path + "/convert-to-ordinary";
        var reason = Map.<String, Object>of("reason", "需要补充调查");
        write(handler, convert, reason, 403);
        write(elsewhere, convert, reason, 2080);
        write(leader, convert, Map.of("reason", " "), 2194);
        JsonNode changed = write(leader, convert, reason, 0);
        assertEquals(number, changed.path("caseNo").asText());
        assertEquals("NORMAL", changed.path("procedureType").asText());
        assertEquals(clueId, changed.path("clueId").asLong());
        assertEquals(files, ids(read(handler, path + "/attachments", 0)));
        var history = read(leader, path + "/corrections", 0).get(0);
        assertEquals("procedureType", history.path("field").asText());
        assertEquals("SUMMARY", history.path("old_value").asText());
        assertEquals("NORMAL", history.path("new_value").asText());
        assertEquals("需要补充调查", history.path("reason").asText());
        assertEquals(leader.name(), history.path("operator").asText());
        write(leader, convert, reason, 2003);
    }

    @Test void ordinaryApprovalUsesRequestKeysAndOnlyOneConcurrentTerminalAction() throws Exception {
        long department = department();
        var handler = actor("HANDLER", "DISTRICT", department);
        var leader = actor("LEADER", "DISTRICT", department);
        long caseId = caseFile(handler, "普通审批撤回", null);
        var body = new java.util.HashMap<String, Object>(Map.of("kind", "EXTEND", "caseId", caseId,
                "reason", "补齐材料", "payload", Map.of("days", 15), "requestKey", "extend-approval-" + System.nanoTime()));
        long approval = write(handler, "/approvals", body, 0).path("id").asLong();
        assertEquals(approval, write(handler, "/approvals", body, 0).path("id").asLong());
        String path = "/approvals/" + approval;
        var cancellation = Map.<String, Object>of("reason", "核对后改报");
        write(leader, path + "/withdraw", cancellation, 403);
        body.put("requestKey", "extend-retry-" + System.nanoTime());
        write(handler, "/approvals", body, 2071);
        write(handler, path + "/withdraw", cancellation, 0);
        write(handler, path + "/withdraw", cancellation, 0);
        JsonNode old = read(handler, "/approvals/mine?focusId=" + approval, 0).get(0);
        assertEquals("CANCELLED", old.path("status").asText());
        assertEquals("补齐材料", old.path("reason").asText());
        assertEquals("核对后改报", old.path("withdraw_reason").asText());
        assertTrue(old.hasNonNull("withdrawn_at"));
        assertTrue(old.path("payload").asText().contains("15"));
        assertFalse(ids(read(leader, "/approvals/pending", 0)).contains(approval));
        long retried = write(handler, "/approvals", body, 0).path("id").asLong();
        assertNotEquals(approval, retried);

        String race = "/approvals/" + retried;
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var withdrawal = executor.submit(() -> {
                start.await();
                try { write(handler, race + "/withdraw", cancellation, 0); return true; }
                catch (AssertionError rejected) { assertTrue(rejected.getMessage().contains("2072")); return false; }
            });
            var rejection = executor.submit(() -> {
                start.await();
                try { write(leader, race + "/decide", Map.of("approve", false, "opinion", "需复核"), 0); return true; }
                catch (AssertionError rejected) { assertTrue(rejected.getMessage().contains("2072")); return false; }
            });
            start.countDown();
            boolean withdrew = withdrawal.get(15, TimeUnit.SECONDS), rejected = rejection.get(15, TimeUnit.SECONDS);
            assertNotEquals(withdrew, rejected);
            assertEquals(withdrew ? "CANCELLED" : "REJECTED",
                    jdbc.queryForObject("select status from biz_approval where id=?", String.class, retried));
        }
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
