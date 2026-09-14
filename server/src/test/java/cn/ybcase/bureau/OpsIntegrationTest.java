package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.DocumentService;
import cn.ybcase.bureau.service.OversightService;
import cn.ybcase.bureau.web.AttachmentController;
import cn.ybcase.bureau.web.OversightController;
import cn.ybcase.core.web.SysConfigController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 参数变更留痕 / 附件作废 / 处罚决定公开撤回（V28）集成测试 */
@SpringBootTest
@ActiveProfiles("test")
class OpsIntegrationTest {

    @Autowired CaseService caseService;
    @Autowired OversightService oversightService;
    @Autowired DocumentService documentService;
    @Autowired CaseCauseRepository causeRepository;
    @Autowired SysConfigController sysConfigController;
    @Autowired AttachmentController attachmentController;
    @Autowired OversightController oversightController;
    @Autowired JdbcTemplate jdbc;

    private static final List<CaseService.OfficerReq> TWO = List.of(
            new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
            new CaseService.OfficerReq("张协办", "YB002", "MEMBER"));

    private static Authentication auth(String name, String... roles) {
        return new UsernamePasswordAuthenticationToken(name, "n/a",
                java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    /** 控制器方法上的 @PreAuthorize 读 SecurityContextHolder：直接调 bean 前先放入当前用户 */
    private static Authentication login(String name, String... roles) {
        Authentication a = auth(name, roles);
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private CaseFile newCase(String tag) {
        var cause = causeRepository.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow();
        return caseService.create(new CaseService.CaseCreateReq(null, cause.getId(), "NORMAL",
                "IT-" + tag + System.nanoTime(), "PROVIDER", null, null, null, null, "集成测试", BigDecimal.TEN,
                TWO, null, null, null, null), "it");
    }

    private long insertAttachment(long caseId, String uploader) {
        return jdbc.queryForObject("""
                insert into case_attachment (case_id, filename, content_type, size_bytes, data, category, uploaded_by)
                values (?, 'x.txt', 'text/plain', 3, decode('616263', 'hex'), 'DOC_SCAN', ?) returning id""",
                Long.class, caseId, uploader);
    }

    // ---------- 参数变更留痕 ----------

    @Test
    void 参数改值写历史_同值不写() {
        Authentication admin = login("it-admin", "ADMIN");
        String key = "org_short";
        String original = jdbc.queryForObject("select cfg_value from sys_config where cfg_key = ?", String.class, key);
        int before = count(key);
        try {
            String v1 = "IT-简称" + System.nanoTime();
            assertEquals(0, sysConfigController.update(key, v1, admin).getCode());
            assertEquals(before + 1, count(key), "改值应写一条历史");
            var last = jdbc.queryForMap(
                    "select old_value, new_value, changed_by from sys_config_history where cfg_key = ? order by id desc limit 1", key);
            assertEquals(original, last.get("old_value"));
            assertEquals(v1, last.get("new_value"));
            assertEquals("it-admin", last.get("changed_by"));

            assertEquals(0, sysConfigController.update(key, v1, admin).getCode());
            assertEquals(before + 1, count(key), "同值保存不写历史");

            assertEquals(1401, sysConfigController.update("no_such_key_" + System.nanoTime(), "x", admin).getCode());

            var hist = sysConfigController.history(key).getData();
            assertFalse(hist.isEmpty());
            assertEquals(v1, hist.get(0).get("new_value"), "历史新在前");
        } finally {
            jdbc.update("update sys_config set cfg_value = ? where cfg_key = ?", original, key);
        }
    }

    private int count(String key) {
        return jdbc.queryForObject("select count(*) from sys_config_history where cfg_key = ?", Integer.class, key);
    }

    // ---------- 附件作废 ----------

    @Test
    void 附件作废_上传人可作废_列表过滤_下载受限() {
        CaseFile c = newCase("附件作废");
        long attId = insertAttachment(c.getId(), "banban");
        Authentication uploader = auth("banban", "HANDLER");
        Authentication other = auth("someone", "HANDLER");
        Authentication leader = auth("juzhang", "LEADER");

        assertEquals(2130, assertThrows(BizException.class, () ->
                attachmentController.voidAttachment(c.getId(), attId, Map.of("reason", " "), uploader)).code);
        assertEquals(2130, assertThrows(BizException.class, () ->
                attachmentController.voidAttachment(c.getId(), attId, null, uploader)).code);
        assertEquals(2133, assertThrows(BizException.class, () ->
                attachmentController.voidAttachment(c.getId(), attId, Map.of("reason", "误传"), other)).code);
        // 附件不属于该案（用另一案的 id 去作废）
        CaseFile c2 = newCase("附件他案");
        assertEquals(2134, assertThrows(BizException.class, () ->
                attachmentController.voidAttachment(c2.getId(), attId, Map.of("reason", "误传"), leader)).code);

        attachmentController.voidAttachment(c.getId(), attId, Map.of("reason", "误传他案病历"), uploader);
        var row = jdbc.queryForMap("select voided, void_reason, voided_by, voided_at, data from case_attachment where id = ?", attId);
        assertEquals(Boolean.TRUE, row.get("voided"));
        assertEquals("误传他案病历", row.get("void_reason"));
        assertEquals("banban", row.get("voided_by"));
        assertNotNull(row.get("voided_at"));
        assertNotNull(row.get("data"), "只打标不删 bytea");

        assertEquals(2132, assertThrows(BizException.class, () ->
                attachmentController.voidAttachment(c.getId(), attId, Map.of("reason", "再作废"), leader)).code);

        // 列表：默认不见；办案员即便传 includeVoided 也不见；负责人 includeVoided 可见并带 voided 字段
        assertTrue(attachmentController.list(c.getId(), false, uploader).getData().isEmpty());
        assertTrue(attachmentController.list(c.getId(), true, uploader).getData().isEmpty());
        var withVoided = attachmentController.list(c.getId(), true, leader).getData();
        assertEquals(1, withVoided.size());
        assertEquals(Boolean.TRUE, withVoided.get(0).get("voided"));
        assertTrue(attachmentController.list(c.getId(), false, leader).getData().isEmpty());

        // 下载：办案员拒 2135，负责人可下
        assertEquals(2135, assertThrows(BizException.class, () ->
                attachmentController.download(attId, uploader)).code);
        assertEquals(200, attachmentController.download(attId, leader).getStatusCode().value());
    }

    @Test
    void 附件作废_负责人可代作废_已归档拒2131() {
        CaseFile c = newCase("附件负责人");
        long attId = insertAttachment(c.getId(), "banban");
        attachmentController.voidAttachment(c.getId(), attId, Map.of("reason", "负责人复核作废"), auth("juzhang", "LEADER"));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("select voided from case_attachment where id = ?", Boolean.class, attId));

        CaseFile closed = newCase("附件归档");
        long att2 = insertAttachment(closed.getId(), "banban");
        jdbc.update("update case_file set status = 'CLOSED' where id = ?", closed.getId());
        assertEquals(2131, assertThrows(BizException.class, () ->
                attachmentController.voidAttachment(closed.getId(), att2, Map.of("reason", "x"), auth("juzhang", "LEADER"))).code);
    }

    // ---------- 处罚决定公开撤回 ----------

    private CaseFile publishedCase() {
        CaseFile c = newCase("公开撤回");
        caseService.report(c.getId(), "调查终结", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟罚", new BigDecimal("5000"),
                BigDecimal.ZERO, BigDecimal.ZERO, null));
        caseService.recordStatement(c.getId(), new CaseService.StatementReq(null, null, null, null, true));
        caseService.decide(c.getId(), new CaseService.DecisionReq("PUNISH", new BigDecimal("5000"),
                BigDecimal.ZERO, null, null, "罚款5000", null, "一般情形"));
        caseService.deliver(c.getId(), new CaseService.DeliveryReq("DIRECT", LocalDate.now(), "当事人", null, null, null));
        caseService.publish(c.getId());
        return c;
    }

    @Test
    void 撤回公开_正例_导出排除_时间轴出事件() {
        CaseFile c = publishedCase();
        String decisionNo = jdbc.queryForObject("select decision_no from case_decision where case_id = ?", String.class, c.getId());
        assertTrue(oversightController.publishExport().getData().stream().anyMatch(r -> decisionNo.equals(r.get("decision_no"))),
                "撤回前出现在公示导出");

        assertEquals(2138, assertThrows(BizException.class, () ->
                oversightService.withdrawPublish(c.getId(), "  ", "juzhang")).code);

        oversightService.withdrawPublish(c.getId(), "复议决定撤销（示复决〔2026〕1号）", "juzhang");
        var d = jdbc.queryForMap("select published, published_at, publish_withdrawn, withdrawn_at, withdraw_reason, withdrawn_by from case_decision where case_id = ?", c.getId());
        assertEquals(Boolean.TRUE, d.get("published"), "不回改 published");
        assertNotNull(d.get("published_at"));
        assertEquals(Boolean.TRUE, d.get("publish_withdrawn"));
        assertNotNull(d.get("withdrawn_at"));
        assertEquals("juzhang", d.get("withdrawn_by"));
        assertEquals("复议决定撤销（示复决〔2026〕1号）", d.get("withdraw_reason"));

        assertEquals(2137, assertThrows(BizException.class, () ->
                oversightService.withdrawPublish(c.getId(), "再撤", "juzhang")).code);

        assertFalse(oversightController.publishExport().getData().stream().anyMatch(r -> decisionNo.equals(r.get("decision_no"))),
                "撤回后不再出现在公示导出");
        var kinds = documentService.timeline(c.getId()).stream().map(e -> (String) e.get("kind")).toList();
        assertTrue(kinds.contains("决定公开") && kinds.contains("撤回公开"), "时间轴：" + kinds);
        // 撤回后 publish 仍受幂等守卫（不能靠重新公开洗白）
        assertEquals(2042, assertThrows(BizException.class, () -> caseService.publish(c.getId())).code);
    }

    @Test
    void 未公开的决定撤回拒2136() {
        CaseFile c = newCase("未公开撤回");
        assertEquals(2136, assertThrows(BizException.class, () ->
                oversightService.withdrawPublish(c.getId(), "理由", "juzhang")).code);
        // 有决定但未公开同样拒
        caseService.report(c.getId(), "调查终结", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟罚", new BigDecimal("5000"),
                BigDecimal.ZERO, BigDecimal.ZERO, null));
        caseService.recordStatement(c.getId(), new CaseService.StatementReq(null, null, null, null, true));
        caseService.decide(c.getId(), new CaseService.DecisionReq("PUNISH", new BigDecimal("5000"),
                BigDecimal.ZERO, null, null, "罚款5000", null, "一般情形"));
        assertEquals(2136, assertThrows(BizException.class, () ->
                oversightService.withdrawPublish(c.getId(), "理由", "juzhang")).code);
    }
}
