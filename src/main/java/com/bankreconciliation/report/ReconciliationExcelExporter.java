package com.bankreconciliation.report;

import com.bankreconciliation.report.ReconciliationReportGenerator.ReportData;
import com.bankreconciliation.report.ReconciliationReportGenerator.RecurringCharge;
import com.bankreconciliation.model.Transaction;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class ReconciliationExcelExporter {

    private static final String CURRENCY_FORMAT = "#,##0.00";

    public static void export(ReportData data, String filePath) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Conciliación Bancaria");

            // Styles
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle subHeaderStyle = createSubHeaderStyle(workbook);
            CellStyle currencyStyle = createCurrencyStyle(workbook);
            CellStyle boldCurrencyStyle = createBoldCurrencyStyle(workbook);
            CellStyle textStyle = createTextStyle(workbook);
            CellStyle titleStyle = createTitleStyle(workbook);
            CellStyle redCurrencyStyle = createRedCurrencyStyle(workbook);

            // ── Title ──
            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("REPORTE DE CONCILIACIÓN BANCARIA");
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));

            int rowNum = 2;

            // ── Summary Section (Columns A-B for Book, D-E for Bank) ──
            Row headerRow = sheet.createRow(rowNum++);
            createCell(headerRow, 0, "LIBRO CONTABLE BANCOS", subHeaderStyle);
            createCell(headerRow, 3, "EXTRACTO BANCARIO", subHeaderStyle);

            // Initial Balance
            Row r1 = sheet.createRow(rowNum++);
            createCell(r1, 0, "Saldo Inicial", textStyle);
            createCell(r1, 1, data.libroSaldoInicial, currencyStyle);
            createCell(r1, 3, "Saldo Inicial", textStyle);
            createCell(r1, 4, data.bancoSaldoInicial, currencyStyle);

            // Debits
            Row r2 = sheet.createRow(rowNum++);
            createCell(r2, 0, "Total Debe", textStyle);
            createCell(r2, 1, data.libroTotalDebe, currencyStyle);
            createCell(r2, 3, "Total Depósitos", textStyle);
            createCell(r2, 4, data.bancoTotalDebe, currencyStyle);

            // Credits
            Row r3 = sheet.createRow(rowNum++);
            createCell(r3, 0, "Total Haber", textStyle);
            createCell(r3, 1, data.libroTotalHaber, currencyStyle);
            createCell(r3, 3, "Total Retiros", textStyle);
            createCell(r3, 4, data.bancoTotalHaber, currencyStyle);

            // Final Balance
            Row r4 = sheet.createRow(rowNum++);
            createCell(r4, 0, "SALDO FINAL", subHeaderStyle);
            createCell(r4, 1, data.libroSaldoFinal, boldCurrencyStyle);
            createCell(r4, 3, "SALDO FINAL", subHeaderStyle);
            createCell(r4, 4, data.bancoSaldoFinal, boldCurrencyStyle);

            rowNum += 2;

            // ── Differences Section ──
            Row diffHeader = sheet.createRow(rowNum++);
            createCell(diffHeader, 0, "ANÁLISIS DE DIFERENCIAS", subHeaderStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, 4));

            addDiffRow(sheet, rowNum++, "Diferencia Libro Bancos - Extracto Bancario", data.diferencia,
                    boldCurrencyStyle);
            addDiffRow(sheet, rowNum++, "(-) DNA - Depósito no abonado en cuenta bancaria", -data.totalDNA,
                    currencyStyle);
            addDiffRow(sheet, rowNum++, "(+) RNE - Retiro no efectuado en CB o cheque no cobrado", data.totalRNE,
                    currencyStyle);
            addDiffRow(sheet, rowNum++, "(+) ANR - Abono en CB no registrado en Libro Bancos", data.totalANR,
                    currencyStyle);
            addDiffRow(sheet, rowNum++, "(-) CNR - Cargo en CB no registrado en Libro Bancos", -data.totalCNR,
                    currencyStyle);

            Row saldoRow = sheet.createRow(rowNum++);
            createCell(saldoRow, 0, "SALDO POR CONCILIAR", subHeaderStyle);
            createCell(saldoRow, 4, data.saldoPorConciliar,
                    data.saldoPorConciliar == 0 ? boldCurrencyStyle : redCurrencyStyle);

            rowNum += 2;

            // ── Recurring Charges Section ──
            if (!data.recurringCharges.isEmpty()) {
                Row recHeader = sheet.createRow(rowNum++);
                createCell(recHeader, 0, "RESUMEN DE CARGOS RECURRENTES (CNR)", subHeaderStyle);
                sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, 4));

                for (RecurringCharge rc : data.recurringCharges) {
                    Row rcRow = sheet.createRow(rowNum++);
                    createCell(rcRow, 0, "• " + rc.concept, textStyle);
                    createCell(rcRow, 4, rc.amount, currencyStyle);
                }
                rowNum += 2;
            }

            // ── Details Section ──
            Row detHeader = sheet.createRow(rowNum++);
            createCell(detHeader, 0, "DETALLE DE NO REGISTRADOS", subHeaderStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, 4));

            // Table Header
            Row tableHead = sheet.createRow(rowNum++);
            createCell(tableHead, 0, "NRO OPERACIÓN", headerStyle);
            createCell(tableHead, 1, "FECHA", headerStyle);
            createCell(tableHead, 2, "DESCRIPCIÓN", headerStyle);
            createCell(tableHead, 4, "MONTO", headerStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 2, 3));

            // Transactions
            rowNum = addTransactionGroup(sheet, rowNum, "(-) DNA - Depósito no abonado en cuenta bancaria",
                    data.dnaTransactions, textStyle, currencyStyle, subHeaderStyle);
            rowNum = addTransactionGroup(sheet, rowNum, "(+) RNE - Retiro no efectuado en CB o cheque no cobrado",
                    data.rneTransactions, textStyle, currencyStyle, subHeaderStyle);
            rowNum = addTransactionGroup(sheet, rowNum, "(+) ANR - Abono en CB no registrado en Libro Bancos",
                    data.anrTransactions, textStyle, currencyStyle, subHeaderStyle);
            rowNum = addTransactionGroup(sheet, rowNum, "(-) CNR - Cargo en CB no registrado en Libro Bancos",
                    data.cnrTransactions, textStyle, currencyStyle, subHeaderStyle);

            // Auto-size columns
            sheet.setColumnWidth(0, 4000); // Op
            sheet.setColumnWidth(1, 3000); // Date
            sheet.setColumnWidth(2, 10000); // Desc
            sheet.setColumnWidth(3, 3000); // spacer
            sheet.setColumnWidth(4, 4000); // Amount

            // Write to file
            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
            }
        }
    }

    private static int addTransactionGroup(Sheet sheet, int rowNum, String title, List<Transaction> list,
            CellStyle textStyle, CellStyle currencyStyle, CellStyle groupHeaderStyle) {
        if (list.isEmpty())
            return rowNum;

        Row header = sheet.createRow(rowNum++);
        createCell(header, 0, title, groupHeaderStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, 4));

        for (Transaction t : list) {
            Row r = sheet.createRow(rowNum++);
            createCell(r, 0, t.getReference(), textStyle);
            createCell(r, 1, t.getDate() != null ? t.getDate().toString() : "", textStyle);
            createCell(r, 2, t.getDescription(), textStyle);
            createCell(r, 4, t.getAbsAmount(), currencyStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 2, 3));
        }
        return rowNum + 1; // spacer
    }

    private static void addDiffRow(Sheet sheet, int rowNum, String label, double value, CellStyle valueStyle) {
        Row r = sheet.createRow(rowNum);
        createCell(r, 0, label, null);
        createCell(r, 4, value, valueStyle);
    }

    private static void createCell(Row row, int col, Object value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value instanceof String) {
            cell.setCellValue((String) value);
        } else if (value instanceof Double) {
            cell.setCellValue((Double) value);
        }
        if (style != null) {
            cell.setCellStyle(style);
        }
    }

    // ── Styles ──

    private static CellStyle createTitleStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        font.setColor(IndexedColors.DARK_TEAL.getIndex());
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        return style;
    }

    private static CellStyle createHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.TEAL.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createSubHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.TEAL.getIndex());
        style.setFont(font);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createTextStyle(Workbook wb) {
        return wb.createCellStyle();
    }

    private static CellStyle createCurrencyStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        DataFormat format = wb.createDataFormat();
        style.setDataFormat(format.getFormat(CURRENCY_FORMAT));
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }

    private static CellStyle createBoldCurrencyStyle(Workbook wb) {
        CellStyle style = createCurrencyStyle(wb);
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }

    private static CellStyle createRedCurrencyStyle(Workbook wb) {
        CellStyle style = createCurrencyStyle(wb);
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.RED.getIndex());
        style.setFont(font);
        return style;
    }
}
