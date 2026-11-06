package cn.ybcase.bureau.service;

import org.springframework.core.io.ClassPathResource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Preserves the report template and writes data rows directly to the compressed workbook. */
final class PenaltyReportWorkbook {
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
    private static final LocalDate EXCEL_FAKE_LEAP_DAY_CUTOFF = LocalDate.of(1900, 3, 1);
    private static final String XML_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String SHEET_PATH = "xl/worksheets/sheet";
    private static final String AUDIT_RELATION = "rIdReconciliation";

    private PenaltyReportWorkbook() {}

    static byte[] write(List<Object[]> historical, List<Object[]> current) {
        return write("administrative-penalties.xlsx", List.of(
                new Sheet(historical, 'R', Map.of()), new Sheet(current, 'S', Map.of())));
    }

    record Sheet(List<Object[]> rows, char lastColumn, Map<String, String> labels, String name) {
        Sheet(List<Object[]> rows, char lastColumn, Map<String, String> labels) {
            this(rows, lastColumn, labels, null);
        }
    }

    static byte[] write(String resource, List<Sheet> sheets) {
        return write(resource, sheets, List.of());
    }

    static byte[] write(String resource, List<Sheet> sheets, List<Object[]> reconciliation) {
        var result = new BoundedOutput();
        var template = new ClassPathResource("reports/" + resource);
        int reconciliationId = sheets.size() + 1;
        boolean includeReconciliation = !reconciliation.isEmpty();
        try (var source = new ZipInputStream(template.getInputStream());
             var target = new ZipOutputStream(result)) {
            for (ZipEntry entry; (entry = source.getNextEntry()) != null;) {
                String name = entry.getName();
                target.putNextEntry(new ZipEntry(name));
                switch (name) {
                    case SHEET_PATH + "1.xml" -> writeSheet(target, source.readAllBytes(), sheets.get(0));
                    case SHEET_PATH + "2.xml" -> writeSheet(target, source.readAllBytes(), sheets.get(1));
                    case "xl/workbook.xml" -> writeWorkbook(target, source.readAllBytes(), sheets, includeReconciliation);
                    case "xl/_rels/workbook.xml.rels" -> {
                        if (includeReconciliation)
                            writeText(target, readText(source).replace("</Relationships>",
                                    "<Relationship Id=\"" + AUDIT_RELATION + "\" Type=\"" + REL_NS
                                            + "/worksheet\" Target=\"worksheets/sheet" + reconciliationId + ".xml\"/></Relationships>"));
                        else source.transferTo(target);
                    }
                    case "[Content_Types].xml" -> {
                        if (includeReconciliation)
                            writeText(target, readText(source).replace("</Types>",
                                    "<Override PartName=\"/" + SHEET_PATH + reconciliationId + ".xml\" "
                                            + "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>"));
                        else source.transferTo(target);
                    }
                    default -> source.transferTo(target);
                }
                target.closeEntry();
            }
            if (includeReconciliation) {
                target.putNextEntry(new ZipEntry(SHEET_PATH + reconciliationId + ".xml"));
                writeText(target, """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                        <cols><col min="1" max="1" width="32" customWidth="1"/><col min="2" max="2" width="106" customWidth="1"/></cols>
                        <sheetData>
                        """);
                writeRows(target, reconciliation, 0);
                writeText(target, "</sheetData></worksheet>");
                target.closeEntry();
            }
        } catch (IOException | ParserConfigurationException | org.xml.sax.SAXException
                 | TransformerException | XMLStreamException e) {
            throw new IllegalStateException("无法生成统计报表", e);
        }
        return result.toByteArray();
    }

    private static void writeWorkbook(ZipOutputStream target, byte[] template, List<Sheet> sheets,
                                      boolean includeReconciliation)
            throws ParserConfigurationException, org.xml.sax.SAXException, IOException, TransformerException {
        if (!includeReconciliation && sheets.stream().noneMatch(sheet -> sheet.name() != null)) {
            target.write(template);
            return;
        }
        var doc = parse(template);
        var entries = doc.getElementsByTagNameNS(XML_NS, "sheet");
        for (int i = 0; i < sheets.size(); i++) {
            if (sheets.get(i).name() != null) ((Element) entries.item(i)).setAttribute("name", sheets.get(i).name());
        }
        if (includeReconciliation) {
            var parent = (Element) doc.getElementsByTagNameNS(XML_NS, "sheets").item(0);
            var entry = append(parent, "sheet");
            entry.setAttribute("name", "内部对账说明");
            entry.setAttribute("sheetId", Integer.toString(sheets.size() + 1));
            entry.setAttributeNS(REL_NS, "r:id", AUDIT_RELATION);
        }
        target.write(serialize(doc));
    }

    private static void writeSheet(ZipOutputStream target, byte[] template, Sheet sheet)
            throws ParserConfigurationException, org.xml.sax.SAXException, IOException, TransformerException, XMLStreamException {
        Document doc = parse(template);
        var dimension = (Element) doc.getElementsByTagNameNS(XML_NS, "dimension").item(0);
        dimension.setAttribute("ref", "A1:" + sheet.lastColumn() + (sheet.rows().size() + 3));
        var headers = doc.getElementsByTagNameNS(XML_NS, "c");
        for (int i = 0; i < headers.getLength(); i++) {
            var cell = (Element) headers.item(i);
            String label = sheet.labels().get(cell.getAttribute("r"));
            if (label != null) {
                cell.setTextContent("");
                cell.setAttribute("t", "inlineStr");
                Element literal = append(append(cell, "is"), "t");
                literal.setAttributeNS(XMLConstants.XML_NS_URI, "xml:space", "preserve");
                literal.setTextContent(excelText(label));
            }
        }
        var headAndTail = new String(serialize(doc), StandardCharsets.UTF_8);
        int insertion = headAndTail.indexOf("</sheetData>");
        if (insertion < 0) throw new IOException("报表模板缺少明细区域");
        writeText(target, headAndTail.substring(0, insertion));
        writeRows(target, sheet.rows(), 3);
        writeText(target, headAndTail.substring(insertion));
    }

    private static void writeRows(ZipOutputStream target, List<Object[]> rows, int headerCount) throws XMLStreamException {
        XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(
                new OutputStreamWriter(target, StandardCharsets.UTF_8));
        int rowNumber = headerCount;
        for (Object[] row : rows) {
            xml.writeStartElement("row");
            xml.writeAttribute("r", Integer.toString(++rowNumber));
            for (int i = 0; i < row.length; i++) {
                Object value = row[i];
                xml.writeStartElement("c");
                xml.writeAttribute("r", String.valueOf((char) ('A' + i)) + rowNumber);
                xml.writeAttribute("s", value instanceof LocalDate ? "19" : "18");
                if (value instanceof LocalDate date) {
                    long serial = ChronoUnit.DAYS.between(EXCEL_EPOCH, date);
                    // Excel reserves serial 60 for the nonexistent 1900-02-29.
                    if (date.isBefore(EXCEL_FAKE_LEAP_DAY_CUTOFF)) serial--;
                    writeValue(xml, Long.toString(serial));
                } else if (value instanceof Number number) {
                    writeValue(xml, number.toString());
                } else if (value instanceof String text && !text.isBlank()) {
                    // Text remains literal even when it starts with "=".
                    xml.writeAttribute("t", "inlineStr");
                    xml.writeStartElement("is");
                    xml.writeStartElement("t");
                    xml.writeAttribute("xml", XMLConstants.XML_NS_URI, "space", "preserve");
                    xml.writeCharacters(excelText(text));
                    xml.writeEndElement();
                    xml.writeEndElement();
                }
                xml.writeEndElement();
            }
            xml.writeEndElement();
        }
        xml.flush();
    }

    private static void writeValue(XMLStreamWriter xml, String value) throws XMLStreamException {
        xml.writeStartElement("v");
        xml.writeCharacters(value);
        xml.writeEndElement();
    }

    private static Document parse(byte[] xml)
            throws ParserConfigurationException, org.xml.sax.SAXException, IOException {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static byte[] serialize(Document doc) throws TransformerException {
        var output = new ByteArrayOutputStream();
        TransformerFactory.newInstance().newTransformer()
                .transform(new DOMSource(doc), new StreamResult(output));
        return output.toByteArray();
    }

    private static String readText(ZipInputStream source) throws IOException {
        return new String(source.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void writeText(ZipOutputStream target, String value) throws IOException {
        target.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Element append(Element parent, String name) {
        Element element = parent.getOwnerDocument().createElementNS(XML_NS, name);
        parent.appendChild(element);
        return element;
    }

    private static String excelText(String text) {
        var result = new StringBuilder();
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            int length = Character.charCount(codePoint);
            i += length;
            if (result.length() + length > 32767) break;
            if (codePoint == 9 || codePoint == 10 || codePoint == 13
                    || (codePoint >= 32 && codePoint <= 0xD7FF)
                    || (codePoint >= 0xE000 && codePoint <= 0xFFFD) || codePoint >= 0x10000) {
                result.appendCodePoint(codePoint);
            }
        }
        return result.toString();
    }

    private static final class BoundedOutput extends ByteArrayOutputStream {
        @Override public synchronized void write(int value) {
            requireCapacity(1);
            super.write(value);
        }

        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            requireCapacity(length);
            super.write(bytes, offset, length);
        }

        private void requireCapacity(int additional) {
            if (additional > ReportExport.MAX_WORKBOOK_BYTES - count) throw ReportExport.tooLarge();
        }
    }
}
