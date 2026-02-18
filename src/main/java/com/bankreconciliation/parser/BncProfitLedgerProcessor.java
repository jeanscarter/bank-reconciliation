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

    @Override
    public List<Transaction> parse(File file, Transaction.Source source) throws Exception {
        List<Transaction> transactions = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);

            // DEBUG: Write raw text to file
            try (java.io.PrintWriter out = new java.io.PrintWriter("debug_profit_raw.txt")) {
                out.println(text);
            } catch (Exception ignored) {
            }

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

    public static boolean isProfitLedger(File file) {
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String text = stripper.getText(doc).toUpperCase();
            // Look for Profit Plus identifiers found in BNC export
            // The file name says "SEGUN PROFIT", so maybe header has "PROFIT PLUS"?
            // Or just check for "PROFIT"
            return text.contains("PROFIT")
                    || (text.contains("DEBE") && text.contains("HABER") && text.contains("SALDO"));
        } catch (Exception e) {
            return false;
        }
    }
}
