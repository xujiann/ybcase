package cn.ybcase.bureau.common;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Map 请求体的安全取值：直接 (Number)body.get(..) / LocalDate.parse(..) 在漏传或类型不符时
 * 抛 NPE/CCE/DateTimeParseException，最终变成"系统内部错误 500"，用户看不到自己错在哪。
 */
public final class ReqValues {

    private ReqValues() {}

    /** 必填整数：漏传或非数字抛业务码 */
    public static int reqInt(Map<String, ?> body, String key, String label) {
        Object v = body == null ? null : body.get(key);
        if (v == null) throw new BizException(2100, "请填写" + label);
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            throw new BizException(2100, label + "须为整数");
        }
    }

    /** 可选长整数（含数字字符串） */
    public static Long optLong(Map<String, ?> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new BizException(2100, key + " 须为数字");
        }
    }

    /** 可选日期：空返回 null，格式错抛业务码而非 500 */
    public static LocalDate optDate(Map<String, ?> body, String key, String label) {
        Object v = body == null ? null : body.get(key);
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new BizException(2100, label + "格式须为 yyyy-MM-dd（收到：" + s + "）");
        }
    }

    /** 必填日期 */
    public static LocalDate reqDate(Map<String, ?> body, String key, String label) {
        LocalDate d = optDate(body, key, label);
        if (d == null) throw new BizException(2100, "请填写" + label);
        return d;
    }

    /** 日期缺省为今天 */
    public static LocalDate dateOrToday(Map<String, ?> body, String key, String label) {
        LocalDate d = optDate(body, key, label);
        return d == null ? LocalDate.now() : d;
    }
}
