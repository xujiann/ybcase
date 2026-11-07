package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import cn.ybcase.bureau.spi.GatewayMonitorFeedAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MonitorGatewayImportService {
    private final MaterialAccess access;
    private final BureauConfig config;
    private final ClueService clues;
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;
    private final ObjectMapper mapper;

    private record Checkpoint(String cursor, long revision) {}

    public Map<String, Object> pull(GatewayMonitorFeedAdapter adapter, String username) {
        var authorization = new TransactionTemplate(transactions);
        authorization.setReadOnly(true);
        var before = authorization.execute(status -> {
            requireAccess(access.readActor(username));
            return checkpoint(false);
        });
        var page = adapter.fetchPage(before.cursor());
        List<Map<String, String>> items = page.items().stream()
                .sorted(Comparator.comparing(item -> item.get("externalId"))).toList();
        return new TransactionTemplate(transactions).execute(status -> {
            var actor = access.lockActor(username, true);
            requireAccess(actor);
            if (checkpoint(true).revision() != before.revision())
                throw new BizException(2163, "已有其他人员推进拉取进度，请重新拉取");
            List<String> numbers = new ArrayList<>();
            for (var item : items) {
                String externalId = item.get("externalId");
                String fingerprint = fingerprint(item);
                int claimed = jdbc.update("""
                        insert into external_monitor_clue (external_id, fingerprint)
                        values (?, ?) on conflict (external_id) do nothing
                        """, externalId, fingerprint);
                if (claimed == 0) {
                    String existing = jdbc.queryForObject(
                            "select fingerprint from external_monitor_clue where external_id=?", String.class, externalId);
                    if (!fingerprint.equals(existing))
                        throw new BizException(2162, "外部疑点编号对应内容发生变化，请核对后重新拉取");
                    continue;
                }
                var clue = clues.create(new ClueService.ClueCreateReq("MONITOR", item.get("content"),
                        item.get("suspectName"), item.get("suspectType"), LocalDate.now(), null), username, actor.deptId());
                jdbc.update("update external_monitor_clue set clue_id=? where external_id=?", clue.getId(), externalId);
                numbers.add(clue.getClueNo());
            }
            if (page.nextCursor() != null && !Objects.equals(page.nextCursor(), before.cursor()))
                jdbc.update("""
                        update external_monitor_checkpoint set cursor_value=?, revision=revision+1 where singleton
                        """, page.nextCursor());
            return Map.of("fetched", numbers.size(), "skipped", items.size() - numbers.size(),
                    "clueNos", numbers, "hasMore", page.hasMore(), "adapter", "GatewayMonitorFeedAdapter");
        });
    }

    private Checkpoint checkpoint(boolean lock) {
        String sql = "select cursor_value, revision from external_monitor_checkpoint where singleton";
        return jdbc.queryForObject(lock ? sql + " for update" : sql,
                (row, number) -> new Checkpoint(row.getString("cursor_value"), row.getLong("revision")));
    }

    private void requireAccess(WorkflowUserAccess.User actor) {
        if (!"BUREAU".equals(actor.accountCategory())
                || !(actor.roles().contains("LEADER") || actor.roles().contains("ADMIN")))
            throw new BizException(403, "外部疑点拉取仅由局机关授权岗位办理");
        if (!config.bool("monitor_feed_enabled", false))
            throw new BizException(2160, "外部疑点拉取尚未启用");
    }

    private String fingerprint(Map<String, String> item) {
        try {
            String canonical = mapper.writeValueAsString(List.of(item.get("suspectName"),
                    item.get("suspectType"), item.get("content")));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("无法生成外部疑点指纹", e);
        }
    }
}
