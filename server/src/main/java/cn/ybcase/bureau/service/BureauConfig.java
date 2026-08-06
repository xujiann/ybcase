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

    public String str(String key, String def) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select cfg_value from sys_config where cfg_key = ?", key);
        return rows.isEmpty() ? def : (String) rows.get(0).get("cfg_value");
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
        var rows = jdbc.queryForList("select day, kind from sys_holiday");
        var holidays = new java.util.HashSet<LocalDate>();
        var shiftWork = new java.util.HashSet<LocalDate>();
        for (var r : rows) {
            LocalDate d = ((java.sql.Date) r.get("day")).toLocalDate();
            if ("SHIFT_WORK".equals(r.get("kind"))) shiftWork.add(d); else holidays.add(d);
        }
        LocalDate d = from;
        int added = 0;
        while (added < n) {
            d = d.plusDays(1);
            boolean weekend = d.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                    || d.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
            boolean working = (!weekend || shiftWork.contains(d)) && !holidays.contains(d);
            if (working) added++;
        }
        return d;
    }

    /** 按当事人类型取阈值（自然人 / 单位：经办机构、定点医药机构、其他主体） */
    public BigDecimal byPartyType(String partyType, String individualKey, String orgKey, String def) {
        return "INDIVIDUAL".equals(partyType) ? decimal(individualKey, def) : decimal(orgKey, def);
    }
}
