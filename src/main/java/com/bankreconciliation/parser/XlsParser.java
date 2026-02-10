package com.bankreconciliation.parser;

import com.bankreconciliation.model.Transaction;
import org.apache.poi.ss.usermodel.*;

import java.io.File;
import java.io.FileInputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Parses .xls and .xlsx files using Apache POI.
 */
public class XlsParser implements FileParser {

    @Override
    public List<Transaction> parse(File file, Transaction.Source source) throws Exception {
        List<Transaction> transactions = new ArrayList<>();

        try (FileInputStream fis = new FileInputStream(file);
                Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            int headerRowIdx = -1;
            Map<Integer, ColumnMapper.Column> columnMap = null;

            // Scan first 15 rows for header
            for (int i = 0; i <= Math.min(15, sheet.getLastRowNum()); i++) {
                Row row = sheet.getRow(i);
                if (row == null)
                    continue;
                String[] cells = rowToStringArray(row);
                if (ColumnMapper.isValidHeaderRow(cells)) {
                    headerRowIdx = i;
                    columnMap = ColumnMapper.mapHeaders(cells);
                    break;
                }
            }

            if (headerRowIdx < 0 || columnMap == null) {
                throw new Exception("No se encontró una fila de encabezado válida en el archivo Excel.");
            }

            // Parse data rows
            for (int i = headerRowIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null)
                    continue;

                Transaction t = parseRow(row, columnMap, source);
                if (t != null) {
                    transactions.add(t);
                }
            }
        }
        return transactions;
    }

    @Override
    public double extractSaldoInicial(File file) {
        try (FileInputStream fis = new FileInputStream(file);
                Workbook workbook = WorkbookFactory.create(fis)) {

            Sheet sheet = workbook.getSheetAt(0);
            // Scan first 15 rows for "Saldo Inicial"
            for (int i = 0; i <= Math.min(15, sheet.getLastRowNum()); i++) {
                Row row = sheet.getRow(i);
                if (row == null)
                    continue;
                StringBuilder sb = new StringBuilder();
                for (Cell cell : row) {
                    sb.append(getCellAsString(cell)).append(" ");
                }
                Double saldo = ColumnMapper.extractSaldoInicial(sb.toString());
                if (saldo != null)
                    return saldo;
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    private Transaction parseRow(Row row, Map<Integer, ColumnMapper.Column> columnMap, Transaction.Source source) {
        LocalDate date = null;
        String reference = "";
        String description = "";
        double deposit = 0;
        double withdrawal = 0;

        for (Map.Entry<Integer, ColumnMapper.Column> entry : columnMap.entrySet()) {
            int idx = entry.getKey();
            Cell cell = row.getCell(idx);
            if (cell == null)
                continue;

            switch (entry.getValue()) {
                case FECHA -> date = parseDateCell(cell);
                case REFERENCIA -> reference = getCellAsString(cell);
                case DESCRIPCION -> description = getCellAsString(cell);
                case DEPOSITO -> deposit = getNumericValue(cell);
                case RETIRO -> withdrawal = getNumericValue(cell);
                default -> {
                }
            }
        }

        if (date == null)
            return null; // Skip rows without valid date
        if (deposit == 0 && withdrawal == 0)
            return null; // Skip empty amount rows

        return new Transaction(date, reference, description, deposit, withdrawal, source);
    }

    private LocalDate parseDateCell(Cell cell) {
        if (cell == null)
            return null;
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                Date d = cell.getDateCellValue();
                return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            // Try parsing as string
            String s = getCellAsString(cell);
            return parseDate(s);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank())
            return null;
        s = s.trim();
        // Try DD/MM/YYYY
        String[] parts = s.split("[/\\-.]");
        if (parts.length == 3) {
            try {
                int a = Integer.parseInt(parts[0].trim());
                int b = Integer.parseInt(parts[1].trim());
                int c = Integer.parseInt(parts[2].trim());
                // DD/MM/YYYY
                if (c > 100)
                    return LocalDate.of(c, b, a);
                // YYYY/MM/DD
                if (a > 100)
                    return LocalDate.of(a, b, c);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private double getNumericValue(Cell cell) {
        if (cell == null)
            return 0;
        try {
            if (cell.getCellType() == CellType.NUMERIC) {
                return Math.abs(cell.getNumericCellValue());
            }
            String s = getCellAsString(cell);
            return Math.abs(ColumnMapper.parseAmount(s));
        } catch (Exception e) {
            return 0;
        }
    }

    private String getCellAsString(Cell cell) {
        if (cell == null)
            return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getDateCellValue().toString();
                }
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    try {
                        yield cell.getStringCellValue();
                    } catch (Exception e2) {
                        yield "";
                    }
                }
            }
            default -> "";
        };
    }

    private String[] rowToStringArray(Row row) {
        int lastCol = row.getLastCellNum();
        String[] result = new String[lastCol < 0 ? 0 : lastCol];
        for (int i = 0; i < result.length; i++) {
            result[i] = getCellAsString(row.getCell(i));
        }
        return result;
    }
}
