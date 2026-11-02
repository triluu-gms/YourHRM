package vn.yourhrm.core.internal.infra;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import vn.yourhrm.core.api.ImportType.Column;
import vn.yourhrm.core.api.ImportType.ColumnType;
import vn.yourhrm.core.api.ImportType.ControlCheck;
import vn.yourhrm.core.api.ImportType.Definition;

/**
 * The Excel files of the import engine: generated templates (BR-CORE-112, US-CORE-115: VI/EN headers, type hints,
 * dropdowns from code lists with long lists on a hidden sheet, an instructions sheet and a hidden version stamp), the
 * error file (US-CORE-114: the user's own columns plus status and messages) and the opening-balance reconciliation
 * report with sign-off fields (US-CORE-117).
 */
@Component
public class ImportWorkbooks {

    public static final String DATA = "DATA";
    public static final String CONTROL = "CONTROL";
    public static final String META = "_meta";
    public static final String LISTS = "_lists";
    public static final String STAMP = "YHRM_IMPORT";
    /** The engine's standard optional column: the source system's id of the row (BR-CORE-114). */
    public static final String EXTERNAL_REF = "external_ref";
    private static final int VALIDATION_ROWS = 50_000;

    public record ErrorRow(int rowNo, List<String> cells, String status, String messages) {
    }

    public record ReconciliationLine(String checkCode, String checkLabel, String grainKey, BigDecimal control,
            BigDecimal staged, BigDecimal committed, BigDecimal tolerance, String status, String waiverReason) {
    }

    /** @param dropdowns column key -> entries ("CODE - Name") for CODE columns */
    public byte[] template(Definition d, boolean english, Map<String, List<String>> dropdowns) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle header = headerStyle(wb);
            CellStyle text = wb.createCellStyle();
            text.setDataFormat(wb.createDataFormat().getFormat("@"));
            CellStyle date = wb.createCellStyle();
            date.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy"));
            Sheet data = wb.createSheet(DATA);
            Sheet lists = null;
            Drawing<?> drawing = data.createDrawingPatriarch();
            List<Column> columns = withExternalRef(d.columns());
            Row h = data.createRow(0);
            int listCol = 0;
            for (int i = 0; i < columns.size(); i++) {
                Column c = columns.get(i);
                Cell cell = h.createCell(i);
                cell.setCellValue(header(c, english) + (c.required() ? " *" : ""));
                cell.setCellStyle(header);
                ClientAnchor anchor = wb.getCreationHelper().createClientAnchor();
                anchor.setCol1(i);
                anchor.setCol2(i + 3);
                anchor.setRow1(0);
                anchor.setRow2(4);
                Comment comment = drawing.createCellComment(anchor);
                comment.setString(wb.getCreationHelper().createRichTextString(hint(c, english)));
                cell.setCellComment(comment);
                data.setDefaultColumnStyle(i, c.type() == ColumnType.DATE ? date
                        : c.type() == ColumnType.INTEGER || c.type() == ColumnType.DECIMAL ? wb.createCellStyle() : text);
                data.setColumnWidth(i, 24 * 256);
                List<String> entries = dropdowns.getOrDefault(c.key(), List.of());
                if (!entries.isEmpty()) {
                    if (lists == null) {
                        lists = wb.createSheet(LISTS);
                    }
                    for (int r = 0; r < entries.size(); r++) {
                        Row lr = lists.getRow(r) == null ? lists.createRow(r) : lists.getRow(r);
                        lr.createCell(listCol).setCellValue(entries.get(r));
                    }
                    String colLetter = org.apache.poi.ss.util.CellReference.convertNumToColString(listCol);
                    DataValidationHelper helper = data.getDataValidationHelper();
                    DataValidationConstraint constraint = helper.createFormulaListConstraint(
                            "'" + LISTS + "'!$" + colLetter + "$1:$" + colLetter + "$" + entries.size());
                    DataValidation v = helper.createValidation(constraint,
                            new CellRangeAddressList(1, VALIDATION_ROWS, i, i));
                    // a name typed instead of a list entry is accepted too (lookup by code or name)
                    v.setErrorStyle(DataValidation.ErrorStyle.WARNING);
                    v.setShowErrorBox(true);
                    data.addValidationData(v);
                    listCol++;
                }
            }
            data.createFreezePane(0, 1);
            if (d.openingBalance() && !d.controlChecks().isEmpty()) {
                Sheet control = wb.createSheet(CONTROL);
                Row ch = control.createRow(0);
                String[] heads = english ? new String[] {"check_code", "grain_key", "control_value", "Check"}
                        : new String[] {"check_code", "grain_key", "control_value", "Kiểm tra"};
                for (int i = 0; i < heads.length; i++) {
                    Cell cell = ch.createCell(i);
                    cell.setCellValue(heads[i]);
                    cell.setCellStyle(header);
                    control.setColumnWidth(i, 26 * 256);
                }
                int r = 1;
                for (ControlCheck cc : d.controlChecks()) {
                    Row row = control.createRow(r++);
                    row.createCell(0).setCellValue(cc.code());
                    row.createCell(1).setCellValue("ALL");
                    row.createCell(3).setCellValue(english ? cc.labelEn() : cc.labelVi());
                }
            }
            instructions(wb, d, columns, english, header);
            if (lists != null) {
                wb.setSheetVisibility(wb.getSheetIndex(lists), SheetVisibility.HIDDEN);
            }
            Sheet meta = wb.createSheet(META);
            Row m0 = meta.createRow(0);
            m0.createCell(0).setCellValue(STAMP);
            m0.createCell(1).setCellValue(d.code());
            m0.createCell(2).setCellValue(d.templateVersion());
            Row m1 = meta.createRow(1);
            for (int i = 0; i < columns.size(); i++) {
                m1.createCell(i).setCellValue(columns.get(i).key());
            }
            wb.setSheetVisibility(wb.getSheetIndex(meta), SheetVisibility.VERY_HIDDEN);
            wb.setActiveSheet(0);
            return write(wb);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void instructions(Workbook wb, Definition d, List<Column> columns, boolean english, CellStyle header) {
        Sheet s = wb.createSheet(english ? "INSTRUCTIONS" : "HUONG_DAN");
        int r = 0;
        s.createRow(r++).createCell(0).setCellValue((english ? d.nameEn() : d.nameVi()) + " (" + d.code() + ", v"
                + d.templateVersion() + ")");
        s.createRow(r++).createCell(0).setCellValue(english
                ? "Fill the DATA sheet from row 2. Columns marked * are required. Keep the columns and their order; "
                        + "do not rename the sheets. Lists accept a code or a name. Dates: DD/MM/YYYY."
                : "Điền dữ liệu vào sheet DATA từ dòng 2. Cột có dấu * là bắt buộc. Giữ nguyên các cột và thứ tự; "
                        + "không đổi tên sheet. Cột danh mục nhận mã hoặc tên. Ngày: DD/MM/YYYY.");
        r++;
        Row h = s.createRow(r++);
        String[] heads = english ? new String[] {"Column", "Required", "Type", "Notes"}
                : new String[] {"Cột", "Bắt buộc", "Kiểu", "Ghi chú"};
        for (int i = 0; i < heads.length; i++) {
            Cell c = h.createCell(i);
            c.setCellValue(heads[i]);
            c.setCellStyle(header);
            s.setColumnWidth(i, (i == 3 ? 70 : 28) * 256);
        }
        for (Column c : columns) {
            Row row = s.createRow(r++);
            row.createCell(0).setCellValue(header(c, english));
            row.createCell(1).setCellValue(c.required() ? (english ? "Yes" : "Có") : (english ? "No" : "Không"));
            row.createCell(2).setCellValue(c.type().name());
            row.createCell(3).setCellValue(hint(c, english));
        }
    }

    /** The user's own columns and order, then the row's status and messages (US-CORE-114). */
    public byte[] errorFile(List<String> headers, List<ErrorRow> rows, boolean english) {
        try (SXSSFWorkbook wb = new SXSSFWorkbook(500)) {
            CellStyle header = headerStyle(wb);
            CellStyle error = wb.createCellStyle();
            error.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            error.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Sheet s = wb.createSheet(DATA);
            Row h = s.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                Cell c = h.createCell(i);
                c.setCellValue(headers.get(i));
                c.setCellStyle(header);
            }
            String[] extra = english ? new String[] {"Source row", "Status", "Errors / warnings"}
                    : new String[] {"Dòng gốc", "Trạng thái", "Lỗi / cảnh báo"};
            for (int i = 0; i < extra.length; i++) {
                Cell c = h.createCell(headers.size() + i);
                c.setCellValue(extra[i]);
                c.setCellStyle(header);
            }
            int r = 1;
            for (ErrorRow e : rows) {
                Row row = s.createRow(r++);
                for (int i = 0; i < e.cells().size(); i++) {
                    row.createCell(i).setCellValue(e.cells().get(i));
                }
                row.createCell(headers.size()).setCellValue(e.rowNo());
                row.createCell(headers.size() + 1).setCellValue(e.status());
                Cell m = row.createCell(headers.size() + 2);
                m.setCellValue(e.messages());
                if ("ERROR".equals(e.status())) {
                    m.setCellStyle(error);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Header lines (label, value), the checks with mismatches highlighted, and the sign-off block. */
    public byte[] reconciliationReport(List<String[]> headerLines, List<ReconciliationLine> lines, boolean english) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            CellStyle header = headerStyle(wb);
            CellStyle mismatch = wb.createCellStyle();
            mismatch.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            mismatch.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Sheet s = wb.createSheet(english ? "RECONCILIATION" : "DOI_CHIEU");
            int r = 0;
            s.createRow(r++).createCell(0).setCellValue(english ? "Opening balance reconciliation report"
                    : "Báo cáo đối chiếu số dư đầu kỳ");
            for (String[] l : headerLines) {
                Row row = s.createRow(r++);
                row.createCell(0).setCellValue(l[0]);
                row.createCell(1).setCellValue(l[1]);
            }
            r++;
            String[] heads = english
                    ? new String[] {"Check", "Description", "Grain", "Control", "Staged", "Committed", "Difference",
                        "Tolerance", "Status", "Waiver reason"}
                    : new String[] {"Mã kiểm tra", "Mô tả", "Chi tiết", "Giá trị kiểm soát", "Giá trị staging",
                        "Giá trị đã ghi", "Chênh lệch", "Dung sai", "Trạng thái", "Lý do miễn trừ"};
            Row h = s.createRow(r++);
            for (int i = 0; i < heads.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(heads[i]);
                c.setCellStyle(header);
                s.setColumnWidth(i, 20 * 256);
            }
            for (ReconciliationLine l : lines) {
                Row row = s.createRow(r++);
                row.createCell(0).setCellValue(l.checkCode());
                row.createCell(1).setCellValue(l.checkLabel() == null ? "" : l.checkLabel());
                row.createCell(2).setCellValue(l.grainKey());
                number(row, 3, l.control());
                number(row, 4, l.staged());
                number(row, 5, l.committed());
                number(row, 6, l.control() == null || l.staged() == null ? null : l.staged().subtract(l.control()));
                number(row, 7, l.tolerance());
                Cell st = row.createCell(8);
                st.setCellValue(l.status());
                if ("MISMATCH".equals(l.status())) {
                    for (int i = 0; i <= 8; i++) {
                        row.getCell(i, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellStyle(mismatch);
                    }
                }
                row.createCell(9).setCellValue(l.waiverReason() == null ? "" : l.waiverReason());
            }
            r += 2;
            String[] sign = english ? new String[] {"Prepared by (implementation consultant)", "Signed off by (customer)",
                "Date"} : new String[] {"Người lập (tư vấn triển khai)", "Người ký xác nhận (khách hàng)", "Ngày"};
            Row signRow = s.createRow(r++);
            for (int i = 0; i < sign.length; i++) {
                Cell c = signRow.createCell(i * 3);
                c.setCellValue(sign[i]);
                c.setCellStyle(header);
            }
            Row lineRow = s.createRow(r + 3);
            for (int i = 0; i < sign.length; i++) {
                lineRow.createCell(i * 3).setCellValue("______________________");
            }
            return write(wb);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Column> withExternalRef(List<Column> columns) {
        java.util.ArrayList<Column> out = new java.util.ArrayList<>(columns);
        out.add(new Column(EXTERNAL_REF, "Mã tham chiếu ngoài", "External reference", ColumnType.TEXT, false, null,
                false, "Mã của dòng trong hệ thống cũ, để truy vết", "The row's id in the source system, for tracing"));
        return out;
    }

    private static String header(Column c, boolean english) {
        String h = english ? c.headerEn() : c.headerVi();
        return h == null ? c.key() : h;
    }

    private static String hint(Column c, boolean english) {
        String type = switch (c.type()) {
            case TEXT -> english ? "Text" : "Văn bản";
            case INTEGER -> english ? "Whole number" : "Số nguyên";
            case DECIMAL -> english ? "Number (decimal point '.')" : "Số (dấu thập phân '.')";
            case DATE -> english ? "Date DD/MM/YYYY" : "Ngày DD/MM/YYYY";
            case BOOLEAN -> english ? "Yes / No" : "Có / Không";
            case CODE -> english ? "Code or name from the list" : "Mã hoặc tên trong danh mục";
        };
        String extra = english ? c.hintEn() : c.hintVi();
        return type + (c.required() ? (english ? ", required" : ", bắt buộc") : "")
                + (extra == null ? "" : ". " + extra);
    }

    private static CellStyle headerStyle(Workbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setBorderBottom(BorderStyle.THIN);
        return s;
    }

    private static void number(Row row, int col, BigDecimal v) {
        Cell c = row.createCell(col);
        if (v != null) {
            c.setCellValue(v.doubleValue());
        }
    }

    private static byte[] write(Workbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }
}
