package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.Workdays;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 省域参数（sys_config）统一读取：期限/限额/阈值全部可配，默认=新行政处罚法+国家局令第4号 */
@Service
@RequiredArgsConstructor
public class BureauConfig {

    private final JdbcTemplate jdbc;

    /**
     * 参数与节假日表整表缓存：单次 decide() 要读七八个参数，逐个查库等于七八次往返。
     * 改参数走 PUT /api/config/{key} 时由事件立即失效；TTL 兜底直接改库的场景。
     */
    private static final long TTL_MS = 30_000;
    private volatile Map<String, String> cache;
    private volatile long cacheAt;
    private volatile Holidays holidayCache;
    private volatile long holidayAt;

    private record Holidays(java.util.Set<LocalDate> off, java.util.Set<LocalDate> shiftWork) {}

    @org.springframework.context.event.EventListener
    public void onConfigChanged(cn.ybcase.core.common.ConfigChangedEvent e) {
        cache = null;
        holidayCache = null;
    }

    /** 节假日表变更后由调用方显式失效（新增/删除节假日） */
    public void evictHolidays() {
        holidayCache = null;
    }

    private Map<String, String> snapshot() {
        Map<String, String> c = cache;
        if (c != null && System.currentTimeMillis() - cacheAt < TTL_MS) return c;
        Map<String, String> loaded = new java.util.HashMap<>();
        for (var r : jdbc.queryForList("select cfg_key, cfg_value from sys_config"))
            loaded.put((String) r.get("cfg_key"), (String) r.get("cfg_value"));
        cache = loaded;
        cacheAt = System.currentTimeMillis();
        return loaded;
    }

    public String str(String key, String def) {
        String v = snapshot().get(key);
        return v == null ? def : v;
    }

    public int intVal(String key, int def) {
        try {
            return Integer.parseInt(str(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public BigDecimal decimal(String key, String def) {
        try {
            return new BigDecimal(str(key, def).trim());
        } catch (NumberFormatException e) {
            return new BigDecimal(def);
        }
    }

    public boolean bool(String key, boolean def) {
        return Boolean.parseBoolean(str(key, String.valueOf(def)));
    }

    /** 按参数单位加期限：WORKDAY 工作日（跳周末+法定节假日，含调休补班）/ NATURAL 自然日 */
    public LocalDate plusByUnit(LocalDate from, int days, String unitKey) {
        String unit = str(unitKey, "WORKDAY");
        return "NATURAL".equalsIgnoreCase(unit) ? from.plusDays(days) : plusWorkdays(from, days);
    }

    /** 工作日推算（第58条：期间届满遇法定节假日顺延；sys_holiday：HOLIDAY 放假 / SHIFT_WORK 调休上班） */
    public LocalDate plusWorkdays(LocalDate from, int n) {
        Holidays h = holidayCache;
        if (h == null || System.currentTimeMillis() - holidayAt >= TTL_MS) {
            var off = new java.util.HashSet<LocalDate>();
            var shiftWork = new java.util.HashSet<LocalDate>();
            for (var r : jdbc.queryForList("select day, kind from sys_holiday")) {
                LocalDate d = ((java.sql.Date) r.get("day")).toLocalDate();
                if ("SHIFT_WORK".equals(r.get("kind"))) shiftWork.add(d); else off.add(d);
            }
            h = new Holidays(off, shiftWork);
            holidayCache = h;
            holidayAt = System.currentTimeMillis();
        }
        return Workdays.plus(from, n, h.off(), h.shiftWork());
    }

    /**
     * 节假日表覆盖度自检：返回"应有而没有放假记录"的年份。
     * plusWorkdays 查到空集不报错、直接退化成只跳周末——2027-01-01 起线索核查 15 工作日、
     * 法制审核 10 工作日、先行登记保存 7 工作日、送达/简易备案 7 工作日全部静默算错，
     * 且没有任何地方提醒管理员"该维护明年的表了"。当年恒查；次年从 11 月起查
     * （国办通知通常 11 月发布，且 11-12 月起算的期限已会跨年）。
     */
    public List<Integer> missingHolidayYears() {
        LocalDate today = LocalDate.now();
        List<Integer> years = new java.util.ArrayList<>();
        years.add(today.getYear());
        if (today.getMonthValue() >= 11) years.add(today.getYear() + 1);
        List<Integer> missing = new java.util.ArrayList<>();
        for (int y : years) {
            Integer n = jdbc.queryForObject(
                    "select count(*) from sys_holiday where kind = 'HOLIDAY' and extract(year from day) = ?",
                    Integer.class, y);
            if (n == null || n == 0) missing.add(y);
        }
        return missing;
    }

    /** 按当事人类型取阈值（自然人 / 单位：经办机构、定点医药机构、其他主体） */
    public BigDecimal byPartyType(String partyType, String individualKey, String orgKey, String def) {
        return "INDIVIDUAL".equals(partyType) ? decimal(individualKey, def) : decimal(orgKey, def);
    }
}
