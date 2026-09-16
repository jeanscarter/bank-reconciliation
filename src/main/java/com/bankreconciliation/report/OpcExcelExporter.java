package com.bankreconciliation.report;

import com.bankreconciliation.model.Transaction;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class OpcExcelExporter {

    private static final String CURRENCY_FORMAT = "#,##0.00";

    public static void export(List<Transaction> bookTransactions, List<Transaction> bankTransactions, String bankName, String filePath) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Procesos OPC");

            // Styles
            CellStyle headerStyle = createHeaderStyle(workbook);
            CellStyle textStyle = createTextStyle(workbook);
            CellStyle currencyStyle = createCurrencyStyle(workbook);
            CellStyle titleStyle = createTitleStyle(workbook);
            CellStyle subHeaderStyle = createSubHeaderStyle(workbook);

            // Filter OPC
            List<Transaction> opcBook = bookTransactions.stream()
                    .filter(t -> t.getStatus() == Transaction.Status.OPC)
                    .collect(Collectors.toList());
            List<Transaction> opcBank = bankTransactions.stream()
                    .filter(t -> t.getStatus() == Transaction.Status.OPC)
                    .collect(Collectors.toList());

            // Title
            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            String titleStr = "REPORTE DE OPERACIONES CONCILIADAS PERFECTAMENTE (OPC)";
            if (bankName != null && !bankName.equals("Desconocido")) {
                titleStr += " - " + bankName.toUpperCase();
            }
            titleCell.setCellValue(titleStr);
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));

            int rowNum = 2;

            rowNum = addTransactionTable(sheet, rowNum, "LIBRO DE BANCO", opcBook, headerStyle, textStyle, currencyStyle, subHeaderStyle);
            rowNum += 2;
            rowNum = addTransactionTable(sheet, rowNum, "ESTADO DE CUENTA BANCARIO", opcBank, headerStyle, textStyle, currencyStyle, subHeaderStyle);

            // Auto-size columns
            sheet.setColumnWidth(0, 4000); // Op / Ref
            sheet.setColumnWidth(1, 3000); // Date
            sheet.setColumnWidth(2, 10000); // Desc
            sheet.setColumnWidth(3, 4000); // Deposit
            sheet.setColumnWidth(4, 4000); // Withdrawal

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                workbook.write(fos);
            }
        }
    }

    private static int addTransactionTable(Sheet sheet, int rowNum, String title, List<Transaction> list,
            CellStyle headerStyle, CellStyle textStyle, CellStyle currencyStyle, CellStyle subHeaderStyle) {
        
        Row titleRow = sheet.createRow(rowNum++);
        createCell(titleRow, 0, title, subHeaderStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, 4));

        Row tableHead = sheet.createRow(rowNum++);
        createCell(tableHead, 0, "REFERENCIA", headerStyle);
        createCell(tableHead, 1, "FECHA", headerStyle);
        createCell(tableHead, 2, "DESCRIPCIÓN", headerStyle);
        createCell(tableHead, 3, "DEBE/DEPÓSITO", headerStyle);
        createCell(tableHead, 4, "HABER/RETIRO", headerStyle);

        if (list.isEmpty()) {
            Row r = sheet.createRow(rowNum++);
            createCell(r, 0, "No hay transacciones OPC en esta sección.", textStyle);
            sheet.addMergedRegion(new CellRangeAddress(rowNum - 1, rowNum - 1, 0, 4));
            return rowNum;
        }

        for (Transaction t : list) {
            Row r = sheet.createRow(rowNum++);
            createCell(r, 0, t.getReference(), textStyle);
            createCell(r, 1, t.getDate() != null ? t.getDate().toString() : "", textStyle);
            createCell(r, 2, t.getDescription(), textStyle);
            if (t.getDeposit() > 0) {
                createCell(r, 3, t.getDeposit(), currencyStyle);
            }
            if (t.getWithdrawal() > 0) {
                createCell(r, 4, t.getWithdrawal(), currencyStyle);
            }
        }
        return rowNum;
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

    private static CellStyle createTitleStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 14);
        font.setColor(IndexedColors.DARK_GREEN.getIndex());
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
        style.setFillForegroundColor(IndexedColors.SEA_GREEN.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setBorderBottom(BorderStyle.THIN);
        return style;
    }

    private static CellStyle createSubHeaderStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.SEA_GREEN.getIndex());
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
}
