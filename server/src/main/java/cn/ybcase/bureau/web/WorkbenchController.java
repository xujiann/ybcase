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
    private final cn.ybcase.bureau.service.SourceAttachmentAccess sourceAccess;
    private final BureauConfig config;
    private final JdbcTemplate jdbc;
    private final cn.ybcase.bureau.repository.CaseFileRepository cases;
    private final cn.ybcase.bureau.service.ExecutionDeadlines deadlines;
    private final cn.ybcase.bureau.service.MaterialAccess materialAccess;

    /** 个人工作台聚合 */
    @GetMapping("/my/workbench")
    @org.springframework.transaction.annotation.Transactional(readOnly = true,
            isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public R<Map<String, Object>> workbench(Authentication auth) {
        String me = auth.getName();
        var actor = materialAccess.readActor(me);
        if (actor.roles().stream().noneMatch(java.util.Set.of("HANDLER", "LEGAL", "LEADER", "ADMIN")::contains))
            throw new cn.ybcase.bureau.common.BizException(403, "当前岗位无权查看医保工作台");
        Map<String, Object> m = new LinkedHashMap<>();
        // 我的在办案件（含有效期限与剩余天数）
        var caseScope = sourceAccess.caseReadPredicate("cf.id", me);
        var caseArgs = new java.util.ArrayList<Object>();
        caseArgs.add(me); caseArgs.addAll(caseScope.args());
        var myCases = jdbc.queryForList("""
                select cf.id, cf.case_no, cf.name, cf.status, cf.filed_at,
                       case when cf.status in ('INVESTIGATING','REPORTED','NOTIFIED') then
                         cf.deadline_at + coalesce((select sum(coalesce(e.end_at,current_date) - e.start_at)::int
                         from case_period_exclusion e where e.case_id = cf.id), 0) end as effective_deadline
                from case_file cf
                where cf.owner_user = ? and cf.status not in ('CLOSED','TERMINATED') and
                """ + caseScope.sql() + " order by effective_deadline nulls last", caseArgs.toArray());
        for (var row : myCases) {
            if (java.util.List.of("DECIDED","DELIVERED").contains(row.get("status")))
                row.put("execution_deadline",deadlines.summary(cases.findById(((Number)row.get("id")).longValue()).orElseThrow(),java.time.LocalDate.now()));
        }
        m.put("myCases",myCases);
        // District staff may have moved since entering the source or received it by department handoff.
        var myClueScope = actor.district() ? sourceAccess.clueReadPredicate("case_clue.id", me)
                : new cn.ybcase.bureau.service.SourceAttachmentAccess.ReadPredicate("true", List.of());
        var myClueArgs = new java.util.ArrayList<Object>();
        myClueArgs.add(me);
        if (actor.district()) {
            myClueArgs.add(me);
            myClueArgs.add("[、,，;；\\s]+");
        }
        myClueArgs.addAll(myClueScope.args());
        String clueOwner = actor.district()
                ? "(created_by=? or ?=any(regexp_split_to_array(coalesce(handler,''),?)))" : "created_by=?";
        m.put("myClues", jdbc.queryForList("select id,clue_no,suspect_name,deadline_at from case_clue"
                + " where status='PENDING' and " + clueOwner + " and " + myClueScope.sql()
                + " order by deadline_at", myClueArgs.toArray()));
        // 我提交的待批申请 / 待我裁决的申请（按角色）
        var applicationCase = sourceAccess.caseReadPredicate("a.case_id", me);
        var applicationClue = sourceAccess.clueReadPredicate("a.clue_id", me);
        var approvalScope = sourceAccess.approvalDepartmentPredicate("a", me);
        var applicationArgs = new java.util.ArrayList<Object>(); applicationArgs.add(me);
        applicationArgs.addAll(applicationCase.args()); applicationArgs.addAll(applicationClue.args());
        applicationArgs.addAll(approvalScope.args());
        m.put("myApplications", jdbc.queryForList("select a.id,a.kind,a.reason,a.status,a.applied_at from biz_approval a"
                + " where a.applicant=? and a.status='PENDING'"
                + " and (a.case_id is null or " + applicationCase.sql() + ")"
                + " and (a.clue_id is null or " + applicationClue.sql() + ") and " + approvalScope.sql()
                + " order by a.id desc", applicationArgs.toArray()));
        boolean leader = actor.roles().contains("LEADER") || actor.roles().contains("ADMIN");
        m.put("pendingForMe", leader
                ? jdbc.queryForObject("select count(*) from biz_approval a where a.status='PENDING' and "
                        + approvalScope.sql(), Long.class, approvalScope.args().toArray()) : 0);
        m.put("unread", messageService.unread(me));
        return R.ok(m);
    }

    // ---------- 站内消息 ----------

    @GetMapping("/messages")
    public R<List<Map<String, Object>>> messages(Authentication auth) {
        return R.ok(messageService.inbox(auth.getName()));
    }

    @GetMapping("/messages/unread-count")
    public R<Long> unreadCount(Authentication auth) {
        return R.ok(messageService.unread(auth.getName()));
    }

    @PostMapping("/messages/{id}/read")
    public R<Void> read(@PathVariable Long id, Authentication auth) {
        messageService.markRead(auth.getName(), id);
        return R.ok();
    }

    @PostMapping("/messages/read-all")
    public R<Void> readAll(Authentication auth) {
        messageService.markAllRead(auth.getName());
        return R.ok();
    }

    /** 手动触发期限提醒生成（定时任务每工作日8点自动跑；此接口供补跑与验证） */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/messages/generate-reminders")
    public R<Map<String, Object>> generateReminders() {
        return R.ok(Map.of("generated", messageService.generateRemindersForAdministrator()));
    }
}
