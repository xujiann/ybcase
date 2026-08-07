package cn.ybcase.bureau.spi;

import java.util.List;
import java.util.Map;

/** 智能监控疑点拉取 SPI：实施期对接省医保智能监控子系统（替换 Bean 即接通） */
public interface MonitorFeedAdapter {

    /** 拉取一批疑点（键：suspectName/suspectType/content/handler），空表示无新疑点 */
    List<Map<String, String>> fetchSuspects();
}
