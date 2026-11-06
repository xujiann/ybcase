package cn.ybcase.bureau.common;

/** CSV text cells: preserve field boundaries and make spreadsheet formula prefixes literal. */
public final class Csv {
    private Csv() {}
    public static String cell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        int first = 0;
        while (first < text.length() && Character.isWhitespace(text.charAt(first))) first++;
        if ((!text.isEmpty() && "\t\r\n".indexOf(text.charAt(0)) >= 0)
                || (first < text.length() && "=+-@".indexOf(text.charAt(first)) >= 0)) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
