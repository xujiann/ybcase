package cn.ybcase.bureau.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** All money is validated at the same precision as numeric(14,2), before comparisons. */
public final class Money {
    private static final BigDecimal MAX = new BigDecimal("999999999999.99");
    private Money() {}

    public static BigDecimal nonNegative(BigDecimal value, String label) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value;
        if (amount.signum() < 0 || amount.compareTo(MAX) > 0)
            throw new BizException(2140, label + "须在0至999999999999.99元之间");
        try {
            return amount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new BizException(2140, label + "最多保留两位小数");
        }
    }

    public static BigDecimal positive(BigDecimal value, String label) {
        BigDecimal amount = nonNegative(value, label);
        if (amount.signum() == 0) throw new BizException(2140, label + "须为正数");
        return amount;
    }
}
