package com.bankreconciliation.parser;

import com.bankreconciliation.model.Transaction;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for Profit Plus Ledger Reports (PDF).
 * Specific to BNC account export.
 */
public class BncProfitLedgerProcessor implements FileParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Regex for Date header lines: 01/12/2025
    private static final Pattern DATE_HEADER_PATTERN = Pattern.compile("^(\\d{2}/\\d{2}/\\d{4})$");

    // Regex for Transaction lines
    // Match lines ending with 3 amounts (Debe, Haber, IGTF)
    // 0000017835 ... 1,967.18 0.00 0.00

    // Pattern for inline-date format lines (Profit Plus "Estado de Cuenta Bancaria Multimoneda")
    // Matches lines starting with 10-digit number: 0000020100 TP 01/01/2026 ...
    private static final Pattern INLINE_LINE_START = Pattern.compile("^(\\d{10})\\s+(TP|TR)\\s+");

    // Pattern to find a clean date dd/MM/yyyy in a line (may be corrupted in TR lines)
    private static final Pattern INLINE_DATE_PATTERN = Pattern.compile("(\\d{2}/\\d{2}/\\d{4})");

    // European amount pattern: 1.234,56 or 0,00 or -272.066,38
    private static final Pattern EUR_AMOUNT_PATTERN = Pattern.compile(
            "(-?\\d{1,3}(?:\\.\\d{3})*,\\d{2})");

    @Override
    public List<Transaction> parse(File file, Transaction.Source source) throws Exception {
        List<Transaction> transactions = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);

            String[] lines = text.split("\\r?\\n");
            LocalDate currentDate = null;

            for (String line : lines) {
                line = line.trim();

                // Check if line is a Date Header
                Matcher dateMatcher = DATE_HEADER_PATTERN.matcher(line);
                if (dateMatcher.matches()) {
                    currentDate = LocalDate.parse(dateMatcher.group(1), DATE_FMT);
                    continue;
                }

                if (currentDate == null)
                    continue;

                // Parse Transaction
                // We use a simplified split approach instead of complex regex to be more robust
                // against spacing
                parseTransactionLine(line, currentDate, source, transactions);
            }
        }

        // Fallback: if date-header format found 0 transactions, try inline-date format
        if (transactions.isEmpty()) {
            transactions = parseInlineFormat(file, source);
        }

        return transactions;
    }

    private void parseTransactionLine(String line, LocalDate date, Transaction.Source source,
            List<Transaction> transactions) {
        if (line.isEmpty())
            return;

        // Split by whitespace
        String[] tokens = line.split("\\s+");
        // Expected minimum tokens: Num, Acct, AcctFull, Type, Doc, Curr, Desc...,
        // Origin, Debe, Haber, IGTF
        // Min length ~ 9-10?
        if (tokens.length < 8)
            return;

        try {
            // Check if last 3 are amounts
            String debeStr = tokens[tokens.length - 3];
            String haberStr = tokens[tokens.length - 2];

            // Verify they look like numbers
            if (!isNumber(debeStr) || !isNumber(haberStr))
                return;

            double debe = parseAmountInternal(debeStr);
            double haber = parseAmountInternal(haberStr);

            if (debe == 0 && haber == 0)
                return;

            // Extract Reference from Doc Number (Index 4 usually?)
            // Line: Num(0) Acct(1) AcctFull(2) Type(3) Doc(4) Curr(5)
            // Example: 0000017835 0108 010803... TP 16861 BS ...
            String reference = "S/N";
            int descStartIndex = 6;

            if (tokens.length > 5 && tokens[5].equals("BS")) {
                reference = tokens[4];
                descStartIndex = 6;
            } else {
                // Fallback scan
                // Look for "BS" or currency
                for (int i = 0; i < tokens.length; i++) {
                    if (tokens[i].equals("BS") || tokens[i].equals("USD") || tokens[i].equals("EUR")) {
                        if (i > 0)
                            reference = tokens[i - 1];
                        descStartIndex = i + 1;
                        break;
                    }
                }
            }

            // Description is everything from descStartIndex to length-3 (Debe) - 1 (Origin)
            // Origin is usually the token before amounts
            int originIndex = tokens.length - 4;

            StringBuilder descBuilder = new StringBuilder();
            if (descStartIndex <= originIndex) {
                for (int i = descStartIndex; i <= originIndex; i++) {
                    descBuilder.append(tokens[i]).append(" ");
                }
            }
            String description = descBuilder.toString().trim();
            if (description.isEmpty())
                description = "Sin descripcion";

            // In Accounting:
            // Debe = Charge/Debit (Increase in Asset? No, Bank is Asset. Debit increases
            // it.)
            // Haber = Credit (Decrease in Asset)
            // So: Debe = Deposit, Haber = Withdrawal

            transactions.add(new Transaction(date, reference, description, debe, haber, source));

        } catch (Exception e) {
            // Ignore parse errors
        }
    }

    private boolean isNumber(String s) {
        return s.matches("[\\d.,]+");
    }

    private double parseAmountInternal(String token) {
        // Format: 1,234.56 (US/Standard)
        // Remove commas, keep dots
        String clean = token.replace(",", "");
        try {
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    @Override
    public double extractSaldoInicial(File file) {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String text = stripper.getText(document);
            String[] lines = text.split("\\r?\\n");

            for (String line : lines) {
                line = line.trim();
                // Look for "Saldo Inicial" followed by an amount
                if (line.toLowerCase().contains("saldo inicial")) {
                    Matcher m = EUR_AMOUNT_PATTERN.matcher(line);
                    // Get the last amount on that line (the saldo value)
                    String lastAmount = null;
                    while (m.find()) {
                        lastAmount = m.group(1);
                    }
                    if (lastAmount != null) {
                        return parseEuropeanAmount(lastAmount);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    public static void main(String[] args) {
        String filePath = "test-data/ESTADO DE CUENTA BNC DICIEMBRE 2025 SEGUN PROFIT.pdf";
        if (args.length > 0)
            filePath = args[0];

        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("File not found: " + file.getAbsolutePath());
            return;
        }

        System.out.println("Processing: " + file.getAbsolutePath());
        BncProfitLedgerProcessor processor = new BncProfitLedgerProcessor();
        try {
            List<Transaction> transactions = processor.parse(file, Transaction.Source.BOOK);
            System.out.println("Found " + transactions.size() + " transactions.");
            for (Transaction t : transactions) {
                System.out.println(t);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ── Inline-date format fallback ──────────────────────────────────────────

    /**
     * Fallback parser for Profit Plus "Estado de Cuenta Bancaria Multimoneda" format
     * where each transaction line contains the date inline:
     * 0000020100 TP 01/01/2026 191021663 Cobro 0000014643 COB  1,00  247.125,00  0,00  0,00  2.181.132,51
     *
     * Note: TR (transfer) lines often have corrupted text from PDFBox text extraction.
     */
    private List<Transaction> parseInlineFormat(File file, Transaction.Source source) throws Exception {
        List<Transaction> transactions = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);
            String[] lines = text.split("\\r?\\n");

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Skip known non-transaction lines
                if (line.startsWith("*") || line.startsWith("Profit Plus")
                        || line.contains("Pßgina:") || line.contains("Pagina:")
                        || line.contains("R.I.F.:") || line.contains("Cuenta Bancaria:")
                        || line.contains("Estado de Cuenta") || line.contains("Moneda")
                        || line.contains("Codigo") || line.contains("N·mero")
                        || line.startsWith("BS ") || line.startsWith("Total en")
                        || line.contains("Saldo Inicial") || line.contains("BOL")) {
                    continue;
                }

                // Check if line starts with a 10-digit number (transaction line)
                Matcher startMatcher = INLINE_LINE_START.matcher(line);
                if (!startMatcher.find()) continue;

                parseInlineTransactionLine(line, source, transactions);
            }
        }

        return transactions;
    }

    private void parseInlineTransactionLine(String line, Transaction.Source source,
            List<Transaction> transactions) {
        try {
            // Extract all European-format amounts from the line
            List<String> amounts = new ArrayList<>();
            Matcher amtMatcher = EUR_AMOUNT_PATTERN.matcher(line);
            while (amtMatcher.find()) {
                amounts.add(amtMatcher.group(1));
            }

            // We expect at least 4 amounts at the end: Tasa, Debe, Haber, IGTF, Saldo
            // But Tasa is usually "1,00" which also matches. We need at least 5 amounts
            // (Tasa + Debe + Haber + IGTF + Saldo) for a valid transaction line.
            if (amounts.size() < 5) return;

            // Last 4 amounts: Debe, Haber, IGTF, Saldo (Tasa is before these)
            // Index: ..., tasa(n-5), debe(n-4), haber(n-3), igtf(n-2), saldo(n-1)
            // Actually: the amounts list includes Tasa.
            // For lines like: ... 1,00  247.125,00  0,00  0,00  2.181.132,51
            // amounts = [1,00, 247.125,00, 0,00, 0,00, 2.181.132,51]
            int n = amounts.size();
            double debe = parseEuropeanAmount(amounts.get(n - 4));
            double haber = parseEuropeanAmount(amounts.get(n - 3));
            // igtf = amounts.get(n - 2) — ignored
            // saldo = amounts.get(n - 1) — ignored

            if (debe == 0 && haber == 0) return;

            // Extract date — try to find a clean dd/MM/yyyy
            LocalDate date = extractInlineDate(line);
            if (date == null) return;

            // Extract reference from the NroDoc field
            // After "TP dd/MM/yyyy" or "TR dd/MM/yyyy", the next token is usually NroDoc
            String reference = "S/N";
            String description = "Sin descripcion";

            // Find position after the type (TP/TR)
            Matcher startM = INLINE_LINE_START.matcher(line);
            if (startM.find()) {
                String afterType = line.substring(startM.end()).trim();

                // Find the first amount position in the remainder to delimit description
                Matcher firstAmtInRemainder = EUR_AMOUNT_PATTERN.matcher(afterType);
                String textBeforeAmounts = afterType;
                if (firstAmtInRemainder.find()) {
                    textBeforeAmounts = afterType.substring(0, firstAmtInRemainder.start()).trim();
                }

                // textBeforeAmounts contains: date + nroDoc + description + origin
                // Try to extract parts with tokens
                String[] parts = textBeforeAmounts.split("\\s+");
                if (parts.length >= 2) {
                    // First token might be a date or corrupted text
                    int descStart = 0;
                    // Skip the date token(s)
                    for (int i = 0; i < parts.length; i++) {
                        if (parts[i].matches("\\d{2}/\\d{2}/\\d{4}")) {
                            descStart = i + 1;
                            break;
                        }
                    }
                    // If no clean date found (corrupted TR), skip date-like tokens
                    if (descStart == 0) {
                        // Skip first token (corrupted date+text mix)
                        descStart = 1;
                    }

                    // Next token after date is usually NroDoc (reference)
                    if (descStart < parts.length) {
                        reference = parts[descStart];
                        descStart++;
                    }

                    // Rest is description (excluding last token which is Origin like COB/PAG/OPA/BAN)
                    int descEnd = parts.length - 1; // skip Origin
                    StringBuilder descBuilder = new StringBuilder();
                    for (int i = descStart; i < descEnd; i++) {
                        descBuilder.append(parts[i]).append(" ");
                    }
                    String desc = descBuilder.toString().trim();
                    if (!desc.isEmpty()) {
                        description = desc;
                    }
                }
            }

            // In this format: Debe = Deposit (money in), Haber = Withdrawal (money out)
            transactions.add(new Transaction(date, reference, description, debe, haber, source));

        } catch (Exception e) {
            // Ignore parse errors for individual lines
        }
    }

    /**
     * Extract a date from an inline transaction line.
     * Handles both clean dates (01/01/2026) and corrupted TR lines
     * where PDFBox merges the date with other text.
     */
    private LocalDate extractInlineDate(String line) {
        // First, try to find a clean date
        Matcher m = INLINE_DATE_PATTERN.matcher(line);
        if (m.find()) {
            try {
                return LocalDate.parse(m.group(1), DATE_FMT);
            } catch (Exception ignored) {
            }
        }

        // For corrupted TR lines like:
        // "0000020659 TR 02/0T1R/2A0N2S6FERENCIA..."
        // Try to extract the day from "dd/" pattern after "TR "
        // and month/year from context or from the surrounding clean lines
        Matcher corruptedDate = Pattern.compile("\\b(\\d{2})/(\\d)\\D").matcher(line);
        if (corruptedDate.find()) {
            try {
                int day = Integer.parseInt(corruptedDate.group(1));
                // Try to reconstruct: look for a 4-digit year anywhere in line
                Matcher yearM = Pattern.compile("20\\d{2}").matcher(line);
                int year = 2026; // default
                int month = 1;   // default
                if (yearM.find()) {
                    year = Integer.parseInt(yearM.group());
                }
                // Try to find month digit after the corrupted day
                String afterDay = line.substring(corruptedDate.start());
                // Pattern: dd/0m/ where m is a digit (month)
                Matcher monthM = Pattern.compile("\\d{2}/(\\d)").matcher(afterDay);
                if (monthM.find()) {
                    month = Integer.parseInt(monthM.group(1));
                    // Check if there's a second month digit
                    int pos = monthM.end();
                    if (pos < afterDay.length() && Character.isDigit(afterDay.charAt(pos))) {
                        month = month * 10 + Character.getNumericValue(afterDay.charAt(pos));
                    }
                }
                if (day >= 1 && day <= 31 && month >= 1 && month <= 12) {
                    return LocalDate.of(year, month, day);
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    /**
     * Parse European format amount: 1.234,56 → 1234.56
     */
    private double parseEuropeanAmount(String token) {
        String clean = token.replace(".", "").replace(",", ".");
        try {
            return Double.parseDouble(clean);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public static boolean isProfitLedger(File file) {
        String filename = file.getName().toUpperCase();
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String text = stripper.getText(doc).toUpperCase();

            // Look for Profit Plus identifiers
            boolean hasProfitKeyword = filename.contains("PROFIT") || text.contains("PROFIT")
                    || text.contains("PROFIT PLUS");
            if (hasProfitKeyword) {
                return true;
            }

            // Also check for distinct Profit Plus report headers if "PROFIT" keyword is not explicitly in text/filename
            return (text.contains("MOVIMIENTOS DE BANCO POR FECHA") || text.contains("ESTADO DE CUENTA BANCARIA MULTIMONEDA"))
                    && !text.contains("BNCNET")
                    && !text.contains("BANCO NACIONAL DE CR");
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String getBankName() {
        return "BNC";
    }
}
