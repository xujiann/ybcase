package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

final class IntegrationSupport {
    private IntegrationSupport() {}
    static BizException invalid(String message) { return new BizException(2193, message); }
    static String text(String value, int max, String label) {
        if (value == null || value.isBlank() || value.length() > max || value.codePoints().anyMatch(Character::isISOControl))
            throw invalid(label + "必填且长度或格式须有效");
        return value.strip();
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static void bureau(WorkflowUserAccess.User actor) {
        if (actor == null || actor.roles().contains("AGENCY") || actor.roles().contains("INTEGRATION")
                || actor.roles().stream().noneMatch(Set.of("HANDLER","LEGAL","LEADER","ADMIN")::contains))
            throw new BizException(403,"当前账号无权使用业务集成台账");
    }
}
