package com.bankreconciliation.parser;

import com.bankreconciliation.model.Transaction;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.io.PrintWriter;
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

        return transactions;
    }

    @Override
    public double extractSaldoInicial(File file) {
        File debugFile = new File("debug_banesco_extraction.txt");
        try (PDDocument document = Loader.loadPDF(file);
                PrintWriter writer = new PrintWriter(debugFile)) {

            // Pass 1: Get First Transaction Amount (Standard Strip)
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setEndPage(1);
            String sortedText = stripper.getText(document);
            String[] sortedLines = sortedText.split("\\r?\\n");

            BigDecimal firstAmount = null;
            for (String line : sortedLines) {
                Matcher m = LINE_PATTERN.matcher(line.trim());
                if (m.matches()) {
                    String amountStr = m.group(3);
                    firstAmount = parseCurrency(amountStr);
                    writer.println("PASS 1: Found First Amount: " + firstAmount);
                    break;
                }
            }

            if (firstAmount == null) {
                writer.println("PASS 1: No transaction found.");
                return 0.0;
            }

            // Pass 2: Extract Right Side using Region
            // Assume Letter size approx 612 pts wide.
            // Balance column should be on the far right.
            // We'll scan the rightmost 200 points.
            org.apache.pdfbox.text.PDFTextStripperByArea stripperArea = new org.apache.pdfbox.text.PDFTextStripperByArea();
            stripperArea.setSortByPosition(true);

            // Define region: x=400, y=0, w=300, h=800
            java.awt.Rectangle rect = new java.awt.Rectangle(350, 0, 400, 800);
            stripperArea.addRegion("rightSide", rect);
            stripperArea.extractRegions(document.getPage(0));

            String rightText = stripperArea.getTextForRegion("rightSide");
            String[] rightLines = rightText.split("\\r?\\n");

            writer.println("=== PASS 2: RIGHT REGION TEXT ===");

            BigDecimal firstBalance = null;
            Pattern numberP = Pattern.compile("^([+\\-]?\\d{1,3}(?:[.]\\d{3})*(?:,\\d{2}))$");
            boolean headerFound = false;

            for (String line : rightLines) {
                line = line.trim();
                writer.println("R-LINE: " + line);

                if (line.equalsIgnoreCase("Balance") || line.toUpperCase().contains("BALANCE")) {
                    headerFound = true;
                    continue; // Skip header
                }

                // Matches "1.961.723,08"
                Matcher nm = numberP.matcher(line);
                if (nm.matches()) {
                    firstBalance = parseCurrency(nm.group(1));
                    writer.println("PASS 2: Found Balance Value in Region: " + firstBalance);
                    break;
                }
            }

            if (firstBalance != null) {
                double result = firstBalance.subtract(firstAmount).doubleValue();
                writer.println("RESULT: " + firstBalance + " - " + firstAmount + " = " + result);
                return result;
            }

            writer.println("PASS 2: No balance found in right region.");

        } catch (Exception e) {
            e.printStackTrace();
        }
        // User requested NOT to hardcode, so we return 0.0 if not found
        return 0.0;
    }

    private BigDecimal parseCurrency(String s) {
        if (s == null)
            return BigDecimal.ZERO;
        String clean = s.trim().replace(".", "").replace(",", ".");
        return new BigDecimal(clean);
    }

    public static boolean isBanescoBankStatement(File file) {
        try (PDDocument doc = Loader.loadPDF(file)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            String firstPage = stripper.getText(doc).toUpperCase();

            // Relaxed detection: Just "BANESCO" is usually enough
            return firstPage.contains("BANESCO");
        } catch (Exception e) {
            return false;
        }
    }
}
