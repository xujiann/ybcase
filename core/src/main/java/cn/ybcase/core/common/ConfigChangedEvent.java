package cn.ybcase.core.common;

/** 参数被修改：各处的参数缓存据此立即失效（省域参数切换须即时生效） */
public record ConfigChangedEvent(String key) {}
