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

    @Override
    public double extractSaldoInicial(File file) {
        try (PDDocument document = Loader.loadPDF(file)) {
            List<Double> balances = extractBalanceColumn(document);
            if (!balances.isEmpty()) {
                // The first value in the Balance column corresponds to the initial balance
                return balances.get(0);
            }
        } catch (Exception e) {
            e.printStackTrace();
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

        return transactions;
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
