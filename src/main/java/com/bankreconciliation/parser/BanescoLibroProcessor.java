package com.bankreconciliation.parser;

import com.bankreconciliation.model.Transaction;
import org.apache.poi.ss.usermodel.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Procesador de Libros Banesco (Excel).
 * <p>
 * Usa detección dinámica de cabeceras para encontrar columnas:
 * FECHA, REFERENCIA, DESCRIPCION, MONTO (o DEBE/HABER, CARGO/ABONO).
 */
public class BanescoLibroProcessor implements FileParser {

    private enum ColType {
        DATE, REF, DESC, AMOUNT, DEBIT, CREDIT, UNKNOWN
    }

    @Override
    public List<Transaction> parse(File file, Transaction.Source source) throws Exception {
        List<Transaction> transactions = new ArrayList<>();
        File debugFile = new File("debug_banesco_xls_parsed.txt");

        try (FileInputStream fis = new FileInputStream(file);
                Workbook workbook = WorkbookFactory.create(fis);
                PrintWriter log = new PrintWriter(debugFile)) {

            Sheet sheet = workbook.getSheetAt(0);
            Map<ColType, Integer> colMap = new HashMap<>();
            int headerRowIdx = -1;

            // 1. Find Header Row
            for (int r = 0; r <= Math.min(20, sheet.getLastRowNum()); r++) {
                Row row = sheet.getRow(r);
                if (row == null)
                    continue;

                colMap.clear();
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    String val = getCellAsString(row.getCell(c)).toUpperCase().trim();
                    if (val.contains("FECHA") && !val.contains("IMP"))
                        colMap.put(ColType.DATE, c);
                    else if (val.contains("REF") || val.contains("DOC"))
                        colMap.put(ColType.REF, c);
                    else if (val.contains("DESC") || val.contains("CONCEPTO") || val.contains("DETALLE"))
                        colMap.put(ColType.DESC, c);
                    else if ((val.contains("MONTO") || val.contains("IMPORTE")) && !colMap.containsKey(ColType.AMOUNT))
                        colMap.put(ColType.AMOUNT, c);
                    else if (val.equals("DEBE") || val.contains("CARGO") || val.contains("RETIRO")
                            || val.contains("DEBITO"))
                        colMap.put(ColType.DEBIT, c);
                    else if (val.equals("HABER") || val.contains("ABONO") || val.contains("DEPOSITO")
                            || val.contains("CREDITO"))
                        colMap.put(ColType.CREDIT, c);
                }

                // Check if we found critical columns
                if (colMap.containsKey(ColType.DATE) &&
                        (colMap.containsKey(ColType.AMOUNT)
                                || (colMap.containsKey(ColType.DEBIT) && colMap.containsKey(ColType.CREDIT)))) {
                    headerRowIdx = r;
                    log.println("Headers found at row " + r + ": " + colMap);
                    break;
                }
            }

            if (headerRowIdx == -1) {
                log.println("ERROR: Could not find header row. Probing content for fallback...");
                // Fallback: Assume standard Banesco columns if headers missing?
                // Usually: Date(0), Ref(1), Desc(2), Debit(3), Credit(4)
                colMap.put(ColType.DATE, 0);
                colMap.put(ColType.REF, 1);
                colMap.put(ColType.DESC, 2);
                colMap.put(ColType.DEBIT, 3);
                colMap.put(ColType.CREDIT, 4);
                headerRowIdx = 0; // Assume row 0 is headers or data starts at 1
            }

            // 2. Parse Data
            for (int r = headerRowIdx + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null)
                    continue;

                try {
                    // Extract Date
                    LocalDate date = null;
                    if (colMap.containsKey(ColType.DATE)) {
                        Cell dateCell = row.getCell(colMap.get(ColType.DATE));
                        if (DateUtil.isCellDateFormatted(dateCell)) {
                            date = dateCell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                        } else {
                            // Try string parse?? Banesco often exports real dates
                            continue; // Skip invalid dates
                        }
                    }

                    // Extract Reference
                    String reference = "S/N";
                    if (colMap.containsKey(ColType.REF)) {
                        reference = getCellAsString(row.getCell(colMap.get(ColType.REF)));
                    }

                    // Extract Description
                    String description = "";
                    if (colMap.containsKey(ColType.DESC)) {
                        description = getCellAsString(row.getCell(colMap.get(ColType.DESC)));
                    }

                    // Extract Amounts
                    double deposit = 0.0;
                    double withdrawal = 0.0;

                    if (colMap.containsKey(ColType.DEBIT) && colMap.containsKey(ColType.CREDIT)) {
                        withdrawal = getNumericValue(row.getCell(colMap.get(ColType.DEBIT)));
                        deposit = getNumericValue(row.getCell(colMap.get(ColType.CREDIT)));
                    } else if (colMap.containsKey(ColType.AMOUNT)) {
                        double net = getNumericValue(row.getCell(colMap.get(ColType.AMOUNT)));
                        if (net < 0)
                            withdrawal = Math.abs(net);
                        else
                            deposit = net;
                    }

                    if (date != null && (deposit != 0 || withdrawal != 0)) {
                        transactions.add(new Transaction(date, reference, description, deposit, withdrawal, source));
                    }

                } catch (Exception e) {
                    log.println("Error reading row " + r + ": " + e.getMessage());
                }
            }
        }

        return transactions;
    }

    @Override
    public double extractSaldoInicial(File file) {
        return 0.0;
    }

    private String getCellAsString(Cell cell) {
        if (cell == null)
            return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf(cell.getNumericCellValue());
            default -> "";
        };
    }

    private double getNumericValue(Cell cell) {
        if (cell == null)
            return 0.0;
        if (cell.getCellType() == CellType.NUMERIC)
            return cell.getNumericCellValue();
        if (cell.getCellType() == CellType.STRING) {
            try {
                return Double.parseDouble(cell.getStringCellValue().replace(",", "").trim());
            } catch (Exception e) {
                return 0.0;
            }
        }
        return 0.0;
    }

    public static boolean isBanescoFormat(File file) {
        String name = file.getName().toUpperCase();
        return name.contains("BANESCO") && (name.endsWith(".XLS") || name.endsWith(".XLSX"));
    }
}
