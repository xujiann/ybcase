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

    /** 发消息（dedupKey 非空时同人同键幂等——SQL 层 on conflict，无异常路径，事务安全） */
    public void send(String username, String kind, String title, String content, String link, String dedupKey) {
        jdbc.update("""
                insert into sys_message (username, kind, title, content, link, dedup_key)
                values (?,?,?,?,?,?)
                on conflict (username, dedup_key) where dedup_key is not null do nothing""",
                username, kind, title, content, link, dedupKey);
    }

    /** 发给某角色的全部启用用户 */
    public void sendToRole(String roleCode, String kind, String title, String content, String link) {
        List<Map<String, Object>> users = jdbc.queryForList("""
                select u.username from sys_user u
                join sys_user_role ur on ur.user_id = u.id
                join sys_role r on r.id = ur.role_id
                where r.code = ? and u.enabled = true""", roleCode);
        for (var u : users) {
            send((String) u.get("username"), kind, title, content, link, null);
        }
    }

    /** 期限提醒生成：每工作日早8点自动跑；也可由管理员手动触发（E2E/补跑） */
    @Scheduled(cron = "0 0 8 * * MON-FRI")
    @Transactional
    public int generateReminders() {
        int ahead = config.intVal("reminder_days_ahead", 5);
        String today = LocalDate.now().toString();
        int n = 0;

        // 线索核查临期/超期 → 登记人
        for (var r : jdbc.queryForList("""
                select id, clue_no, suspect_name, deadline_at, created_by from case_clue
                where status = 'PENDING' and deadline_at <= current_date + ?::int""", ahead)) {
            if (r.get("created_by") == null) continue;
            boolean overdue = ((java.sql.Date) r.get("deadline_at")).toLocalDate().isBefore(LocalDate.now());
            send((String) r.get("created_by"), "DEADLINE",
                    (overdue ? "【超期】" : "【临期】") + "线索核查 " + r.get("clue_no"),
                    r.get("suspect_name") + "，核查期限 " + r.get("deadline_at"),
                    "/case/clues", "CLUE-" + r.get("id") + "-" + today);
            n++;
        }
        // 办案期限临期/超期 → 承办人
        for (var r : jdbc.queryForList("""
                select cf.id, cf.case_no, cf.owner_user,
                       cf.deadline_at + coalesce((select sum(e.end_at - e.start_at)::int from case_period_exclusion e
                                                  where e.case_id = cf.id and e.end_at is not null), 0) as eff
                from case_file cf
                where cf.status in ('INVESTIGATING','REPORTED','NOTIFIED') and cf.owner_user is not null""")) {
            LocalDate eff = ((java.sql.Date) r.get("eff")).toLocalDate();
            if (eff.isAfter(LocalDate.now().plusDays(ahead))) continue;
            boolean overdue = eff.isBefore(LocalDate.now());
            send((String) r.get("owner_user"), "DEADLINE",
                    (overdue ? "【超期】" : "【临期】") + "办案期限 " + r.get("case_no"),
                    "有效期限 " + eff + "（如需延长请提交审批申请）",
                    "/case/detail/" + r.get("id"), "CASE-" + r.get("id") + "-" + today);
            n++;
        }
        // 执行阶段（第52-55条/行政强制法53-54条）→ 承办人
        // 办案期限那段把 DELIVERED 排除在外，决定书送达后系统就不再替办案人计时；
        // 而强执申请期是全流程唯一"错过即失权、罚没款再也追不回"的期限，必须主动推送而非等人来看看板
        int payDays = config.intVal("payment_days", 15);
        for (var r : jdbc.queryForList("""
                select cf.id, cf.case_no, cf.owner_user,
                       cf.delivered_at + ?::int as pay_deadline,
                       ((cf.delivered_at + ?::int) + interval '3 month')::date - current_date as days_left,
                       exists (select 1 from case_document doc
                               where doc.case_id = cf.id and doc.doc_type = 'URGE_LETTER') as urged
                from case_file cf join case_decision d on d.case_id = cf.id
                where cf.status = 'DELIVERED' and cf.delivered_at is not null
                  and cf.owner_user is not null and cf.court_enforce_applied = false
                  and cf.delivered_at + ?::int <= current_date + ?::int
                  and (coalesce((select sum(e.amount) from case_execution e
                                 where e.case_id = cf.id and e.kind = 'FINE'), 0) < d.fine_amount
                    or coalesce((select sum(e.amount) from case_execution e
                                 where e.case_id = cf.id and e.kind = 'RECOUP'), 0) < d.recoup_amount
                    or coalesce((select sum(e.amount) from case_execution e
                                 where e.case_id = cf.id and e.kind = 'CONFISCATE'), 0) < d.confiscate_amount)""",
                payDays, payDays, payDays, ahead)) {
            long daysLeft = ((Number) r.get("days_left")).longValue();
            boolean urged = Boolean.TRUE.equals(r.get("urged"));
            // 只有强执申请期临期(30日内)/已过时才升级为紧急提示，否则是常规催缴
            String title = daysLeft < 0
                    ? "【已失权】强制执行申请期已过 " + r.get("case_no")
                    : daysLeft <= 30
                        ? "【紧急】强制执行申请期剩 " + daysLeft + " 天 " + r.get("case_no")
                        : "【催缴】缴款期届满未缴清 " + r.get("case_no");
            String body = "缴款期限 " + r.get("pay_deadline")
                    + (urged ? "；已下达催告书" : "；尚未下达催告书（申请强执的法定前置，须先制作 URGE_LETTER 并满 "
                        + config.intVal("court_urge_days", 10) + " 日）")
                    + (daysLeft < 0 ? "。申请期已过，罚没款不能再通过法院强制执行追缴，请说明情况另行处理。"
                        : "。逾期未申请将丧失强制执行权。");
            send((String) r.get("owner_user"), "DEADLINE", title, body,
                    "/case/detail/" + r.get("id"), "EXEC-" + r.get("id") + "-" + today);
            n++;
        }
        // 待批审批单 → 负责人
        Integer pending = jdbc.queryForObject(
                "select count(*) from biz_approval where status = 'PENDING'", Integer.class);
        if (pending != null && pending > 0) {
            for (var u : jdbc.queryForList("""
                    select u.username from sys_user u
                    join sys_user_role ur on ur.user_id = u.id join sys_role r on r.id = ur.role_id
                    where r.code = 'LEADER' and u.enabled = true""")) {
                send((String) u.get("username"), "APPROVAL", "有 " + pending + " 张审批单待裁决",
                        null, "/case/approvals", "PENDING-APPROVALS-" + today);
                n++;
            }
        }
        // 已解决反馈超期未确认 → 自动关闭并告知提交人
        int autoDays = config.intVal("feedback_autoclose_days", 7);
        for (var r : jdbc.queryForList("""
                select id, username, title from feedback
                where status = 'RESOLVED' and handled_at <= now() - make_interval(days => ?)""", autoDays)) {
            jdbc.update("update feedback set status = 'CLOSED' where id = ? and status = 'RESOLVED'", r.get("id"));
            send((String) r.get("username"), "SYSTEM",
                    "反馈「" + r.get("title") + "」已自动关闭",
                    "已解决满 " + autoDays + " 天未确认，系统自动关闭；如未解决请重新提交",
                    "/my/feedback", "FEEDBACK-AUTOCLOSE-" + r.get("id"));
            n++;
        }
        log.info("期限提醒生成 {} 条", n);
        return n;
    }
}
