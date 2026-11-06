package cn.ybcase.bureau.service;

import cn.ybcase.bureau.common.BizException;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PenaltyReportWorkbookTest {
    @Test void reportBudgetRejectsOverflowInsteadOfReturningAnIncompleteWorkbook() {
        var rows = new ArrayList<Object[]>();
        var budget = new ReportExport.Budget();
        for (int i = 0; i < ReportExport.MAX_ROWS; i++) budget.add(rows, new Object[]{i});
        assertThrows(BizException.class, () -> budget.add(rows, new Object[]{"over limit"}));
        assertEquals(ReportExport.MAX_ROWS, rows.size());

        var textBudget = new ReportExport.Budget();
        var textRows = new ArrayList<Object[]>();
        String text = "报".repeat(32_000);
        for (int i = 0; i < 500; i++) textBudget.add(textRows, new Object[]{text});
        assertThrows(BizException.class, () -> textBudget.add(textRows, new Object[]{"over limit"}));
        assertEquals(500, textRows.size());
        assertThrows(BizException.class, () -> new ReportExport.Budget().add(textRows, new Object[]{"报".repeat(32_768)}));
    }

    @Test void annualPeriodAllowsDecisionBatchesAtOneExecutionCutoff() {
        var cutoff = LocalDate.of(2027, 2, 1);
        var period = ReportExport.AnnualPeriod.of(2026, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 6, 30), cutoff, cutoff);
        assertEquals(LocalDate.of(2026, 6, 30), period.end());
        assertEquals(cutoff, period.cutoff());
        assertThrows(BizException.class, () -> ReportExport.AnnualPeriod.of(2027, null,
                LocalDate.of(2027, 2, 2), cutoff, cutoff));
    }

    @Test void largeWorkbookIncludesEveryAcceptedDataRow() throws Exception {
        var rows = new ArrayList<Object[]>();
        for (int i = 1; i <= 7_500; i++) rows.add(new Object[]{i, "合成测试数据"});
        byte[] workbook = PenaltyReportWorkbook.write("department-interviews.xlsx",
                List.of(new PenaltyReportWorkbook.Sheet(rows, 'I', Map.of())));
        try (var zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals("xl/worksheets/sheet1.xml")) continue;
                var sheet = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(zip);
                var sheetRows = sheet.getElementsByTagName("row");
                var last = (Element) sheetRows.item(sheetRows.getLength() - 1);
                assertEquals("7503", last.getAttribute("r"));
                assertTrue(last.getTextContent().contains("7500"));
                return;
            }
        }
        fail("缺少约谈台账工作表");
    }

    @Test void earlyInterviewDatesRespectExcel1900Calendar() throws Exception {
        var rows = List.of(
                new Object[]{LocalDate.of(1900, 1, 1)},
                new Object[]{LocalDate.of(1900, 2, 28)},
                new Object[]{LocalDate.of(1900, 3, 1)},
                new Object[]{LocalDate.of(2026, 1, 1)});
        byte[] workbook = PenaltyReportWorkbook.write("department-interviews.xlsx",
                List.of(new PenaltyReportWorkbook.Sheet(rows, 'I', Map.of())));

        try (var zip = new ZipInputStream(new ByteArrayInputStream(workbook))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!entry.getName().equals("xl/worksheets/sheet1.xml")) continue;
                var sheet = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(zip);
                var expected = Map.of("A4", "1", "A5", "59", "A6", "61", "A7", "46023");
                var cells = sheet.getElementsByTagName("c");
                int checked = 0;
                for (int index = 0; index < cells.getLength(); index++) {
                    var cell = (Element) cells.item(index);
                    String serial = expected.get(cell.getAttribute("r"));
                    if (serial == null) continue;
                    assertEquals(serial, cell.getTextContent());
                    assertEquals("19", cell.getAttribute("s"));
                    checked++;
                }
                assertEquals(expected.size(), checked);
                return;
            }
        }
        fail("缺少约谈台账工作表");
    }
}
