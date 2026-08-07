package cn.ybcase.bureau.web;

import cn.ybcase.bureau.service.BureauConfig;
import cn.ybcase.bureau.service.MessageService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** v1.2 待办中心：我的案件/我的待办/消息（把督办送到人，而不是等人看板） */
@RestController
@RequestMapping("/api/bureau")
@RequiredArgsConstructor
public class WorkbenchController {

    private final MessageService messageService;
    private final BureauConfig config;
    private final JdbcTemplate jdbc;

    /** 个人工作台聚合 */
    @GetMapping("/my/workbench")
    public R<Map<String, Object>> workbench(Authentication auth) {
        String me = auth.getName();
        Map<String, Object> m = new LinkedHashMap<>();
        // 我的在办案件（含有效期限与剩余天数）
        m.put("myCases", jdbc.queryForList("""
                select cf.id, cf.case_no, cf.name, cf.status, cf.filed_at,
                       cf.deadline_at + coalesce((select sum(e.end_at - e.start_at)::int from case_period_exclusion e
                                                  where e.case_id = cf.id and e.end_at is not null), 0) as effective_deadline
                from case_file cf
                where cf.owner_user = ? and cf.status not in ('CLOSED','TERMINATED')
                order by effective_deadline""", me));
        // 我登记且未办结的线索
        m.put("myClues", jdbc.queryForList("""
                select id, clue_no, suspect_name, deadline_at from case_clue
                where created_by = ? and status = 'PENDING' order by deadline_at""", me));
        // 我提交的待批申请 / 待我裁决的申请（按角色）
        m.put("myApplications", jdbc.queryForList("""
                select id, kind, reason, status, applied_at from biz_approval
                where applicant = ? and status = 'PENDING' order by id desc""", me));
        boolean leader = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_LEADER") || a.getAuthority().equals("ROLE_ADMIN"));
        m.put("pendingForMe", leader
                ? jdbc.queryForObject("select count(*) from biz_approval where status = 'PENDING'", Long.class) : 0);
        m.put("unread", jdbc.queryForObject(
                "select count(*) from sys_message where username = ? and read_at is null", Long.class, me));
        return R.ok(m);
    }

    // ---------- 站内消息 ----------

    @GetMapping("/messages")
    public R<List<Map<String, Object>>> messages(Authentication auth) {
        return R.ok(jdbc.queryForList("""
                select id, kind, title, content, link, created_at, read_at from sys_message
                where username = ? order by (read_at is null) desc, id desc limit 50""", auth.getName()));
    }

    @GetMapping("/messages/unread-count")
    public R<Long> unreadCount(Authentication auth) {
        return R.ok(jdbc.queryForObject(
                "select count(*) from sys_message where username = ? and read_at is null",
                Long.class, auth.getName()));
    }

    @PostMapping("/messages/{id}/read")
    public R<Void> read(@PathVariable Long id, Authentication auth) {
        jdbc.update("update sys_message set read_at = now() where id = ? and username = ? and read_at is null",
                id, auth.getName());
        return R.ok();
    }

    @PostMapping("/messages/read-all")
    public R<Void> readAll(Authentication auth) {
        jdbc.update("update sys_message set read_at = now() where username = ? and read_at is null", auth.getName());
        return R.ok();
    }

    /** 手动触发期限提醒生成（定时任务每工作日8点自动跑；此接口供补跑与验证） */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/messages/generate-reminders")
    public R<Map<String, Object>> generateReminders() {
        return R.ok(Map.of("generated", messageService.generateReminders()));
    }
}
