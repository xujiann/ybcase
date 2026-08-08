package cn.ybcase.bureau.web;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.service.MessageService;
import cn.ybcase.core.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用户反馈直报：提交（自动上下文）→ 管理员处理回复 → 提交人确认关闭；全程站内消息闭环 */
@RestController
@RequestMapping("/api/bureau/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final MessageService messageService;
    private final JdbcTemplate jdbc;

    private static final List<String> KINDS = List.of("BUG", "FEATURE", "QUESTION");
    private static final Map<String, String> KIND_NAMES = Map.of(
            "BUG", "问题缺陷", "FEATURE", "功能需求", "QUESTION", "使用咨询");

    public record FeedbackReq(String kind, String title, String content,
                              String pageRoute, String caseRef, String appVersion, String userAgent,
                              String requestId, String recentErrors, String screenshotBase64) {}

    @PostMapping
    public R<Map<String, Object>> submit(@RequestBody FeedbackReq req, Authentication auth) {
        if (!KINDS.contains(req.kind())) throw new BizException(2090, "反馈类型须为 BUG/FEATURE/QUESTION");
        if (req.title() == null || req.title().isBlank()) throw new BizException(2090, "请填写标题");
        if (req.content() == null || req.content().isBlank()) throw new BizException(2090, "请描述问题或需求");
        byte[] screenshot = null;
        if (req.screenshotBase64() != null && !req.screenshotBase64().isBlank()) {
            String b64 = req.screenshotBase64();
            int comma = b64.indexOf(',');
            screenshot = Base64.getDecoder().decode(comma >= 0 ? b64.substring(comma + 1) : b64);
            if (screenshot.length > 5 * 1024 * 1024) throw new BizException(2090, "截图不得超过 5MB");
        }
        Long id = jdbc.queryForObject("""
                insert into feedback (kind, title, content, page_route, case_ref, app_version,
                                      user_agent, request_id, recent_errors, screenshot, username)
                values (?,?,?,?,?,?,?,?,?,?,?) returning id""", Long.class,
                req.kind(), req.title(), req.content(), req.pageRoute(), req.caseRef(), req.appVersion(),
                req.userAgent(), req.requestId(), req.recentErrors(), screenshot, auth.getName());
        messageService.sendToRole("ADMIN", "SYSTEM",
                "新反馈：【" + KIND_NAMES.get(req.kind()) + "】" + req.title(),
                auth.getName() + " 提交，页面 " + (req.pageRoute() == null ? "-" : req.pageRoute()),
                "/system/feedback");
        return R.ok(Map.of("id", id));
    }

    /** 我的反馈 */
    @GetMapping("/mine")
    public R<List<Map<String, Object>>> mine(Authentication auth) {
        return R.ok(jdbc.queryForList("""
                select id, kind, title, content, page_route, status, reply, handler, created_at, handled_at
                from feedback where username = ? order by id desc limit 100""", auth.getName()));
    }

    /** 管理员列表 + 统计（按状态/类型/页面聚合） */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String kind) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> args = new java.util.ArrayList<>();
        if (status != null && !status.isBlank()) { where.append(" and status = ? "); args.add(status); }
        if (kind != null && !kind.isBlank()) { where.append(" and kind = ? "); args.add(kind); }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rows", jdbc.queryForList("""
                select id, kind, title, content, page_route, case_ref, app_version, request_id,
                       recent_errors, (screenshot is not null) as has_screenshot,
                       username, created_at, status, handler, reply, handled_at
                from feedback""" + where + " order by id desc limit 200", args.toArray()));
        m.put("byStatus", jdbc.queryForList("select status, count(*) as cnt from feedback group by status"));
        m.put("byKind", jdbc.queryForList("select kind, count(*) as cnt from feedback group by kind"));
        m.put("byPage", jdbc.queryForList("""
                select page_route, count(*) as cnt from feedback
                where kind = 'BUG' and page_route is not null
                group by page_route order by cnt desc limit 10"""));
        return R.ok(m);
    }

    /** 管理员处理：状态流转+回复（通知提交人） */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/handle")
    public R<Void> handle(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication auth) {
        String status = body.get("status");
        if (!List.of("PROCESSING", "RESOLVED", "REJECTED").contains(status))
            throw new BizException(2091, "处理状态须为 PROCESSING/RESOLVED/REJECTED");
        if ((status.equals("RESOLVED") || status.equals("REJECTED"))
                && (body.get("reply") == null || body.get("reply").isBlank()))
            throw new BizException(2091, "解决/不采纳须填写回复说明");
        var rows = jdbc.queryForList("select username, title from feedback where id = ?", id);
        if (rows.isEmpty()) throw new BizException(2091, "反馈不存在");
        jdbc.update("""
                update feedback set status = ?, reply = coalesce(?, reply), handler = ?, handled_at = now()
                where id = ?""", status, body.get("reply"), auth.getName(), id);
        String label = switch (status) { case "PROCESSING" -> "处理中"; case "RESOLVED" -> "已解决（请确认关闭）"; default -> "不采纳"; };
        messageService.send((String) rows.get(0).get("username"), "SYSTEM",
                "您的反馈「" + rows.get(0).get("title") + "」" + label,
                body.get("reply"), "/my/feedback", null);
        return R.ok();
    }

    /** 提交人确认关闭（已解决 → 关闭闭环） */
    @PostMapping("/{id}/close")
    public R<Void> close(@PathVariable Long id, Authentication auth) {
        int n = jdbc.update("""
                update feedback set status = 'CLOSED' where id = ? and username = ? and status = 'RESOLVED'""",
                id, auth.getName());
        if (n == 0) throw new BizException(2091, "仅本人已解决的反馈可确认关闭");
        return R.ok();
    }

    @GetMapping("/{id}/screenshot")
    public ResponseEntity<byte[]> screenshot(@PathVariable Long id, Authentication auth) {
        var rows = jdbc.queryForList("select username, screenshot from feedback where id = ?", id);
        if (rows.isEmpty() || rows.get(0).get("screenshot") == null) return ResponseEntity.notFound().build();
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        if (!admin && !auth.getName().equals(rows.get(0).get("username")))
            return ResponseEntity.status(403).build();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body((byte[]) rows.get(0).get("screenshot"));
    }

    /** 导出 CSV（给开发排期的原始输入） */
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/export")
    public ResponseEntity<byte[]> export() {
        var rows = jdbc.queryForList("""
                select id, kind, status, title, content, page_route, app_version, request_id,
                       username, created_at, handler, reply from feedback order by id""");
        StringBuilder sb = new StringBuilder("﻿id,类型,状态,标题,描述,页面,版本,request_id,提交人,时间,处理人,回复\n");
        for (var r : rows) {
            sb.append(r.get("id")).append(',').append(r.get("kind")).append(',').append(r.get("status")).append(',')
              .append(csv(r.get("title"))).append(',').append(csv(r.get("content"))).append(',')
              .append(csv(r.get("page_route"))).append(',').append(csv(r.get("app_version"))).append(',')
              .append(csv(r.get("request_id"))).append(',').append(r.get("username")).append(',')
              .append(r.get("created_at")).append(',').append(csv(r.get("handler"))).append(',')
              .append(csv(r.get("reply"))).append('\n');
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=feedback.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=utf-8"))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String csv(Object v) {
        return v == null ? "" : String.valueOf(v).replace(",", "，").replace("\n", " ");
    }
}
