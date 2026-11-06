package cn.ybcase.bureau.common;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** 工作日计算（跳过周六周日；法定节假日暂不建表，与 ADR-0003 一致） */
public final class Workdays {

    private Workdays() {}

    /** 自 from 起加 n 个工作日（期间开始之日不计算在内，第58条；不含节假日表时仅跳周末） */
    public static LocalDate plus(LocalDate from, int n) {
        return plus(from, n, java.util.Set.of(), java.util.Set.of());
    }

    /** 节假日感知版：holidays 放假日、shiftWork 调休补班（周末上班）——纯函数，供单元测试 */
    public static LocalDate plus(LocalDate from, int n,
                                 java.util.Set<LocalDate> holidays, java.util.Set<LocalDate> shiftWork) {
        LocalDate d = from;
        int added = 0;
        while (added < n) {
            d = d.plusDays(1);
            if (isWorkingDay(d, holidays, shiftWork)) added++;
        }
        return d;
    }

    public static boolean isWorkingDay(LocalDate day, java.util.Set<LocalDate> holidays, java.util.Set<LocalDate> shiftWork) {
        boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;
        return (!weekend || shiftWork.contains(day)) && !holidays.contains(day);
    }

    /** 加处罚款测算（第55条：每日3%，累计不超过罚款数额）——纯函数，供单元测试 */
    public static java.math.BigDecimal lateFee(java.math.BigDecimal base, long overdueDays,
                                               java.math.BigDecimal fineCap) {
        if (overdueDays <= 0 || base.signum() <= 0) return java.math.BigDecimal.ZERO;
        return base.multiply(new java.math.BigDecimal("0.03"))
                .multiply(java.math.BigDecimal.valueOf(overdueDays)).min(fineCap);
    }
}
