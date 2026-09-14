package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseCause;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.ClueService;
import cn.ybcase.bureau.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 案件要素更正通道 + 线索分页检索（真库，Flyway 全迁移） */
@SpringBootTest
@ActiveProfiles("test")
class CorrectionIntegrationTest {

    @Autowired CaseService caseService;
    @Autowired ClueService clueService;
    @Autowired DocumentService documentService;
    @Autowired CaseCauseRepository causeRepository;
    @Autowired JdbcTemplate jdbc;

    private static final List<CaseService.OfficerReq> TWO = List.of(
            new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
            new CaseService.OfficerReq("张协办", "YB002", "MEMBER"));

    private CaseCause cause(int itemNo) {
        return causeRepository.findAll().stream()
                .filter(c -> c.getItemNo() == itemNo).findFirst().orElseThrow();
    }

    private CaseFile newCase(String party) {
        return caseService.create(new CaseService.CaseCreateReq(null, cause(13).getId(), "NORMAL",
                party, "PROVIDER", "9137000000000000XA", null, null, null, "集成测试", BigDecimal.TEN,
                TWO, null, null, null, null), "it");
    }

    private static CaseService.CorrectionReq req(String partyName, BigDecimal amount, Long causeId, String reason) {
        return new CaseService.CorrectionReq(partyName, null, null, null, null, null, amount, causeId, reason);
    }

    @Test
    void 更正成功写记录并重算案件名称() {
        CaseFile c = newCase("IT-更正医院" + System.nanoTime());
        String oldName = c.getName();
        CaseFile r = caseService.correctPartyInfo(c.getId(),
                req("IT-更正后医院", new BigDecimal("123.45"), null, "立案时名称多打一字"), "it");
        assertEquals("IT-更正后医院", r.getPartyName());
        assertEquals("IT-更正后医院涉嫌" + cause(13).getCategory() + "案", r.getName());
        assertNotEquals(oldName, r.getName());

        List<Map<String, Object>> rows = caseService.corrections(c.getId());
        assertEquals(3, rows.size(), "partyName + amountInvolved + name 各一行");
        assertEquals(1, rows.stream().map(x -> x.get("batch_no")).distinct().count(), "同一次更正共用 batch_no");
        Map<String, Object> byField = new java.util.HashMap<>();
        rows.forEach(x -> byField.put((String) x.get("field"), x.get("new_value")));
        assertEquals("IT-更正后医院", byField.get("partyName"));
        assertEquals("123.45", byField.get("amountInvolved"));
        assertEquals(r.getName(), byField.get("name"));
        assertTrue(rows.stream().allMatch(x -> "it".equals(x.get("operator"))));

        // 详情一次带回 + 大事记有更正事件
        assertEquals(3, ((List<?>) caseService.detail(c.getId()).get("corrections")).size());
        assertTrue(documentService.timeline(c.getId()).stream()
                .anyMatch(e -> "要素更正".equals(e.get("kind")) && String.valueOf(e.get("detail")).contains("当事人名称")));
    }

    @Test
    void 案由更正须主体匹配否则2001() {
        CaseFile c = newCase("IT-案由更正" + System.nanoTime());
        var e = assertThrows(BizException.class, () -> caseService.correctPartyInfo(c.getId(),
                req(null, null, cause(31).getId(), "案由选错"), "it"));   // 31 是自然人案由
        assertEquals(2001, e.code);
        // 同主体的另一案由可改，且 name 随案由重算
        CaseCause other = causeRepository.findAll().stream()
                .filter(x -> "PROVIDER".equals(x.getSubjectType()) && x.getItemNo() != 13).findFirst().orElseThrow();
        CaseFile r = caseService.correctPartyInfo(c.getId(), req(null, null, other.getId(), "案由选错"), "it");
        assertEquals(other.getId(), r.getCauseId());
        assertTrue(r.getName().contains(other.getCategory()));
    }

    @Test
    void 已告知状态拒2120() {
        CaseFile c = newCase("IT-已告知" + System.nanoTime());
        jdbc.update("update case_file set status = 'NOTIFIED' where id = ?", c.getId());
        var e = assertThrows(BizException.class, () -> caseService.correctPartyInfo(c.getId(),
                req("X", null, null, "理由"), "it"));
        assertEquals(2120, e.code);
    }

    @Test
    void 空理由拒2121() {
        CaseFile c = newCase("IT-空理由" + System.nanoTime());
        var e = assertThrows(BizException.class, () -> caseService.correctPartyInfo(c.getId(),
                req("X", null, null, " "), "it"));
        assertEquals(2121, e.code);
    }

    @Test
    void 无变化拒2122() {
        CaseFile c = newCase("IT-无变化" + System.nanoTime());
        var e = assertThrows(BizException.class, () -> caseService.correctPartyInfo(c.getId(),
                new CaseService.CorrectionReq(c.getPartyName(), c.getPartyCreditNo(), null, null, null,
                        null, BigDecimal.TEN, c.getCauseId(), "理由"), "it"));
        assertEquals(2122, e.code);
        assertTrue(caseService.corrections(c.getId()).isEmpty());
    }

    @Test
    void 线索分页检索total与rows正确() {
        String tag = "IT-分页" + System.nanoTime();
        for (int i = 0; i < 3; i++)
            clueService.create(new ClueService.ClueCreateReq("COMPLAINT", "内容" + i, tag + "-" + i,
                    "PROVIDER", LocalDate.now(), null), "it");
        Map<String, Object> p = clueService.pageList(null, tag, null, null, 1, 2);
        assertEquals(3L, ((Number) p.get("total")).longValue());
        assertEquals(2, ((List<?>) p.get("rows")).size());
        Map<String, Object> p2 = clueService.pageList("PENDING", tag, LocalDate.now(), LocalDate.now(), 2, 2);
        assertEquals(1, ((List<?>) p2.get("rows")).size());
        Map<String, Object> none = clueService.pageList(null, tag, LocalDate.now().plusDays(1), null, 1, 2);
        assertEquals(0L, ((Number) none.get("total")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> row = ((List<Map<String, Object>>) p.get("rows")).get(0);
        assertTrue(row.containsKey("clue_no") && row.containsKey("suspect_name"), "jdbc 蛇形列名");
    }
}
