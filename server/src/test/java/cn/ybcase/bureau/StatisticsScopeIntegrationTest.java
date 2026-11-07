package cn.ybcase.bureau;

import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.core.common.ConfigChangedEvent;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
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
import java.util.*;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@SpringBootTest @ActiveProfiles("test") @AutoConfigureMockMvc
class StatisticsScopeIntegrationTest {
    @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired ObjectMapper mapper;
    @Autowired SysUserRepository users; @Autowired SysRoleRepository roles; @Autowired JwtService jwt; @Autowired BureauConfig config;
    String original;
    record Actor(long id,String name,String token) {}
    Actor actor(String role) {
        var u=new SysUser();u.setUsername("stats_"+System.nanoTime());u.setRealName("统计隔离测试");u.setPassword("unused");
        if(role!=null)u.getRoles().add(roles.findByCode(role).orElseThrow());u=users.saveAndFlush(u);
        return new Actor(u.getId(),u.getUsername(),jwt.issue(u.getUsername(),u.getTokenVersion()));
    }
    void scope(String value) {
        if(original==null)original=jdbc.queryForObject("select cfg_value from sys_config where cfg_key='case_view_scope'",String.class);
        jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'",value);config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));
    }
    @AfterEach void restore(){if(original!=null){jdbc.update("update sys_config set cfg_value=? where cfg_key='case_view_scope'",original);config.onConfigChanged(new ConfigChangedEvent("case_view_scope"));}}
    JsonNode call(Actor who,String path,int expected) throws Exception {
        var res=mvc.perform(get("/api/bureau"+path).header("Authorization","Bearer "+who.token())).andReturn().getResponse();
        var root=mapper.readTree(res.getContentAsString());assertEquals(expected,root.path("code").asInt(),root.toString());return root.path("data");
    }
    JsonNode overview(Actor a)throws Exception{return call(a,"/stats/overview",0);}
    JsonNode report(Actor a)throws Exception{return call(a,"/stats/report?year="+LocalDate.now().getYear(),0);}
    long clue(Actor owner) {
        return jdbc.queryForObject("insert into case_clue(clue_no,source,content,suspect_name,suspect_type,received_at,deadline_at,created_by) values(?,'COMPLAINT','虚构','虚构医院','PROVIDER',current_date,current_date,?) returning id",Long.class,"STAT-"+System.nanoTime(),owner.name());
    }
    long fixture(Actor owner,int fine) {
        long cl=clue(owner);
        long id=jdbc.queryForObject("insert into case_file(case_no,name,clue_id,cause_id,party_name,party_type,filed_at,deadline_at,owner_user) values(?,'统计',( ?),(select min(id) from case_cause),'虚构医院','PROVIDER',current_date,current_date,?) returning id",Long.class,"STAT-"+System.nanoTime(),cl,owner.name());
        jdbc.update("insert into case_decision(case_id,decision_type,fine_amount,recoup_amount,content,decided_at) values(?,'PUNISH',?,10,'虚构决定',current_date)",id,fine);
        jdbc.update("insert into case_execution(case_id,kind,amount,method,paid_at) values(?,'FINE',30,'BANK',current_date),(?,'RECOUP',2,'BANK',current_date)",id,id);
        jdbc.update("insert into case_hearing(case_id,notice_sent_at,scheduled_at,host,held_at,status) values(?,current_date,current_date,'虚构主持',current_date,'HELD')",id);
        jdbc.update("insert into inspection(insp_no,object_name,object_type,officers,planned_at,done_at,clue_id,created_by) values(?,'虚构','PROVIDER','甲、乙',current_date,current_date,?,?)","STAT-"+System.nanoTime(),cl,owner.name());
        jdbc.update("insert into reward(clue_id,reporter_name,amount,paid_at) values(?,'虚构',5,current_date)",cl);
        jdbc.update("insert into case_transfer(clue_id,case_id,direction,target_org,kind,reason,sent_at) values(?,?,'IN','虚构','ADMIN','虚构',current_date)",cl,id);
        return id;
    }
    @Test void penaltyWorkbookUsesDecisionYearAndSeparatesFineFromOtherCollections() throws Exception {
        var handler = actor("HANDLER");
        var leader = actor("LEADER");
        jdbc.update("update sys_user set account_category='BUREAU' where id=?", leader.id());
        long historic = fixture(handler, 100);
        long current = fixture(handler, 200);
        long excluded = fixture(handler, 300);
        jdbc.update("update case_decision set decided_at=date '2025-12-31' where case_id=?", historic);
        jdbc.update("update case_decision set decided_at=date '2026-01-01' where case_id=?", current);
        jdbc.update("update case_decision set decided_at=date '2027-01-01' where case_id=?", excluded);
        jdbc.update("update case_file set institution_code='excluded-2027' where id=?", excluded);
        jdbc.update("""
                update case_file set party_type='INDIVIDUAL', party_credit_no='001234',
                       party_name='=1+1', filed_at=date '2025-12-30' where id=?
                """, current);
        jdbc.update("""
                insert into case_execution(case_id,kind,amount,method,paid_at)
                values(?,'LATE_FEE',5,'BANK',current_date)
                """, current);
        jdbc.update("update case_file set designated_no='JURISDICTION-ONLY' where id=?", historic);
        jdbc.update("""
                update case_file set institution_code='000321', institution_ownership='SOCIAL',
                       institution_grade='UNGRADED', confirmed_violation_start=date '2025-12-01',
                       confirmed_violation_end=date '2025-12-02', confirmed_violation_facts='确认的事实',
                       confirmed_violation_amount=7.50 where id=?
                """, historic);
        jdbc.update("update case_decision set penalty_basis='测试依据' where case_id=?", historic);
        var districtLeader = actor("LEADER");
        jdbc.update("update sys_user set account_category='DISTRICT' where id=?", districtLeader.id());
        String path = "/stats/penalties/download";
        call(handler, path, 403);
        call(districtLeader, path, 403);
        var response = mvc.perform(get("/api/bureau" + path)
                .header("Authorization", "Bearer " + leader.token())).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(response.getHeader("Content-Disposition").startsWith("attachment"));
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
            var sheets = new HashMap<String, org.w3c.dom.Document>();
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().startsWith("xl/worksheets/sheet")) {
                    sheets.put(entry.getName(), javax.xml.parsers.DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder().parse(new java.io.ByteArrayInputStream(zip.readAllBytes())));
                }
            }
            var olderSheet = sheets.get("xl/worksheets/sheet1.xml");
            var newerSheet = sheets.get("xl/worksheets/sheet2.xml");
            int older = reportRow(olderSheet, "D", "000321");
            int newer = reportRow(newerSheet, "E", "001234");
            assertEquals(-1, reportRow(olderSheet, "D", "excluded-2027"));
            assertEquals(-1, reportRow(newerSheet, "E", "excluded-2027"));
            assertEquals("2025-12-01 至 2025-12-02", reportCell(olderSheet, "C" + older).getTextContent());
            assertEquals("000321", reportCell(olderSheet, "D" + older).getTextContent());
            assertEquals("社会办", reportCell(olderSheet, "F" + older).getTextContent());
            assertEquals("未定级", reportCell(olderSheet, "G" + older).getTextContent());
            assertEquals("确认的事实", reportCell(olderSheet, "H" + older).getTextContent());
            assertEquals("7.50", reportCell(olderSheet, "I" + older).getTextContent());
            assertTrue(reportCell(olderSheet, "J" + older).getTextContent().contains("处罚依据：测试依据"));
            assertEquals("70.00", reportCell(olderSheet, "O" + older).getTextContent());
            assertEquals("001234", reportCell(newerSheet, "E" + newer).getTextContent());
            assertEquals("inlineStr", reportCell(newerSheet, "F" + newer).getAttribute("t"));
            assertEquals("=1+1", reportCell(newerSheet, "F" + newer).getTextContent());
            assertEquals("30.00", reportCell(newerSheet, "O" + newer).getTextContent());
            assertEquals("170.00", reportCell(newerSheet, "P" + newer).getTextContent());
            assertEquals("否", reportCell(newerSheet, "M" + newer).getTextContent());
            assertEquals("", reportCell(newerSheet, "Q" + newer).getTextContent());
        }
    }

    @Test void annualPenaltyReconcilesCrossYearPaymentsAndAuditsTheEffectiveRange() throws Exception {
        var handler = actor("HANDLER");
        var supervisor = actor("LEGAL");
        jdbc.update("update sys_user set account_category='BUREAU' where id=?", supervisor.id());
        long id = fixture(handler, 100);
        String code = "cutoff-" + System.nanoTime();
        jdbc.update("update case_file set institution_code=? where id=?", code, id);
        jdbc.update("""
                update case_decision set decided_at=date '1999-12-31', publish_withdrawn=true,
                       withdrawn_at=timestamptz '2000-01-04 10:00:00+08' where case_id=?
                """, id);
        jdbc.update("delete from case_execution where case_id=?", id);
        jdbc.update("""
                insert into case_execution(case_id,kind,amount,method,paid_at) values
                  (?,'FINE',30,'INSTALLMENT',date '1999-12-31'),
                  (?,'FINE',20,'INSTALLMENT',date '2000-01-05'),
                  (?,'LATE_FEE',7,'BANK',date '1999-12-31'),
                  (?,'RECOUP',10,'BANK',date '2000-01-01')
                """, id, id, id, id);
        String path = "/stats/penalties/download?year=1999&start=1999-12-31&end=1999-12-31&cutoff=";
        call(supervisor, path + "1999-12-30", 2100);
        for (String cutoff : List.of("2000-01-03", "2000-01-06")) {
            boolean later = cutoff.endsWith("06");
            var response = mvc.perform(get("/api/bureau" + path + cutoff)
                    .header("Authorization", "Bearer " + supervisor.token())).andReturn().getResponse();
            assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", response.getContentType());
            var sheets = reportSheets(response.getContentAsByteArray());
            assertEquals(2, sheets.size());
            var sheet = sheets.get("xl/worksheets/sheet1.xml");
            int row = reportRow(sheet, "E", code);
            assertTrue(row >= 4);
            assertEquals(later ? "50.00" : "30.00", reportCell(sheet, "O" + row).getTextContent());
            assertEquals(later ? "50.00" : "70.00", reportCell(sheet, "P" + row).getTextContent());
            assertEquals("是", reportCell(sheet, "M" + row).getTextContent());
            assertEquals("", reportCell(sheet, "Q" + row).getTextContent());
            var notes = sheets.get("xl/worksheets/sheet2.xml");
            assertEquals("7.00", reportCell(notes, "B" + reportRow(notes, "A", "已缴加处罚款（元）")).getTextContent());
            assertEquals(later ? "1" : "0",
                    reportCell(notes, "B" + reportRow(notes, "A", "截至日已撤回公示记录数")).getTextContent());
            assertEquals("1", reportCell(notes, "B" + reportRow(notes, "A", "缺确认违法事实记录数")).getTextContent());

            String requestId = response.getHeader("X-Request-Id");
            assertNotNull(requestId);
            var audit = jdbc.queryForMap("""
                    select username, report_type, report_start::text, report_end::text,
                           report_cutoff::text, report_row_count
                    from sys_audit_log where request_id=?
                    """, requestId);
            assertEquals(supervisor.name(), audit.get("username"));
            assertEquals("PENALTIES", audit.get("report_type"));
            assertEquals("1999-12-31", audit.get("report_start"));
            assertEquals("1999-12-31", audit.get("report_end"));
            assertEquals(cutoff, audit.get("report_cutoff"));
            assertEquals(1, audit.get("report_row_count"));
        }
    }

    @Test void penaltyMissingFieldsUsesAnnualPopulationAndOnlyApplicableSourceFields() throws Exception {
        var bureau = actor("LEGAL");
        var district = actor("LEADER");
        var unclassified = actor("ADMIN");
        jdbc.update("update sys_user set account_category='BUREAU' where id=?", bureau.id());
        jdbc.update("update sys_user set account_category='DISTRICT', dept_id=2 where id=?", district.id());

        LocalDate first = LocalDate.of(1907, 3, 1);
        LocalDate second = first.plusDays(1);
        String provider = penaltyQualityFixture("缺项核对机构", "PROVIDER", "PUNISH", first);
        jdbc.update("""
                update case_file set institution_code=?, confirmed_violation_facts=?,
                    confirmed_violation_start=date '1907-01-01', confirmed_violation_amount=0 where case_no=?
                """, " \t", " \n", provider);
        jdbc.update("""
                update case_decision set penalty_basis=?, publish_withdrawn=true, withdrawn_at=timestamptz '1907-03-01 09:00:00+08'
                where case_id=(select id from case_file where case_no=?)
                """, " \t", provider);

        String individual = penaltyQualityFixture("缺项核对自然人", "INDIVIDUAL", "PUNISH", second);
        String complete = penaltyQualityFixture("字段齐全机构", "PROVIDER", "PUNISH", second);
        jdbc.update("""
                update case_file set confirmed_violation_start=date '1907-01-01', confirmed_violation_end=date '1907-01-02',
                    confirmed_violation_facts='确认事实' where case_no in (?,?)
                """, individual, complete);
        jdbc.update("""
                update case_file set institution_code='0009', institution_ownership='SOCIAL',
                    institution_grade='UNGRADED', confirmed_violation_amount=0 where case_no=?
                """, complete);
        jdbc.update("update case_decision set penalty_basis='有效依据' where case_id in (select id from case_file where case_no in (?,?))",
                individual, complete);
        penaltyQualityFixture("非处罚记录", "PROVIDER", "NO_PUNISH", second);
        penaltyQualityFixture("其他主体记录", "OTHER", "PUNISH", second);
        penaltyQualityFixture("区间外记录", "PROVIDER", "PUNISH", second.plusDays(1));

        String filters = "?year=1907&start=" + first + "&end=" + second + "&cutoff=1907-04-01";
        String path = "/stats/penalties/missing-fields" + filters;
        call(district, path, 403);
        call(unclassified, path, 403);
        call(actor("HANDLER"), path, 403);
        call(bureau, path + "&size=101", 2100);
        call(bureau, path + "&page=0", 2100);
        var firstPage = call(bureau, path + "&size=1", 0);
        assertEquals(2, firstPage.path("total").asInt());
        assertEquals(first.toString(), firstPage.path("start").asText());
        assertEquals(second.toString(), firstPage.path("end").asText());
        assertEquals("1907-04-01", firstPage.path("cutoff").asText());
        var firstRow = firstPage.path("rows").get(0);
        assertEquals(provider, firstRow.path("caseNo").asText());
        assertEquals(first.toString(), firstRow.path("decidedAt").asText());
        assertEquals("缺项核对机构", firstRow.path("partyName").asText());
        assertEquals(mapper.valueToTree(List.of("机构编码", "机构性质", "机构等级", "违法发生期间", "认定违法事实", "处罚依据")),
                firstRow.path("missingFields"));

        var secondRow = call(bureau, path + "&page=2&size=1", 0).path("rows").get(0);
        assertEquals(individual, secondRow.path("caseNo").asText());
        assertEquals(mapper.valueToTree(List.of("参保人证件号码", "认定违法违规金额")), secondRow.path("missingFields"));
        var outsidePage = call(bureau, path + "&page=2147483647&size=100", 0);
        assertEquals(2, outsidePage.path("total").asInt());
        assertTrue(outsidePage.path("rows").isEmpty());

        var check = mvc.perform(get("/api/bureau" + path)
                .header("Authorization", "Bearer " + bureau.token())).andReturn().getResponse();
        assertEquals("no-store", check.getHeader("Cache-Control"));

        var response = mvc.perform(get("/api/bureau/stats/penalties/download" + filters)
                .header("Authorization", "Bearer " + bureau.token())).andReturn().getResponse();
        var sheets = reportSheets(response.getContentAsByteArray());
        var details = sheets.get("xl/worksheets/sheet1.xml");
        var notes = sheets.get("xl/worksheets/sheet2.xml");
        assertTrue(reportRow(details, "F", "缺项核对机构") >= 4);
        assertTrue(reportRow(details, "F", "缺项核对自然人") >= 4);
        assertTrue(reportRow(details, "F", "字段齐全机构") >= 4);
        assertEquals("3", reportCell(notes, "B" + reportRow(notes, "A", "处罚记录数")).getTextContent());
    }

    @Test void approvedReportingSupplementsFeedBothWorkbooksWithoutChangingSourceFacts() throws Exception {
        var handler = actor("HANDLER");
        var reviewer = actor("LEGAL");
        jdbc.update("update sys_user set account_category='DISTRICT', dept_id=1 where id=?", handler.id());
        jdbc.update("update sys_user set account_category='BUREAU', dept_id=(select id from sys_dept where code='DL_OFF_LEGAL') where id=?",
                reviewer.id());
        var date = LocalDate.of(1908, 3, 1);
        String number = penaltyQualityFixture("历史补录报表机构", "PROVIDER", "PUNISH", date);
        long id = jdbc.queryForObject("select id from case_file where case_no=?", Long.class, number);
        jdbc.update("""
                update case_file set owner_user=?, owner_dept_id=1, institution_kind='MEDICAL',
                    confirmed_violation_amount=0, status='CLOSED', closed_at=? where id=?
                """, handler.name(), date, id);
        String filters = "?year=1908&start=1908-03-01&end=1908-03-01&cutoff=1908-03-01";
        assertEquals(1, call(reviewer, "/stats/penalties/missing-fields" + filters, 0).path("total").asInt());
        var requested = mvc.perform(post("/api/bureau/cases/" + id + "/report-supplements")
                .header("Authorization", "Bearer " + handler.token()).contentType("application/json")
                .content(mapper.writeValueAsString(Map.of("reason", "依据原卷补齐报表字段", "sourceReference", "原卷第2至5页",
                        "values", Map.of("institutionCode", "001-history", "institutionOwnership", "SOCIAL",
                                "institutionGrade", "UNGRADED", "confirmedViolationStart", "1908-01-01",
                                "confirmedViolationEnd", "1908-01-02", "confirmedViolationFacts", "原卷确认的历史事实",
                                "penaltyBasis", "原决定载明的依据")))))
                .andReturn().getResponse();
        var request = mapper.readTree(requested.getContentAsString());
        assertEquals(0, request.path("code").asInt(-1), request.toString());
        long requestId = request.path("data").path("id").asLong();
        assertEquals(1, call(reviewer, "/stats/penalties/missing-fields" + filters, 0).path("total").asInt());
        var reviewed = mvc.perform(post("/api/bureau/cases/" + id + "/report-supplements/" + requestId + "/review")
                .header("Authorization", "Bearer " + reviewer.token()).contentType("application/json")
                .content("{\"approve\":true,\"opinion\":\"已核原卷材料\"}")).andReturn().getResponse();
        assertEquals(0, mapper.readTree(reviewed.getContentAsString()).path("code").asInt(-1));
        assertEquals(0, call(reviewer, "/stats/penalties/missing-fields" + filters, 0).path("total").asInt());

        var penalty = mvc.perform(get("/api/bureau/stats/penalties/download" + filters)
                .header("Authorization", "Bearer " + reviewer.token())).andReturn().getResponse();
        var sheets = reportSheets(penalty.getContentAsByteArray());
        var penaltySheet = sheets.get("xl/worksheets/sheet1.xml");
        int row = reportRow(penaltySheet, "E", "001-history");
        assertTrue(row >= 4);
        assertEquals("社会办", reportCell(penaltySheet, "G" + row).getTextContent());
        assertEquals("未定级", reportCell(penaltySheet, "H" + row).getTextContent());
        assertEquals("原卷确认的历史事实", reportCell(penaltySheet, "I" + row).getTextContent());
        assertEquals("0.00", reportCell(penaltySheet, "J" + row).getTextContent());
        assertTrue(reportCell(penaltySheet, "K" + row).getTextContent().contains("原决定载明的依据"));
        var notes = sheets.get("xl/worksheets/sheet2.xml");
        assertEquals("1", reportCell(notes, "B" + reportRow(notes, "A", "有已审核补录台账的案件数")).getTextContent());

        var work = mvc.perform(get("/api/bureau/stats/enforcement-work/download?start=1908-03-01&end=1908-03-01")
                .header("Authorization", "Bearer " + reviewer.token())).andReturn().getResponse();
        var workSheet = reportSheets(work.getContentAsByteArray()).get("xl/worksheets/sheet1.xml");
        int workRow = reportRow(workSheet, "D", "历史补录报表机构");
        assertEquals("非公立医疗机构", reportCell(workSheet, "E" + workRow).getTextContent());
        assertEquals("无等级", reportCell(workSheet, "F" + workRow).getTextContent());
        assertEquals("原卷确认的历史事实", reportCell(workSheet, "K" + workRow).getTextContent());
        assertTrue(reportCell(workSheet, "L" + workRow).getTextContent().contains("原决定载明的依据"));

        var original = jdbc.queryForMap("""
                select c.institution_code, c.institution_ownership, c.institution_grade,
                       c.confirmed_violation_start, c.confirmed_violation_end, c.confirmed_violation_facts,
                       c.confirmed_violation_amount, d.penalty_basis
                from case_file c join case_decision d on d.case_id=c.id where c.id=?
                """, id);
        assertEquals(new java.math.BigDecimal("0.00"), original.remove("confirmed_violation_amount"));
        assertTrue(original.values().stream().allMatch(Objects::isNull));
    }

    private String penaltyQualityFixture(String partyName, String partyType, String decisionType, LocalDate date) {
        String number = "QUALITY-" + System.nanoTime();
        long id = jdbc.queryForObject("""
                insert into case_file (case_no, name, cause_id, party_name, party_type, filed_at, deadline_at)
                values (?, '缺项核对测试', (select min(id) from case_cause), ?, ?, ?, ?) returning id
                """, Long.class, number, partyName, partyType, date.minusDays(1), date);
        jdbc.update("insert into case_decision(case_id, decision_type, content, decided_at) values (?, ?, '测试决定', ?)",
                id, decisionType, date);
        return number;
    }

    @Test void traceCodeCluesCanBeFilteredBeforePagination() throws Exception {
        var handler = actor("HANDLER");
        var marker = "trace-" + System.nanoTime();
        for (String source : List.of("TRACE_CODE", "ASSIGNED")) {
            var response = mvc.perform(post("/api/bureau/clues")
                            .header("Authorization", "Bearer " + handler.token())
                            .contentType("application/json")
                            .content(mapper.writeValueAsString(Map.of("source", source, "content", marker,
                                    "suspectName", "药品追溯测试机构", "suspectType", "PROVIDER"))))
                    .andReturn().getResponse();
            var body = mapper.readTree(response.getContentAsString());
            assertEquals(0, body.path("code").asInt(-1), body.toString());
            assertEquals(source, body.path("data").path("source").asText());
        }
        var paged = call(handler, "/clues?page=1&size=1&status=PENDING&source=TRACE_CODE&q=" + marker, 0);
        assertEquals(1, paged.path("total").asLong());
        assertEquals("TRACE_CODE", paged.path("rows").get(0).path("source").asText());
        assertEquals(2, call(handler, "/clues?page=1&size=1&q=" + marker, 0).path("total").asLong());
        var legacy = call(handler, "/clues?status=PENDING&source=TRACE_CODE", 0);
        assertTrue(legacy.isArray());
        assertFalse(legacy.isEmpty());
        legacy.forEach(row -> assertEquals("TRACE_CODE", row.path("source").asText()));
    }

    @Test void enforcementReportIncludesCaseAndStandaloneInspectionOnce() throws Exception {
        var handler = actor("HANDLER");
        var leader = actor("LEADER");
        jdbc.update("update sys_user set account_category='BUREAU' where id=?", leader.id());
        long id = fixture(handler, 100);
        jdbc.update("""
                update case_file set party_name='工作报表测试机构', institution_kind='CHAIN_PHARMACY',
                       confirmed_violation_facts='已核实', status='CLOSED', closed_at=current_date where id=?
                """, id);
        jdbc.update("update inspection set task_level='NATIONAL' where clue_id=(select clue_id from case_file where id=?)", id);
        jdbc.update("""
                insert into inspection(insp_no,object_name,object_type,officers,planned_at,done_at,created_by,task_level)
                values(?,'独立专项检查','PROVIDER','甲、乙',current_date,current_date,?,'MUNICIPAL')
                """, "WORK-" + System.nanoTime(), handler.name());
        var crossCheck = mvc.perform(post("/api/bureau/inspections")
                        .header("Authorization", "Bearer " + handler.token()).contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("objectName", "独立市级交叉检查", "objectType", "PROVIDER",
                                "officers", "甲、乙", "taskLevel", "CITY_CROSS_CHECK"))))
                .andReturn().getResponse();
        var crossBody = mapper.readTree(crossCheck.getContentAsString());
        assertEquals(0, crossBody.path("code").asInt(-1), crossBody.toString());
        var cross = crossBody.path("data");
        assertEquals("CITY_CROSS_CHECK", cross.path("task_level").asText());
        var completed = mvc.perform(post("/api/bureau/inspections/" + cross.path("id").asLong() + "/complete")
                        .header("Authorization", "Bearer " + handler.token()).contentType("application/json")
                        .content("{\"result\":\"核查完成\",\"violationFound\":false}"))
                .andReturn().getResponse();
        assertEquals(0, mapper.readTree(completed.getContentAsString()).path("code").asInt(-1));
        jdbc.update("""
                insert into inspection(insp_no,object_name,object_type,officers,planned_at,done_at,clue_id,created_by,task_level)
                select ?, '已转线索的交叉检查', 'PROVIDER', '甲、乙', current_date, current_date, clue_id, ?, 'CITY_CROSS_CHECK'
                from case_file where id=?
                """, "CROSS-" + System.nanoTime(), handler.name(), id);
        jdbc.update("update case_clue set source='TRACE_CODE' where id=(select clue_id from case_file where id=?)", id);
        var date = LocalDate.now();
        jdbc.update("""
                insert into agreement_action(case_id, action, org, sent_at, replied_at, result,
                    agency_dept_id, agency_status, agency_cycle, agency_result_type, agency_result_effective_at)
                values (?,'TERMINATE_AGREEMENT','经办机构',?,?,'结构化解除',3,'COMPLETED',1,'COMPLETED',?),
                       (?,'TERMINATE_AGREEMENT','经办机构',?,?,'结构化不适用',3,'COMPLETED',1,'NOT_APPLICABLE',null),
                       (?,'TERMINATE_AGREEMENT','经办机构',?,?,'尚未生效解除',3,'COMPLETED',1,'COMPLETED',?)
                """, id, date, date, date, id, date, date, id, date, date, date.plusDays(1));
        jdbc.update("""
                insert into agreement_action(case_id, action, org, sent_at, replied_at, result, agency_dept_id,
                    agency_status, agency_cycle, agency_result_type, agency_recovered_amount, agency_result_reference)
                values (?,'RECOVER','经办机构',?,?,'结构化追回',3,'COMPLETED',1,'COMPLETED',45.50,'回执2026')
                """, id, date, date);
        long transfer = jdbc.queryForObject("""
                insert into case_transfer(case_id,direction,target_org,kind,reason,sent_at)
                values(?,'OUT','公安机关','JUDICIAL','涉嫌犯罪',current_date) returning id
                """, Long.class, id);
        var saved = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/bureau/transfers/" + transfer + "/judgment")
                        .header("Authorization", "Bearer " + handler.token())
                        .contentType("application/json")
                        .content(mapper.writeValueAsString(Map.of("number", "测试判决01", "date", date.toString(), "result", "判决结果"))))
                .andReturn().getResponse();
        assertEquals(0, mapper.readTree(saved.getContentAsString()).path("code").asInt(-1));
        String path = "/stats/enforcement-work/download?start=" + date + "&end=" + date;
        call(handler, path, 403);
        var response = mvc.perform(get("/api/bureau" + path)
                .header("Authorization", "Bearer " + leader.token())).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals("xl/worksheets/sheet1.xml")) continue;
                var sheet = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(new java.io.ByteArrayInputStream(zip.readAllBytes()));
                int reportedCase = reportRow(sheet, "D", "工作报表测试机构");
                int inspection = reportRow(sheet, "D", "独立专项检查");
                assertEquals("连锁药店", reportCell(sheet, "E" + reportedCase).getTextContent());
                assertEquals("是", reportCell(sheet, "I" + reportedCase).getTextContent());
                assertTrue(reportCell(sheet, "Q" + reportedCase).getTextContent().contains("测试判决01"));
                var agreement = reportCell(sheet, "M" + reportedCase).getTextContent();
                assertTrue(agreement.contains("实际追回 45.50 元"));
                assertTrue(agreement.contains("回执文号 回执2026"));
                var termination = reportCell(sheet, "P" + reportedCase).getTextContent();
                assertTrue(termination.contains("结构化解除"));
                assertFalse(termination.contains("结构化不适用"));
                assertFalse(termination.contains("尚未生效解除"));
                assertTrue(reportCell(sheet, "R" + reportedCase).getTextContent().contains("STAT-"));
                assertTrue(reportCell(sheet, "T" + inspection).getTextContent().contains("WORK-"));
                assertTrue(reportCell(sheet, "H" + reportedCase).getTextContent().startsWith("药品追溯码线索 "));
                assertTrue(reportCell(sheet, "T" + reportedCase).getTextContent().startsWith("市级交叉检查 "));
                int standaloneCross = reportRow(sheet, "D", "独立市级交叉检查");
                assertTrue(reportCell(sheet, "T" + standaloneCross).getTextContent().contains(cross.path("insp_no").asText()));
                assertTrue(reportCell(sheet, "H" + standaloneCross).getTextContent().startsWith("市级交叉检查 "));
                assertEquals(-1, reportRow(sheet, "D", "已转线索的交叉检查"));
                assertEquals(-1, reportRow(sheet, "D", "虚构"));
                return;
            }
        }
        fail("缺少办理案件情况工作表");
    }

    @Test void enforcementReportRetainsBackfilledOrdinaryInspectionLinkedToPreviouslyClosedCase() throws Exception {
        var handler = actor("HANDLER");
        var bureau = actor("LEGAL");
        jdbc.update("update sys_user set account_category='BUREAU' where id=?", bureau.id());
        long caseId = fixture(handler, 100);
        String caseName = "补录检查关联案件-" + caseId;
        String inspectionName = "补录日常检查-" + caseId;
        String inspectionNo = "BACKFILL-" + caseId;
        jdbc.update("""
                update case_file set party_name=?, status='CLOSED',
                    filed_at=current_date-10, decided_at=current_date-3, closed_at=current_date-1
                where id=?
                """, caseName, caseId);
        jdbc.update("update case_decision set decided_at=current_date-3 where case_id=?", caseId);
        jdbc.update("""
                update inspection set insp_no=?, object_name=?, task_level=null
                where clue_id=(select clue_id from case_file where id=?)
                """, inspectionNo, inspectionName, caseId);

        LocalDate today = LocalDate.now();
        var response = mvc.perform(get("/api/bureau/stats/enforcement-work/download")
                        .param("start", today.toString()).param("end", today.toString())
                        .header("Authorization", "Bearer " + bureau.token())).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals("xl/worksheets/sheet1.xml")) continue;
                var sheet = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                        .newDocumentBuilder().parse(new java.io.ByteArrayInputStream(zip.readAllBytes()));
                int row = reportRow(sheet, "D", caseName);
                assertTrue(row >= 4, "已结案但统计期内有补录日常检查的案件应保留");
                assertEquals("是", reportCell(sheet, "I" + row).getTextContent());
                assertTrue(reportCell(sheet, "U" + row).getTextContent().contains("日常检查：" + today + " " + inspectionNo));
                assertEquals(-1, reportRow(sheet, "D", inspectionName), "转案检查不再单列");
                return;
            }
        }
        fail("缺少办理案件情况工作表");
    }

    @Test void interviewLedgerExportsRealEventsByYearAndEnforcesDepartmentScope() throws Exception {
        var handler = actor("HANDLER");
        var other = actor("LEADER");
        var bureau = actor("LEGAL");
        jdbc.update("update sys_user set account_category='DISTRICT', dept_id=1 where id=?", handler.id());
        jdbc.update("update sys_user set account_category='DISTRICT', dept_id=2 where id=?", other.id());
        jdbc.update("update sys_user set account_category='BUREAU', dept_id=3 where id=?", bureau.id());
        LocalDate today = LocalDate.now();
        String code = "001-" + System.nanoTime();
        var record = new HashMap<String, Object>(Map.of(
                "institutionCode", code, "institutionName", "=定点机构",
                "interviewType", "个别约谈", "reason", "核实情况", "content", "当面说明",
                "participants", "甲、乙", "interviewDate", today.toString()));
        String endpoint = "/api/bureau/interviews";
        var saved = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(endpoint)
                .header("Authorization", "Bearer " + handler.token()).contentType("application/json")
                .content(mapper.writeValueAsString(record))).andReturn().getResponse();
        assertEquals(0, mapper.readTree(saved.getContentAsString()).path("code").asInt(-1));
        String list = "/interviews?year=" + today.getYear();
        assertTrue(call(other, list, 0).isEmpty());
        var own = call(handler, list, 0).get(0);
        assertEquals(code, own.path("institutionCode").asText());
        assertEquals("基金监督管理处", own.path("departmentName").asText());
        assertTrue(own.path("editable").asBoolean());

        long rowId = own.path("id").asLong();
        record.put("content", "更新后的实际约谈内容");
        for (var editor : List.of(other, handler)) {
            var changed = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .put(endpoint + "/" + rowId).header("Authorization", "Bearer " + editor.token())
                    .contentType("application/json").content(mapper.writeValueAsString(record)))
                    .andReturn().getResponse();
            assertEquals(editor.equals(handler) ? 0 : 403, mapper.readTree(changed.getContentAsString()).path("code").asInt(-1));
        }
        jdbc.update("""
                insert into department_interview (dept_id, department_name, institution_code, institution_name,
                    interview_type, reason, content, participants, interview_date, created_by)
                values (1, '基金监督管理处', 'prior-' || ?, '往年机构', '约谈', '事由', '内容', '人员', ?, ?)
                """, code, today.minusYears(1), handler.id());
        String paging = "/interviews/page?year=" + today.getYear();
        var page = call(handler, paging + "&page=1&size=1", 0);
        assertEquals(1, page.path("total").asLong());
        assertEquals(code, page.path("rows").get(0).path("institutionCode").asText());
        assertTrue(call(handler, paging + "&page=2&size=1", 0).path("rows").isEmpty());
        assertEquals(0, call(other, paging, 0).path("total").asLong());
        call(handler, paging + "&size=101", 2100);

        String path = "/stats/interviews/download?year=" + today.getYear();
        call(other, path, 403);
        call(actor("LEGAL"), path, 403);
        var response = mvc.perform(get("/api/bureau" + path).header("Authorization", "Bearer " + bureau.token()))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals("xl/worksheets/sheet1.xml")) continue;
                var sheet = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                        .parse(new java.io.ByteArrayInputStream(zip.readAllBytes()));
                int row = reportRow(sheet, "C", code);
                assertTrue(row >= 4);
                assertEquals(-1, reportRow(sheet, "C", "prior-" + code));
                assertEquals(today.getYear() + "年医保部门约谈定点医药机构台账", reportCell(sheet, "A1").getTextContent());
                assertEquals("基金监督管理处", reportCell(sheet, "B" + row).getTextContent());
                assertEquals("inlineStr", reportCell(sheet, "D" + row).getAttribute("t"));
                assertEquals("=定点机构", reportCell(sheet, "D" + row).getTextContent());
                assertEquals("更新后的实际约谈内容", reportCell(sheet, "G" + row).getTextContent());
                assertEquals("甲、乙", reportCell(sheet, "H" + row).getTextContent());
                assertEquals("19", reportCell(sheet, "I" + row).getAttribute("s"));
                return;
            }
        }
        fail("缺少约谈台账工作表");
    }

    private static Map<String, org.w3c.dom.Document> reportSheets(byte[] workbook) throws Exception {
        var sheets = new HashMap<String, org.w3c.dom.Document>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(workbook))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().startsWith("xl/worksheets/sheet")) {
                    sheets.put(entry.getName(), javax.xml.parsers.DocumentBuilderFactory.newInstance()
                            .newDocumentBuilder().parse(new java.io.ByteArrayInputStream(zip.readAllBytes())));
                }
            }
        }
        return sheets;
    }

    private static int reportRow(org.w3c.dom.Document sheet, String column, String text) {
        var cells = sheet.getElementsByTagName("c");
        for (int i = 0; i < cells.getLength(); i++) {
            var cell = (org.w3c.dom.Element) cells.item(i);
            String address = cell.getAttribute("r");
            if (address.matches(column + "[0-9]+") && text.equals(cell.getTextContent()))
                return Integer.parseInt(address.substring(column.length()));
        }
        return -1;
    }

    private static org.w3c.dom.Element reportCell(org.w3c.dom.Document sheet, String address) {
        var cells = sheet.getElementsByTagName("c");
        for (int i = 0; i < cells.getLength(); i++) {
            var cell = (org.w3c.dom.Element) cells.item(i);
            if (address.equals(cell.getAttribute("r"))) return cell;
        }
        throw new AssertionError("Missing report cell " + address);
    }

    @Test void selfOverviewAndAnnualAllDimensionsMatchVisibleCasesAndSources()throws Exception {
        scope("SELF");var own=actor("HANDLER");var other=actor("HANDLER");fixture(own,100);fixture(other,900);
        var o=overview(own);assertEquals(1,o.path("caseTotal").asInt());assertEquals(100,o.path("fineDecided").asInt());assertEquals(30,o.path("fineCollected").asInt());assertEquals(2,o.path("recoupCollected").asInt());
        assertEquals(1,o.path("caseByStatus").get(0).path("cnt").asInt());assertEquals(1,o.path("byPartyType").get(0).path("cnt").asInt());assertEquals(1,o.path("byCause").get(0).path("cnt").asInt());
        var r=report(own);var t=r.path("totals");for(String key:List.of("filed_total","hearing_total","transfer_total","inspection_total"))assertEquals(1,t.path(key).asInt(),key);
        assertEquals(5,t.path("reward_paid").asInt());assertEquals(12,r.path("monthly").size());
        var month=r.path("monthly").get(LocalDate.now().getMonthValue()-1);assertEquals(100,month.path("fine_decided").asInt());assertEquals(32,month.path("collected").asInt());assertEquals(1,month.path("filed").asInt());
        assertEquals(overview(actor("ADMIN")).path("clueTotal"),o.path("clueTotal"),"shared clue register remains shared");
        assertTrue(o.path("scopeLabel").asText().contains("可见"));
    }
    @Test void participationAvoidanceAndSupervisoryRolesUseTheSameExistingScope()throws Exception {
        scope("SELF");var own=actor("HANDLER");var other=actor("HANDLER");long id=fixture(other,700);
        assertEquals(0,overview(own).path("caseTotal").asInt());
        long officer=jdbc.queryForObject("insert into case_officer(case_id,name,cert_no,duty,user_id) values(?,'同名不授权','STATCERT','MEMBER',?) returning id",Long.class,id,own.id());
        assertEquals(700,overview(own).path("fineDecided").asInt());assertEquals(1,report(own).path("totals").path("filed_total").asInt());
        jdbc.update("update case_officer set avoided=true where id=?",officer);assertEquals(0,overview(own).path("caseTotal").asInt());
        var admin=actor("ADMIN");var expected=overview(admin);
        for(String role:List.of("LEGAL","LEADER")) {var data=overview(actor(role));assertEquals(expected.path("caseTotal"),data.path("caseTotal"));assertEquals(expected.path("fineDecided"),data.path("fineDecided"));assertEquals("全局业务统计",data.path("scopeLabel").asText());}
        scope("ALL");assertEquals(expected.path("caseTotal"),overview(own).path("caseTotal"));
    }
    @Test void allCaseScopeDoesNotExposeUnadoptedForeignSourcesOrCountUnboundTransfers()throws Exception {
        scope("ALL");var own=actor("HANDLER");var other=actor("HANDLER");var before=report(own).path("totals");long cl=clue(other);
        jdbc.update("insert into inspection(insp_no,object_name,object_type,officers,planned_at,done_at,created_by) values(?,'私有','PROVIDER','甲、乙',current_date,current_date,?)","STAT-"+System.nanoTime(),other.name());
        jdbc.update("insert into reward(clue_id,reporter_name,amount,paid_at) values(?,'私有',99,current_date)",cl);
        jdbc.update("insert into case_transfer(direction,target_org,kind,reason,sent_at) values('IN','无来源','ADMIN','历史',current_date)");
        var after=report(own).path("totals");for(String key:List.of("inspection_total","reward_paid","transfer_total"))assertEquals(before.path(key),after.path(key),key);
    }
    @Test void genericAccountCannotReadStatisticsAndInvalidYearIsBusinessError()throws Exception {
        var generic=actor(null);call(generic,"/stats/overview",403);call(generic,"/stats/report?year=2026",403);
        var handler=actor("HANDLER");call(handler,"/stats/report?year=0",2100);call(handler,"/stats/report?year=10000",2100);
    }
}
