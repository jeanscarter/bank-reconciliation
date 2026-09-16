package com.bankreconciliation.parser;

import com.bankreconciliation.model.Transaction;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;

import java.io.IOException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Procesador de Estados de Cuenta Banesco (PDF).
 * <p>
 * Caracteristicas:
 * - Fechas DD/MM/YYYY
 * - Montos con signo explícito al final de la línea:
 * - "+1.234,56" -> Depósito
 * - "-100,00" -> Retiro
 * - "1.234,56" -> Depósito (asumido)
 */
public class BanescoBankStatementProcessor implements FileParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Regex: Date | Description | Amount | Balance (Optional)
    // Matches: "01/12/2025 DESC 100,00 500,00" or "01/12/2025 DESC 100,00"
    // Group 1: Date
    // Group 2: Description
    // Group 3: Amount (Transaction)
    // Group 4: Balance (Rolling Balance)
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^(\\d{2}/\\d{2}/\\d{4})\\s+(.+?)\\s+([+\\-]?\\d{1,3}(?:[.]\\d{3})*(?:,\\d{2}))(?:\\s+([+\\-]?\\d{1,3}(?:[.]\\d{3})*(?:,\\d{2})))?$");

    // Pattern for day-only format: "DD REF CONCEPTO MONTO SALDO"
    // Group 1: Day (2 digits)
    // Group 2: Reference
    // Group 3: Description
    // Group 4: Amount (Cargo or Abono)
    // Group 5: Saldo (running balance, may be negative)
    private static final Pattern DAY_ONLY_PATTERN = Pattern.compile(
            "^(\\d{2})\\s+(\\d{5,})\\s+(.+?)\\s+(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2})\\s+(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2})$");

    // Pattern for saldo mes anterior line
    private static final Pattern SALDO_MES_ANTERIOR_PATTERN = Pattern.compile(
            "SALDO\\s+MES\\s+ANTERIOR\\s+(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2})");

    // European amount pattern
    private static final Pattern EUR_AMT = Pattern.compile(
            "(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2})");

    @Override
    public double extractSaldoInicial(File file) {
        // Try existing Balance-column method first
        try (PDDocument document = Loader.loadPDF(file)) {
            List<Double> balances = extractBalanceColumn(document);
            if (!balances.isEmpty()) {
                // The first value in the Balance column corresponds to the initial balance
                return balances.get(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Fallback: look for "SALDO MES ANTERIOR" line
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String text = stripper.getText(document);
            // Search raw text and parse European amount
            for (String line : text.split("\\r?\\n")) {
                String upper = line.trim().toUpperCase();
                if (upper.contains("SALDO MES ANTERIOR") || upper.contains("SALDO ANTERIOR")) {
                    Matcher amtM = EUR_AMT.matcher(line);
                    String lastAmt = null;
                    while (amtM.find()) {
                        lastAmt = amtM.group(1);
                    }
                    if (lastAmt != null) {
                        return parseEurAmount(lastAmt);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return 0.0;
    }

    private List<Double> extractBalanceColumn(PDDocument document) throws IOException {
        List<Double> balances = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();

        for (int i = 1; i <= document.getNumberOfPages(); i++) {
            stripper.setStartPage(i);
            stripper.setEndPage(i);
            String pageText = stripper.getText(document);

            // Capturar sección de Balance (viene después del encabezado "Balance")
            String[] lines = pageText.split("\\n");
            boolean inBalanceSection = false;

            for (String line : lines) {
                line = line.trim();
                // Check for header start
                if (line.equalsIgnoreCase("Balance")) {
                    inBalanceSection = true;
                    continue;
                }

                // If inside section, try to parse numbers
                if (inBalanceSection && !line.isEmpty()) {
                    // Parsear número venezolano: 1.961.723,08 → 1961723.08
                    // Also handle negative numbers like -100.00
                    try {
                        String normalized = line
                                .replace(".", "") // quitar separadores de miles
                                .replace(",", "."); // punto decimal
                        double val = Double.parseDouble(normalized);
                        balances.add(val);
                    } catch (NumberFormatException e) {
                        // línea no es número, puede ser encabezado repetido "Balance" o texto basura
                        // reset flag if needed? No, usually balance column continues.
                        // check if it's another header?
                        // For now, ignore non-numbers in balance column
                    }
                }
            }
        }
        return balances;
    }

    @Override
    public List<Transaction> parse(File file, Transaction.Source source) throws Exception {
        List<Transaction> transactions = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(file)) {

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            String[] lines = text.split("\\r?\\n");

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty())
                    continue;

                Matcher m = LINE_PATTERN.matcher(line);
                if (m.matches()) {
                    try {
                        String dateStr = m.group(1);
                        String descWithRef = m.group(2).trim();
                        String amountStr = m.group(3).trim();

                        LocalDate date = LocalDate.parse(dateStr, DATE_FMT);

                        // Parse Amount
                        String cleanAmt = amountStr.replace(".", "").replace(",", ".");
                        BigDecimal amount = new BigDecimal(cleanAmt);

                        double deposit = 0.0;
                        double withdrawal = 0.0;

                        if (amount.compareTo(BigDecimal.ZERO) < 0) {
                            withdrawal = amount.abs().doubleValue();
                        } else {
                            deposit = amount.doubleValue();
                        }

                        // Extract Reference
                        String reference = "S/N";
                        String description = descWithRef;

                        Pattern refP = Pattern.compile("^(\\d{5,})\\s+");
                        Matcher refM = refP.matcher(descWithRef);
                        if (refM.find()) {
                            reference = refM.group(1);
                            description = descWithRef.substring(refM.end()).trim();
                        }

                        transactions.add(new Transaction(date, reference, description, deposit, withdrawal, source));

                    } catch (Exception e) {
                        // ignore error
                    }
                }
            }
        }

        // Fallback: if DD/MM/YYYY format found 0 transactions, try day-only format
        if (transactions.isEmpty()) {
            transactions = parseDayOnlyFormat(file, source);
        }

        return transactions;
    }

    // ── Day-only format fallback ─────────────────────────────────────────────

    /**
     * Fallback parser for Banesco EDC where dates only have the DAY (DD), not full date.
     * Format: DD  REFERENCIA  CONCEPTO  MONTO  SALDO
     * 
     * Pages 2+ have double-column layout which PDFBox merges into single lines
     * containing two transactions.
     * 
     * Cargo vs Abono is determined by running balance comparison.
     */
    private List<Transaction> parseDayOnlyFormat(File file, Transaction.Source source) throws Exception {
        List<Transaction> transactions = new ArrayList<>();

        // 1. Determine period (month/year) from file content or filename
        int month = 1;
        int year = 2026;
        double initialSaldo = 0.0;

        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String fullText = stripper.getText(document);
            String[] allLines = fullText.split("\\r?\\n");

            // Extract period from header
            int[] period = extractPeriod(fullText, file.getName());
            month = period[0];
            year = period[1];

            // Extract initial saldo
            for (String line : allLines) {
                String upper = line.trim().toUpperCase();
                if (upper.contains("SALDO MES ANTERIOR") || upper.contains("SALDO ANTERIOR")) {
                    Matcher amtM = EUR_AMT.matcher(line);
                    String lastAmt = null;
                    while (amtM.find()) {
                        lastAmt = amtM.group(1);
                    }
                    if (lastAmt != null) {
                        initialSaldo = parseEurAmount(lastAmt);
                    }
                    break;
                }
            }

            // 2. Parse all transaction lines
            double runningBalance = initialSaldo;

            for (String rawLine : allLines) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;

                // Skip non-transaction lines
                if (isHeaderOrMetaLine(line)) continue;

                // Try to extract one or two transactions from this line
                // (double-column pages have 2 transactions per line)
                List<DayOnlyRecord> records = extractDayOnlyRecords(line);

                for (DayOnlyRecord rec : records) {
                    try {
                        int day = rec.day;
                        if (day < 1 || day > 31) continue;

                        LocalDate date = LocalDate.of(year, month, day);

                        // Determine cargo vs abono by comparing saldo
                        double deposit = 0.0;
                        double withdrawal = 0.0;

                        if (rec.saldo > runningBalance) {
                            // Balance went up → abono (deposit)
                            deposit = rec.amount;
                        } else {
                            // Balance went down → cargo (withdrawal)
                            withdrawal = rec.amount;
                        }

                        runningBalance = rec.saldo;

                        if (deposit > 0 || withdrawal > 0) {
                            transactions.add(new Transaction(date, rec.reference, rec.description,
                                    deposit, withdrawal, source));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        return transactions;
    }

    /** Simple record for a parsed day-only transaction */
    private static class DayOnlyRecord {
        int day;
        String reference;
        String description;
        double amount;
        double saldo;

        DayOnlyRecord(int day, String reference, String description, double amount, double saldo) {
            this.day = day;
            this.reference = reference;
            this.description = description;
            this.amount = amount;
            this.saldo = saldo;
        }
    }

    /**
     * Extracts one or two DayOnlyRecords from a line.
     * Double-column lines have two transactions side by side.
     */
    private List<DayOnlyRecord> extractDayOnlyRecords(String line) {
        List<DayOnlyRecord> records = new ArrayList<>();

        // Find all European amounts in the line
        List<int[]> amountPositions = new ArrayList<>(); // [start, end]
        List<String> amountValues = new ArrayList<>();
        Matcher amtM = EUR_AMT.matcher(line);
        while (amtM.find()) {
            amountPositions.add(new int[]{amtM.start(), amtM.end()});
            amountValues.add(amtM.group(1));
        }

        // A single transaction needs at least 2 amounts (monto + saldo)
        // Double-column needs at least 4 amounts (monto1 + saldo1 + monto2 + saldo2)

        if (amountValues.size() >= 4) {
            // Likely double-column: try to split into two halves
            // The line looks like: "DD REF CONCEPTO MONTO SALDO DD REF CONCEPTO MONTO SALDO"
            // After saldo1 (index 1), there should be a new "DD REF" starting the second transaction
            // Split point: after the 2nd amount (saldo of first transaction)
            int splitPoint = amountPositions.get(1)[1]; // end of second amount
            String leftHalf = line.substring(0, splitPoint).trim();
            String rightHalf = line.substring(splitPoint).trim();

            DayOnlyRecord left = parseSingleDayOnlyRecord(leftHalf);
            DayOnlyRecord right = parseSingleDayOnlyRecord(rightHalf);

            if (left != null) records.add(left);
            if (right != null) records.add(right);
        } else if (amountValues.size() >= 2) {
            // Single-column transaction
            DayOnlyRecord rec = parseSingleDayOnlyRecord(line);
            if (rec != null) records.add(rec);
        }

        return records;
    }

    /**
     * Parse a single day-only transaction from a line segment.
     * Expected: "DD REFERENCIA CONCEPTO MONTO SALDO"
     */
    private DayOnlyRecord parseSingleDayOnlyRecord(String segment) {
        segment = segment.trim();
        if (segment.isEmpty()) return null;

        Matcher m = DAY_ONLY_PATTERN.matcher(segment);
        if (m.matches()) {
            try {
                int day = Integer.parseInt(m.group(1));
                String reference = m.group(2);
                String description = m.group(3).trim();
                double amount = parseEurAmount(m.group(4));
                double saldo = parseEurAmount(m.group(5));
                return new DayOnlyRecord(day, reference, description, amount, saldo);
            } catch (Exception e) {
                return null;
            }
        }

        // Fallback: try to extract with looser approach
        // Must start with 2-digit day
        if (!segment.matches("^\\d{2}\\s+.*")) return null;

        String[] tokens = segment.split("\\s+");
        if (tokens.length < 4) return null;

        try {
            int day = Integer.parseInt(tokens[0]);
            if (day < 1 || day > 31) return null;

            // Find amounts from the end
            List<String> amounts = new ArrayList<>();
            Matcher amtM = EUR_AMT.matcher(segment);
            while (amtM.find()) {
                amounts.add(amtM.group(1));
            }
            if (amounts.size() < 2) return null;

            double amount = parseEurAmount(amounts.get(amounts.size() - 2));
            double saldo = parseEurAmount(amounts.get(amounts.size() - 1));

            // Reference is second token
            String reference = tokens[1];

            // Description is everything between reference and first amount
            // Find where reference ends
            int refEnd = segment.indexOf(reference) + reference.length();
            // Find where first amount starts (from the last 2 amounts)
            Matcher firstOfLast2 = EUR_AMT.matcher(segment);
            int amountStart = segment.length();
            int amtCount = amounts.size();
            int skip = amtCount - 2;
            int idx = 0;
            while (firstOfLast2.find()) {
                if (idx == skip) {
                    amountStart = firstOfLast2.start();
                    break;
                }
                idx++;
            }

            String description = "";
            if (refEnd < amountStart) {
                description = segment.substring(refEnd, amountStart).trim();
            }
            if (description.isEmpty()) description = "Sin descripcion";

            return new DayOnlyRecord(day, reference, description, amount, saldo);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if a line is a header, metadata, or non-transaction line.
     */
    private boolean isHeaderOrMetaLine(String line) {
        String upper = line.toUpperCase();
        return upper.contains("DETALLE DE MOVIMIENTOS")
                || upper.contains("DIA REF")
                || upper.contains("CHEQUES")
                || upper.contains("CHEQUE DIA MONTO")
                || upper.startsWith("SL-")
                || upper.contains("SALDO MES ANTERIOR")
                || upper.contains("SALDO ANTERIOR")
                || upper.contains("RESUMEN DE")
                || upper.contains("CONCEPTO CANTIDAD")
                || upper.contains("CHEQUES PAGADOS")
                || upper.contains("OTROS D")
                || upper.contains("DEP")
                || upper.contains("OTROS CR")
                || upper.contains("INICIAL")
                || upper.contains("FINAL")
                || upper.contains("PROMEDIO")
                || upper.contains("NO. DE CUENTA")
                || upper.contains("RIF:")
                || upper.contains("DROGUERIA")
                || upper.contains("AV.")
                || upper.contains("ENTRE CALLES")
                || upper.contains("APT.")
                || upper.contains("MARACAIBO")
                || upper.matches("^\\d{1,3}(?:\\.\\d{3})*,\\d{2}$") // standalone amount
                || upper.matches("^\\d+$") // standalone number
                || !upper.matches(".*\\d{2}\\s+\\d{5,}.*"); // must contain DD + reference
    }

    /**
     * Extract month and year from the PDF content or filename.
     */
    private int[] extractPeriod(String text, String filename) {
        int month = 1;
        int year = 2026;

        // Try to find "Período: MM-YYYY" or "Periodo: MM-YYYY"
        Matcher periodM = Pattern.compile("Per[ií\\x{00ed}\\x{00fd}]odo:\\s*(\\d{1,2})-(\\d{4})", Pattern.CASE_INSENSITIVE).matcher(text);
        if (periodM.find()) {
            month = Integer.parseInt(periodM.group(1));
            year = Integer.parseInt(periodM.group(2));
            return new int[]{month, year};
        }

        // Try from filename: "01-BANESCO ENERO 2026"
        Matcher fnM = Pattern.compile("(\\d{2})[-_].*?(\\d{4})").matcher(filename);
        if (fnM.find()) {
            month = Integer.parseInt(fnM.group(1));
            year = Integer.parseInt(fnM.group(2));
            return new int[]{month, year};
        }

        // Try month names in Spanish
        String upper = (text + " " + filename).toUpperCase();
        String[] months = {"", "ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO",
                "JULIO", "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE"};
        for (int i = 1; i <= 12; i++) {
            if (upper.contains(months[i])) {
                month = i;
                break;
            }
        }

        // Try to find a 4-digit year
        Matcher yearM = Pattern.compile("(20\\d{2})").matcher(upper);
        if (yearM.find()) {
            year = Integer.parseInt(yearM.group(1));
        }

        return new int[]{month, year};
    }

    /**
     * Parse European format amount: 1.234,56 → 1234.56
     */
    private static double parseEurAmount(String token) {
        String clean = token.replace(".", "").replace(",", ".");
        try {
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public static boolean isBanescoBankStatement(File file) {
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String firstPage = stripper.getText(doc).toUpperCase();

            // Exclude Profit Plus files which may mention Banesco in transactions
            if (firstPage.contains("PROFIT")) {
                return false;
            }

            // Relaxed detection: Just "BANESCO" is usually enough
            return firstPage.contains("BANESCO");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getBankName() {
        return "Banesco";
    }
}
