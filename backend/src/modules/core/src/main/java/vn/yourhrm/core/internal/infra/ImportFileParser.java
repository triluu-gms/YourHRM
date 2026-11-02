package vn.yourhrm.core.internal.infra;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

/**
 * Streaming reader of import files (BR-CORE-111: at least 50,000 rows): XLSX through POI's SAX event model (the
 * workbook is never loaded as a whole) and CSV (UTF-8, comma or semicolon, RFC 4180 quoting). Cells are the
 * formatted texts as Excel shows them.
 */
@Component
public class ImportFileParser {

    /** Receives the rows of every sheet in order; {@code rowNo} is the 1-based sheet row number. */
    public interface RowSink {
        void row(String sheet, int rowNo, List<String> cells);
    }

    /** A file that cannot be read as the declared format. */
    public static class UnreadableFileException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UnreadableFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** @param sheets the sheets to read (XLSX), null = all; a CSV file is one sheet named DATA */
    public void parse(byte[] file, boolean xlsx, java.util.Set<String> sheets, RowSink sink) {
        if (xlsx) {
            parseXlsx(file, sheets, sink);
        } else {
            parseCsv(file, sink);
        }
    }

    private void parseXlsx(byte[] file, java.util.Set<String> only, RowSink sink) {
        try (OPCPackage pkg = OPCPackage.open(new ByteArrayInputStream(file))) {
            XSSFReader reader = new XSSFReader(pkg);
            ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
            StylesTable styles = reader.getStylesTable();
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            DataFormatter formatter = new DataFormatter(java.util.Locale.ROOT);
            while (sheets.hasNext()) {
                try (InputStream in = sheets.next()) {
                    String sheet = sheets.getSheetName();
                    if (only != null && !only.contains(sheet)) {
                        continue;
                    }
                    XMLReader xml = XMLHelper.newXMLReader();
                    xml.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, new Collector(sheet, sink),
                            formatter, false));
                    xml.parse(new InputSource(in));
                }
            }
        } catch (RuntimeException e) {
            // problems of the workbook itself are POI's; anything else (e.g. a row limit) comes from the sink
            if (e.getClass().getName().startsWith("org.apache.poi")) {
                throw new UnreadableFileException("The file is not a readable Excel workbook (.xlsx)", e);
            }
            throw e;
        } catch (Exception e) {
            throw new UnreadableFileException("The file is not a readable Excel workbook (.xlsx)", e);
        }
    }

    /** Collects one row's cells by column index (empty cells are absent from the XML). */
    private static final class Collector implements SheetContentsHandler {

        private final String sheet;
        private final RowSink sink;
        private final List<String> cells = new ArrayList<>();
        private int rowNo;

        Collector(String sheet, RowSink sink) {
            this.sheet = sheet;
            this.sink = sink;
        }

        @Override
        public void startRow(int rowNum) {
            rowNo = rowNum + 1;
            cells.clear();
        }

        @Override
        public void endRow(int rowNum) {
            sink.row(sheet, rowNo, List.copyOf(cells));
        }

        @Override
        public void cell(String ref, String value, XSSFComment comment) {
            int col = ref == null ? cells.size() : new CellReference(ref).getCol();
            while (cells.size() < col) {
                cells.add("");
            }
            cells.add(value == null ? "" : value);
        }
    }

    private static void parseCsv(byte[] file, RowSink sink) {
        String text = new String(file, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        int firstLineEnd = text.indexOf('\n');
        String firstLine = firstLineEnd < 0 ? text : text.substring(0, firstLineEnd);
        char sep = firstLine.chars().filter(c -> c == ';').count() > firstLine.chars().filter(c -> c == ',').count()
                ? ';' : ',';
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int rowNo = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.isEmpty()) {
                quoted = true;
            } else if (c == sep) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                cells.add(cell.toString());
                cell.setLength(0);
                sink.row("DATA", rowNo++, List.copyOf(cells));
                cells.clear();
            } else {
                cell.append(c);
            }
        }
        if (quoted) {
            throw new UnreadableFileException("Unterminated quoted value in the CSV file", null);
        }
        if (!cell.isEmpty() || !cells.isEmpty()) {
            cells.add(cell.toString());
            sink.row("DATA", rowNo, List.copyOf(cells));
        }
    }
}
