package cn.ybcase.bureau.spi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Mock 监控源：返回空（接口链路可用，数据待实施期对接真实监控系统） */
@Component
@ConditionalOnMissingBean(value = MonitorFeedAdapter.class, ignored = MockMonitorFeedAdapter.class)
public class MockMonitorFeedAdapter implements MonitorFeedAdapter {

    @Override
    public List<Map<String, String>> fetchSuspects() {
        return List.of();
    }
}
