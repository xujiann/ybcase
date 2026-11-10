package cn.ybcase.bureau;

import cn.ybcase.core.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ReportingVersionsIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired javax.sql.DataSource dataSource;
    record Actor(long id, String name, long department, String token) {}

    @BeforeEach void isolated() {
        assertTrue(jdbc.queryForObject("select current_database()", String.class)
                .matches("ybcase_authowner_test_[0-9]{8}_[0-9a-f]{8}"));
    }

    @Test void approvedSupplementCorrectionPreservesLegalSourceAndOldFrozenBytes() throws Exception {
        var handler = actor("DISTRICT", "HANDLER");
        var generator = actor("BUREAU", "LEADER");
        var reviewer = actor("BUREAU", "LEGAL");
        LocalDate date = LocalDate.of(2010, 6, 15);
        long caseId = caseFile(handler, date);
        jdbc.update("update case_file set confirmed_violation_amount=0 where id=?", caseId);
        String source = source(caseId);
        String supplement = "/cases/" + caseId + "/report-supplements";
        long fill = api(handler, post(supplement), Map.of("reason", "原卷缺项", "sourceReference", "原卷1页",
                "values", Map.of("institutionCode", "BEFORE")), 0).path("id").asLong();
        review(reviewer, supplement, fill);

        Map<String, Object> request = criteria("PENALTIES", date);
        long first = api(generator, post("/report-versions/batches"), request, 0).path("id").asLong();
        assertEquals(first, api(generator, post("/report-versions/batches"), request, 0).path("id").asLong());
        api(reviewer, post("/report-versions/batches/" + first + "/confirm"), Map.of("opinion", "已逐行核对"), 0);
        byte[] oldBytes = download(generator, first);
        String correction = "/cases/" + caseId + "/report-supplement-corrections";
        api(handler, post(correction), Map.of("reason", "不应覆盖业务零值", "sourceReference", "原卷2页",
                "values", Map.of("confirmedViolationAmount", 1)), 2191);
        long change = api(handler, post(correction), Map.of("reason", "编码补录录错", "sourceReference", "原卷2页",
                "values", Map.of("institutionCode", "AFTER")), 0).path("id").asLong();
        assertEquals("BEFORE", jdbc.queryForObject("select institution_code from case_reporting_facts where case_id=?", String.class, caseId));
        review(reviewer, supplement, change);
        assertEquals(source, source(caseId));
        assertEquals("AFTER", jdbc.queryForObject("select institution_code from case_reporting_facts where case_id=?", String.class, caseId));
        assertArrayEquals(oldBytes, download(generator, first));

        var again = criteria("PENALTIES", date);
        again.put("parentBatchId", first);
        long second = api(generator, post("/report-versions/batches"), again, 0).path("id").asLong();
        var diffs = api(reviewer, get("/report-versions/batches/" + second + "/changes"), null, 0);
        assertTrue(diffs.path("total").asLong() >= 1);
        var changed = find(diffs.path("rows"), "row_key", "CASE:" + caseId);
        assertEquals("BEFORE", changed.path("previous_cells").get(4).asText());
        assertEquals("AFTER", changed.path("current_cells").get(4).asText());
        assertEquals("CHANGED", changed.path("change_type").asText());
        assertEquals(2, api(handler, get(supplement), null, 0).path("history").size());
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("update report_frozen_batch set workbook=? where id=?", new byte[]{1}, first));
    }

    @Test void frozenReportsRecheckCurrentRolesAndRequireAnotherBureauBusinessReviewer() throws Exception {
        var handler = actor("DISTRICT", "HANDLER");
        var generator = actor("BUREAU", "LEADER");
        var peer = actor("BUREAU", "LEGAL");
        var administrator = actor("BUREAU", "ADMIN");
        var district = actor("DISTRICT", "LEADER");
        var agency = actor("AGENCY", "AGENCY");
        LocalDate date = LocalDate.of(2011, 6, 15);
        caseFile(handler, date);
        long id = api(generator, post("/report-versions/batches"), criteria("PENALTIES", date), 0).path("id").asLong();
        api(generator, post("/report-versions/batches/" + id + "/confirm"), Map.of("opinion", "自己确认"), 403);
        api(administrator, post("/report-versions/batches/" + id + "/confirm"), Map.of("opinion", "缺业务岗"), 403);
        api(district, get("/report-versions/batches/" + id + "/rows"), null, 403);
        api(agency, get("/report-versions/batches"), null, 403);
        assertTrue(api(peer, get("/report-versions/batches/" + id), null, 0).path("canConfirm").asBoolean());
        api(peer, post("/report-versions/batches/" + id + "/confirm"), Map.of("opinion", "跨处室复核通过"), 0);
        api(peer, post("/report-versions/batches/" + id + "/confirm"), Map.of("opinion", "跨处室复核通过"), 0);
        jdbc.update("delete from sys_user_role where user_id=?", generator.id());
        api(generator, get("/report-versions/batches/" + id), null, 403);
    }

    @Test void businessCutoffIncludesApprovedLateRecordedFundsAndExcludesPendingFutureVersions() throws Exception {
        var handler = actor("DISTRICT", "HANDLER");
        var bureau = actor("BUREAU", "LEADER");
        var reviewer = actor("BUREAU", "LEGAL");
        LocalDate date = LocalDate.of(2012, 3, 1);
        long caseId = caseFile(handler, date.minusDays(20));
        long original = jdbc.queryForObject("""
                insert into case_execution(case_id,kind,amount,paid_at,method)
                values(?,'FINE',20,?,'BANK') returning id
                """, Long.class, caseId, date.minusDays(10));
        long additional = entry(caseId, handler, reviewer, 30, date.minusDays(5), "APPROVED", false, null);
        entry(caseId, handler, reviewer, 90, date.minusDays(5), "PENDING", false, null);
        entry(caseId, handler, reviewer, 40, date.plusDays(1), "APPROVED", false, null);
        entry(caseId, handler, reviewer, 50, date.minusDays(4), "APPROVED", true, null);
        entry(caseId, handler, reviewer, -20, date.minusDays(3), "APPROVED", false, original);
        moneyEntry(caseId, handler, reviewer, -5, date.minusDays(2), "APPROVED", false, "REFUND", null, additional);
        long courtRequest = jdbc.queryForObject("""
                insert into case_continuation_request(case_id,request_key,request_type,payload,source_snapshot,
                  reason,source_reference,requested_by,requested_username,requester_dept_id,requester_category,
                  status,reviewed_by,reviewed_username,reviewed_at,opinion)
                values(?,?,'COURT_APPLY','{}'::jsonb,'{}'::jsonb,'申请法院执行','法院申请件',?,?,?,'DISTRICT',
                  'APPROVED',?,?,current_timestamp,'核对通过') returning id
                """, Long.class, caseId, UUID.randomUUID().toString(), handler.id(), handler.name(), handler.department(),
                reviewer.id(), reviewer.name());
        jdbc.update("""
                insert into case_court_round(case_id,request_id,round_no,applied_at,application_reference,eligibility_basis)
                values(?,?,1,?,'法院申请文号','人工核对符合申请条件')
                """, caseId, courtRequest, date.minusDays(1));
        var request = criteria("PENALTIES", date);
        request.put("start", date.minusDays(20).toString());
        long id = api(bureau, post("/report-versions/batches"), request, 0).path("id").asLong();
        var metadata = api(bureau, get("/report-versions/batches/" + id), null, 0);
        assertFalse(metadata.path("known_at").asText().isBlank());
        assertEquals(date.toString(), metadata.path("business_cutoff").asText());
        var line = find(api(bureau, get("/report-versions/batches/" + id + "/rows"), null, 0).path("rows"),
                "row_key", "CASE:" + caseId);
        assertEquals(0, line.path("cells").get(14).decimalValue().compareTo(new BigDecimal("25")));
        assertEquals("是", line.path("cells").get(18).asText());
        String workbook = sheetsText(download(bureau, id));
        assertTrue(workbook.contains("本表三类款项冲正／退付合计"));
        assertTrue(workbook.contains("本表三类款项正向收缴合计"));
    }

    @Test void penaltyEnforcementColumnsFollowTheEffectiveDecisionVersionAndPreserveFrozenBytes() throws Exception {
        var handler = actor("DISTRICT", "HANDLER");
        var bureau = actor("BUREAU", "LEADER");
        var reviewer = actor("BUREAU", "LEGAL");
        LocalDate originalDate = LocalDate.of(2015, 6, 15);
        long caseId = caseFile(handler, originalDate);
        long unrelatedCase = caseFile(handler, originalDate.minusYears(1));
        LocalDate oldApplicationDate = originalDate.plusDays(2);
        long originalDocument = jdbc.queryForObject("""
                insert into case_document(case_id,doc_type,title,content,made_at,maker)
                values(?,'URGE_LETTER','原卷催告','原卷催告全文',?,?) returning id
                """, Long.class, caseId, originalDate, handler.name());
        jdbc.update("""
                insert into case_delivery(case_id,document_id,doc_kind,method,delivered_at,receiver,receipt_no)
                values(?,?,'URGE_LETTER','DIRECT',?,'当事人','原卷催告送达回证')
                """, caseId, originalDocument, originalDate.plusDays(1));
        jdbc.update("update case_file set court_enforce_applied=true,court_applied_at=? where id=?", oldApplicationDate, caseId);
        courtRound(caseId, null, handler, reviewer, 1, oldApplicationDate, "APPROVED", false);

        long original = penaltyBatch(bureau, originalDate, originalDate.plusDays(4));
        assertEnforcement(bureau, original, caseId, 100, "是", "是");
        byte[] frozen = download(bureau, original);

        LocalDate firstEffective = originalDate.plusDays(5);
        LocalDate nextEffective = originalDate.plusDays(10);
        long firstVersion = decisionVersion(caseId, handler, reviewer, firstEffective, 200);
        long nextVersion = decisionVersion(caseId, handler, reviewer, nextEffective, 300);
        supplementUrge(caseId, caseId, firstVersion, handler, "APPROVED", false, firstEffective, firstEffective.plusDays(2));
        courtRound(caseId, firstVersion, handler, reviewer, 2, firstEffective.plusDays(3), "APPROVED", false);

        // Even on the same business date the wrong case, unreviewed bundle and future review cannot count.
        supplementUrge(caseId, unrelatedCase, nextVersion, handler, "APPROVED", false, nextEffective, nextEffective);
        supplementUrge(unrelatedCase, caseId, nextVersion, handler, "APPROVED", false, nextEffective, nextEffective);
        supplementUrge(caseId, caseId, nextVersion, handler, "PENDING", false, nextEffective, nextEffective);
        supplementUrge(caseId, caseId, nextVersion, handler, "APPROVED", true, nextEffective, nextEffective);
        supplementUrge(caseId, caseId, nextVersion, handler, "APPROVED", false, nextEffective, null);
        supplementUrge(caseId, caseId, nextVersion, handler, "APPROVED", false, nextEffective.minusDays(1), nextEffective.minusDays(1));
        courtRound(caseId, nextVersion, handler, reviewer, 3, nextEffective, "PENDING", false);
        courtRound(caseId, nextVersion, handler, reviewer, 4, nextEffective, "APPROVED", true);
        courtRound(caseId, nextVersion, handler, reviewer, 5, nextEffective.minusDays(1), "APPROVED", false);
        LocalDate currentDelivery = nextEffective.plusDays(3);
        LocalDate currentApplication = nextEffective.plusDays(4);
        supplementUrge(caseId, caseId, nextVersion, handler, "APPROVED", false, nextEffective, currentDelivery);
        courtRound(caseId, nextVersion, handler, reviewer, 6, currentApplication, "APPROVED", false);

        assertEnforcement(bureau, penaltyBatch(bureau, originalDate, oldApplicationDate), caseId, 100, "是", "是");
        assertEnforcement(bureau, penaltyBatch(bureau, originalDate, firstEffective), caseId, 200, "否", "否");
        assertEnforcement(bureau, penaltyBatch(bureau, originalDate, firstEffective.plusDays(3)), caseId, 200, "是", "是");
        assertEnforcement(bureau, penaltyBatch(bureau, originalDate, nextEffective), caseId, 300, "否", "否");
        assertEnforcement(bureau, penaltyBatch(bureau, originalDate, currentDelivery), caseId, 300, "是", "否");
        assertEnforcement(bureau, penaltyBatch(bureau, originalDate, currentApplication), caseId, 300, "是", "是");
        assertEnforcement(bureau, original, caseId, 100, "是", "是");
        assertArrayEquals(frozen, download(bureau, original));
    }

    @Test void allThreeVersionsExposeBoundedSourceLinkedPreviewsAndRealExcelFiles() throws Exception {
        var handler = actor("DISTRICT", "HANDLER");
        var bureau = actor("BUREAU", "LEGAL");
        LocalDate date = LocalDate.of(2013, 6, 15);
        caseFile(handler, date);
        jdbc.update("""
                insert into department_interview(dept_id,department_name,institution_code,institution_name,
                  interview_type,reason,content,participants,interview_date,created_by)
                values(?,'测试部门','IV-001','预览机构','个别约谈','事由','实际内容','甲、乙',?,?)
                """, handler.department(), date, handler.id());
        jdbc.update("""
                insert into inspection(insp_no,object_name,object_type,officers,planned_at,done_at,created_by,owner_dept_id)
                values(?,'独立检查预览','PROVIDER','甲、乙',?,?,?,?)
                """, "PREVIEW-" + UUID.randomUUID().toString().substring(0, 20), date, date, handler.name(), handler.department());
        for (String type : new String[]{"PENALTIES", "ENFORCEMENT_WORK", "INTERVIEWS"}) {
            long id = api(bureau, post("/report-versions/batches"), criteria(type, date), 0).path("id").asLong();
            String route = "/report-versions/batches/" + id;
            assertEquals("PENALTIES".equals(type) ? "PENALTIES_EFFECTIVE_DECISION_EXECUTION_V2" : "V48_BUSINESS_DATE_APPROVED_KNOWN_AT",
                    api(bureau, get(route), null, 0).path("rule_version").asText());
            var preview = api(bureau, get(route + "/rows?page=1&size=1"), null, 0);
            assertEquals(1, preview.path("rows").size());
            assertTrue(preview.path("rows").get(0).path("link").asText().startsWith("/case/"));
            assertFalse(preview.path("columns").isEmpty());
            assertTrue(download(bureau, id).length > 1000);
            api(bureau, get(route + "/rows?size=101"), null, 2195);
            assertTrue(api(bureau, get(route + "/rows?page=2147483647"), null, 0).path("rows").isEmpty());
        }
    }

    @Test void reliefAndDeferUseOnlyTheEffectiveDecisionAndDatedExecutionBasis() throws Exception {
        var handler = actor("DISTRICT", "HANDLER");
        var bureau = actor("BUREAU", "LEADER");
        var reviewer = actor("BUREAU", "LEGAL");
        LocalDate original = LocalDate.of(2017, 6, 1);
        long caseId = caseFile(handler, original);
        jdbc.update("insert into biz_approval(kind,case_id,reason,applicant,applied_at) values('DEFER',?,'原决定暂缓',?,?::timestamptz)",
                caseId, handler.name(), original + "T12:00:00+08:00");
        long before = penaltyBatch(bureau, original, original.plusDays(1));
        byte[] oldFile = download(bureau, before);
        assertTrue(penaltyCells(bureau, before, caseId).get(15).asText().startsWith("是（未缴 "));

        LocalDate effective = original.plusDays(2);
        long version = decisionVersion(caseId, handler, reviewer, effective, 200);
        long delivery = supplementDelivery(caseId, caseId, version, handler, "APPROVED", false, effective,
                effective.plusDays(1), "DECISION");
        LocalDate basisDate = effective.plusDays(2);
        LocalDate deadline = effective.plusDays(3);
        jdbc.update("""
                insert into case_version_execution_basis(case_id,decision_version_id,delivery_id,relief_deadline,
                    relief_pending,basis,defer_until,recorded_by,recorded_at)
                values(?,?,?,?,false,'本版救济依据',?,?,?::timestamptz)
                """, caseId, version, delivery, deadline, deadline.plusDays(8), handler.name(), basisDate + "T12:00:00+08:00");
        var unknown = penaltyCells(bureau, penaltyBatch(bureau, original, effective.plusDays(1)), caseId);
        assertTrue(unknown.get(15).isNumber(), "原决定暂缓申请不能沿用到新决定");
        assertTrue(unknown.get(16).isNull(), "依据登记前历史救济状态须留空");
        var pendingDeadline = penaltyCells(bureau, penaltyBatch(bureau, original, deadline), caseId);
        assertTrue(pendingDeadline.get(15).asText().startsWith("是（未缴 "));
        assertEquals("否", pendingDeadline.get(16).asText());
        assertEquals("是", penaltyCells(bureau, penaltyBatch(bureau, original, deadline.plusDays(1)), caseId).get(16).asText());
        assertArrayEquals(oldFile, download(bureau, before));
    }

    @Test void monthlySubmissionFreezesSourceAndReceiptAcrossApprovedCorrection() throws Exception {
        var district = actor("DISTRICT", "HANDLER");
        var generator = actor("BUREAU", "LEADER");
        var reviewer = actor("BUREAU", "LEGAL");
        var administrator = actor("BUREAU", "ADMIN");
        LocalDate start = LocalDate.of(2007, 2, 1);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        long source = caseFile(district, start);
        caseFile(district, end.plusDays(1));
        var request = criteria("MONTHLY_SUBMISSION", start);
        api(generator, post("/report-versions/batches"), request, 2195);
        request.put("end", end.toString());
        request.put("cutoff", end.toString());
        var generated = api(generator, post("/report-versions/batches"), request, 0);
        long id = generated.path("id").asLong();
        String route = "/report-versions/batches/" + id;
        assertEquals("全局业务统计", generated.path("scope_label").asText());
        assertEquals("MONTHLY_BUSINESS_CUTOFF_V1", generated.path("rule_version").asText());
        assertEquals(id, api(generator, post("/report-versions/batches"), request, 0).path("id").asLong());
        var csv = mvc.perform(get(route + "/download").header("Authorization", "Bearer " + generator.token()))
                .andReturn().getResponse();
        assertEquals("text/csv;charset=UTF-8", csv.getContentType());
        assertEquals(generated.path("file_sha256").asText(), csv.getHeader("X-Content-SHA256"));
        assertTrue(new String(csv.getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8).contains("2007-02,1,1,100.00"));
        var receipt = Map.of("submittedAt", LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(),
                "recipient", "上级统计接收岗", "receiptNo", "统计回执-01", "note", "外部渠道实收");
        api(reviewer, post(route + "/receipt"), receipt, 2195);
        api(district, get(route), null, 403);
        api(generator, post(route + "/confirm"), Map.of("opinion", "本人不能复核"), 403);
        api(reviewer, post(route + "/confirm"), Map.of("opinion", "已核对口径和摘要"), 0);
        api(administrator, post(route + "/receipt"), receipt, 403);
        var accepted = api(reviewer, post(route + "/receipt"), receipt, 0).path("submissionReceipt");
        assertEquals("统计回执-01", accepted.path("receipt_no").asText());
        assertEquals(accepted, api(reviewer, post(route + "/receipt"), receipt, 0).path("submissionReceipt"));
        api(reviewer, post(route + "/receipt"), Map.of("submittedAt", receipt.get("submittedAt"),
                "recipient", "上级统计接收岗", "receiptNo", "异号"), 2195);
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.update("delete from report_submission_receipt where batch_id=?", id));

        entry(source, district, reviewer, 7, end, "APPROVED", false, null);
        api(reviewer, post(route + "/transition"), Map.of("action", "VOID", "opinion", "已报批次需关联更正"), 0);
        request.put("requestKey", UUID.randomUUID().toString());
        request.put("parentBatchId", id);
        long second = api(generator, post("/report-versions/batches"), request, 0).path("id").asLong();
        var difference = api(reviewer, get("/report-versions/batches/" + second + "/changes"), null, 0).path("rows").get(0);
        assertEquals("MONTH:200702", difference.path("row_key").asText());
        assertEquals(7, difference.path("current_cells").get(5).asInt());
        assertEquals(accepted, api(reviewer, get(route), null, 0).path("submissionReceipt"));
        assertArrayEquals(csv.getContentAsByteArray(), mvc.perform(get(route + "/download")
                .header("Authorization", "Bearer " + generator.token())).andReturn().getResponse().getContentAsByteArray());
    }

    @Test void concurrentSameGenerationKeyStoresOneWorkbookAndRowsAndCanRetryAfterRollback() throws Exception {
        var handler = actor("DISTRICT", "HANDLER");
        var generator = actor("BUREAU", "LEADER");
        LocalDate date = LocalDate.of(2014, 6, 15);
        caseFile(handler, date);
        var request = criteria("PENALTIES", date);
        String body = mapper.writeValueAsString(request);
        try (var blocker = dataSource.getConnection(); var workers = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.prepareStatement("select id from sys_user where id=? for update")) {
                statement.setLong(1, generator.id());
                try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
            }
            var task = (java.util.concurrent.Callable<JsonNode>) () -> mapper.readTree(mvc.perform(
                    post("/report-versions/batches").header("Authorization", "Bearer " + generator.token())
                            .contentType("application/json").content(body)).andReturn().getResponse().getContentAsString());
            var first = workers.submit(task);
            var second = workers.submit(task);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean bothWaiting = false;
                while (System.nanoTime() < deadline) {
                    int waiting = jdbc.queryForObject("""
                            select count(*) from pg_stat_activity where datname=current_database()
                              and pid<>pg_backend_pid() and wait_event_type='Lock'
                              and query ilike '%sys_user%'
                            """, Integer.class);
                    if (waiting >= 2) { bothWaiting = true; break; }
                    Thread.sleep(20);
                }
                assertTrue(bothWaiting, "Both generations must establish their snapshots before releasing the actor gate");
            } finally { blocker.rollback(); }
            JsonNode left = first.get(30, TimeUnit.SECONDS), right = second.get(30, TimeUnit.SECONDS);
            assertTrue(left.path("code").asInt() == 0 || right.path("code").asInt() == 0);
            assertTrue(left.path("code").asInt() == 0 || left.path("code").asInt() == 2193, left.toString());
            assertTrue(right.path("code").asInt() == 0 || right.path("code").asInt() == 2193, right.toString());
            long id = (left.path("code").asInt() == 0 ? left : right).path("data").path("id").asLong();
            assertEquals(1, jdbc.queryForObject("select count(*) from report_frozen_batch where generated_by=? and request_key=?::uuid",
                    Integer.class, generator.id(), request.get("requestKey")));
            assertEquals(1, jdbc.queryForObject("select count(*) from report_frozen_row where batch_id=?", Integer.class, id));
            byte[] frozen = download(generator, id);
            assertEquals(id, api(generator, post("/report-versions/batches"), request, 0).path("id").asLong());
            assertArrayEquals(frozen, download(generator, id));
        }
    }

    private long entry(long caseId, Actor owner, Actor reviewer, int amount, LocalDate paidAt,
                       String status, boolean futureApproval, Long reversed) {
        return moneyEntry(caseId, owner, reviewer, amount, paidAt, status, futureApproval,
                amount < 0 ? "REVERSAL" : "EXECUTION", reversed, null);
    }

    private long penaltyBatch(Actor bureau, LocalDate start, LocalDate cutoff) throws Exception {
        var request = criteria("PENALTIES", cutoff);
        request.put("start", start.toString());
        return api(bureau, post("/report-versions/batches"), request, 0).path("id").asLong();
    }

    private void assertEnforcement(Actor bureau, long batch, long caseId, int fine, String urged, String enforced) throws Exception {
        var line = penaltyCells(bureau, batch, caseId);
        assertEquals(fine, line.get(11).asInt(), "The expected decision version must be effective");
        assertEquals(urged, line.get(17).asText(), "催告送达");
        assertEquals(enforced, line.get(18).asText(), "申请法院强制执行");
    }

    private JsonNode penaltyCells(Actor bureau, long batch, long caseId) throws Exception {
        return find(api(bureau, get("/report-versions/batches/" + batch + "/rows"), null, 0).path("rows"),
                "row_key", "CASE:" + caseId).path("cells");
    }

    private long decisionVersion(long caseId, Actor owner, Actor reviewer, LocalDate effective, int fine) {
        long remedy = jdbc.queryForObject("""
                insert into case_remedy(case_id,request_key,remedy_type,accepted_at,reference,reason,source_reference,
                                       registered_by,registered_username)
                values(?,?,'CORRECTION',?,'测试受理号','复核原卷','测试受理件',?,?) returning id
                """, Long.class, caseId, UUID.randomUUID().toString(), effective.minusDays(1), owner.id(), owner.name());
        long request = continuation(caseId, owner, reviewer, "REMEDY_RESULT", "APPROVED", false);
        return jdbc.queryForObject("""
                insert into case_decision_version(case_id,request_id,remedy_id,outcome,effective_at,decision_type,
                  decision_no,fine_amount,recoup_amount,confiscate_amount,penalty_basis,content)
                values(?,?,?,'CHANGE',?,'PUNISH',?,?,0,0,'本版处罚依据','本版处罚决定') returning id
                """, Long.class, caseId, request, remedy, effective, "VERSION-" + UUID.randomUUID(), fine);
    }

    private void courtRound(long caseId, Long version, Actor owner, Actor reviewer, int round, LocalDate applied,
                            String status, boolean futureApproval) {
        long request = continuation(caseId, owner, reviewer, "COURT_APPLY", status, futureApproval);
        jdbc.update("""
                insert into case_court_round(case_id,request_id,decision_version_id,round_no,applied_at,application_reference,eligibility_basis)
                values(?,?,?,?,?,'合成法院申请文号','已核对合成申请条件')
                """, caseId, request, version, round, applied);
    }

    private void supplementUrge(long documentCase, long bundleCase, long version, Actor owner, String status,
                                boolean futureApproval, LocalDate madeAt, LocalDate deliveredAt) {
        supplementDelivery(documentCase, bundleCase, version, owner, status, futureApproval, madeAt, deliveredAt, "URGE_LETTER");
    }

    private long supplementDelivery(long documentCase, long bundleCase, long version, Actor owner, String status,
                                    boolean futureApproval, LocalDate madeAt, LocalDate deliveredAt, String documentType) {
        long bundle = jdbc.queryForObject("""
                insert into case_supplement_bundle(case_id,request_key,title,source_reference,status,created_by,
                    applicant_id,source_snapshot,reviewed_at)
                values(?,?,'催告材料','测试原件',?,?,?,'{}'::jsonb,
                    case when ?='APPROVED' then current_timestamp + (? * interval '1 day') end) returning id
                """, Long.class, bundleCase, UUID.randomUUID().toString(), status, owner.name(), owner.id(), status,
                futureApproval ? 1 : 0);
        long document = jdbc.queryForObject("""
                insert into case_supplement_document(case_id,bundle_id,decision_version_id,request_key,doc_type,
                    title,content,content_sha256,made_at,created_by)
                values(?,?,?,?,?,'独立版本文书','合成文书全文',?,?,?) returning id
                """, Long.class, documentCase, bundle, version, UUID.randomUUID().toString(), documentType, "0".repeat(64), madeAt, owner.name());
        return deliveredAt == null ? 0 : jdbc.queryForObject("""
                insert into case_supplement_delivery(document_id,method,delivered_at,receiver,receipt_no,source_reference,recorded_by)
                values(?,'DIRECT',?,'当事人',?,'测试回证原件',?) returning id
                """, Long.class, document, deliveredAt, "DELIVERY-" + UUID.randomUUID(), owner.name());
    }

    private long continuation(long caseId, Actor owner, Actor reviewer, String type, String status, boolean futureApproval) {
        return jdbc.queryForObject("""
                insert into case_continuation_request(case_id,request_key,request_type,payload,source_snapshot,
                  reason,source_reference,requested_by,requested_username,requester_dept_id,requester_category,
                  status,reviewed_by,reviewed_username,reviewed_at,opinion)
                values(?,?,?, '{}'::jsonb,'{}'::jsonb,'测试','原件',?,?,?,'DISTRICT',?,
                  case when ?='APPROVED' then ?::bigint end,case when ?='APPROVED' then ? end,
                  case when ?='APPROVED' then current_timestamp + (? * interval '1 day') end,
                  case when ?='APPROVED' then '复核' end) returning id
                """, Long.class, caseId, UUID.randomUUID().toString(), type, owner.id(), owner.name(),
                owner.department(), status, status, reviewer.id(), status, reviewer.name(), status, futureApproval ? 1 : 0, status);
    }

    private long moneyEntry(long caseId, Actor owner, Actor reviewer, int amount, LocalDate paidAt,
                       String status, boolean futureApproval, String type, Long reversed, Long refundEntry) {
        String key = UUID.randomUUID().toString();
        long request = continuation(caseId, owner, reviewer, type, status, futureApproval);
        return jdbc.queryForObject("""
                insert into case_execution_continuation(case_id,request_id,kind,amount,paid_at,receipt_no,method,note,
                  reverses_execution_id,refunds_entry_id)
                values(?,?,'FINE',?,?,?,?,'来源单据',?,?) returning id
                """, Long.class, caseId, request, amount, paidAt, key,
                amount < 0 ? type : "BANK", reversed, refundEntry);
    }

    private void review(Actor reviewer, String path, long request) throws Exception {
        api(reviewer, post(path + "/" + request + "/review"), Map.of("approve", true, "opinion", "核对原卷通过"), 0);
    }

    private Map<String, Object> criteria(String type, LocalDate date) {
        var body = new HashMap<String, Object>();
        body.put("type", type); body.put("start", date.toString()); body.put("end", date.toString());
        body.put("cutoff", date.toString()); body.put("requestKey", UUID.randomUUID().toString());
        if (!"ENFORCEMENT_WORK".equals(type)) body.put("year", date.getYear());
        return body;
    }

    private JsonNode api(Actor actor, MockHttpServletRequestBuilder request, Object body, int code) throws Exception {
        if (body != null) request.contentType("application/json").content(mapper.writeValueAsString(body));
        var response = mvc.perform(request.header("Authorization", "Bearer " + actor.token())).andReturn().getResponse();
        if (code == 403 && response.getStatus() == 403) return mapper.nullNode();
        var result = mapper.readTree(response.getContentAsString());
        assertEquals(code, result.path("code").asInt(-1), result.toString());
        if (code == 0) assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        return result.path("data");
    }

    private MockHttpServletRequestBuilder get(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path.startsWith("/api/") ? path : "/api/bureau" + path);
    }

    private MockHttpServletRequestBuilder post(String path) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path.startsWith("/api/") ? path : "/api/bureau" + path);
    }

    private byte[] download(Actor actor, long id) throws Exception {
        var response = mvc.perform(get("/api/bureau/report-versions/batches/" + id + "/download")
                .header("Authorization", "Bearer " + actor.token())).andReturn().getResponse();
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.getContentType());
        assertTrue(response.getHeader("Cache-Control").contains("no-store"));
        return response.getContentAsByteArray();
    }

    private Actor actor(String category, String role) {
        String key = "REPORT-" + UUID.randomUUID().toString().substring(0, 16);
        long department = jdbc.queryForObject("insert into sys_dept(name,code,type,org_kind) values(?,?,'ADMIN',?) returning id",
                Long.class, key, key, category);
        FixtureUsers.ensure(jdbc, key, role);
        jdbc.update("update sys_user set account_category=?,dept_id=? where username=?", category, department, key);
        long id = jdbc.queryForObject("select id from sys_user where username=?", Long.class, key);
        return new Actor(id, key, department, jwt.issue(key, 0));
    }

    private long caseFile(Actor owner, LocalDate date) {
        long id = jdbc.queryForObject("""
                insert into case_file(case_no,name,cause_id,party_name,party_type,status,filed_at,deadline_at,
                    owner_user,owner_dept_id,archive_no,closed_at)
                values(?,'冻结测试',(select min(id) from case_cause),'冻结机构','PROVIDER','CLOSED',?,?,?,?,?,?) returning id
                """, Long.class, "RPT-" + UUID.randomUUID().toString().substring(0, 24), date, date, owner.name(), owner.department(), "测试卷", date);
        jdbc.update("insert into case_decision(case_id,decision_type,content,decided_at,fine_amount) values(?,'PUNISH','原始决定',?,100)",
                id, date);
        return id;
    }

    private String source(long caseId) {
        return jdbc.queryForObject("""
                select md5(jsonb_build_object('c',to_jsonb(c),'d',to_jsonb(d))::text)
                from case_file c join case_decision d on d.case_id=c.id where c.id=?
                """, String.class, caseId);
    }

    private static JsonNode find(JsonNode rows, String field, String value) {
        for (var row : rows) if (value.equals(row.path(field).asText())) return row;
        fail("Missing " + field + "=" + value);
        return null;
    }

    private static String sheetsText(byte[] workbook) throws Exception {
        var text = new StringBuilder();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(workbook))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                if (entry.getName().startsWith("xl/worksheets/"))
                    text.append(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return text.toString();
    }
}
