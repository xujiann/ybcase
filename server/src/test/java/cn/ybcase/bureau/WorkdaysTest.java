package cn.ybcase.bureau;

import cn.ybcase.bureau.common.TemplateUtil;
import cn.ybcase.bureau.common.Workdays;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 期限计算与文书渲染纯函数单测（期限是行政复议高发争议点，边界必须钉死） */
class WorkdaysTest {

    // 2026-08-03 周一
    private static final LocalDate MON = LocalDate.of(2026, 8, 3);

    @Test
    void 开始之日不计入且跳过周末() {
        // 周一+5个工作日：二三四五 + 下周一 = 8-10（第58条：开始之日不计算在内）
        assertEquals(LocalDate.of(2026, 8, 10), Workdays.plus(MON, 5));
    }

    @Test
    void 周五起加一个工作日为下周一() {
        assertEquals(LocalDate.of(2026, 8, 10), Workdays.plus(LocalDate.of(2026, 8, 7), 1));
    }

    @Test
    void 节假日顺延() {
        // 周二 8-04 放假：周一+2 个工作日 = 周三8-05、周四8-06 → 8-06（8-04 不计入）
        assertEquals(LocalDate.of(2026, 8, 6),
                Workdays.plus(MON, 2, Set.of(LocalDate.of(2026, 8, 4)), Set.of()));
    }

    @Test
    void 调休补班的周六算工作日() {
        // 周六 8-08 补班：周五+1 → 周六
        assertEquals(LocalDate.of(2026, 8, 8),
                Workdays.plus(LocalDate.of(2026, 8, 7), 1, Set.of(), Set.of(LocalDate.of(2026, 8, 8))));
    }

    @Test
    void 节假日优先于调休标记() {
        // 同一天既是 HOLIDAY 又是 SHIFT_WORK 时按放假处理
        LocalDate sat = LocalDate.of(2026, 8, 8);
        assertEquals(LocalDate.of(2026, 8, 10),
                Workdays.plus(LocalDate.of(2026, 8, 7), 1, Set.of(sat), Set.of(sat)));
    }

    @Test
    void 加处罚款每日百分之三() {
        assertEquals(0, new BigDecimal("15.00").compareTo(
                Workdays.lateFee(new BigDecimal("100"), 5, new BigDecimal("100"))));
    }

    @Test
    void 加处罚款不超过罚款本金() {
        // 100 元罚款逾期 40 日：3%*40=120 → 封顶 100（第55条）
        assertEquals(0, new BigDecimal("100").compareTo(
                Workdays.lateFee(new BigDecimal("100"), 40, new BigDecimal("100"))));
    }

    @Test
    void 加处罚款无逾期或基数为零时为零() {
        assertEquals(BigDecimal.ZERO, Workdays.lateFee(new BigDecimal("100"), 0, new BigDecimal("100")));
        assertEquals(BigDecimal.ZERO, Workdays.lateFee(BigDecimal.ZERO, 10, new BigDecimal("100")));
    }

    @Test
    void 模板占位符渲染与空值容错() {
        String out = TemplateUtil.fill("{{partyName}}：罚款{{fine}}元（{{missing}}）",
                Map.of("partyName", "某医院", "fine", "1000"));
        assertEquals("某医院：罚款1000元（{{missing}}）", out);
        assertEquals("：", TemplateUtil.fill("{{a}}：", java.util.Collections.singletonMap("a", null)));
    }

    @org.junit.jupiter.api.Test
    void 业务日期时区兜底逻辑() {
        // 不断言"当前 JVM 恰好是东八区"——CI runner 是 UTC，那样测的是环境不是代码。
        // 这里验证兜底逻辑本身：容器默认 UTC 时必须被纠正，部署方显式设定则尊重。
        java.util.TimeZone original = java.util.TimeZone.getDefault();
        try {
            org.junit.jupiter.api.Assertions.assertEquals("Asia/Shanghai",
                    BureauServerApplication.applyBusinessTimeZone("UTC", "Asia/Shanghai"),
                    "容器默认 UTC 未被纠正：中国 0-8 点的立案日/告知日会差一天");
            org.junit.jupiter.api.Assertions.assertEquals("Asia/Shanghai",
                    BureauServerApplication.applyBusinessTimeZone(null, "Asia/Shanghai"),
                    "时区未设置时未兜底");
            org.junit.jupiter.api.Assertions.assertEquals("Asia/Urumqi",
                    BureauServerApplication.applyBusinessTimeZone("Asia/Urumqi", "Asia/Shanghai"),
                    "部署方显式设定的时区不应被覆盖（跨省部署）");
            // 纠正后，中国凌晨时刻算出的业务日期必须是中国当日
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(
                    BureauServerApplication.applyBusinessTimeZone("UTC", "Asia/Shanghai")));
            java.time.Instant earlyMorning = java.time.ZonedDateTime
                    .of(2026, 8, 28, 2, 0, 0, 0, java.time.ZoneId.of("Asia/Shanghai")).toInstant();
            org.junit.jupiter.api.Assertions.assertEquals(
                    java.time.LocalDate.of(2026, 8, 28),
                    java.time.LocalDate.ofInstant(earlyMorning, java.time.ZoneId.systemDefault()),
                    "纠正后中国凌晨的业务日期仍不正确");
        } finally {
            java.util.TimeZone.setDefault(original);   // 不污染同批其它测试
        }
    }
}
