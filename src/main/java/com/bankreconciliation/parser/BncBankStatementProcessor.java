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
 * Parser for BNC (Banco Nacional de Crédito) Bank Statements (PDF).
 */
public class BncBankStatementProcessor implements FileParser {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    // Regex to match lines like:
    // 01/12/2025 TRANSF. DE TERCEROS MISMOBANCO 123456 1.000,00 500,00 1.500,00
    // Groups:
    // 1: Date
    // 2: Description + Reference (needs cleanup)
    // 3: Debit (optional)
    // 4: Credit (optional)
    // 5: Balance (optional)
    // Note: This matches lines starting with a date. BNC often puts Reference
    // inside Description or in a separate column.
    // We will look for 2 or 3 amounts at the end of the line.

    // Pattern strategy: Start with Date, then capture everything until the amounts
    // at the end.
    // Normalized amounts: 1.234,56
    private static final Pattern LINE_PATTERN = Pattern.compile(
            "^(\\d{2}/\\d{2}/\\d{4})\\s+(.+?)\\s+([\\d\\.,]+(?:\\s+[\\d\\.,]+){0,2})$");

    @Override
    public List<Transaction> parse(File file, Transaction.Source source) throws Exception {
        List<Transaction> transactions = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(document);

            // DEBUG: Write raw text to file
            try (java.io.PrintWriter out = new java.io.PrintWriter("debug_bnc_raw.txt")) {
                out.println(text);
            } catch (Exception ignored) {
            }

            String[] lines = text.split("\\r?\\n");

            for (String line : lines) {
                line = line.trim();
                parseLine(line, source, transactions);
            }
        }

        return transactions;
    }

    private void parseLine(String line, Transaction.Source source, List<Transaction> transactions) {
        if (line.isEmpty())
            return;

        // BNC Line Format from debug log:
        // Date ... Description ... Reference Debe Haber Saldo
        // 01/12/2025 ... TRANSFERENCIA ... A 75649415 0,00 +23.430,01 327.155,80

        Matcher m = LINE_PATTERN.matcher(line);
        if (m.matches()) {
            try {
                String[] tokens = line.split("\\s+");
                if (tokens.length < 5)
                    return;

                // Expected: Last 3 are amounts (Debe, Haber, Saldo)
                // Token[N-1]: Saldo
                // Token[N-2]: Haber
                // Token[N-3]: Debe
                // Token[N-4]: Reference

                String saldoStr = tokens[tokens.length - 1];
                String haberStr = tokens[tokens.length - 2];
                String debeStr = tokens[tokens.length - 3];
                String refStr = tokens[tokens.length - 4];

                // Validate they look like numbers
                if (!isAmount(haberStr) || !isAmount(debeStr))
                    return;

                double debe = parseAmountInternal(debeStr);
                double haber = parseAmountInternal(haberStr);
                // Note: In BNC DB log:
                // Debe is negative (e.g. -60.488,40) for withdrawals?
                // Haber is positive (e.g. +23.430,01) for deposits?
                // ReconciliationEngine expects absolute positive values for Debit/Credit
                // fields.
                // source=BANK:
                // Debit = Payment/Withdrawal
                // Credit = Deposit

                // Let's take absolute values.
                // If Debe column has value (negative), it's a Debit.
                // If Haber column has value (positive), it's a Credit.

                double debitVal = Math.abs(debe);
                double creditVal = Math.abs(haber);

                LocalDate date = LocalDate.parse(tokens[0], DATE_FMT);

                // Description: Join text between Date(0) and Reference(N-4)
                StringBuilder desc = new StringBuilder();
                for (int i = 1; i < tokens.length - 4; i++) {
                    desc.append(tokens[i]).append(" ");
                }
                String description = desc.toString().trim();

                if (debitVal > 0 || creditVal > 0) {
                    transactions.add(new Transaction(date, refStr, description, creditVal, debitVal, source));
                }

            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private boolean isAmount(String s) {
        return s.matches("[+-]?[\\d.,]+");
    }

    private boolean isDebit(String description) {
        String upper = description.toUpperCase();
        return upper.contains("RETIRO") ||
                upper.contains("COMISION") ||
                upper.contains("IMPUESTO") ||
                upper.contains("CHEQUE") ||
                upper.contains("DEBITO") ||
                upper.contains("COMPRA") ||
                upper.contains("PAGO");
    }

    private double parseAmountInternal(String token) {
        // European format: 1.000,00 -> 1000.00
        // Also handle + and - prefixes
        String clean = token.replace(".", "").replace(",", ".");
        // Remove + sign if present, - sign is handled by Double.parseDouble
        if (clean.startsWith("+")) {
            clean = clean.substring(1);
        }
        return Double.parseDouble(clean);
    }

    @Override
    public double extractSaldoInicial(File file) {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            // Only need the first few pages to find the first transaction
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String text = stripper.getText(document);
            String[] lines = text.split("\\r?\\n");

            for (String line : lines) {
                line = line.trim();
                Matcher m = LINE_PATTERN.matcher(line);
                if (m.matches()) {
                    // Found first transaction line
                    String[] tokens = line.split("\\s+");
                    if (tokens.length < 5)
                        continue;

                    // Columns: Date ... Ref Debe Haber Saldo
                    // We need Debe, Haber, Saldo to reverse-calculate initial balance

                    String saldoStr = tokens[tokens.length - 1];
                    String haberStr = tokens[tokens.length - 2];
                    String debeStr = tokens[tokens.length - 3];

                    if (!isAmount(saldoStr) || !isAmount(haberStr) || !isAmount(debeStr))
                        continue;

                    double saldo = parseAmountInternal(saldoStr);
                    double haber = parseAmountInternal(haberStr);
                    double debe = parseAmountInternal(debeStr);

                    // Logic:
                    // Saldo = Initial + Haber + Debe (Debe is negative in the file)
                    // Initial = Saldo - Haber - Debe

                    return saldo - haber - debe;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return 0.0;
    }

    // Main method for self-testing and debugging
    public static void main(String[] args) {
        // Allow running locally to test
        String filePath = "test-data/ESTADO DE CUENTA BNC DICIEMBRE 2025.pdf";
        if (args.length > 0)
            filePath = args[0];

        File file = new File(filePath);
        if (!file.exists()) {
            System.err.println("File not found: " + file.getAbsolutePath());
            return;
        }

        System.out.println("Processing: " + file.getAbsolutePath());
        BncBankStatementProcessor processor = new BncBankStatementProcessor();
        try {
            List<Transaction> transactions = processor.parse(file, Transaction.Source.BANK);
            System.out.println("Found " + transactions.size() + " transactions.");
            for (Transaction t : transactions) {
                System.out.println(t);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isBncBankStatement(File file) {
        // Fallback: Check filename
        String filename = file.getName().toUpperCase();
        if (filename.contains("BNC") && filename.contains("ESTADO") && !filename.contains("PROFIT")) {
            return true;
        }

        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String text = stripper.getText(doc).toUpperCase();

            // DEBUG DETECTION
            try (java.io.FileWriter fw = new java.io.FileWriter("debug_bnc_detection.txt", true)) {
                fw.write("Checking file: " + file.getName() + "\n");
                fw.write("Content: " + text + "\n");
            } catch (Exception ignored) {
            }

            // Look for BNC identifiers
            return text.contains("BANCO NACIONAL DE CREDITO") // Plain
                    || text.contains("BANCO NACIONAL DE CRÉDITO") // Accent
                    || (text.contains("BNC") && text.contains("ESTADO DE CUENTA"));
        } catch (Exception e) {
            return false;
        }
    }
}
