package cn.ybcase.bureau.common;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 文书模板 {{占位符}} 渲染（要素+模板分离） */
public final class TemplateUtil {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)}}");

    private TemplateUtil() {}

    public static String fill(String tpl, Map<String, String> vars) {
        // Only the original template contains instructions; inserted case data stays literal.
        return PLACEHOLDER.matcher(tpl).replaceAll(match -> {
            String key = match.group(1);
            String value = vars.containsKey(key) ? vars.get(key) : match.group();
            return Matcher.quoteReplacement(value == null ? "" : value);
        });
    }
}
