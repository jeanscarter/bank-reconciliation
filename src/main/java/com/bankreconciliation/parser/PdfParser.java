package com.bankreconciliation.parser;

import com.bankreconciliation.model.Transaction;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses .pdf files using Apache PDFBox text extraction.
 * Attempts to identify tabular data by detecting date patterns at line starts
 * and numeric amounts within each line.
 */
public class PdfParser implements FileParser {

    // Date patterns: DD/MM/YYYY, DD-MM-YYYY, DD.MM.YYYY
    private static final Pattern DATE_PATTERN = Pattern.compile(
            "^\\s*(\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4})");

    // Amount pattern: numbers with optional thousand separators and decimals
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{2}))");

    @Override
    public List<Transaction> parse(File file, Transaction.Source source) throws Exception {
        List<Transaction> transactions = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            String[] lines = text.split("\\r?\\n");

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty())
                    continue;

                Matcher dateMatcher = DATE_PATTERN.matcher(line);
                if (!dateMatcher.find())
                    continue;

                LocalDate date = parseDate(dateMatcher.group(1));
                if (date == null)
                    continue;

                // Extract remaining text after date
                String remainder = line.substring(dateMatcher.end()).trim();

                // Extract all amounts from the line
                List<Double> amounts = new ArrayList<>();
                Matcher amtMatcher = AMOUNT_PATTERN.matcher(remainder);
                int lastAmountEnd = -1;
                while (amtMatcher.find()) {
                    amounts.add(ColumnMapper.parseAmount(amtMatcher.group(1)));
                    lastAmountEnd = amtMatcher.start();
                }

                if (amounts.isEmpty())
                    continue;

                // Extract description: text between date and first amount
                String description = "";
                if (lastAmountEnd > 0) {
                    // Find first amount position
                    Matcher firstAmt = AMOUNT_PATTERN.matcher(remainder);
                    if (firstAmt.find()) {
                        description = remainder.substring(0, firstAmt.start()).trim();
                    }
                }

                // Try to find a reference in the description
                String reference = extractReference(description);
                if (!reference.isEmpty()) {
                    description = description.replace(reference, "").trim();
                    // Clean up extra whitespace
                    description = description.replaceAll("\\s{2,}", " ").trim();
                }

                // Map amounts to deposit/withdrawal
                double deposit = 0;
                double withdrawal = 0;

                if (amounts.size() >= 2) {
                    // Two amount columns: first = deposit, second = withdrawal (or vice versa)
                    deposit = amounts.get(0);
                    withdrawal = amounts.get(1);
                } else if (amounts.size() == 1) {
                    // Single amount: determine based on keywords
                    double amt = amounts.get(0);
                    String lowerLine = line.toLowerCase();
                    if (lowerLine.contains("cargo") || lowerLine.contains("retiro") ||
                            lowerLine.contains("pago") || lowerLine.contains("débito") ||
                            lowerLine.contains("debito") || lowerLine.contains("cheque")) {
                        withdrawal = amt;
                    } else {
                        deposit = amt;
                    }
                }

                if (deposit == 0 && withdrawal == 0)
                    continue;

                transactions.add(new Transaction(date, reference, description, deposit, withdrawal, source));
            }
        }

        return transactions;
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
                Double saldo = ColumnMapper.extractSaldoInicial(line);
                if (saldo != null)
                    return saldo;
            }
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank())
            return null;
        String[] parts = s.split("[/\\-.]");
        if (parts.length == 3) {
            try {
                int a = Integer.parseInt(parts[0].trim());
                int b = Integer.parseInt(parts[1].trim());
                int c = Integer.parseInt(parts[2].trim());
                if (c < 100)
                    c += 2000; // 2-digit year
                if (a > 100)
                    return LocalDate.of(a, b, c);
                return LocalDate.of(c, b, a); // DD/MM/YYYY
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String extractReference(String text) {
        // Look for patterns like CHK-1001, BC-5001, REF-123, #123, etc.
        Pattern refPattern = Pattern.compile("([A-Z]{2,4}[\\-#]\\d{3,}|#\\d{3,}|\\d{6,})");
        Matcher m = refPattern.matcher(text);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }
}
