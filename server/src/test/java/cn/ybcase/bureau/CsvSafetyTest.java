package cn.ybcase.bureau;
import cn.ybcase.bureau.common.Csv;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CsvSafetyTest {
    @Test void formulaPrefixesRemainLiteralIncludingWhitespace() {
        for(String text:new String[]{"=1+1","+SUM(1)","-1","@cmd","  =1+1","\ttext","\rtext","\ntext"})
            assertEquals("\"'"+text+"\"",Csv.cell(text),text);
        assertEquals("\"plain\"",Csv.cell("plain"));assertEquals("\"\"",Csv.cell(null));
    }
    @Test void quotesCommasAndNewlinesStayInOneCsvField() {
        assertEquals("\"a,\"\"b\"\"\r\nc\"",Csv.cell("a,\"b\"\r\nc"));
    }
}
