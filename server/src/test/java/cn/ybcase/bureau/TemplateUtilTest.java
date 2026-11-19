package cn.ybcase.bureau;

import cn.ybcase.bureau.common.TemplateUtil;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TemplateUtilTest {
    @Test void suppliedValuesAreLiteralRegardlessOfVariableTraversalOrder() {
        var values = new LinkedHashMap<String, String>();
        values.put("partyName", "原文{{partyLegalRep}}");
        values.put("partyLegalRep", "张某");
        String template = "{{partyName}} / {{partyLegalRep}} / {{partyName}}";
        String expected = "原文{{partyLegalRep}} / 张某 / 原文{{partyLegalRep}}";
        assertEquals(expected, TemplateUtil.fill(template, values));
        var reverse = new LinkedHashMap<String, String>();
        reverse.put("partyLegalRep", "张某");
        reverse.put("partyName", "原文{{partyLegalRep}}");
        assertEquals(expected, TemplateUtil.fill(template, reverse));
    }

    @Test void nullValuesBecomeEmptyButUnknownPlaceholdersStayVisible() {
        var values = new LinkedHashMap<String, String>();
        values.put("empty", null);
        values.put("ordinary", "通常文字");
        assertEquals("通常文字||{{unknown}}|{{broken", TemplateUtil.fill(
                "{{ordinary}}|{{empty}}|{{unknown}}|{{broken", values));
    }

    @Test void dollarBackslashUnicodeAndPlaceholderLookingTextRemainExact() {
        String raw = "$1\\原文😀{{other}}\\$";
        assertEquals(raw + " / 已知", TemplateUtil.fill("{{raw}} / {{other}}",
                Map.of("raw", raw, "other", "已知")));
    }
}
