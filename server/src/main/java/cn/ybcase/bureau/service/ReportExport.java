package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

/** A completed report and the non-identifying facts persisted with its request audit. */
public record ReportExport(byte[] workbook, Audit audit, List<Line> lines) {
    public ReportExport(byte[] workbook, Audit audit) {
        this(workbook, audit, List.of());
    }

    public record Line(String key, String sourceType, long sourceId, String sourceNumber,
                       int sheetIndex, int rowNumber, List<Object> cells) {
        public Line {
            cells = Collections.unmodifiableList(new ArrayList<>(cells));
        }
        static Line of(String sourceType, long sourceId, String sourceNumber, int sheetIndex, int rowNumber, Object[] cells) {
            return new Line(sourceType + ":" + sourceId, sourceType, sourceId, sourceNumber,
                    sheetIndex, rowNumber, Arrays.asList(cells));
        }
    }
    public static final String AUDIT_ATTRIBUTE = "ybcaseReportExport";
    public static final java.util.concurrent.Semaphore GENERATION_SLOTS = new java.util.concurrent.Semaphore(2);

    public enum Kind { PENALTIES, ENFORCEMENT_WORK, INTERVIEWS, MONTHLY_SUBMISSION }

    public record Audit(Kind kind, LocalDate start, LocalDate end, LocalDate cutoff, int rowCount) {}

    static final int MAX_ROWS = 25_000;
    static final int MAX_TEXT_CHARS = 16_000_000;
    static final int MAX_WORKBOOK_BYTES = 24 * 1024 * 1024;

    static BizException tooLarge() {
        return new BizException(2100, "报表超出单次导出容量，请缩短统计区间后分批导出");
    }

    /** Keep only bounded, validated rows. PostgreSQL fetches in batches inside the read transaction. */
    static void query(JdbcTemplate jdbc, String sql, RowCallbackHandler rows, Object... parameters) {
        jdbc.query(connection -> {
            var statement = connection.prepareStatement(sql + " limit " + (MAX_ROWS + 1));
            statement.setFetchSize(250);
            statement.setQueryTimeout(45);
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            return statement;
        }, rows);
    }

    static final class Budget {
        private int rowCount;
        private long textChars;

        void add(List<Object[]> rows, Object[] row) {
            if (++rowCount > MAX_ROWS) throw tooLarge();
            for (Object cell : row) {
                if (cell instanceof String text) {
                    if (text.length() > 32_767)
                        throw new BizException(2100, "单条记录的字段超过 Excel 单元格容量，请联系管理员核对该记录");
                    textChars += text.length();
                }
            }
            if (textChars > MAX_TEXT_CHARS) throw tooLarge();
            rows.add(row);
        }

        int rowCount() { return rowCount; }
    }

    record AnnualPeriod(LocalDate start, LocalDate end, LocalDate cutoff) {
        static AnnualPeriod of(int year, LocalDate start, LocalDate cutoff, LocalDate today) {
            return of(year, start, null, cutoff, today);
        }

        static AnnualPeriod of(int year, LocalDate start, LocalDate end, LocalDate cutoff, LocalDate today) {
            if (year < 1900 || year > today.getYear())
                throw new BizException(2100, "请选择1900年至当年的统计年度");
            var first = LocalDate.of(year, 1, 1);
            var last = LocalDate.of(year, 12, 31);
            var from = start == null ? first : start;
            var asOf = cutoff == null ? today : cutoff;
            var latestEnd = asOf.isBefore(last) ? asOf : last;
            var to = end == null ? latestEnd : end;
            if (from.isBefore(first) || from.isAfter(to) || to.isAfter(latestEnd) || asOf.isAfter(today))
                throw new BizException(2100, "统计日期须在所选年度内，且依次为起始日、结束日、截至日，截至日不得晚于今天");
            return new AnnualPeriod(from, to, asOf);
        }
    }
}
