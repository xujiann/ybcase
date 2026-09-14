package cn.ybcase.bureau;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.entity.CaseFile;
import cn.ybcase.bureau.repository.CaseCauseRepository;
import cn.ybcase.bureau.service.CaseService;
import cn.ybcase.bureau.service.MessageService;
import cn.ybcase.bureau.service.OwnerTransferService;
import cn.ybcase.bureau.spi.CaseOwnerDeactivationGuard;
import cn.ybcase.bureau.web.BureauStatsController;
import cn.ybcase.core.entity.SysUser;
import cn.ybcase.core.repository.SysRoleRepository;
import cn.ybcase.core.repository.SysUserRepository;
import cn.ybcase.core.web.SysUserController;
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

/** 人员变动闭环：停用守卫 / 按承办人查询与批量移交 / 提醒不进停用账号 / 执法证临期看板 */
@SpringBootTest
@ActiveProfiles("test")
class StaffIntegrationTest {

    @Autowired CaseService caseService;
    @Autowired OwnerTransferService ownerTransferService;
    @Autowired MessageService messageService;
    @Autowired CaseOwnerDeactivationGuard guard;
    @Autowired SysUserController sysUserController;
    @Autowired BureauStatsController statsController;
    @Autowired CaseCauseRepository causeRepository;
    @Autowired SysUserRepository sysUserRepository;
    @Autowired SysRoleRepository sysRoleRepository;
    @Autowired JdbcTemplate jdbc;

    /** 直接调用带 @PreAuthorize 的控制器方法须有认证上下文：以 ADMIN 身份模拟（不引入 spring-security-test） */
    @org.junit.jupiter.api.BeforeEach
    void asAdmin() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("it", "n/a",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuth() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private static final List<CaseService.OfficerReq> TWO = List.of(
            new CaseService.OfficerReq("王办案", "YB001", "LEAD"),
            new CaseService.OfficerReq("张协办", "YB002", "MEMBER"));

    private SysUser newUser(String prefix, String roleCode) {
        var u = new SysUser();
        u.setUsername(prefix + System.nanoTime());
        u.setPassword("x");
        u.setRealName("人员变动测试");
        if (roleCode != null) u.getRoles().add(sysRoleRepository.findByCode(roleCode).orElseThrow());
        return sysUserRepository.saveAndFlush(u);
    }

    private CaseFile newCase(String owner) {
        var cause = causeRepository.findAll().stream().filter(c -> c.getItemNo() == 13).findFirst().orElseThrow();
        return caseService.create(new CaseService.CaseCreateReq(null, cause.getId(), "NORMAL",
                "IT-人员变动" + System.nanoTime(), "PROVIDER", null, null, null, null, "集成测试", BigDecimal.TEN,
                TWO, null, null, null, null), owner);
    }

    @Test
    void 名下有在办案件拒停用1105_移交后可停用() {
        SysUser handler = newUser("staff", "HANDLER");
        SysUser receiver = newUser("staffrecv", "HANDLER");
        CaseFile c = newCase(handler.getUsername());

        List<String> blockers = guard.blockers(handler.getUsername());
        assertEquals(1, blockers.size());
        assertTrue(blockers.get(0).contains("1 件在办案件") && blockers.get(0).contains(c.getCaseNo()), blockers.get(0));

        var r = sysUserController.setEnabled(handler.getId(), false);
        assertEquals(1105, r.getCode());
        assertTrue(r.getMessage().contains(c.getCaseNo()));
        assertEquals(Boolean.TRUE, jdbc.queryForObject(
                "select enabled from sys_user where id = ?", Boolean.class, handler.getId()), "拒绝停用时不得改动账号");

        // 承办人清单能看见他 1 件在办
        var mine = ownerTransferService.owners().stream()
                .filter(o -> handler.getUsername().equals(o.get("username"))).findFirst().orElseThrow();
        assertEquals(1L, ((Number) mine.get("active_cases")).longValue());
        assertEquals(0L, ((Number) mine.get("overdue_cases")).longValue());

        var res = ownerTransferService.transfer(handler.getUsername(), receiver.getUsername(), null, "it");
        assertEquals(1, res.get("count"));
        assertEquals(List.of(c.getCaseNo()), res.get("caseNos"));
        assertEquals(receiver.getUsername(), caseService.get(c.getId()).getOwnerUser());

        assertEquals(0, sysUserController.setEnabled(handler.getId(), false).getCode());
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "select enabled from sys_user where id = ?", Boolean.class, handler.getId()));
        // 名下已无案件的账号不再出现在承办人清单；反之"停用后残留案件"（历史数据）会带 enabled=false 露出来
        assertTrue(ownerTransferService.owners().stream().noneMatch(o -> handler.getUsername().equals(o.get("username"))));
        jdbc.update("update case_file set owner_user = ? where id = ?", handler.getUsername(), c.getId());
        assertTrue(ownerTransferService.owners().stream()
                .anyMatch(o -> handler.getUsername().equals(o.get("username")) && Boolean.FALSE.equals(o.get("enabled"))
                        && ((Number) o.get("active_cases")).longValue() == 1));
        jdbc.update("update case_file set owner_user = ? where id = ?", receiver.getUsername(), c.getId());
    }

    @Test
    void 批量移交负例_无案2110_同人2111_越界2112() {
        SysUser a = newUser("staffa", "HANDLER");
        SysUser b = newUser("staffb", "HANDLER");
        assertEquals(2110, assertThrows(BizException.class,
                () -> ownerTransferService.transfer(a.getUsername(), b.getUsername(), null, "it")).code);
        assertEquals(2110, assertThrows(BizException.class,
                () -> ownerTransferService.transfer("no-such-user-xyz", b.getUsername(), null, "it")).code);
        CaseFile c = newCase(a.getUsername());
        assertEquals(2111, assertThrows(BizException.class,
                () -> ownerTransferService.transfer(a.getUsername(), a.getUsername(), null, "it")).code);
        assertEquals(2112, assertThrows(BizException.class,
                () -> ownerTransferService.transfer(a.getUsername(), b.getUsername(), List.of(c.getId(), 999999999L), "it")).code);
        // 接收人不存在：透传 transferOwner 的 2081；且整批回滚，案件仍在 a 名下
        assertEquals(2081, assertThrows(BizException.class,
                () -> ownerTransferService.transfer(a.getUsername(), "no-such-user-xyz", null, "it")).code);
        assertEquals(a.getUsername(), caseService.get(c.getId()).getOwnerUser());
        // 指定 caseIds 的正例
        var res = ownerTransferService.transfer(a.getUsername(), b.getUsername(), List.of(c.getId()), "it");
        assertEquals(1, res.get("count"));
    }

    @Test
    void 案件列表按承办人过滤_不传时不受影响() {
        SysUser a = newUser("staffpl", "HANDLER");
        CaseFile c = newCase(a.getUsername());
        newCase("it");
        var page = caseService.pageList(null, null, a.getUsername(), 1, 20, "it", true);
        var rows = (List<Map<String, Object>>) page.get("rows");
        assertEquals(1L, ((Number) page.get("total")).longValue());
        assertEquals(c.getCaseNo(), rows.get(0).get("case_no"));
        // 旧签名等价于 owner=null：总数 ≥ 2 且含两案
        var all = caseService.pageList(null, null, 1, 100, "it", true);
        assertTrue(((Number) all.get("total")).longValue() >= 2);
        assertEquals(all.get("total"), caseService.pageList(null, null, null, 1, 100, "it", true).get("total"));
    }

    @Test
    void 承办人已停用时期限提醒转负责人不进死信箱() {
        SysUser handler = newUser("staffoff", "HANDLER");
        SysUser leader = newUser("staffldr", "LEADER");
        CaseFile c = newCase(handler.getUsername());
        // 期限置为昨天（超期），并绕过停用守卫直接停用——模拟"先停用后发现残留案件"的历史数据
        jdbc.update("update case_file set deadline_at = current_date - 1 where id = ?", c.getId());
        jdbc.update("update sys_user set enabled = false where id = ?", handler.getId());
        // 线索登记人同样已停用
        String clueNo = "XS-STAFF-" + System.nanoTime();
        jdbc.update("""
                insert into case_clue (clue_no, source, content, suspect_name, suspect_type, received_at, deadline_at, created_by)
                values (?, 'COMPLAINT', 'it', 'it', 'PROVIDER', current_date - 20, current_date - 1, ?)""",
                clueNo, handler.getUsername());
        Long clueId = jdbc.queryForObject("select id from case_clue where clue_no = ?", Long.class, clueNo);

        messageService.generateReminders();
        String today = LocalDate.now().toString();

        assertEquals(0, jdbc.queryForObject("select count(*) from sys_message where username = ?",
                Integer.class, handler.getUsername()), "停用账号不得收到任何提醒");
        var toLeader = jdbc.queryForList(
                "select title, content from sys_message where username = ? and dedup_key = ?",
                leader.getUsername(), "CASE-" + c.getId() + "-" + today + "-INACTIVE");
        assertEquals(1, toLeader.size(), "负责人应收到转发的办案期限提醒");
        assertTrue(((String) toLeader.get(0).get("title")).startsWith("【承办人已停用】【超期】办案期限 " + c.getCaseNo()));
        assertTrue(((String) toLeader.get(0).get("content")).contains(handler.getUsername()));
        assertEquals(1, jdbc.queryForObject(
                "select count(*) from sys_message where username = ? and dedup_key = ? and title like '【登记人已停用】%'",
                Integer.class, leader.getUsername(), "CLUE-" + clueId + "-" + today + "-INACTIVE"));
        // 同日重跑幂等
        messageService.generateReminders();
        assertEquals(1, jdbc.queryForObject("select count(*) from sys_message where username = ? and dedup_key = ?",
                Integer.class, leader.getUsername(), "CASE-" + c.getId() + "-" + today + "-INACTIVE"));

        // 对照：启用的承办人仍直发本人，负责人不收副本
        SysUser active = newUser("staffon", "HANDLER");
        CaseFile c2 = newCase(active.getUsername());
        jdbc.update("update case_file set deadline_at = current_date - 1 where id = ?", c2.getId());
        messageService.generateReminders();
        assertEquals(1, jdbc.queryForObject("select count(*) from sys_message where username = ? and dedup_key = ?",
                Integer.class, active.getUsername(), "CASE-" + c2.getId() + "-" + today));
        assertEquals(0, jdbc.queryForObject("select count(*) from sys_message where dedup_key = ?",
                Integer.class, "CASE-" + c2.getId() + "-" + today + "-INACTIVE"));
    }

    @Test
    void 督办看板执法证临期第17类() {
        String certNo = "STAFF-" + System.nanoTime();
        jdbc.update("insert into enforcer (name, cert_no, dept, cert_expire_at) values ('临期测试', ?, 'it', current_date + 20)", certNo);
        String certNoOk = "STAFF-OK-" + System.nanoTime();
        jdbc.update("insert into enforcer (name, cert_no, dept, cert_expire_at) values ('未临期', ?, 'it', current_date + 31)", certNoOk);
        try {
            var sup = statsController.supervision().getData();
            var list = (List<Map<String, Object>>) sup.get("enforcerCertExpiring");
            assertNotNull(list);
            var hit = list.stream().filter(r -> certNo.equals(r.get("cert_no"))).findFirst().orElseThrow();
            assertEquals("临期测试", hit.get("name"));
            assertEquals(Boolean.FALSE, hit.get("expired"));
            assertEquals(0L, ((Number) hit.get("active_cases")).longValue());
            assertNotNull(hit.get("cert_expire_at"));
            assertTrue(list.stream().noneMatch(r -> certNoOk.equals(r.get("cert_no"))), "31 天后到期的不预警");
            // 停用的证不预警
            jdbc.update("update enforcer set enabled = false where cert_no = ?", certNo);
            var again = (List<Map<String, Object>>) statsController.supervision().getData().get("enforcerCertExpiring");
            assertTrue(again.stream().noneMatch(r -> certNo.equals(r.get("cert_no"))));
        } finally {
            jdbc.update("delete from enforcer where cert_no in (?, ?)", certNo, certNoOk);
        }
    }
}
