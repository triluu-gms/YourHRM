package vn.yourhrm.people.internal.application.report;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * A minimal statutory-layout spreadsheet (Apache POI, .xlsx; Vietnamese text is plain Unicode): title lines, a bold
 * header row and data rows. Dates are written as dd/MM/yyyy text, the statutory display format.
 */
final class ExcelSheet {

    static final String MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private ExcelSheet() {
    }

    static byte[] write(String sheetName, List<String> titleLines, List<String> headers, List<List<Object>> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle boldStyle = wb.createCellStyle();
            boldStyle.setFont(bold);
            int r = 0;
            for (String line : titleLines) {
                Cell c = sheet.createRow(r++).createCell(0);
                c.setCellValue(line);
                c.setCellStyle(boldStyle);
            }
            r++;
            Row header = sheet.createRow(r++);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = header.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(boldStyle);
                sheet.setColumnWidth(i, Math.min(255, Math.max(12, headers.get(i).length() + 4)) * 256);
            }
            for (List<Object> values : rows) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < values.size(); i++) {
                    set(row.createCell(i), values.get(i));
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void set(Cell c, Object v) {
        switch (v) {
            case null -> c.setBlank();
            case Number n when n instanceof BigDecimal d -> c.setCellValue(d.doubleValue());
            case Number n -> c.setCellValue(n.doubleValue());
            case LocalDate d -> c.setCellValue(VN.format(d));
            case Boolean b -> c.setCellValue(b ? "Có" : "Không");
            default -> c.setCellValue(v.toString());
        }
    }

    static String date(LocalDate d) {
        return d == null ? "" : VN.format(d);
    }
}
