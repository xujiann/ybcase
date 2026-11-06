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
    private final IntegrationGateway gateway;

    @Override
    public List<Map<String, String>> fetchSuspects() {
        var items = gateway.post("monitor", "clues/pull", Map.of("limit", 500), null).path("items");
        if (!items.isArray() || items.size() > 500) throw IntegrationGateway.invalidResponse();
        List<Map<String, String>> result = new ArrayList<>();
        for (var item : items) {
            String type = IntegrationGateway.text(item, "suspectType", 16);
            if (!Set.of("PROVIDER", "AGENCY", "INDIVIDUAL", "OTHER").contains(type))
                throw IntegrationGateway.invalidResponse();
            result.add(Map.of("externalId", IntegrationGateway.text(item, "externalId", 128),
                    "suspectName", IntegrationGateway.text(item, "suspectName", 128),
                    "suspectType", type, "content", IntegrationGateway.text(item, "content", 12000)));
        }
        return result;
    }
}
