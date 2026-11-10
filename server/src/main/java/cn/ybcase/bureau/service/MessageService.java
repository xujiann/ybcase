package cn.ybcase.bureau.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 站内消息：审批事件即时通知 + 期限临期/超期每日提醒（去重），把督办从"看板"变成"找到人" */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageService {

    private final JdbcTemplate jdbc;
    private final BureauConfig config;
    private final ExecutionAlerts executionAlerts;
    private final SourceAttachmentAccess sourceAccess;
    private final cn.ybcase.core.security.AdminWriteAccess administratorAccess;
    private final MaterialAccess materialAccess;

    /** 发消息（dedupKey 非空时同人同键幂等——SQL 层 on conflict，无异常路径，事务安全） */
    public void send(String username, String kind, String title, String content, String link, String dedupKey) {
        // Titles are derived summaries; keep source records intact while respecting the notification column.
        if (title != null && title.codePointCount(0, title.length()) > 255)
            title = title.substring(0, title.offsetByCodePoints(0, 255));
        jdbc.update("""
                insert into sys_message (username, kind, title, content, link, dedup_key)
                values (?,?,?,?,?,?)
                on conflict (username, dedup_key) where dedup_key is not null do nothing""",
                username, kind, title, content, link, dedupKey);
    }

    /** 发给某角色的全部启用用户 */
    public void sendToRole(String roleCode, String kind, String title, String content, String link) {
        sendToRole(roleCode, kind, title, content, link, null);
    }

    /** 同上，带去重键（唯一索引按 (username, dedup_key)，同一键对每个收件人各去重一次） */
    public void sendToRole(String roleCode, String kind, String title, String content, String link, String dedupKey) {
        List<Map<String, Object>> users = jdbc.queryForList("""
                select u.username from sys_user u
                join sys_user_role ur on ur.user_id = u.id
                join sys_role r on r.id = ur.role_id
                where r.code = ? and u.enabled = true""", roleCode);
        for (var u : users) {
            send((String) u.get("username"), kind, title, content, link, dedupKey);
        }
    }

    public void sendApprovalRequest(long approvalId, String title, String content) {
        for (String leader : enabledLeaders()) {
            var scope = sourceAccess.approvalDepartmentPredicate("a", leader);
            var args = new java.util.ArrayList<Object>();
            args.add(approvalId);
            args.addAll(scope.args());
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "select exists(select 1 from biz_approval a where a.id=? and " + scope.sql() + ")",
                    Boolean.class, args.toArray())))
                send(leader, "APPROVAL", title, content, "/case/approvals", "APPROVAL-REQUEST-" + approvalId);
        }
    }

    /** 期限提醒生成：每工作日早8点自动跑；也可由管理员手动触发（E2E/补跑） */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Shanghai")
    public void scheduledReminders() {
        if (config.isWorkingDay(LocalDate.now())) self.getObject().generateReminders();
    }

    private final org.springframework.beans.factory.ObjectProvider<MessageService> self;

    @Transactional
    public int generateReminders() {
        return generateReminders(false);
    }

    @Transactional
    public int generateRemindersForAdministrator() {
        return generateReminders(true);
    }

    private int generateReminders(boolean administrator) {
        // Both entry points take this gate before any notification insert. It prevents a
        // manual run holding feedback locks from waiting on a scheduled run's dedup key.
        jdbc.queryForList("select pg_advisory_xact_lock(2026102803)");
        int autoDays = config.intVal("feedback_autoclose_days", 7);
        var closingFeedback = jdbc.queryForList("""
                select id, username, title from feedback
                where status = 'RESOLVED' and handled_at <= now() - make_interval(days => ?)
                order by id""", autoDays);
        if (administrator) {
            // Feedback handlers use feedback -> actor. Acquire every affected source
            // before the actor, so account changes and feedback handling cannot invert it.
            if (!closingFeedback.isEmpty()) {
                var ids = closingFeedback.stream().map(row -> row.get("id")).toArray();
                jdbc.queryForList("select id from feedback where id in ("
                        + String.join(",", java.util.Collections.nCopies(ids.length, "?"))
                        + ") order by id for update", ids);
            }
            administratorAccess.requireCurrentAdmin();
        }
        int ahead = config.intVal("reminder_days_ahead", 5);
        LocalDate currentDay = LocalDate.now();
        String today = currentDay.toString();
        int n = 0;

        // 承办人/登记人已停用时的兜底收件人：全部启用的负责人（只查一次）
        List<String> leaders = enabledLeaders();
        n += remindClueDeadlines(ahead, currentDay);
        // 办案期限临期/超期 → 承办人（已停用则转负责人）
        for (var r : jdbc.queryForList("""
                select cf.id, cf.case_no, cf.owner_user, su.enabled,
                       cf.deadline_at + coalesce((select sum(coalesce(e.end_at, current_date) - e.start_at)::int from case_period_exclusion e
                                                  where e.case_id = cf.id), 0) as eff
                from case_file cf left join sys_user su on su.username = cf.owner_user
                where cf.status in ('INVESTIGATING','REPORTED','NOTIFIED') and cf.owner_user is not null""")) {
            LocalDate eff = ((java.sql.Date) r.get("eff")).toLocalDate();
            if (eff.isAfter(LocalDate.now().plusDays(ahead))) continue;
            boolean overdue = eff.isBefore(LocalDate.now());
            sendOrEscalate((String) r.get("owner_user"), r.get("enabled"), leaders, "承办人", "DEADLINE",
                    (overdue ? "【超期】" : "【临期】") + "办案期限 " + r.get("case_no"),
                    "有效期限 " + eff + "（如需延长请提交审批申请）",
                    "/case/detail/" + r.get("id"), "CASE-" + r.get("id") + "-" + today);
            n++;
        }
        for (var r : executionAlerts.rows(currentDay)) {
            LocalDate payment = ExecutionAlerts.date(r.get("pay_deadline"));
            String court = (String) r.get("active_court_status");
            if (court == null && (payment == null || !currentDay.isAfter(payment))) continue;
            LocalDate application = ExecutionAlerts.date(r.get("apply_deadline"));
            Long daysLeft = r.get("days_left") == null ? null : ((Number)r.get("days_left")).longValue();
            String title = switch (court == null ? "" : court) {
                case "RETURNED" -> "【法院退回】核对补正事项";
                case "REJECTED" -> "【法院不予受理】请人工核处";
                default -> daysLeft != null && daysLeft < 0 ? "【请法制核实】已过登记的申请期限"
                        : daysLeft != null && daysLeft <= 30 ? "【临期】法院申请期限"
                        : "【催缴】缴款期届满未缴清";
            };
            String body = "缴款期限 " + (payment == null ? "待核实" : payment)
                    + (application == null ? "；法定救济期限尚未确认或救济处理中，不推定已失权"
                                          : "；登记的法院申请期限 " + application)
                    + (r.get("urged_at") == null ? "；尚无催告送达记录" : "；催告送达日 " + r.get("urged_at"));
            long caseId = ((Number) r.get("id")).longValue();
            Long version = ExecutionDeadlines.version(r);
            Number round = (Number) r.get("active_court_round_id");
            String phase = court != null ? court : daysLeft != null && daysLeft < 0 ? "LAPSED" : "DUE";
            String dedup = "EXEC-" + caseId + "-" + (version == null ? 0 : version) + "-"
                    + (round == null ? 0 : round) + "-" + phase + ("DUE".equals(phase) ? "-" + today : "");
            if (version == null && round == null && court == null)
                dedup = "LAPSED".equals(phase) ? "EXEC-LAPSED-" + caseId : "EXEC-" + caseId + "-" + today;
            for (String recipient : executionRecipients(caseId)) {
                send(recipient,"DEADLINE",title + " " + r.get("case_no"),body,
                        ExecutionAlerts.executionLink(caseId,version,round),dedup);
                n++;
            }
        }
        // 待批审批单 → 负责人
        for (String leader : leaders) {
            var scope = sourceAccess.approvalDepartmentPredicate("a", leader);
            Long pending = jdbc.queryForObject("select count(*) from biz_approval a where a.status='PENDING' and "
                    + scope.sql(), Long.class, scope.args().toArray());
            if (pending != null && pending > 0) {
                var department = materialAccess.departmentScope(leader);
                String prefix = department.district() ? "DISTRICT-" + department.deptId() + "-" : "";
                send(leader, "APPROVAL", "有 " + pending + " 张审批单待裁决",
                        null, "/case/approvals", prefix + "PENDING-APPROVALS-" + today);
                n++;
            }
        }
        n += remindAgencyDeadlines(ahead);
        // 已解决反馈超期未确认 → 自动关闭并告知提交人
        for (var r : closingFeedback) {
            int closed = jdbc.update("update feedback set status = 'CLOSED' where id = ? and status = 'RESOLVED'"
                    + " and handled_at <= now() - make_interval(days => ?)", r.get("id"), autoDays);
            if (closed == 0) continue; // Another handler changed the state while the reminder transaction waited.
            send((String) r.get("username"), "SYSTEM",
                    "反馈「" + r.get("title") + "」已自动关闭",
                    "已解决满 " + autoDays + " 天未确认，系统自动关闭；如未解决请重新提交",
                    "/my/feedback", "FEEDBACK-AUTOCLOSE-" + r.get("id"));
            n++;
        }
        // 节假日表缺年 → 管理员（每月提醒一次直到录入；次年从 11 月起算，见 BureauConfig.missingHolidayYears）
        for (int year : config.missingHolidayYears()) {
            sendToRole("ADMIN", "SYSTEM", "【请维护】" + year + " 年法定节假日表尚未录入",
                    "在录入之前，" + year + " 年内所有按工作日计算的法定期限（线索核查/法制审核/先行登记保存/送达/简易备案）"
                            + "只跳周末、不跳节假日，期限会算短、督办看板会误报超期。请按国办放假通知在「节假日表」页批量录入。",
                    "/system/holidays", "HOLIDAY-MISSING-" + year + "-" + today.substring(0, 7));
            n++;
        }
        log.info("期限提醒生成 {} 条", n);
        return n;
    }

    private List<String> executionRecipients(long caseId) {
        return jdbc.queryForList("""
                with recipients as (
                    select u.username, min(case when u.username=c.owner_user then 0
                                               when r.code='LEADER' then 1 else 2 end) as priority
                    from case_file c cross join sys_user u
                    left join sys_dept d on d.id=u.dept_id
                    join sys_user_role ur on ur.user_id=u.id join sys_role r on r.id=ur.role_id
                    where c.id=? and u.enabled and (u.dept_id is null or d.enabled)
                      and (u.account_category is distinct from 'DISTRICT' or u.dept_id=c.owner_dept_id)
                      and (not exists(select 1 from sys_user_role ar join sys_role role on role.id=ar.role_id
                                      where ar.user_id=u.id and role.code in ('AGENCY','INTEGRATION')))
                      and ((u.username=c.owner_user and r.code in ('HANDLER','LEADER','ADMIN'))
                        or (r.code in ('LEADER','ADMIN') and
                            (c.owner_dept_id is null or u.dept_id=c.owner_dept_id
                              or u.account_category='BUREAU' and d.org_kind='BUREAU')))
                    group by u.username
                )
                select username from recipients where priority=(select min(priority) from recipients) order by username
                """, String.class, caseId);
    }

    private int remindClueDeadlines(int ahead, LocalDate today) {
        LocalDate horizon = today.plusDays(ahead);
        var clues = jdbc.queryForList("""
                select c.id,c.clue_no,c.suspect_name,c.deadline_at,c.owner_dept_id,c.created_by,u.enabled
                from case_clue c left join sys_user u on u.username=c.created_by
                where c.status='PENDING' and c.deadline_at<=?
                  and not exists (select 1 from case_file f where f.clue_id=c.id)
                order by c.id
                """, horizon);
        int count = 0;
        for (var clue : clues) {
            long id = ((Number) clue.get("id")).longValue();
            // Resolve recipient, assignment and organization in one current database statement.
            var recipients = jdbc.queryForList("""
                    with candidates as (
                        select u.username, min(case
                            when u.username=any(regexp_split_to_array(coalesce(c.handler,''),?)) then 0
                            when u.username=c.created_by and not exists (
                                select 1 from department_handoff h where h.clue_id=c.id and h.status='ACCEPTED'
                            ) then 1
                            when r.code='LEADER' then 2
                            when r.code='ADMIN' then 3
                        end) as priority
                        from case_clue c cross join sys_user u
                        left join sys_dept d on d.id=u.dept_id
                        join sys_user_role ur on ur.user_id=u.id join sys_role r on r.id=ur.role_id
                        where c.id=? and c.status='PENDING' and c.deadline_at<=?
                          and not exists (select 1 from case_file f where f.clue_id=c.id)
                          and u.enabled and r.code in ('HANDLER','LEADER','ADMIN')
                          and (u.dept_id is null or d.enabled)
                          and ((c.owner_dept_id is null and u.account_category is distinct from 'DISTRICT')
                               or (c.owner_dept_id=u.dept_id and d.enabled
                                   and (d.org_kind is null or d.org_kind=u.account_category)))
                        group by u.id
                    )
                    select username,priority from candidates
                    where priority=(select min(priority) from candidates)
                    order by username
                    """, "[、,，;；\\s]+", id, horizon);
            String key = "CLUE-" + id + "-" + today;
            boolean overdue = ((java.sql.Date) clue.get("deadline_at")).toLocalDate().isBefore(today);
            String baseTitle = (overdue ? "【超期】" : "【临期】") + "线索核查 " + clue.get("clue_no");
            String baseBody = clue.get("suspect_name") + "，核查期限 " + clue.get("deadline_at");
            for (var recipient : recipients) {
                String account = (String) recipient.get("username");
                boolean fallback = ((Number) recipient.get("priority")).intValue() >= 2;
                // Keep the historical unowned-source escalation key and title. Both keys
                // share the same recipient/day deduplication when responsibility changes.
                boolean legacyInactive = fallback && clue.get("owner_dept_id") == null
                        && clue.get("created_by") != null && !Boolean.TRUE.equals(clue.get("enabled"));
                String title = legacyInactive ? "【登记人已停用】" + baseTitle : fallback ? "【待分派】" + baseTitle : baseTitle;
                String body = fallback ? baseBody + "，请核对当前承办人并办理。" : baseBody;
                if (Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from sys_message where username=? and dedup_key in (?,?))
                        """, Boolean.class, account, key, key + "-INACTIVE"))) continue;
                send(account, "DEADLINE", title, body, "/case/clues?focus=" + id, legacyInactive ? key + "-INACTIVE" : key);
                count++;
            }
        }
        return count;
    }

    private int remindAgencyDeadlines(int ahead) {
        LocalDate today = LocalDate.now();
        var candidates = jdbc.queryForList("""
                select a.id from agreement_action a join sys_dept d on d.id=a.agency_dept_id
                where a.agency_status in ('PENDING','ACCEPTED') and a.agency_due_at<=?
                  and d.enabled and d.org_kind='AGENCY'
                  and (a.agency_auto_reminded_at is null or a.agency_auto_reminded_at<?)
                order by a.id
                """, today.plusDays(ahead), today);
        int count = 0;
        for (var candidate : candidates) {
            long id = ((Number) candidate.get("id")).longValue();
            int changed = jdbc.update("""
                    update agreement_action a set agency_auto_reminded_at=?
                    where a.id=? and a.agency_status in ('PENDING','ACCEPTED') and a.agency_due_at<=?
                      and (a.agency_auto_reminded_at is null or a.agency_auto_reminded_at<?)
                      and exists(select 1 from sys_dept d where d.id=a.agency_dept_id and d.enabled and d.org_kind='AGENCY')
                      and exists(select 1 from case_file c where c.id=a.case_id and c.party_type='PROVIDER')
                    """, today, id, today.plusDays(ahead), today);
            if (changed == 0) continue;
            jdbc.update("""
                    insert into agency_agreement_event(agreement_id,agency_cycle,event_type,agency_dept_id,
                        department_name,detail,due_at)
                    select id,agency_cycle,'AUTO_REMINDED',agency_dept_id,org,'系统按办理期限提醒',agency_due_at
                    from agreement_action where id=?
                    """, id);
            var row = jdbc.queryForList("""
                    select a.agency_cycle,a.agency_due_at,c.id,c.case_no,u.username
                    from agreement_action a join case_file c on c.id=a.case_id
                    left join sys_user u on u.username=c.owner_user and u.enabled
                      and exists(select 1 from sys_user_role ur join sys_role r on r.id=ur.role_id
                                 where ur.user_id=u.id and r.code in ('HANDLER','LEADER','LEGAL','ADMIN'))
                    where a.id=?
                    """, id).getFirst();
            LocalDate due = ((java.sql.Date) row.get("agency_due_at")).toLocalDate();
            if (row.get("username") instanceof String recipient)
                send(recipient, "DEADLINE", (due.isBefore(today) ? "【超期】" : "【临期】") + "经办事项 " + row.get("case_no"),
                        "经办办理期限 " + due + "，请核对进度，接收部门待办已标记系统提醒。",
                        "/case/detail/" + row.get("id"), "AGENCY-DUE-" + id + "-" + row.get("agency_cycle") + "-" + today);
            count++;
        }
        return count;
    }

    /** Re-evaluate source access at read time: recipients may have lost a case or supervisory role. */
    private SourceAttachmentAccess.ReadPredicate visible(String username) {
        String caseId = "(select msg_case.id from case_file msg_case where msg_case.id::text=substring(m.link from '^/case/detail/([0-9]+)(?:[/?#].*)?$'))";
        String clueId = "(select msg_clue.id from case_clue msg_clue where msg_clue.id::text=substring(m.dedup_key from '^CLUE-([0-9]+)-'))";
        var caseScope = sourceAccess.caseReadPredicate(caseId, username);
        var clueScope = sourceAccess.clueReadPredicate(clueId, username);
        var approvalCase = sourceAccess.caseReadPredicate("msg_approval.case_id", username);
        var approvalClue = sourceAccess.clueReadPredicate("msg_approval.clue_id", username);
        var approvalDepartment = sourceAccess.approvalDepartmentPredicate("msg_approval", username);
        var args = new java.util.ArrayList<Object>(); args.add(username);
        args.addAll(caseScope.args()); args.addAll(clueScope.args()); args.addAll(approvalCase.args()); args.addAll(approvalClue.args());
        args.addAll(approvalDepartment.args());
        String sql = "m.username=? and (coalesce(m.link,'') !~ '^/case/detail/' or " + caseScope.sql() + ")"
            + " and (coalesce(m.dedup_key,'') !~ '^CLUE-' or " + clueScope.sql() + ")"
            + " and (m.kind<>'APPROVAL' or m.title !~ '^审批单 #' or exists (select 1 from biz_approval msg_approval"
            + " where msg_approval.id::text=substring(m.title from '^审批单 #([0-9]+)')"
            + " and (msg_approval.case_id is null or " + approvalCase.sql() + ")"
            + " and (msg_approval.clue_id is null or " + approvalClue.sql() + ")"
            + " and " + approvalDepartment.sql() + "))";
        if (!sourceAccess.hasRole(username, "ADMIN")) sql += " and coalesce(m.link,'') not like '/system/%'";
        if (!sourceAccess.hasRole(username, "ADMIN", "LEADER"))
            sql += " and not (coalesce(m.link,'')='/case/approvals' and m.title like '有 % 张审批单待裁决')";
        var department = materialAccess.departmentScope(username);
        if (department.district()) {
            var approval = sourceAccess.approvalDepartmentPredicate("request", username);
            sql += " and (m.kind<>'APPROVAL' or m.title not like '新审批单：%' or exists (select 1 from biz_approval request"
                    + " where request.id::text=substring(m.dedup_key from '^APPROVAL-REQUEST-([0-9]+)$')"
                    + " and " + approval.sql() + "))"
                    + " and (m.kind<>'APPROVAL' or m.title not like '有 % 张审批单待裁决' or m.dedup_key like ?)";
            args.addAll(approval.args());
            args.add("DISTRICT-" + department.deptId() + "-PENDING-APPROVALS-%");
        }
        return new SourceAttachmentAccess.ReadPredicate(sql, List.copyOf(args));
    }

    /** Personal inbox checks and source visibility belong to the returned data's snapshot. */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> inbox(String username) {
        requirePersonalActor(username, false);
        var scope = visible(username);
        return jdbc.queryForList("select m.id,m.kind,m.title,m.content,m.link,m.created_at,m.read_at from sys_message m where "
                + scope.sql() + " order by (m.read_at is null) desc,m.id desc limit 50", scope.args().toArray());
    }
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Long unread(String username) {
        requirePersonalActor(username, false);
        var scope = visible(username);
        return jdbc.queryForObject("select count(*) from sys_message m where m.read_at is null and " + scope.sql(), Long.class, scope.args().toArray());
    }
    @Transactional
    public void markRead(String username, Long id) {
        requirePersonalActor(username, true);
        var scope = visible(username); var args = new java.util.ArrayList<Object>(scope.args()); args.add(id);
        jdbc.update("update sys_message m set read_at=now() where m.read_at is null and " + scope.sql() + " and m.id=?", args.toArray());
    }
    @Transactional
    public void markAllRead(String username) {
        requirePersonalActor(username, true);
        var scope = visible(username);
        jdbc.update("update sys_message m set read_at=now() where m.read_at is null and " + scope.sql(), scope.args().toArray());
    }

    /** No additional business-role requirement for personal messages or internal domain callers. */
    private void requirePersonalActor(String username, boolean write) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) return;
        if (!username.equals(auth.getName())) throw new cn.ybcase.bureau.common.BizException(403, "仅可处理本人的消息");
        // Message writers use actor -> message, matching reminder inserts. They never lock a source case.
        if (write) materialAccess.lockActor(username, false);
        else materialAccess.readActor(username);
    }

    private List<String> enabledLeaders() {
        return jdbc.queryForList("""
                select u.username from sys_user u
                join sys_user_role ur on ur.user_id = u.id join sys_role r on r.id = ur.role_id
                where r.code = 'LEADER' and u.enabled = true
                  and (u.dept_id is null
                       or exists (select 1 from sys_dept d where d.id=u.dept_id and d.enabled))
                order by u.username""").stream()
                .map(u -> (String) u.get("username")).toList();
    }

    /**
     * 期限提醒的收件规则：收件人启用则直发；已停用（或账号已不存在）则转发全部启用负责人。
     * 此前按 owner_user/created_by 直发不判 enabled，而同一方法里的审批提醒却判了——停用账号的
     * 临期/超期消息全部投进无人看的信箱，直到超期才被看板发现。负责人副本去重键加后缀，与本人的键区分。
     */
    private void sendOrEscalate(String recipient, Object enabled, List<String> leaders, String roleLabel,
                                String kind, String title, String content, String link, String dedupKey) {
        if (Boolean.TRUE.equals(enabled)) {
            send(recipient, kind, title, content, link, dedupKey);
            return;
        }
        // 无启用负责人（空库只有 admin、或负责人临时停用）时退回管理员：否则消息被静默丢弃，
        // 比投进停用账号的信箱更不可见，而线索核查 15 工作日错过即 2022 失权
        List<String> targets = leaders.isEmpty() ? enabledAdmins() : leaders;
        if (targets.isEmpty()) log.warn("期限提醒无人可收：{} {} 已停用且无启用的负责人/管理员（{}）", roleLabel, recipient, title);
        for (String t : targets) {
            send(t, kind, "【" + roleLabel + "已停用】" + title,
                    roleLabel + " " + recipient + " 已停用，请移交或指定接手人；" + content,
                    link, dedupKey + "-INACTIVE");
        }
    }

    private List<String> enabledAdmins() {
        return jdbc.queryForList("""
                select u.username from sys_user u
                join sys_user_role ur on ur.user_id = u.id join sys_role r on r.id = ur.role_id
                where r.code = 'ADMIN' and u.enabled = true
                  and (u.dept_id is null
                       or exists (select 1 from sys_dept d where d.id=u.dept_id and d.enabled))
                order by u.username""").stream()
                .map(u -> (String) u.get("username")).toList();
    }
}
