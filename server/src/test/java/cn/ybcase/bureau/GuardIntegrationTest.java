package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseCause;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.ApprovalService;
import cn.ybcase.bureau.service.CaseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 法定守卫服务层集成测试（真库 ybcase_test，Flyway 全迁移；CI 由 postgres 服务容器提供） */
@SpringBootTest
@ActiveProfiles("test")
class GuardIntegrationTest {

    @Autowired CaseService caseService;
    @Autowired ApprovalService approvalService;
    @Autowired CaseCauseRepository causeRepository;
    @Autowired cn.ybcase.bureau.service.OversightService oversightService;
    @Autowired cn.ybcase.bureau.service.ProcedureService procedureService;
    @Autowired cn.ybcase.bureau.service.ExecutionService executionService;
    @Autowired cn.ybcase.bureau.service.DocumentService documentService;
    @Autowired cn.ybcase.core.repository.SysUserRepository sysUserRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private CaseCause providerCause() {
        return causeRepository.findAll().stream()
                .filter(c -> c.getItemNo() == 13).findFirst().orElseThrow();
    }

    private CaseService.CaseCreateReq req(String party, List<CaseService.OfficerReq> officers) {
        return new CaseService.CaseCreateReq(null, providerCause().getId(), "NORMAL",
                party, "PROVIDER", null, null, null, null, "集成测试", BigDecimal.TEN,
                officers, null, null, null, null);
    }

    private static final List<CaseService.OfficerReq> TWO = List.of(
            new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
            new CaseService.OfficerReq("张协办", "YB002", "MEMBER"));

    @Test
    void 执法人员不足两人拒2002() {
        var e = assertThrows(BizException.class, () -> caseService.create(
                req("IT-单人立案" + System.nanoTime(), List.of(new CaseService.OfficerReq("王办案", "YB001", "LEAD"))),
                "it"));
        assertEquals(2002, e.code);
    }

    @Test
    void 执法证不在台账拒2055() {
        var e = assertThrows(BizException.class, () -> caseService.create(
                req("IT-无证立案" + System.nanoTime(), List.of(
                        new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
                        new CaseService.OfficerReq("路人", "NOPE", "MEMBER"))), "it"));
        assertEquals(2055, e.code);
    }

    @Test
    void 案由主体不匹配拒2001() {
        var cause = providerCause();
        var e = assertThrows(BizException.class, () -> caseService.create(
                new CaseService.CaseCreateReq(null, cause.getId(), "NORMAL",
                        "IT-个人" + System.nanoTime(), "INDIVIDUAL", null, null, null, null, null,
                        BigDecimal.ONE, TWO, null, null, null, null), "it"));
        assertEquals(2001, e.code);
    }

    @Test
    void 简易程序限额拒2003() {
        CaseFile c = caseService.create(new CaseService.CaseCreateReq(null,
                causeRepository.findAll().stream().filter(x -> x.getItemNo() == 31).findFirst().orElseThrow().getId(),
                "SUMMARY", "IT-简易" + System.nanoTime(), "INDIVIDUAL", null, null, null, null, null,
                BigDecimal.ONE, TWO, null, null, null, null), "it");
        var e = assertThrows(BizException.class, () -> caseService.decide(c.getId(),
                new CaseService.DecisionReq("PUNISH", new BigDecimal("500"), null, null, null, "x", null, "r")));
        assertEquals(2003, e.code);
    }

    @Test
    void 告知前决定拒2006与不加重拒2007() {
        CaseFile c = caseService.create(req("IT-流程" + System.nanoTime(), TWO), "it");
        var e = assertThrows(BizException.class, () -> caseService.decide(c.getId(),
                new CaseService.DecisionReq("PUNISH", BigDecimal.ONE, null, null, null, "x", null, "r")));
        assertEquals(2006, e.code);
        caseService.report(c.getId(), "调查终结：集成测试", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟罚", new BigDecimal("1000"), BigDecimal.ZERO, null));
        var e2 = assertThrows(BizException.class, () -> caseService.decide(c.getId(),
                new CaseService.DecisionReq("PUNISH", new BigDecimal("2000"), null, null, null, "x", null, "r")));
        assertEquals(2007, e2.code);
    }

    @Test
    void 申请听证未举行不得决定拒2075与陈述申辩期未届满拒2076() {
        CaseFile c = caseService.create(req("IT-听证" + System.nanoTime(), TWO), "it");
        caseService.report(c.getId(), "调查终结", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟罚", new BigDecimal("150000"),
                BigDecimal.ZERO, null));
        // 未放弃、未陈述申辩且期限未届满
        var e1 = assertThrows(BizException.class, () -> caseService.decide(c.getId(),
                new CaseService.DecisionReq("PUNISH", new BigDecimal("150000"), null, null, null, "x", null, "r")));
        assertEquals(2076, e1.code);
        // 申请听证后即便放弃陈述申辩也须先开听证
        caseService.recordStatement(c.getId(),
                new CaseService.StatementReq(null, null, true, null, true));
        var e2 = assertThrows(BizException.class, () -> caseService.decide(c.getId(),
                new CaseService.DecisionReq("PUNISH", new BigDecimal("150000"), null, null, null, "x", null, "r")));
        assertEquals(2075, e2.code);
    }

    @Test
    void 重复告知加重须载明变更理由拒2077() {
        CaseFile c = caseService.create(req("IT-再告知" + System.nanoTime(), TWO), "it");
        caseService.report(c.getId(), "调查终结", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟罚1万", new BigDecimal("10000"),
                BigDecimal.ZERO, null));
        var e = assertThrows(BizException.class, () -> caseService.notify(c.getId(),
                new CaseService.NoticeReq("改拟罚10万", new BigDecimal("100000"), BigDecimal.ZERO, null)));
        assertEquals(2077, e.code);
        caseService.notify(c.getId(), new CaseService.NoticeReq("改拟罚10万", new BigDecimal("100000"),
                BigDecimal.ZERO, "复核发现新增违法事实，认定金额变更"));
        // 决定仍以历次告知最低额为上限
        caseService.recordStatement(c.getId(), new CaseService.StatementReq(null, null, null, null, true));
        var e2 = assertThrows(BizException.class, () -> caseService.decide(c.getId(),
                new CaseService.DecisionReq("PUNISH", new BigDecimal("100000"), null, null, null, "x", null, "r")));
        assertEquals(2007, e2.code);
    }

    @Test
    void 审批单流转_延期申请批准后期限顺延() {
        CaseFile c = caseService.create(req("IT-审批" + System.nanoTime(), TWO), "banban");
        long id = approvalService.apply(new ApprovalService.ApplyReq(
                "EXTEND", null, c.getId(), Map.of("days", 10), "案情复杂"), "banban");
        var result = approvalService.decide(id, true, "同意", "juzhang");
        assertTrue((Boolean) result.get("approved"));
        CaseFile after = caseService.get(c.getId());
        assertEquals(c.getDeadlineAt().plusDays(10), after.getDeadlineAt());
        assertEquals(10, after.getExtensionDays());
    }

    @Test
    void 立案审批payload含LocalDate字段可正确执行() {
        // violationEndDate 走 JSON 存 payload 再反序列化为 LocalDate（须 JavaTime 模块，回归 ObjectMapper 注入修复）
        var cause = providerCause();
        String party = "IT-立案审批" + System.nanoTime();
        long id = approvalService.apply(new ApprovalService.ApplyReq("FILE_CASE", null, null,
                Map.of("causeId", cause.getId(), "procedureType", "NORMAL",
                        "partyName", party, "partyType", "PROVIDER",
                        "summary", "含时效日期的立案审批", "amountInvolved", 100,
                        "violationEndDate", java.time.LocalDate.now().minusMonths(6).toString(),
                        "healthHarm", false,
                        "officers", List.of(
                                Map.of("name", "王办案", "certNo", "YB001", "duty", "LEAD"),
                                Map.of("name", "张协办", "certNo", "YB002", "duty", "MEMBER"))),
                "立案审批表"), "banban");
        var result = approvalService.decide(id, true, "同意立案", "juzhang");
        assertTrue((Boolean) result.get("approved"));
    }

    @Test
    void 账号并发修改触发乐观锁冲突() {
        // 停用与改密并发时后写覆盖先写（已停用账号被改回启用）——@Version 兜底；
        // 本测试保证该注解或 2102 映射被回退时 CI 变红
        // 用本测试专属账号，避免与其它用例共享 banban 造成版本读取顺序耦合
        var seed = new cn.ybcase.core.entity.SysUser();
        seed.setUsername("optlock" + System.nanoTime());
        seed.setPassword("x");
        seed.setRealName("乐观锁测试");
        sysUserRepository.saveAndFlush(seed);
        String uname = seed.getUsername();
        var u1 = sysUserRepository.findByUsername(uname).orElseThrow();
        var u2 = sysUserRepository.findByUsername(uname).orElseThrow();  // 两个独立事务 → 两份游离实体
        u1.setPhone("13800000001");
        sysUserRepository.saveAndFlush(u1);  // rowVersion +1
        u2.setPhone("13800000002");  // 携带过期版本
        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,
                () -> sysUserRepository.saveAndFlush(u2));
    }

    @Test
    void 专家评审扣除与既有区间重叠时只记净增部分() {
        java.time.LocalDate today = java.time.LocalDate.now();
        CaseFile c = caseService.create(req("IT-评审重叠" + System.nanoTime(), TWO), "it");
        // 回拨立案日，使能构造历史区间（服务层校验起始日不得早于立案日）
        jdbc.update("update case_file set filed_at = ? where id = ?", today.minusDays(20), c.getId());
        // 既有鉴定扣除 [today-10, today)
        caseService.addExclusion(c.getId(), new CaseService.ExclusionReq(
                "APPRAISE", today.minusDays(10), today, "鉴定"));
        // 专家评审 [today-15, today)：与鉴定重叠 [today-10, today)，净增只应是 [today-15, today-10)
        oversightService.startExpertReview(c.getId(), "专家甲", today.minusDays(15));
        Long reviewId = jdbc.queryForObject(
                "select max(id) from expert_review where case_id = ?", Long.class, c.getId());
        oversightService.endExpertReview(c.getId(), reviewId, "意见", null, today);
        var expertRows = jdbc.queryForList(
                "select start_at, end_at from case_period_exclusion where case_id = ? and reason = 'EXPERT'",
                c.getId());
        assertEquals(1, expertRows.size(), "重叠部分应被剔除，只落一条净增区间");
        assertEquals(today.minusDays(15), ((java.sql.Date) expertRows.get(0).get("start_at")).toLocalDate());
        assertEquals(today.minusDays(10), ((java.sql.Date) expertRows.get(0).get("end_at")).toLocalDate());
        // 总扣除 = 鉴定10日 + 评审净增5日（重叠不双算）
        Integer total = jdbc.queryForObject(
                "select coalesce(sum(end_at - start_at), 0) from case_period_exclusion where case_id = ?",
                Integer.class, c.getId());
        assertEquals(15, total);
    }

    @Test
    void 同案不允许并行第二条专家评审() {
        CaseFile c = caseService.create(req("IT-并行评审" + System.nanoTime(), TWO), "it");
        oversightService.startExpertReview(c.getId(), "专家甲", null);
        var e = assertThrows(BizException.class,
                () -> oversightService.startExpertReview(c.getId(), "专家乙", null));
        assertEquals(2066, e.code);
    }

    @Test
    void 未到计划日不得登记听证举行拒2057() {
        java.time.LocalDate today = java.time.LocalDate.now();
        CaseFile c = caseService.create(req("IT-听证时序" + System.nanoTime(), TWO), "it");
        caseService.report(c.getId(), "调查终结", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟罚", new java.math.BigDecimal("150000"),
                java.math.BigDecimal.ZERO, null));
        caseService.recordStatement(c.getId(), new CaseService.StatementReq(null, null, true, null, null));
        // 通知日回拨 8 天、计划日在未来 → 满足"通知满7日"但未到计划日
        procedureService.scheduleHearing(c.getId(), new cn.ybcase.bureau.service.ProcedureService.HearingScheduleReq(
                today.minusDays(8), today.minusDays(8), today.plusDays(5), "李法制", "政策法规处", "书记员"));
        Long hid = jdbc.queryForObject("select max(id) from case_hearing where case_id = ?", Long.class, c.getId());
        var e = assertThrows(BizException.class,
                () -> procedureService.holdHearing(c.getId(), hid, "提前举行的笔录"));
        assertEquals(2057, e.code);
    }

    @Test
    void 未送达或非处罚决定不得公开() {
        CaseFile c = caseService.create(req("IT-公开守卫" + System.nanoTime(), TWO), "it");
        caseService.report(c.getId(), "调查终结", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟不予处罚", java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, null));
        caseService.recordStatement(c.getId(), new CaseService.StatementReq(null, null, null, null, true));
        caseService.decide(c.getId(), new CaseService.DecisionReq("NO_PUNISH", null, null, null, null,
                "不予处罚", null, null));
        // 非处罚决定不需要依法公开（辽56条针对的是行政处罚决定）
        var e = assertThrows(BizException.class, () -> caseService.publish(c.getId()));
        assertEquals(2042, e.code);
    }

    @Test
    void 分期缴清后可结案() {
        // 分期缴纳此前只标记 case_installment.paid_at 而不写 case_execution，
        // 而结案判定只看 case_execution → 批准了分期的案件永远无法结案（2011）。
        CaseFile c = caseService.create(req("IT-分期结案" + System.nanoTime(), TWO), "it");
        caseService.report(c.getId(), "调查终结", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟罚", new BigDecimal("5000"),
                BigDecimal.ZERO, null));
        caseService.recordStatement(c.getId(), new CaseService.StatementReq(null, null, null, null, true));
        caseService.decide(c.getId(), new CaseService.DecisionReq("PUNISH", new BigDecimal("5000"),
                BigDecimal.ZERO, null, null, "罚款5000", null, "一般情形"));
        caseService.deliver(c.getId(), new CaseService.DeliveryReq("DIRECT",
                java.time.LocalDate.now(), "当事人", null, null, null));
        executionService.approveDefer(c.getId());
        oversightService.addInstallment(c.getId(), new cn.ybcase.bureau.service.OversightService
                .InstallmentReq(1, java.time.LocalDate.now(), new BigDecimal("2500"), "FINE"));
        oversightService.addInstallment(c.getId(), new cn.ybcase.bureau.service.OversightService
                .InstallmentReq(2, java.time.LocalDate.now(), new BigDecimal("2500"), "FINE"));
        // 计划总额不得超出决定书就该类款项确定的金额（此前无此前置，缴完计划仍判未缴清）
        assertEquals(2065, assertThrows(BizException.class, () ->
                oversightService.addInstallment(c.getId(), new cn.ybcase.bureau.service.OversightService
                        .InstallmentReq(3, java.time.LocalDate.now(), new BigDecimal("1"), "FINE"))).code);
        // 决定书未判退回基金时不得就其排分期（此前一律按 FINE 入账，无从发现）
        assertEquals(2065, assertThrows(BizException.class, () ->
                oversightService.addInstallment(c.getId(), new cn.ybcase.bureau.service.OversightService
                        .InstallmentReq(4, java.time.LocalDate.now(), new BigDecimal("100"), "RECOUP"))).code);
        for (var row : jdbc.queryForList(
                "select id from case_installment where case_id = ? order by seq", c.getId())) {
            oversightService.payInstallment(((Number) row.get("id")).longValue());
        }
        // 分期入账后累计应等于决定金额，结案不再被 2011 拦住
        assertEquals(0, executionService.sum(c.getId(), "FINE").compareTo(new BigDecimal("5000")));
        CaseFile closed = caseService.close(c.getId(), "分期缴清，执行完毕", "it");
        assertEquals("CLOSED", closed.getStatus());
    }

    @Test
    void 退回基金分期缴清后可结案() {
        // 分期入账此前写死 kind='FINE'：只判退回基金、不罚款的案件（医保案常见）
        // 缴完计划后 sum(RECOUP) 仍为 0，fullyExecuted 永远不成立 → 无法结案
        CaseFile c = caseService.create(req("IT-退基分期" + System.nanoTime(), TWO), "it");
        caseService.report(c.getId(), "调查终结", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟退回基金", BigDecimal.ZERO,
                new BigDecimal("6000"), null));
        caseService.recordStatement(c.getId(), new CaseService.StatementReq(null, null, null, null, true));
        caseService.decide(c.getId(), new CaseService.DecisionReq("PUNISH", BigDecimal.ZERO,
                new BigDecimal("6000"), null, null, "责令退回基金6000", null, "一般情形"));
        caseService.deliver(c.getId(), new CaseService.DeliveryReq("DIRECT",
                java.time.LocalDate.now(), "当事人", null, null, null));
        executionService.approveDefer(c.getId());
        oversightService.addInstallment(c.getId(), new cn.ybcase.bureau.service.OversightService
                .InstallmentReq(1, java.time.LocalDate.now(), new BigDecimal("6000"), "RECOUP"));
        for (var row : jdbc.queryForList(
                "select id from case_installment where case_id = ? order by seq", c.getId())) {
            oversightService.payInstallment(((Number) row.get("id")).longValue());
        }
        assertEquals(0, executionService.sum(c.getId(), "RECOUP").compareTo(new BigDecimal("6000")));
        assertEquals(0, executionService.sum(c.getId(), "FINE").compareTo(BigDecimal.ZERO));
        assertEquals("CLOSED", caseService.close(c.getId(), "退回基金已缴清", "it").getStatus());
    }

    @Test
    void 终止调查案件可立卷归档且保留终止状态() {
        // close() 此前要求"有处理决定"且状态在 DECIDED/DELIVERED——终止调查的案件两条都不满足，
        // 拿不到案卷号、产不出结案报告，第57条一案一卷对这批案件只能线下手工立卷
        CaseFile c = caseService.create(req("IT-终止归档" + System.nanoTime(), TWO), "it");
        caseService.terminate(c.getId(), "违法事实不能成立（第47条）");
        assertEquals("TERMINATED", caseService.get(c.getId()).getStatus());

        CaseFile archived = caseService.close(c.getId(), "经调查违法事实不能成立，终止调查并立卷归档", "it");
        assertNotNull(archived.getArchiveNo());
        assertEquals("TERMINATED", archived.getStatus());   // 归档不改写案件的真实出口
        assertEquals("TERMINATED", archived.getCloseReason());
        assertNotNull(archived.getClosedAt());
        // 重复归档须被拒（状态仍是 TERMINATED，靠 archive_no 而非状态判定）
        assertEquals(2011, assertThrows(BizException.class,
                () -> caseService.close(c.getId(), "再归一次", "it")).code);
    }

    @Test
    void 卷内目录纳入结构化法定材料() {
        // 立案审批表在 biz_approval、法制审核意见在 case_review、集体讨论在 case_meeting、
        // 送达回证在 case_delivery——此前 archiveCatalog 只查 case_document，这些一件都不进卷
        CaseFile c = caseService.create(req("IT-卷内目录" + System.nanoTime(), TWO), "it");
        approvalService.recordDirect("FILE_CASE", c.getId(), "直接立案", "juzhang");
        caseService.report(c.getId(), "调查终结", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟罚", new BigDecimal("2000"),
                BigDecimal.ZERO, null));
        // 再告知加重须载明变更理由（第52条）——这两条告知记录都必须留在案卷里
        caseService.notify(c.getId(), new CaseService.NoticeReq("改拟罚1500", new BigDecimal("1500"),
                BigDecimal.ZERO, "复核后调整认定金额"));
        caseService.recordStatement(c.getId(), new CaseService.StatementReq(null, null, null, null, true));
        var rv = caseService.submitReview(c.getId(), "重大案件");
        caseService.doReview(rv.getId(), "李法制", "AGREE", "程序合法");
        caseService.addMeeting(c.getId(), new CaseService.MeetingReq(java.time.LocalDate.now(),
                "局领导班子", "讨论一致", "同意处罚"));
        caseService.decide(c.getId(), new CaseService.DecisionReq("PUNISH", new BigDecimal("1500"),
                BigDecimal.ZERO, null, null, "罚款1500", null, "一般情形"));
        caseService.deliver(c.getId(), new CaseService.DeliveryReq("DIRECT",
                java.time.LocalDate.now(), "当事人", null, null, null));

        var catalog = documentService.archiveCatalog(c.getId());
        @SuppressWarnings("unchecked")
        var entries = (java.util.List<java.util.Map<String, Object>>) catalog.get("catalog");
        var types = entries.stream().map(e -> (String) e.get("doc_type")).toList();
        assertTrue(types.contains("FILING_APPROVAL"), "立案审批表未进卷：" + types);
        assertTrue(types.contains("LEGAL_OPINION"), "法制审核意见未进卷：" + types);
        assertTrue(types.contains("MEETING_RECORD"), "集体讨论记录未进卷：" + types);
        assertTrue(types.contains("DELIVERY_RECEIPT"), "送达回证未进卷：" + types);
        // 装订顺序：立案审批表在最前，决定书在送达回证之前
        assertEquals("FILING_APPROVAL", types.get(0), "立案审批表应排卷首：" + types);
        assertTrue(types.indexOf("DECISION") < types.indexOf("DELIVERY_RECEIPT"), "装订顺序错：" + types);
        // 手工制作同类文书后，结构化记录不得被"去重"掉：
        // 按类型（乃至类型+日期）去重都会把上面那两次告知记录抹掉
        caseService.addDocument(c.getId(), new CaseService.DocumentReq("NOTICE",
                "行政处罚告知书（手工）", "手工录入", java.time.LocalDate.now(), "it", false, null, null));
        var after = documentService.archiveCatalog(c.getId());
        @SuppressWarnings("unchecked")
        var afterEntries = (java.util.List<java.util.Map<String, Object>>) after.get("catalog");
        long notices = afterEntries.stream().filter(e -> "NOTICE".equals(e.get("doc_type"))).count();
        assertEquals(3, notices, "2 次告知记录 + 1 份手工文书都应在卷：" + afterEntries.stream()
                .map(e -> e.get("doc_type") + "/" + e.get("source")).toList());

        // 虚拟卷内件无 case_document 行，不应带 id（否则前端会拿去签章/送达）
        entries.stream().filter(e -> "RECORD".equals(e.get("source")))
                .forEach(e -> assertNull(e.get("id"), "虚拟卷内件不应有 id：" + e.get("title")));
        // 序号连续
        for (int i = 0; i < entries.size(); i++)
            assertEquals(i + 1, entries.get(i).get("seq"));
    }

    @Test
    void 小额处罚案件不因未做法制审核被判卷内缺件() {
        // 第37条法制审核默认是条件式的（THRESHOLD：达数额较大或经听证才须审核）。
        // 必备清单若无条件要求 LEGAL_OPINION，所有小额处罚案件恒被判"必备文书缺失"，
        // 开启齐全性强制后直接卡死结案，而事后补审已被状态守卫堵死（submitReview 限 REPORTED/NOTIFIED）
        CaseFile c = caseService.create(req("IT-小额审核" + System.nanoTime(), TWO), "it");
        approvalService.recordDirect("FILE_CASE", c.getId(), "直接立案", "juzhang");
        caseService.report(c.getId(), "调查终结", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟罚", new BigDecimal("5000"),
                BigDecimal.ZERO, null));
        caseService.recordStatement(c.getId(), new CaseService.StatementReq(null, null, null, null, true));
        caseService.decide(c.getId(), new CaseService.DecisionReq("PUNISH", new BigDecimal("5000"),
                BigDecimal.ZERO, null, null, "罚款5000", null, "一般情形"));   // 未经法制审核即可决定
        caseService.deliver(c.getId(), new CaseService.DeliveryReq("DIRECT",
                java.time.LocalDate.now(), "当事人", null, null, null));

        @SuppressWarnings("unchecked")
        var missing = (java.util.List<String>) documentService.archiveCatalog(c.getId()).get("missing");
        assertFalse(missing.contains("LEGAL_OPINION"),
                "小额案未达审核标准，不应判缺法制审核意见：" + missing);
    }

    @Test
    void 归档后不得再往卷内加文书() {
        // 封卷闸门此前只认 CLOSED，而终止类案件归档后状态仍是 TERMINATED
        CaseFile c = caseService.create(req("IT-封卷" + System.nanoTime(), TWO), "it");
        caseService.terminate(c.getId(), "违法事实不能成立");
        caseService.close(c.getId(), "终止调查并立卷归档", "it");
        assertEquals(2031, assertThrows(BizException.class, () -> caseService.addDocument(
                c.getId(), new CaseService.DocumentReq("OTHER", "补件", "归档后不该加得进去",
                        java.time.LocalDate.now(), "it", false, null, null))).code);
    }

    @Test
    void 非处罚决定的卷内件文书名不得写成行政处罚决定书() {
        CaseFile c = caseService.create(req("IT-不予处罚" + System.nanoTime(), TWO), "it");
        caseService.report(c.getId(), "调查终结", "it");
        caseService.notify(c.getId(), new CaseService.NoticeReq("拟不予处罚", BigDecimal.ZERO,
                BigDecimal.ZERO, null));
        caseService.recordStatement(c.getId(), new CaseService.StatementReq(null, null, null, null, true));
        caseService.decide(c.getId(), new CaseService.DecisionReq("NO_PUNISH", BigDecimal.ZERO,
                BigDecimal.ZERO, null, null, "违法行为轻微并及时改正，不予行政处罚", null, null));
        @SuppressWarnings("unchecked")
        var entries = (java.util.List<java.util.Map<String, Object>>)
                documentService.archiveCatalog(c.getId()).get("catalog");
        var title = entries.stream().filter(e -> "DECISION".equals(e.get("doc_type")))
                .map(e -> (String) e.get("title")).findFirst().orElse("");
        assertTrue(title.startsWith("不予行政处罚决定书"), "不予处罚案的文书名错：" + title);
        assertFalse(title.contains("行政处罚决定书 （"), "无文号时不应留下尾随空格：" + title);
    }

    @Test
    void 审批驳回不执行动作() {
        CaseFile c = caseService.create(req("IT-驳回" + System.nanoTime(), TWO), "banban");
        long id = approvalService.apply(new ApprovalService.ApplyReq(
                "SUSPEND", null, c.getId(), null, "待鉴定"), "banban");
        approvalService.decide(id, false, "理由不足", "juzhang");
        assertEquals("INVESTIGATING", caseService.get(c.getId()).getStatus());
    }
}
