package cn.ybcase.bureau.common;

import java.util.Map;

/** 文书模板 {{占位符}} 渲染（要素+模板分离） */
public final class TemplateUtil {

    private TemplateUtil() {}

    public static String fill(String tpl, Map<String, String> vars) {
        String s = tpl;
        for (var e : vars.entrySet()) {
            s = s.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return s;
    }
}
