package vn.yourhrm.attendance.internal.application.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * The .xlsx layout of the T&amp;A reports (Apache POI, as Core / People): a centred title block, bordered header
 * row(s), data rows, optional footer / legend lines and the VN signature block (Người lập / Trưởng bộ phận / Giám đốc)
 * under the table. Dates are dd/MM/yyyy text; numbers stay numeric. Landscape A4, fit to width.
 */
final class ReportWorkbook {

    static final String MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * @param titleLines  first line = title (bold, large), the others = sub-titles; all centred over the table
     * @param headerRows  one or more header rows (all of the same width)
     * @param footerLines plain lines under the table (legend, notes)
     * @param signatures  labels of the signature cells (empty: no block); {@code hints} the line under each
     */
    record Sheet(String name, List<String> titleLines, List<List<String>> headerRows, List<List<Object>> rows,
            List<Object> totalRow, List<String> footerLines, List<String> signatures, List<String> hints,
            String dateLine, int firstNarrowColumn, int narrowColumns) {
    }

    private ReportWorkbook() {
    }

    static byte[] write(Sheet s) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet(s.name());
            int width = s.headerRows().isEmpty() ? 1 : s.headerRows().getFirst().size();
            Styles st = new Styles(wb);
            int r = 0;
            for (int i = 0; i < s.titleLines().size(); i++) {
                Row row = sheet.createRow(r);
                Cell c = row.createCell(0);
                c.setCellValue(s.titleLines().get(i));
                c.setCellStyle(i == 0 ? st.title : st.subtitle);
                if (width > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(r, r, 0, width - 1));
                }
                r++;
            }
            r++;
            for (List<String> header : s.headerRows()) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < header.size(); i++) {
                    Cell c = row.createCell(i);
                    c.setCellValue(header.get(i) == null ? "" : header.get(i));
                    c.setCellStyle(st.header);
                }
            }
            for (List<Object> values : s.rows()) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < width; i++) {
                    Cell c = row.createCell(i);
                    set(c, i < values.size() ? values.get(i) : null);
                    c.setCellStyle(i < values.size() && values.get(i) instanceof Number ? st.number : st.text);
                }
            }
            if (s.totalRow() != null && !s.totalRow().isEmpty()) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < width; i++) {
                    Cell c = row.createCell(i);
                    set(c, i < s.totalRow().size() ? s.totalRow().get(i) : null);
                    c.setCellStyle(st.total);
                }
            }
            r++;
            for (String line : s.footerLines()) {
                sheet.createRow(r++).createCell(0).setCellValue(line);
            }
            if (!s.signatures().isEmpty()) {
                r = signatures(sheet, st, r + 1, width, s);
            }
            for (int i = 0; i < width; i++) {
                boolean narrow = i >= s.firstNarrowColumn() && i < s.firstNarrowColumn() + s.narrowColumns();
                int chars = narrow ? 5 : headerChars(s.headerRows(), i);
                sheet.setColumnWidth(i, chars * 256);
            }
            sheet.getPrintSetup().setLandscape(true);
            sheet.getPrintSetup().setPaperSize(PrintSetup.A4_PAPERSIZE);
            sheet.setFitToPage(true);
            sheet.getPrintSetup().setFitWidth((short) 1);
            sheet.getPrintSetup().setFitHeight((short) 0);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Signature cells spread evenly over the table width: label (bold), hint (italic), room to sign. */
    private static int signatures(org.apache.poi.ss.usermodel.Sheet sheet, Styles st, int r, int width, Sheet s) {
        int n = s.signatures().size();
        int span = Math.max(1, width / n);
        if (s.dateLine() != null) {
            Row row = sheet.createRow(r);
            int start = (n - 1) * span;
            Cell c = row.createCell(start);
            c.setCellValue(s.dateLine());
            c.setCellStyle(st.hint);
            int end = Math.max(start, width - 1);
            if (end > start) {
                sheet.addMergedRegion(new CellRangeAddress(r, r, start, end));
            }
            r++;
        }
        Row labels = sheet.createRow(r);
        Row hints = sheet.createRow(r + 1);
        for (int i = 0; i < n; i++) {
            int start = i * span;
            int end = i == n - 1 ? Math.max(start, width - 1) : start + span - 1;
            Cell l = labels.createCell(start);
            l.setCellValue(s.signatures().get(i));
            l.setCellStyle(st.signature);
            Cell h = hints.createCell(start);
            h.setCellValue(i < s.hints().size() ? s.hints().get(i) : "");
            h.setCellStyle(st.hint);
            if (end > start) {
                sheet.addMergedRegion(new CellRangeAddress(r, r, start, end));
                sheet.addMergedRegion(new CellRangeAddress(r + 1, r + 1, start, end));
            }
        }
        return r + 6;   // room for the signatures
    }

    private static int headerChars(List<List<String>> headers, int col) {
        int max = 8;
        for (List<String> h : headers) {
            if (col < h.size() && h.get(col) != null) {
                max = Math.max(max, Math.min(40, h.get(col).length() + 2));
            }
        }
        return max;
    }

    private static void set(Cell c, Object v) {
        switch (v) {
            case null -> c.setBlank();
            case BigDecimal d -> c.setCellValue(d.doubleValue());
            case Number n -> c.setCellValue(n.doubleValue());
            case LocalDate d -> c.setCellValue(VN.format(d));
            case Boolean b -> c.setCellValue(b ? "Có" : "Không");
            default -> c.setCellValue(v.toString());
        }
    }

    static String date(LocalDate d) {
        return d == null ? "" : VN.format(d);
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle subtitle;
        final CellStyle header;
        final CellStyle text;
        final CellStyle number;
        final CellStyle total;
        final CellStyle signature;
        final CellStyle hint;

        Styles(XSSFWorkbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            Font italic = wb.createFont();
            italic.setItalic(true);
            title = centred(wb, big);
            subtitle = centred(wb, null);
            header = bordered(wb, bold);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setWrapText(true);
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            text = bordered(wb, null);
            number = bordered(wb, null);
            number.setDataFormat(wb.createDataFormat().getFormat("#,##0.##"));
            total = bordered(wb, bold);
            total.setDataFormat(wb.createDataFormat().getFormat("#,##0.##"));
            signature = centred(wb, bold);
            hint = centred(wb, italic);
        }

        private static CellStyle centred(XSSFWorkbook wb, Font f) {
            CellStyle s = wb.createCellStyle();
            s.setAlignment(HorizontalAlignment.CENTER);
            if (f != null) {
                s.setFont(f);
            }
            return s;
        }

        private static CellStyle bordered(XSSFWorkbook wb, Font f) {
            CellStyle s = wb.createCellStyle();
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
            s.setVerticalAlignment(VerticalAlignment.CENTER);
            if (f != null) {
                s.setFont(f);
            }
            return s;
        }
    }
}
