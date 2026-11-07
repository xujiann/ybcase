package cn.ybcase.bureau.spi;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@Primary
@ConditionalOnProperty(prefix = "ybcase.integration.monitor", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class GatewayMonitorFeedAdapter implements MonitorFeedAdapter {
    private static final int LIMIT = 500;
    private final IntegrationGateway gateway;

    public record Page(List<Map<String, String>> items, String nextCursor, boolean hasMore) {}

    @Override
    public List<Map<String, String>> fetchSuspects() {
        return fetchPage(null).items();
    }

    public Page fetchPage(String cursor) {
        var request = cursor == null ? Map.of("limit", LIMIT) : Map.of("limit", LIMIT, "cursor", cursor);
        var response = gateway.post("monitor", "clues/pull", request, null);
        var items = response.path("items");
        if (!items.isArray() || items.size() > LIMIT) throw IntegrationGateway.invalidResponse();
        String nextCursor = response.hasNonNull("nextCursor")
                ? IntegrationGateway.text(response, "nextCursor", 512) : null;
        if (response.has("hasMore") && !response.path("hasMore").isBoolean())
            throw IntegrationGateway.invalidResponse();
        boolean hasMore = response.path("hasMore").asBoolean();
        if (hasMore && (nextCursor == null || nextCursor.equals(cursor)))
            throw IntegrationGateway.invalidResponse();
        List<Map<String, String>> result = new ArrayList<>();
        for (var item : items) {
            String type = IntegrationGateway.text(item, "suspectType", 16);
            if (!Set.of("PROVIDER", "AGENCY", "INDIVIDUAL", "OTHER").contains(type))
                throw IntegrationGateway.invalidResponse();
            result.add(Map.of("externalId", IntegrationGateway.text(item, "externalId", 128),
                    "suspectName", IntegrationGateway.text(item, "suspectName", 128),
                    "suspectType", type, "content", IntegrationGateway.text(item, "content", 12000)));
        }
        return new Page(result, nextCursor, hasMore);
    }
}
